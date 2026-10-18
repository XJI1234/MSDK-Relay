'use strict';

// DJI aircraft 5.17.0 arm64; ELF virtual addresses, without Ghidra's image base.
// Original libdjisdk_jni.so SHA-256: 9e0c7adbd803bbbd1d843e04c5e69119c812b1abb27f12dc89ea04114396eb5c.
const PROFILE = [
  { name: 'avoid-modern', offset: 0x2a7d388, prefix: 'ff8301d1f85f02a9f65703a9f44f04a9' },
  { name: 'avoid-legacy', offset: 0x2283028, prefix: 'ff4302d1f92300f9f85f05a9f65706a9' },
  { name: 'confirmation', offset: 0x2d6767c, prefix: 'ff8301d1f85f02a9f65703a9f44f04a9' },
  { name: 'cache', offset: 0x1ed29ac, prefix: 'fc6fbaa9fa6701a9f85f02a9f65703a9' },
  { name: 'jni', offset: 0x18729b0, prefix: 'fc6fbaa9fa6701a9f85f02a9f65703a9' },
  { name: 'jni-env', offset: 0x1872cb8, prefix: 'f80300aaa00100b4b70800b4e80240f9' },
  { name: 'vision-legacy', offset: 0x21a84bc, prefix: 'ffc301d1f71b00f9f65704a9f44f05a9' },
  { name: 'start', offset: 0x2d6634c, prefix: 'ff8305d1fc5f12a9f65713a9f44f14a9' },
  { name: 'stop', offset: 0x2d66770, prefix: 'ff8305d1fc5f12a9f65713a9f44f14a9' },
  { name: 'confirm', offset: 0x2d67258, prefix: 'ff8305d1fc5f12a9f65713a9f44f14a9' },
  { name: 'control-send', offset: 0x2d5f7e8, prefix: 'ff4307d1fc6f17a9fa6718a9f85f19a9' },
  { name: 'control-result', offset: 0x2d606e8, prefix: 'fc5fbca9f65701a9f44f02a9fd7b03a9' },
];

function confirmationNeeded(word) { return (word & 32) !== 0; }

function validateProfile(readPrefix) {
  for (const site of PROFILE) {
    if (readPrefix(site.offset) !== site.prefix) throw new Error('native fingerprint mismatch: ' + site.name);
  }
  return true;
}

function createRecorder(now, sink, capacity = 512) {
  let queue = [], sequence = 0, dropped = 0, sinkErrors = 0, captureErrors = 0;
  function record(kind, data) {
    try {
      const event = Object.assign({}, data, now(), { seq: ++sequence, kind });
      if (queue.length < capacity) queue.push(event);
      else dropped++;
      return event.seq;
    } catch (_) { captureErrors++; return null; }
  }
  return {
    record,
    flush() {
      const batch = queue;
      queue = [];
      if (dropped) { const count = dropped; dropped = 0; record('overflow', { dropped: count }); batch.push(...queue); queue = []; }
      for (const event of batch) {
        try { sink(event); } catch (_) { sinkErrors++; }
      }
    },
    stats: () => ({ sequence, queued: queue.length, dropped, sinkErrors, captureErrors }),
  };
}

