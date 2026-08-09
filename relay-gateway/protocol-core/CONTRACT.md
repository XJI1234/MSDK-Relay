# relay-gateway.protocol-core 二级模块契约

状态：待审阅
版本：0.1.0
父模块：`../CONTRACT.md`
模块标识：`relay-protocol-core`

## 1. 模块目的

`protocol-core` 定义并校验 Sky Command 与 MSDK Relay 之间的消息模型。它只处理协议数据，不建立网络连接，不调用 Android 或 DJI API，也不读取文件。

## 2. 消息帧

协议帧使用 UTF-8 JSON 文本表示，但 JSON 库是实现细节。模块对外只暴露不可变的 Kotlin 数据类型和编码/解码结果。

```text
hello {
  type: "hello",
  deviceId: non-empty string,
  protocolVersion: "1"
}

paired {
  type: "paired",
  sessionId: non-empty string,
  protocolVersion: "1"
}

telemetry {
  type: "telemetry",
  payload: object,
  capabilities: object
}

command {
  type: "command",
  id: non-empty string,
  command: object with non-empty name
}

command-result {
  type: "command-result",
  id: non-empty string,
  ok: boolean,
  detail: bounded string
}

mission-begin {
  type: "mission-begin",
  id: non-empty string,
  fileName: safe basename ending in .kmz,
  size: integer from 1 through 104857600,
  sha256: 64 lowercase hexadecimal characters
}

mission-chunk {
  type: "mission-chunk",
  id: non-empty string,
  data: base64 of 1 through 49152 bytes
}

mission-complete {
  type: "mission-complete",
  id: non-empty string
}

mission-result {
  type: "mission-result",
  id: non-empty string,
  ok: boolean,
  detail: bounded string
}
```

`49152` bytes是当前桌面端的任务分块大小。`104857600` bytes是当前桌面端和手机端共同使用的 100 MiB 任务上限。

## 3. 编码接口

```text
RelayFrameCodec.encode(frame) -> UTF8 bytes | EncodeError
RelayFrameCodec.decode(bytes) -> DecodeResult
```

`DecodeResult` 只有三种结果：

```text
Decoded(frame)
Rejected(error)
Ignored(unknownType)
```

语法损坏、非法 UTF-8、字段类型错误、字段越界、非法文件名和非法 Base64 必须返回 `Rejected`，不得抛出第三方异常给调用方。未知但结构合法的扩展帧返回 `Ignored`，不得让连接因此断开。

## 4. 协议状态机

```text
DISCONNECTED
  -> HELLO_SENT
  -> PAIRED
  -> ACTIVE
```

规则：

- 手机端只能在 `HELLO_SENT` 等待 `paired`；
- `telemetry`、`command`、任务帧和结果帧不能在 `PAIRED` 之前生效；
- `paired` 缺少 session ID、版本不兼容或重复握手必须拒绝；当前 v1 对端可以省略 `protocolVersion`，省略时按 v1 处理；
- 未知消息类型可以忽略，但已知消息的字段错误必须拒绝；
- `mission-chunk` 必须先有相同 ID 的 `mission-begin`；
- `mission-complete` 前收到的总字节数必须等于声明大小；
- 同一任务 ID 的重复 `mission-begin` 必须返回 `TRANSFER_SUPERSEDED`；
- 任务摘要必须针对实际收到的原始字节计算；
- 任意断开都会清除当前任务帧状态。

## 5. 安全和隐私

- `deviceId`、session ID、命令 ID 和文件名都必须限制长度；
- `fileName` 只能是 basename，不得包含 `/`、`\\`、`..`、NUL 或控制字符；
- 协议错误不得返回完整 JSON、完整 Base64、路径、令牌或 SDK 异常；
- `sha256` 只用于完整性校验，不代表 DJI 任务合法性；
- DJI WPMZ 合法性必须由 `wayline-mission` 调用 DJI WPMZ SDK 再次验证；
- 当前桌面端未强制 pairing token，正式版本必须在一级契约中明确鉴权策略，不能默认为安全。

## 6. 兼容策略

- 新实现发送的 `protocolVersion` 为 `1`；v1 接收方必须兼容当前桌面端省略该字段的 `paired` 帧；
- 新增字段默认只能是可忽略字段；
- 改变字段含义、消息顺序或大小限制必须提升主版本；
- 不认识主版本时返回 `PROTOCOL_VERSION_UNSUPPORTED` 并关闭会话；
- 命令名未知不升级协议版本，只返回 `INVALID_COMMAND`。

## 7. 测试要求

至少覆盖：

- 每种合法帧的编码和解码；
- 空文本、空对象、非对象 JSON、非法 UTF-8 和重复字段；
- 缺少字段、错误类型、超长字段和越界数字；
- 全部路径穿越、控制字符和大小写扩展名场景；
- Base64 截断、空块、超大块和非法字符；
- 握手前后帧、重复握手、未知帧和版本不兼容；
- 任务 begin/chunk/complete 的正确、错序、重复、超量、少量和摘要错误；
- 任意输入不会抛出 JSON 库、Android 或第三方库异常。
