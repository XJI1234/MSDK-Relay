const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const sourcePath = path.join(__dirname, '../src/app/src/landingDiagnostic/probe/landing-probe.js');
const source = () => fs.readFileSync(sourcePath, 'utf8');
const load = () => {
  const context = { module: { exports: {} } };
  vm.runInNewContext(source(), context);
  return context.module.exports;
};

test('confirmation reads bit 5, independently of adjacent flags', () => {
  const { confirmationNeeded } = load();
  for (let word = 0; word <= 0xffff; word++) {
    assert.equal(confirmationNeeded(word), (word & 32) !== 0);
  }
});

test('bounded capture retains ordering, reports overflow and preserves event timestamps', () => {
  const { createRecorder } = load();
  let tick = 0;
  const written = [];
  const recorder = createRecorder(() => ({ monoNs: String(++tick) }), event => written.push(event), 2);
  recorder.record('raw', { word: 0 });
  recorder.record('raw', { word: 32 });
  recorder.record('raw', { word: 0 });
  recorder.flush();
  assert.deepEqual(written.map(e => [e.seq, e.monoNs, e.kind]), [[1, '1', 'raw'], [2, '2', 'raw'], [4, '4', 'overflow']]);
  assert.equal(written[2].dropped, 1);
  recorder.record('callback', {});
  recorder.flush();
  assert.equal(written[3].seq, 5);
});

test('failed diagnostic sink cannot throw into an SDK callback', () => {
  const { createRecorder } = load();
  const recorder = createRecorder(() => ({ monoNs: '1' }), () => { throw Error('disk full'); }, 2);
  assert.doesNotThrow(() => { recorder.record('raw', {}); recorder.flush(); });
  assert.equal(recorder.stats().sinkErrors, 1);
});

test('short confirmation pulse and small RC changes are retained without value suppression', () => {
  const { createRecorder } = load();
  const written = [];
  const recorder = createRecorder(() => ({}), event => written.push(event), 8);
  for (const word of [0, 32, 0]) recorder.record('raw-confirmation', { word });
  for (const value of [0, 1, -1, 0]) recorder.record('jni-value', { key: 'RightVerticalValue', value });
  recorder.flush();
  assert.equal(written.length, 7);
  assert.deepEqual(written.slice(0, 3).map(e => e.word), [0, 32, 0]);
});

test('incompatible native entry point disables the complete profile before any hook is attached', () => {
  const { validateProfile, PROFILE } = load();
  let reads = 0;
  assert.throws(() => validateProfile(() => { reads++; return '00000000'; }), /fingerprint/);
  assert.ok(reads > 0);
  assert.equal(validateProfile(offset => PROFILE.find(p => p.offset === offset).prefix), true);
});

