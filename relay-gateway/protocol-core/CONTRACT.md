# relay-gateway.protocol-core 二级模块契约

状态：已批准并实现
版本：0.2.0
父模块：[`../CONTRACT.md`](../CONTRACT.md)
模块标识：`relay-protocol-core`
模块目录：`relay-gateway/protocol-core/`
Gradle 路径：`:relay-gateway:protocol-core`

> 本文件是 `protocol-core` 唯一有效的设计、使用说明和验收依据。实现计划、历史讨论和代码注释都不得改变本文件规定的行为。

契约、构建文件、源码和测试必须位于同一模块目录。仓库根目录旧 `protocol-core/` 在本次实现中迁入上述目录，迁移后不得保留第二份实现。

## 1. 唯一职责

`protocol-core` 只负责一件事：**定义不可变协议帧，并安全地编码、解码和校验单个协议帧。**

调用方只需要知道协议帧、协议限制和结构化结果，不需要知道 Jackson、JSON 解析细节、线程、网络库、Android 或 DJI SDK。

本模块必须是纯 Kotlin/JVM 模块。没有 Android 设备、DJI SDK、WebSocket 或文件系统时，全部行为仍然可以测试。

## 2. 负责与明确不负责

### 2.1 负责

- 定义 `hello`、`paired`、`telemetry`、`command`、结果帧和任务传输帧；
- 定义所有协议级长度、大小和复杂度上限；
- 把合法帧编码成 UTF-8 JSON 字节；
- 把一段有界字节解码成合法帧、稳定拒绝结果或未知帧结果；
- 严格校验字段类型、必填字段、协议版本、文件名、SHA-256 和 Base64；
- 保证公开帧、集合和字节数据不可被调用方在构造后修改；
- 把解析器异常转换成不含敏感数据的稳定协议错误。

### 2.2 明确不负责

- 不建立、关闭或重连 WebSocket；
- 不保存当前连接状态、`sessionId` 或 `SessionGeneration`；
- 不判断当前是否允许握手或接收业务帧；
- 不保存当前任务传输 ID，不累计任务字节，不计算传输摘要；
- 不决定重复任务是拒绝还是替换，不取消旧任务；
- 不注册或执行命令，不校验具体业务命令参数；
- 不生成遥测内容，不调用 Android、DJI、网络、数据库或文件系统；
- 不记录原始 JSON、Base64、文件路径、完整标识或第三方异常。

运行状态的唯一所有者固定如下：

| 运行事实 | 唯一所有者 | 本模块只提供 |
| --- | --- | --- |
| 当前连接五态、会话代次和 `sessionId` | `connection-session` | 帧结构与协议版本校验 |
| 当前任务传输、累计字节、摘要和取消 | `mission-transfer` | 单个任务帧结构校验 |
| 命令注册和命令结果关联 | `command-dispatcher` | 命令帧模型 |
| 发送顺序和旧代次隔离 | `outbound-publisher` | 帧编码 |

因此，本模块不得出现 `RelaySessionStateMachine`、`MissionTransferState` 或等价的运行状态仓库。

## 3. 对外接口

具体 Kotlin 名称可以在不改变语义时微调，但公开能力必须等价于：

```text
validate(frame)
  -> Accepted(frame)
   | Rejected(error)

RelayFrameCodec.encode(frame)
  -> Accepted(UTF8 bytes)
   | Rejected(error)

RelayFrameCodec.decode(bytes)
  -> Decoded(frame)
   | Rejected(error)
   | Ignored(type)
```

### 3.1 `validate`

- 只检查一个已经构造的帧是否满足本契约；
- 不读取网络、磁盘、全局状态或当前会话；
- 相同输入必须得到相同结果；
- 失败不得修改输入，也不得抛出第三方异常；
- `TelemetryFrame` 和 `CommandFrame` 中的通用 JSON 值也必须接受复杂度校验。

### 3.2 `encode`

- 先执行与 `validate` 等价的校验，再编码；
- 成功时返回一份新的 UTF-8 字节数组；
- 输出超过单帧上限时返回 `FRAME_TOO_LARGE`；
- 失败时不得返回部分 JSON；
- 不得把 Jackson 类型暴露给调用方。

### 3.3 `decode`

- 输入可以来自不可信网络；
- 所有资源限制必须严格按照 §6 的固定顺序执行；
- 语法、UTF-8、类型、边界和已知字段错误返回 `Rejected`；
- 合法但未知的消息类型返回 `Ignored(type)`；
- 任意输入都不得把 JSON 库异常、`StackOverflowError` 或原始数据抛给调用方。