function startProbe() {
  const logWrite = new NativeFunction(Module.getGlobalExportByName('__android_log_write'), 'int', ['int', 'pointer', 'pointer']);
  const logTag = Memory.allocUtf8String('LandingNativeProbe');
  function log(message) { logWrite(4, logTag, Memory.allocUtf8String(message)); }
  const clockGettime = new NativeFunction(Module.getGlobalExportByName('clock_gettime'), 'int', ['int', 'pointer']);
  const timespec = Memory.alloc(16);
  const now = () => {
    if (clockGettime(1, timespec) !== 0) throw new Error('CLOCK_MONOTONIC unavailable');
    return {
      monoNs: timespec.readS64().toString() + timespec.add(8).readS64().toString().padStart(9, '0'),
      wallMs: Date.now(), pid: Process.id,
    };
  };
  const output = '/data/data/com.skycommand.relay/no_backup/landing-probe/events-' + Process.id + '-' + Date.now() + '.jsonl';
  const file = new File(output, 'w');
  let bytes = 0, stopped = false;
  const recorder = createRecorder(now, event => {
    if (stopped) return;
    const line = JSON.stringify(event) + '\n';
    if (bytes + line.length > 16 * 1024 * 1024) {
      stopped = true;
      file.flush(); file.close();
      log('CAPTURE_LIMIT reached; recording stopped');
      return;
    }
    try { file.write(line); bytes += line.length; }
    catch (error) { stopped = true; log('SINK_FAILED ' + String(error)); throw error; }
  });
  const hex = (address, size) => Array.from(new Uint8Array(address.readByteArray(size)), b => b.toString(16).padStart(2, '0')).join('');
  function cxxString(address) {
    const first = address.readU8();
    const long = (first & 1) !== 0;
    const length = long ? address.add(8).readU64().toNumber() : first >>> 1;
    if (length < 1 || length > 128) return '';
    return (long ? address.add(16).readPointer() : address.add(1)).readUtf8String(length);
  }
  function characteristicParam(characteristics) {
    // libdjibase.so Characteristics::GetParam (ELF 0x1132aa0): PARAM is map key 0,
    // and the string is shared_ptr<string> at node+0x28, not an inline string.
    let node = characteristics.add(0x18).readPointer();
    for (let visited = 0; !node.isNull() && visited < 32; visited++) {
      if (node.add(0x10).readU32() === 0 && node.add(0x20).readU8() === 1) {
        const string = node.add(0x28).readPointer();
        return string.isNull() ? '' : cxxString(string);
      }
      node = node.readPointer();
    }
    return '';
  }
  function value(shared) {
    if (shared.isNull()) return null;
    const object = shared.readPointer();
    if (object.isNull()) return null;
    // Do not guess a DjiValue subtype. Save the vtable and the eight data bytes.
    return { vtable: object.readPointer().toString(), data8: hex(object.add(8), 8) };
  }
  function response(rsp, maximum) {
    if (rsp.isNull()) return null;
    const length = rsp.add(0x24).readU32();
    const data = rsp.add(0x28).readPointer();
    if (length === 0 || length > 65536 || data.isNull()) return { length, invalid: true };
    return { length, payload: hex(data, Math.min(length, maximum)) };
  }
  const selectedKey = key => /IsLandingConfirmationNeeded|LandingProtectionState|LandingProtectType|FlightMode|MissionExitReason|FlightControlAuthorityChangeReason|IsInLandingMode|PauseButtonDown|StickLeftVertical|StickRightVertical|IsFlying|AreMotorsOn/.test(key);
  const packetByThread = new Map(), rawByThread = new Map(), jniByThread = new Map();
  const counts = {}, errors = {};
  let installed = false, handles = [], moduleBase = null;
  function guarded(kind, operation) {
    try { return operation(); }
    catch (error) {
      errors[kind] = (errors[kind] || 0) + 1;
      if (errors[kind] <= 5) recorder.record('probe-error', { site: kind, error: String(error).slice(0, 240) });
      return null;
    }
  }
  function install(module) {
    if (installed || module.name !== 'libdjisdk_jni.so') return;
    guarded('install', () => {
      if (Process.arch !== 'arm64') throw new Error('only arm64 is supported');
      validateProfile(offset => hex(module.base.add(offset), 16));
      moduleBase = module.base;
      function attach(name, callbacks) {
        const site = PROFILE.find(p => p.name === name);
        const wrapped = {};
        if (callbacks.onEnter) wrapped.onEnter = function(args) {
          counts[name] = (counts[name] || 0) + 1;
          guarded(name, () => callbacks.onEnter.call(this, args));
        };
        if (callbacks.onLeave) wrapped.onLeave = function(retval) { guarded(name + '-leave', () => callbacks.onLeave.call(this, retval)); };
        handles.push(Interceptor.attach(moduleBase.add(site.offset), wrapped));
      }
      try {
        for (const path of ['modern', 'legacy']) attach('avoid-' + path, {
          onEnter(args) {
            this.previousPacket = packetByThread.get(this.threadId);
            const sample = response(args[1], 8);
            const id = recorder.record('raw-avoid-packet', {
              thread: this.threadId, path, rsp: args[1].toString(),
              header: args[1].isNull() ? null : hex(args[1], 0x28), sample,
            });
            packetByThread.set(this.threadId, { id, path });
          },
          onLeave() {
            if (this.previousPacket) packetByThread.set(this.threadId, this.previousPacket);
            else packetByThread.delete(this.threadId);
          },
        });
        attach('confirmation', {
          onEnter(args) {
            const thread = this.threadId;
            this.previousRaw = rawByThread.get(thread);
            const sample = response(args[2], 8);
            if (!sample || sample.invalid || sample.length < 2) throw new Error('invalid confirmation packet');
            const word = args[2].add(0x28).readPointer().readU16();
            const packet = packetByThread.get(thread);
            const id = recorder.record('raw-confirmation', { thread, packetSeq: packet ? packet.id : null, word, needed: confirmationNeeded(word), sample });
            rawByThread.set(thread, { id, characteristics: args[1].toString() });
          },
          onLeave() {
            if (this.previousRaw) rawByThread.set(this.threadId, this.previousRaw);
            else rawByThread.delete(this.threadId);
          },
        });
        attach('cache', { onEnter(args) {
          const raw = rawByThread.get(this.threadId);
          const packet = packetByThread.get(this.threadId);
          if (raw && raw.characteristics === args[1].toString()) {
            recorder.record('confirmation-cache-input', { thread: this.threadId, rawSeq: raw.id, value: value(args[2]), source: args[3].toInt32() });
          } else if (packet) {
            const key = characteristicParam(args[1]);
            const kind = key.includes('IsLandingConfirmationNeeded') ? 'confirmation-cache-input' : 'avoid-cache-input';
            recorder.record(kind, { thread: this.threadId, packetSeq: packet.id, path: packet.path, key, value: value(args[2]), source: args[3].toInt32() });
          }
        } });
        attach('jni', {
          onEnter(args) {
            this.previousJni = jniByThread.get(this.threadId);
            const key = cxxString(args[0].add(8));
            if (!selectedKey(key)) { jniByThread.delete(this.threadId); return; }
            const id = recorder.record('jni-value', { thread: this.threadId, key, old: value(args[2]), next: value(args[3]) });
            jniByThread.set(this.threadId, { id, key });
          },
          onLeave() {
            if (this.previousJni) jniByThread.set(this.threadId, this.previousJni);
            else jniByThread.delete(this.threadId);
          },
        });
        attach('jni-env', { onEnter() {
          const scope = jniByThread.get(this.threadId);
          if (scope) recorder.record('jni-environment', { thread: this.threadId, jniSeq: scope.id, key: scope.key, available: !this.context.x0.isNull() });
        } });
        attach('vision-legacy', { onEnter(args) {
          const sample = response(args[1], 8);
          recorder.record('vision-landing-packet', { thread: this.threadId, path: 'legacy', sample });
        } });
        for (const name of ['start', 'stop', 'confirm']) attach(name, { onEnter() {
          recorder.record('landing-action-entry', { thread: this.threadId, action: name, caller: this.returnAddress.toString() });
        } });
        attach('control-send', {
          onEnter(args) {
            const request = args[1];
            // libdjibase Buffer::assign (ELF 0x905c4c): length +4, data +8.
            // The request embeds Buffer at +0x20; these are not vector pointers.
            const sample = guarded('control-payload', () => {
              const length = request.add(0x24).readU32(), data = request.add(0x28).readPointer();
              if (length < 1 || length > 65536 || data.isNull()) return { length, invalid: true };
              return { length, payload: hex(data, Math.min(length, 32)), actionByte: data.readU8() };
            });
            this.sendSeq = recorder.record('function-control-send', Object.assign({
              thread: this.threadId, key: guarded('control-key', () => characteristicParam(args[2])), header: hex(request, 32),
              caller: this.returnAddress.toString(),
            }, sample || { invalid: true }));
          },
          onLeave(retval) { if (this.sendSeq) recorder.record('function-control-dispatch-return', { sendSeq: this.sendSeq, result: retval.toInt32() }); },
        });
        attach('control-result', { onEnter(args) {
          recorder.record('function-control-result', { thread: this.threadId, key: guarded('control-key', () => characteristicParam(args[0].add(0x38))), status: args[1].toInt32(), response: response(args[2], 32) });
        } });
        installed = true;
        recorder.record('ready', { profile: 'dji-aircraft-5.17.0-arm64', base: moduleBase.toString(), hooks: PROFILE.map(p => p.name) });
        log('READY hooks=' + handles.length + ' output=' + output);
      } catch (error) {
        for (const handle of handles) handle.detach();
        handles = [];
        throw error;
      }
    });
  }
  const observer = Process.attachModuleObserver({ onAdded: install });
  recorder.record('capture-start', { clock: 'CLOCK_MONOTONIC', sdk: '5.17.0', gadget: '17.24.0' });
  const flushTimer = setInterval(() => {
    recorder.flush();
    if (!stopped) guarded('flush', () => file.flush());
  }, 250);
  const healthTimer = setInterval(() => {
    recorder.record('health', { installed, counts: Object.assign({}, counts), errors: Object.assign({}, errors), recorder: recorder.stats(), bytes, stopped });
    if (!installed) log('WAITING_FOR_HOOKS');
  }, 5000);
  return () => {
    observer.detach();
    for (const handle of handles) handle.detach();
    clearInterval(flushTimer); clearInterval(healthTimer);
    recorder.flush();
    if (!stopped) { file.flush(); file.close(); }
  };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { confirmationNeeded, createRecorder, validateProfile, PROFILE };
} else {
  let dispose = () => {};
  rpc.exports = {
    init() { dispose = startProbe(); },
    dispose() { dispose(); },
  };
}