test('script exposes only passive hooks and contains no command or memory-write API', () => {
  const text = source();
  for (const forbidden of [/Interceptor\.replace/, /Memory\.patchCode/, /\.write(?:U\d+|S\d+|ByteArray|Pointer)\(/, /retval\.replace/, /args\[\d+\]\s*=/]) {
    assert.doesNotMatch(text, forbidden);
  }
});

// Exercise the shipped script with the actual native ABI layouts recovered by REA.
// The only simulated native exports are the clock and Android logger.
function runtime({ incompatible = false, failAttach = false } = {}) {
  const { PROFILE } = load();
  const memory = new Map(), hooks = new Map(), lines = [], intervals = [];
  let allocated = 0x10000000, detached = 0;
  const read = (address, size) => Buffer.from(Array.from({ length: size }, (_, i) => memory.get(address + i) ?? 0));
  const write = (address, buffer) => buffer.forEach((byte, i) => memory.set(address + i, byte));
  class Pointer {
    constructor(address) { this.address = address; }
    add(delta) { return new Pointer(this.address + delta); }
    sub(other) { return new Pointer(this.address - other.address); }
    isNull() { return this.address === 0; }
    toString() { return '0x' + this.address.toString(16); }
    toInt32() { return this.address | 0; }
    readU8() { return read(this.address, 1)[0]; }
    readU16() { return read(this.address, 2).readUInt16LE(); }
    readU32() { return read(this.address, 4).readUInt32LE(); }
    readU64() { const n = read(this.address, 8).readBigUInt64LE(); return { toNumber: () => Number(n), toString: () => String(n) }; }
    readS64() { return this.readU64(); }
    readPointer() { return new Pointer(this.readU64().toNumber()); }
    readByteArray(size) { return Uint8Array.from(read(this.address, size)).buffer; }
    readUtf8String(length) { return read(this.address, length).toString('utf8'); }
  }
  const pointer = address => new Pointer(address);
  const allocate = (size = 256) => { const result = pointer(allocated); allocated += size; return result; };
  const putPointer = (at, value) => { const b = Buffer.alloc(8); b.writeBigUInt64LE(BigInt(value.address)); write(at.address, b); };
  const putString = (at, value) => {
    const bytes = Buffer.from(value);
    if (bytes.length < 23) write(at.address, Buffer.concat([Buffer.from([bytes.length * 2]), bytes]));
    else { write(at.address, Buffer.from([1])); putPointer(at.add(8), pointer(bytes.length)); const data = allocate(bytes.length); write(data.address, bytes); putPointer(at.add(16), data); }
  };
  const putCharacteristics = (at, param) => {
    // Characteristics wraps an unordered_map at +8; PARAM is element key 0.
    // Node: next +0, hash +8, key +0x10, CharacteristicsElement +0x20.
    const node = allocate(512), string = allocate();
    putPointer(at.add(0x18), node);
    write(node.address + 0x20, Buffer.from([1]));
    putString(string, param); putPointer(node.add(0x28), string);
  };
  const base = pointer(0x40000000);
  for (const site of PROFILE) write(base.address + site.offset, Buffer.from(site.prefix, 'hex'));
  if (incompatible) write(base.address + PROFILE[PROFILE.length - 1].offset, Buffer.alloc(16));
  const context = {
    rpc: { exports: {} },
    Process: { arch: 'arm64', id: 123, attachModuleObserver(observer) { observer.onAdded({ name: 'libdjisdk_jni.so', base }); return { detach() {} }; } },
    Module: { getGlobalExportByName: name => name },
    NativeFunction: function(name) { return () => name === 'clock_gettime' ? 0 : 1; },
    Memory: { alloc: allocate, allocUtf8String: value => { const p = allocate(); write(p.address, Buffer.from(value + '\0')); return p; } },
    Interceptor: { attach(at, callbacks) {
      if (failAttach && hooks.size === 2) throw Error('attach unavailable');
      hooks.set(at.address - base.address, callbacks);
      return { detach() { detached++; hooks.delete(at.address - base.address); } };
    } },
    File: class { write(line) { lines.push(JSON.parse(line)); } flush() {} close() {} },
    setInterval(callback) { intervals.push(callback); return intervals.length; }, clearInterval() {},
  };
  vm.runInNewContext(source(), context);
  context.rpc.exports.init();
  const enter = (name, args, extra = {}) => {
    const state = { threadId: 42, returnAddress: base.add(0x1234), ...extra };
    const callback = hooks.get(PROFILE.find(p => p.name === name).offset);
    callback.onEnter.call(state, args);
    return result => { if (callback.onLeave) callback.onLeave.call(state, pointer(result ?? 0)); };
  };
  return { memory, hooks, lines, pointer, allocate, write, putPointer, putString, putCharacteristics, enter, flush: () => intervals[0](), detached: () => detached };
}

test('native confirmation, cache input, JNI value and JNI environment correlate across the ABI', () => {
  const r = runtime();
  const payload = r.allocate(), rsp = r.allocate(), characteristics = r.allocate();
  r.write(payload.address, Buffer.from([0x20, 0]));
  r.write(rsp.address + 0x24, Buffer.from([2, 0, 0, 0]));
  r.putPointer(rsp.add(0x28), payload);
  const leaveRaw = r.enter('confirmation', [r.pointer(0), characteristics, rsp]);
  const object = r.allocate(), shared = r.allocate();
  r.putPointer(object, r.pointer(0x44444444)); r.write(object.address + 8, Buffer.from([1])); r.putPointer(shared, object);
  r.enter('cache', [r.pointer(0), characteristics, shared, r.pointer(4)]);
  leaveRaw();
  // The same cache function called outside this packet must not create a false correlation.
  r.enter('cache', [r.pointer(0), characteristics, shared, r.pointer(4)]);
  const listener = r.allocate(); r.putString(listener.add(8), 'IsLandingConfirmationNeeded');
  const leaveJni = r.enter('jni', [listener, r.pointer(0), r.pointer(0), shared]);
  r.enter('jni-env', [], { context: { x0: r.pointer(0x12345) } });
  leaveJni();
  r.flush();
  const raw = r.lines.find(e => e.kind === 'raw-confirmation');
  const cache = r.lines.filter(e => e.kind === 'confirmation-cache-input');
  assert.equal(raw.needed, true);
  assert.equal(cache.length, 1);
  assert.equal(cache[0].rawSeq, raw.seq);
  assert.equal(cache[0].value.data8, '0100000000000000');
  const jni = r.lines.find(e => e.kind === 'jni-value');
  assert.equal(jni.key, 'IsLandingConfirmationNeeded');
  assert.equal(jni.next.data8, '0100000000000000');
  const env = r.lines.find(e => e.kind === 'jni-environment');
  assert.equal(env.jniSeq, jni.seq);
  assert.equal(env.available, true);
});

test('malformed packet is recorded as an error and cannot escape into SDK execution', () => {
  const r = runtime();
  assert.doesNotThrow(() => r.enter('confirmation', [r.pointer(0), r.allocate(), r.allocate()])());
  r.flush();
  assert.equal(r.lines.some(e => e.kind === 'probe-error' && e.site === 'confirmation'), true);
});

test('all function control commands including cancel are recorded with the outgoing action byte', () => {
  const r = runtime();
  const request = r.allocate(), data = r.allocate(), characteristics = r.allocate();
  // libdjibase Buffer::assign (ELF 0x905c4c): capacity +0, length +4, data +8.
  // dji_cmd_req embeds that Buffer at +0x20; it is not a vector.
  r.write(request.address + 0x20, Buffer.from([32, 0, 0, 0, 1, 0, 0, 0]));
  r.putPointer(request.add(0x28), data);
  r.putCharacteristics(characteristics, 'StopAutoLanding');
  r.write(data.address, Buffer.from([14]));
  r.enter('control-send', [r.pointer(0), request, characteristics])(0);
  r.flush();
  const event = r.lines.find(e => e.kind === 'function-control-send');
  assert.equal(event.actionByte, 14);
  assert.equal(event.payload, '0e');
  assert.equal(event.key, 'StopAutoLanding');
  assert.equal(r.lines.find(e => e.kind === 'function-control-dispatch-return').sendSeq, event.seq);
});

test('invalid control payload retains request evidence and the dispatch return', () => {
  const r = runtime();
  const request = r.allocate(), characteristics = r.allocate();
  r.putCharacteristics(characteristics, 'StartAutoLanding');
  r.enter('control-send', [r.pointer(0), request, characteristics])(7);
  r.flush();
  const event = r.lines.find(e => e.kind === 'function-control-send');
  assert.ok(event, 'a malformed optional payload must not discard the command');
  assert.equal(event.invalid, true);
  assert.equal(event.length, 0);
  assert.equal(event.key, 'StartAutoLanding');
  assert.equal(event.header.length, 64);
  const returned = r.lines.find(e => e.kind === 'function-control-dispatch-return');
  assert.equal(returned.sendSeq, event.seq);
  assert.equal(returned.result, 7);
});

test('unreadable optional control metadata does not lose the outgoing payload', () => {
  const r = runtime();
  const request = r.allocate(), data = r.allocate();
  r.write(request.address + 0x20, Buffer.from([32, 0, 0, 0, 1, 0, 0, 0]));
  r.putPointer(request.add(0x28), data);
  r.write(data.address, Buffer.from([2]));
  const unreadable = { add() { throw Error('unreadable metadata'); } };
  r.enter('control-send', [r.pointer(0), request, unreadable])(0);
  r.flush();
  const event = r.lines.find(e => e.kind === 'function-control-send');
  assert.equal(event.payload, '02');
  assert.equal(event.key, null);
  assert.ok(r.lines.some(e => e.kind === 'probe-error' && e.site === 'control-key'));
});

test('native fingerprint failure installs no hooks; partial attach failure detaches all prior hooks', () => {
  for (const options of [{ incompatible: true }, { failAttach: true }]) {
    const r = runtime(options);
    r.flush();
    assert.equal(r.hooks.size, 0);
    assert.equal(r.lines.some(e => e.kind === 'ready'), false);
    assert.equal(r.lines.some(e => e.kind === 'probe-error' && e.site === 'install'), true);
    if (options.failAttach) assert.equal(r.detached(), 2);
  }
});

test('records the legacy input before its lateness filter and correlates its direct cache update', () => {
  const r = runtime();
  const payload = r.allocate(), rsp = r.allocate(), characteristics = r.allocate();
  r.write(payload.address, Buffer.from([0x20, 0]));
  r.write(rsp.address + 0x24, Buffer.from([2, 0, 0, 0])); r.putPointer(rsp.add(0x28), payload);
  r.putCharacteristics(characteristics, 'IsLandingConfirmationNeeded');
  const object = r.allocate(), shared = r.allocate();
  r.putPointer(shared, object);
  const leave = r.enter('avoid-legacy', [r.pointer(0), rsp]);
  r.enter('cache', [r.pointer(0), characteristics, shared, r.pointer(4)]);
  leave();
  r.flush();
  const raw = r.lines.find(e => e.kind === 'raw-avoid-packet');
  const cache = r.lines.find(e => e.kind === 'confirmation-cache-input');
  assert.equal(raw.path, 'legacy');
  assert.equal(raw.sample.payload, '2000');
  assert.equal(cache.packetSeq, raw.seq);
  assert.equal(cache.path, 'legacy');
});

test('function-control result finds PARAM after another hash node and records the response', () => {
  const r = runtime();
  const context = r.allocate(1024), characteristics = context.add(0x38);
  r.putCharacteristics(characteristics, 'StartAutoLanding');
  const first = characteristics.add(0x18).readPointer(), other = r.allocate(512);
  r.write(other.address + 0x10, Buffer.from([3, 0, 0, 0]));
  r.putPointer(other, first); r.putPointer(characteristics.add(0x18), other);
  const rsp = r.allocate(), payload = r.allocate();
  r.write(rsp.address + 0x24, Buffer.from([1, 0, 0, 0])); r.putPointer(rsp.add(0x28), payload);
  r.enter('control-result', [context, r.pointer(0), rsp]);
  r.flush();
  const result = r.lines.find(e => e.kind === 'function-control-result');
  assert.equal(result.key, 'StartAutoLanding');
  assert.equal(result.status, 0);
  assert.equal(result.response.payload, '00');
});

test('native FlightMode key is observed alongside the public FCFlightMode name', () => {
  const r = runtime();
  const listener = r.allocate(); r.putString(listener.add(8), 'FlightMode');
  r.enter('jni', [listener, r.pointer(0), r.pointer(0), r.pointer(0)])();
  r.flush();
  assert.equal(r.lines.some(e => e.kind === 'jni-value' && e.key === 'FlightMode'), true);
});