### 3.4 不可变模型

- 所有帧字段均为只读值；
- `MissionChunkFrame` 构造时复制输入字节，读取时再次返回副本；
- `JsonObject` 构造时复制 Map，并只暴露只读 Map；
- `JsonArray` 构造时复制 List，并只暴露只读 List；
- `ProtocolError` 只能由本模块创建，其他模块只能读取返回的错误；
- 公开签名不得包含 Jackson、Android、DJI、WebSocket、文件、线程池或可变集合类型。

## 4. 固定协议限制

以下数值是双端共同遵守的协议事实，不能只藏在实现常量中：

| 项目 | 固定限制 |
| --- | --- |
| 单个 UTF-8 JSON 帧 | `1..98304` 字节，即最大 96 KiB |
| JSON 容器嵌套深度 | 最大 `32` 层，包含顶层对象 |
| 单帧 JSON token 数 | 最大 `8192` |
| 单个 JSON 字符串 | 最大 `65536` 个 Unicode code point |
| JSON 数字 token | 最大 `128` 个字符 |
| 任意 JSON 字段名 | `1..128` 个 Unicode code point，且不含控制字符 |
| 消息 `type` | `1..64` 个 Unicode code point，非空白且不含控制字符 |
| `deviceId`、`sessionId`、命令 ID、传输 ID | `1..128` 个 Unicode code point，非空白且不含控制字符 |
| 命令名称 | `1..64` 个 Unicode code point，非空白且不含控制字符 |
| 任务文件名 | `1..128` 个 Unicode code point |
| 结果 `detail` | `0..1024` 个 Unicode code point，且不含控制字符 |
| 任务文件大小 | `1..104857600` 字节，即最大 100 MiB |
| 单个任务分块原始字节 | `1..49152` 字节，即最大 48 KiB |
| 单个任务分块 Base64 文本 | 最大 `65536` 个 ASCII 字符 |
| 协议错误消息 | `1..256` 个 Unicode code point，且不含控制字符 |

当前 48 KiB 分块经过标准 Base64 编码后最多为 65536 个字符。96 KiB 单帧上限能够容纳该数据、最长传输 ID 和 JSON 外壳，同时为普通命令和遥测保留余量。

## 5. 帧目录

方向由 gateway 后续路由模块执行，本模块只负责帧结构。当前 v1 帧如下：

### 5.1 `hello`

```text
hello {
  type: "hello",
  deviceId: 合法设备 ID,
  protocolVersion: "1"
}
```

- `deviceId` 和 `protocolVersion` 必填；
- 编码器必须写出 `protocolVersion`；
- 其他版本返回 `PROTOCOL_VERSION_UNSUPPORTED`。

### 5.2 `paired`

```text
paired {
  type: "paired",
  sessionId: 合法会话 ID,
  protocolVersion: "1" | 缺失
}
```

- `sessionId` 必填；
- v1 接收方必须兼容电脑端省略 `protocolVersion`，缺失时按 v1 处理；
- 字段存在但为空、类型错误或不是 `"1"` 时必须拒绝。

### 5.3 `telemetry`

```text
telemetry {
  type: "telemetry",
  payload: object,
  capabilities: object
}
```

- 两个对象都必填；
- 本模块只校验通用 JSON 结构和资源限制，不解释遥测业务字段。

### 5.4 `command`

```text
command {
  type: "command",
  id: 合法命令 ID,
  command: {
    name: 合法命令名称,
    ...业务字段
  }
}
```

- `id`、`command` 和 `command.name` 必填；
- `CommandFrame.fields` 表示除 `name` 外的业务字段；调用方构造时不得再次放入保留字段 `name`；
- 本模块不判断命令名是否已经注册，也不校验具体业务参数。

### 5.5 `command-result`

```text
command-result {
  type: "command-result",
  id: 合法命令 ID,
  ok: boolean,
  detail: 有界字符串
}
```

- `id` 和 `ok` 必填；
- v1 解码兼容缺失 `detail`，缺失时转换为空字符串；
- 编码器始终写出 `detail`。

### 5.6 `mission-begin`

```text
mission-begin {
  type: "mission-begin",
  id: 合法传输 ID,
  fileName: 安全 .kmz basename,
  size: 1..104857600 的整数,
  sha256: 64 个小写十六进制字符
}
```

