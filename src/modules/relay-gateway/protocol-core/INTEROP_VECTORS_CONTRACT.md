# relay-v1 跨语言互操作测试向量契约

## 目的

本文件约束 Sky Command 桌面端 TypeScript 协议核与 MSDK relay Android 端 Kotlin 协议核之间的固定互操作测试向量。向量的职责是锁定共同帧的线协议语义，防止任一端在重构时悄悄改变编码顺序、字段兼容性或稳定错误码。

它不负责 WebSocket 传输、配对、重连、Android 生命周期、DJI SDK 调用、航线业务含义或真机验证。

## 资源与唯一格式

桌面端资源位于 `tests/fixtures/relay-v1-interop-vectors.json`；Android 端的同一资源位于 `protocol-core/src/test/resources/relay-v1-interop-vectors.json`。两份文件必须是逐字节相同的 UTF-8 JSON，修改后必须比较 SHA-256。

根对象固定为：

```json
{
  "format": "sky-command-relay-interop-v1",
  "revision": 1,
  "vectors": []
}
```

每个向量必须有唯一、稳定的 `id` 和原始紧凑 JSON `wire`。`expected` 只能是以下三种之一：

- `{ "kind": "decoded", "canonicalWire": "..." }`：两端必须解码，并将所得帧重新编码为与 `canonicalWire` 完全一致的 UTF-8 字节。
- `{ "kind": "rejected", "code": "..." }`：两端必须拒绝，且错误码必须一致；不约束具体错误文案。
- `{ "kind": "ignored", "type": "..." }`：两端必须将结构正确的未知帧识别为忽略，不得把它当作协议错误。

## 当前保证的共同语义

向量必须持续覆盖：带结构化 `result` 的 `command-result`、结构化结果内的 JSON `null`、无 `result` 的旧版 `command-result`、`mission-phase`、带标准 Base64 的 `mission-chunk`、非对象 `result`、非规范 Base64、非整数任务序列号以及未来未知帧。

向量中 `canonicalWire` 的字段顺序是协议的一部分。已知帧的编码器必须产生该顺序，不得依赖运行时对象排序。任务块的 Base64 必须是标准、带填充且无空白的规范形式。

## 变更规则

新增兼容帧时，先同时新增两端均会失败的向量测试，再在两端实现共同语义。变更既有向量意味着协议兼容性变化，必须同时更新两端实现、两端资源和本契约；不得以只放宽某一端断言的方式掩盖差异。

执行桌面端 `npm test -- protocol-core-interop-vectors.test.ts` 与 Android 端 `./gradlew.bat :relay-gateway:protocol-core:test --tests com.skycommand.relay.protocol.RelayFrameInteropVectorsTest` 后，两端均须通过；最后比较两份资源的 SHA-256。