- `size` 必须是 JSON 整数；
- 整数必须能够被有符号 64 位整数精确表示，禁止截断或回绕；
- 本模块不检查该 ID 是否已开始传输。

### 5.7 `mission-chunk`

```text
mission-chunk {
  type: "mission-chunk",
  id: 合法传输 ID,
  data: 规范 Base64 文本
}
```

- 本模块只校验并解码一个分块；
- 不检查 begin/chunk/complete 顺序，不累计总字节。

### 5.8 `mission-complete`

```text
mission-complete {
  type: "mission-complete",
  id: 合法传输 ID
}
```

本模块不检查是否收到 begin，也不验证最终大小或摘要。

### 5.9 `mission-result`

```text
mission-result {
  type: "mission-result",
  id: 合法传输 ID,
  ok: boolean,
  detail: 有界字符串
}
```

`detail` 的兼容和编码规则与 `command-result` 相同。

## 6. 解码算法和固定顺序

`decode(bytes)` 必须按以下顺序执行：

1. 空字节返回 `INVALID_JSON`；
2. 超过 98304 字节立即返回 `FRAME_TOO_LARGE`，不得先构造 String、JSON 树或 Base64 数组；
3. 使用严格 UTF-8 解码器验证字节；
4. 使用开启重复字段检测和尾随 token 拒绝的 JSON 解析器；
5. 解析器在建立 JSON 树前执行文档长度、深度 `32`、token `8192`、数字字符 `128` 以及不缩小本契约合法输入集合的字符串和字段名安全限制；
6. 顶层不是对象时返回 `INVALID_JSON`；
7. 读取并校验 `type`；
8. 合法未知类型返回 `Ignored(type)`；
9. 已知类型严格读取必填字段，不允许数字、字符串和布尔值之间自动转换；
10. 构造不可变帧并执行字段校验；
11. 只有全部成功才返回 `Decoded(frame)`。

已知帧可以包含额外的可忽略顶层字段，便于 v1 增加兼容字段。重复字段无论是否已知都必须拒绝。

解析完成后仍必须按 Unicode code point 重新校验字符串和字段名。解析器内部按 UTF-16 code unit 计算的安全限制只能更宽，不能把满足 §4 code point 上限且未超过单帧字节上限的输入误拒绝。

JSON 整数只有同时满足 `isIntegralNumber` 和“可精确转换为 Long”时才能读取为 `Long`。例如 `18446744073709551617` 必须拒绝，不能变成 `1`。

## 7. 编码和通用 JSON 规则

编码器必须：

1. 校验帧自身字段；
2. 校验通用 `JsonObject`、`JsonArray`、字段名、字符串、数字和总深度；
3. 使用本契约规定的标准字段名构造 JSON；
4. 编码为 UTF-8；
5. 最后确认输出不超过 98304 字节。

通用 JSON 规则：

- `JsonNumber.value` 必须匹配标准 JSON 数字语法：可选负号、整数部分、可选小数、可选指数；
- 不接受 `+1`、前导零、`NaN`、`Infinity`、空数字或超过 128 字符的数字；
- 任意字段名必须非空白、无控制字符并满足 128 code point 上限；
- 单个字符串不得超过 65536 code point；
- 整个帧的容器深度和 token 总数不得超限；
- `CommandFrame.fields` 包含 `name` 时返回 `INVALID_FIELD`，不得静默覆盖；
- 字符串中的普通换行等内容可以由 JSON 转义，但 ID、类型、命令名和结果详情仍按各自更严格规则校验。

编码期间的实现异常必须转换成稳定错误，不得返回堆栈、第三方消息或部分字节。

## 8. Base64 固定规则

任务分块使用 RFC 4648 标准 Base64 字母表和规范填充：

- 只允许 `A-Z`、`a-z`、`0-9`、`+`、`/` 和末尾 `=`；
- 文本长度必须能被 `4` 整除；
- 必需的 `=` 不得省略；
- 不允许空白、换行、URL-safe 的 `-`/`_`、中间填充或多余填充；
- 文本超过 65536 字符时，在解码前返回 `CHUNK_TOO_LARGE`；
- 语法非法或重新编码后与原文本不一致时返回 `INVALID_BASE64`；
- 解码后为 0 字节时返回 `EMPTY_CHUNK`；
- 解码后超过 49152 字节时返回 `CHUNK_TOO_LARGE`。

规范校验必须发生在大数组分配之前。当前电脑端使用的标准 Base64 编码器与该规则兼容。

## 9. 文件名、标识和结果规则

### 9.1 ID

所有 ID：

- 不能是空字符串或全空白；
- 最大 128 Unicode code point；
- 不能含有 Unicode 控制字符；
- 本模块不执行 trim，不改变调用方给出的合法 ID。

### 9.2 任务文件名

合法任务文件名必须：

- 是 basename，不能包含 `/` 或 `\`；
- 不能包含 `..`；
- 不能是空白，不能含控制字符；
- 最大 128 Unicode code point；
- 以 ASCII 大小写不敏感的 `.kmz` 结尾。

本模块不创建路径，不检查文件是否存在，也不判断 KMZ 是否满足 DJI 航线要求。

### 9.3 SHA-256

`sha256` 必须恰好是 64 个 `0-9` 或 `a-f` 字符。大写、空白、前缀和其他长度均拒绝。

### 9.4 结果详情

`detail` 可以为空，最大 1024 Unicode code point，不能含控制字符。错误详情不得包含完整路径、原始载荷、凭证、DJI 对象或堆栈。

## 10. 错误模型

本模块公开的错误码只描述单帧结构和字段，不描述运行状态：

```text
FRAME_TOO_LARGE
INVALID_UTF8
INVALID_JSON
INVALID_FIELD
INVALID_BASE64
PROTOCOL_VERSION_UNSUPPORTED
INVALID_DEVICE_ID
INVALID_SESSION_ID
INVALID_MESSAGE_ID
INVALID_MESSAGE_TYPE
INVALID_COMMAND_NAME
INVALID_FILE_NAME
INVALID_SHA256
MISSION_SIZE_OUT_OF_RANGE
EMPTY_CHUNK
CHUNK_TOO_LARGE
INVALID_RESULT_DETAIL
```

固定映射：

| 条件 | 错误码 |
| --- | --- |
| 空输入、JSON 损坏、非对象、尾随 token、深度/token/字符串/数字解析限制 | `INVALID_JSON` |
| 单帧字节超限或编码结果超限 | `FRAME_TOO_LARGE` |
| 非法 UTF-8 | `INVALID_UTF8` |
| 缺字段、字段类型错误、Long 溢出、保留字段冲突 | `INVALID_FIELD` |
| `type` 空白、控制字符或超长 | `INVALID_MESSAGE_TYPE` |
| 显式协议版本不是 `"1"` | `PROTOCOL_VERSION_UNSUPPORTED` |
| Base64 非法或不规范 | `INVALID_BASE64` |
| 任务大小不在范围内 | `MISSION_SIZE_OUT_OF_RANGE` |

每个 `ProtocolError` 只包含错误码和 1..256 code point 的固定短消息。消息不得包含输入值、原始 JSON、Base64、文件路径、endpoint、标识、token、解析器消息或异常堆栈。

以下错误属于其他模块，不得重新加入本模块：

```text
NOT_CONNECTED
HANDSHAKE_TIMEOUT
DUPLICATE_HANDSHAKE
TRANSFER_NOT_ACTIVE
TRANSFER_ALREADY_ACTIVE
TRANSFER_SUPERSEDED
TRANSFER_SIZE_MISMATCH
TRANSFER_CHECKSUM_MISMATCH
```

## 11. 状态、生命周期和并发

- 本模块没有 start、stop、connect、disconnect、begin、append、complete 或 reset 生命周期；
- 本模块不保存上一次调用、当前会话或当前传输；
- `validate`、`encode` 和 `decode` 的结果只由本次输入决定；
- 同一个 codec 实例必须允许多个线程并发调用；
- 任意失败都不得影响下一次调用；
- 未知帧不得改变后续已知帧的解析行为。

## 12. 依赖和可替换性

允许的生产依赖：

```text
Kotlin/JVM 标准库
JDK UTF-8、Base64 和不可变数据支持
隐藏在 RelayFrameCodec 内部的 JSON 解析器
```

禁止依赖或公开：

```text
Android
DJI SDK
OkHttp、Ktor、WebSocket
Activity、Service、Context
文件系统、数据库
线程池、协程作用域
第三方 JSON 类型
```

替换 JSON 库时，只要本契约全部测试继续通过，调用方不需要修改。

## 13. 安全和隐私

- 先检查字节大小，再进行 UTF-8、JSON 或 Base64 分配；
- JSON 解析必须同时限制文档长度、深度、token、字符串、数字和字段名；
- 不允许宽松类型转换、重复字段或尾随内容；
- 所有 ByteArray 和集合跨公开边界时必须防御性复制；
- 错误和日志不得回显网络输入；
- SHA-256 只证明传输完整性，不证明航线业务合法；
- v1 协议没有认证，本模块不得把字段校验描述成身份认证。

## 14. 兼容和变更规则

### 14.1 v1 兼容行为

- 编码的协议版本固定为字符串 `"1"`；
- `paired.protocolVersion` 缺失时按 v1 接受；
- 标准带填充 Base64 与当前电脑端兼容；
- 48 KiB 分块和 100 MiB 文件上限不变；
- 未知但结构合法的消息类型继续返回 `Ignored`；
- 已知帧的额外顶层字段可以忽略。

### 14.2 可以兼容增加

- 增加新的可选字段；
- 增加新的帧类型，并让旧版本返回 `Ignored`；
- 更换内部 JSON 库；
- 优化校验实现但不改变接受集合和错误分类。

### 14.3 必须先修改契约并同步电脑端

- 修改任何固定上限；
- 修改字段类型、必填性、单位、大小写或成功条件；
- 接受非规范 Base64；
- 改变未知帧或额外字段行为；
- 增加认证字段或新协议主版本；
- 把会话或任务传输状态重新放入本模块。

## 15. 必测矩阵

### 15.1 每种合法帧

- 九种帧分别完成编码和解码往返；
- `paired` 分别覆盖显式 `"1"` 和省略版本；
- 两种结果帧分别覆盖有详情和缺失详情；
- 通用 JSON 覆盖 null、字符串、数字、布尔、数组和嵌套对象；
- 已知帧额外字段可忽略，未知合法类型返回 `Ignored`。

### 15.2 固定边界

- 单帧 98304 字节边界和 98305 字节拒绝；
- JSON 深度 32 接受、33 拒绝；
- token 8192 边界和超限；
- JSON 字符串、数字、字段名和消息类型的最大值及超限值；
- ID、命令名、文件名和详情分别测试空、最大值、超限、控制字符和 Unicode code point；
- 任务大小 `1`、`104857600`、`0`、负数和 `104857601`；
- 超出 Long 的正负整数必须拒绝且不能回绕。

### 15.3 JSON 和字段错误

- 空字节、空白文本、空对象、数组、null 和普通标量；
- 非法 UTF-8、截断 JSON、重复字段、尾随 token；
- 每个已知帧缺少必填字段和每种错误字段类型；
- `CommandFrame.fields` 的保留 `name` 冲突；
- 非法 `JsonNumber` 和过深的程序内构造对象；
- 编码结果超限返回 `FRAME_TOO_LARGE`。

### 15.4 文件名和摘要

- `/`、`\`、`..`、NUL、控制字符、空白和错误扩展名；
- `.kmz`、`.KMZ` 和混合大小写扩展名；
- 文件名 code point 最大值和超限值；
- SHA-256 长度不足、超长、大写和非法字符。

### 15.5 Base64

- 1 字节和 49152 字节正常分块；
- 空分块和 49153 字节分块；
- 缺失填充、错误填充、多余填充、中间填充；
- 空白、换行、URL-safe 字符和非法字符；
- 非规范 pad bits；
- 编码文本超限时必须在 Base64 解码前拒绝。

### 15.6 健壮性和边界依赖

- 使用固定随机种子生成至少 10000 个任意字节输入，全部只能返回三种 `DecodeResult`；
- 解析器异常不得泄漏；
- 多线程并发 encode/decode 不串扰；
- main 源码禁止依赖扫描无结果；
- `connection-session` 完整测试继续通过。

## 16. 验收标准

只有同时满足以下条件，状态才能改为“已批准并实现”：

1. 契约中的每项负责、不负责、限制和错误都能定位到实现和测试；
2. 实现中不存在会话或任务传输运行状态；
3. 超大整数不会回绕，超大帧不会进入 JSON 解析，超大 Base64 不会先分配大数组；
4. 九种帧和全部固定边界都有自动化测试；
5. 所有测试零失败、零错误、零跳过；
6. 至少三次 fresh 模块测试通过，一次完整仓库测试通过；
7. `git diff --check` 无错误；
8. Android、DJI、WebSocket、文件系统和第三方类型边界扫描无违规。
