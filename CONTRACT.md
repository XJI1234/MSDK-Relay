# MSDK Relay 手机端程序契约

**文档用途：** Sky Command 电脑端与 MSDK Relay 手机端共同使用的说明书
**当前版本：** v1
**状态：** 手机端重构的总契约
**适用范围：** `D:\Desktop\MSDK-relay` 中的新手机端项目

> 这份文档回答三个问题：手机端负责什么、电脑端怎样调用手机端、双方遇到异常时应怎样理解和处理。
> 电脑端 agent 和手机端 agent 都必须以这份文档为准。具体代码结构可以变化，但本文件规定的对外行为不能被悄悄改变。

底层帧格式、字段长度限制和状态机见：

- [`relay-gateway/CONTRACT.md`](relay-gateway/CONTRACT.md)
- [`relay-gateway/protocol-core/CONTRACT.md`](relay-gateway/protocol-core/CONTRACT.md)
- [`docs/2026-08-09-mobile-relay-design.md`](docs/2026-08-09-mobile-relay-design.md)

---

## 1. 一句话定义

手机端是连接 DJI 设备的 Android 中继程序。

电脑端负责界面、地图、航线文件选择与生成、媒体接收，以及向用户发起操作；手机端负责连接遥控器和飞行器，调用 DJI MSDK 完成设备侧操作，并把设备状态和操作结果回传给电脑端。

手机端不是第二个电脑端，也不是飞行控制器。它只执行本契约列出的设备侧能力。

---

## 2. 手机端能做什么

手机端必须支持以下能力：

1. 连接遥控器和飞行器，并向电脑端报告连接状态。
2. 执行遥控器与飞行器的配对操作，并报告配对状态。
3. 持续发布设备遥测，包括连接状态、飞行状态、电量、位置、直播状态和航线任务状态。
4. 接收电脑端的 RTMP 直播命令，让飞行器视频推送到电脑端指定的 RTMP 地址。
5. 接收电脑端的 KMZ 航线文件，校验完整性并暂存。
6. 根据电脑端命令生成、上传、开始、暂停、恢复和停止航线任务。
7. 在连接断开、设备未就绪或 DJI SDK 拒绝操作时，返回明确的失败结果，而不是假装操作成功。

这些能力分别属于以下一级模块：

| 一级模块 | 唯一职责 |
| --- | --- |
| `app-runtime` | Android 应用生命周期、前台运行、权限和模块组装 |
| `relay-settings` | 电脑端地址、手机设备身份和本地设置 |
| `relay-gateway` | 与电脑端建立 WebSocket 会话，收发协议帧，分发命令和结果 |
| `device-connection` | DJI MSDK 初始化，以及遥控器、飞行器和配对状态 |
| `telemetry` | 读取、整理并发布遥测 |
| `wayline-mission` | KMZ 航线传输、校验、暂存、上传和任务控制 |
| `live-stream` | RTMP 地址校验、直播启动、停止和状态回报 |

模块之间应通过清晰的接口协作。`relay-gateway` 不得直接依赖 DJI SDK；业务模块不得自己创建 WebSocket 连接。这样更换网络库、JSON 库或 DJI SDK 版本时，不需要同时修改所有模块。

---

## 3. 手机端明确不做什么

以下能力不属于当前手机端契约，电脑端不得调用，手机端也不得偷偷实现成半成品：

- 起飞、降落、返航。
- 虚拟摇杆或任何直接飞行动作控制。
- 航线规划、地图编辑和航点绘制。
- 电脑端 UI、地图显示和媒体播放界面。
- 电脑端的 RTMP 接收、转码、HLS 播放和录像管理。
- 长期保留的 localhost HTTP 控制接口。
- 替电脑端决定业务流程，例如自动决定是否开始任务或是否覆盖已有航线。

旧项目中的 localhost HTTP 接口可以作为迁移期间的独立兼容模块暂时存在，但它不能成为新核心模块的依赖，也不能改变本契约规定的 WebSocket 行为。

---

## 4. 谁负责什么

### 4.1 手机端负责

- 使用 Android 和 DJI MSDK 的真实接口完成设备操作。
- 保持设备状态的单一来源，并把最新状态发布给电脑端。
- 检查命令参数、设备前置条件和操作顺序。
- 对航线文件做大小、文件名、分块数量和 SHA-256 校验。
- 只在 DJI SDK 确认成功后返回 `ok: true`。
- 连接中断时清理未完成的传输和未确认的临时状态。
- 屏蔽 Android 路径、DJI 对象、异常堆栈和其他内部实现细节。

### 4.2 电脑端负责

- 启动 WebSocket 服务，并把服务地址提供给手机端。
- 生成唯一的命令 ID，等待并匹配相同 ID 的结果。
- 在调用前检查是否选择了在线手机设备。
- 负责航线规划、文件选择、KMZ 生成和电脑端文件管理。
- 负责接收 RTMP、转码、播放和媒体状态展示。
- 根据遥测和结果向用户展示可理解的状态和错误。
- 不直接依赖手机端的 Kotlin 类、Android 类或 DJI SDK 类型。

### 4.3 人仍然必须完成的事情

自动化不能替代以下现场操作：

- 连接 USB 线或准备网络连接。
- 允许 Android 的 USB 访问和运行时权限。
- 打开遥控器和飞行器。
- 在需要时按设备要求执行物理配对。
- 处理 DJI 的系统提示、固件升级、校准和 FlySafe 提示。
- 在真正执行航线前确认现场安全、空域和设备状态。

---

## 5. 电脑端如何连接手机端

### 5.1 连接方式

电脑端运行 WebSocket 服务。手机端主动连接电脑端地址。连接建立后，手机端发送 `hello`，电脑端验证后返回 `paired`。

当前 v1 是局域网中继协议。`deviceId` 只表示手机身份，不等于安全凭证；正式部署时仍必须保护电脑端服务所在网络。以后增加认证时，应增加兼容的认证字段或提升协议主版本，不能把 `deviceId` 直接当密码使用。

### 5.2 连接顺序

```mermaid
sequenceDiagram
    participant P as 手机端
    participant D as 电脑端
    participant U as 用户
    P->>D: 建立 WebSocket
    P->>D: hello(deviceId, protocolVersion)
    D-->>P: paired(sessionId)
    P-->>D: telemetry(持续发布)
    D->>P: command(id, command)
    P-->>D: command-result(id, ok, detail)
    U->>D: 发起连接、直播或航线操作
```

连接状态的含义如下：

| 状态 | 含义 | 允许的业务行为 |
| --- | --- | --- |
| `STOPPED` | 手机端未启动中继 | 不收发业务消息 |
| `CONNECTING` | 正在连接电脑端 | 等待连接结果 |
| `AWAITING_PAIRING` | WebSocket 已建立，尚未收到 `paired` | 只能等待握手，不能执行命令 |
| `ACTIVE` | 会话已确认 | 可以收发命令、遥测和航线传输 |
| `RECONNECT_WAIT` | 连接失败，等待重连 | 不执行未确认的旧命令 |

要求：

- 手机端连接成功后必须尽快发送一次 `hello`。
- `hello` 必须包含稳定的 `deviceId` 和协议版本 `"1"`。
- 当前电脑端可能省略 `paired.protocolVersion`，v1 手机端必须兼容这种情况，并按 v1 处理。
- 只有收到合法的 `paired` 后，手机端才进入可执行命令的状态。
- 同一个 `deviceId` 的新连接生效后，旧连接必须失效。
- 连接断开时，手机端必须停止接受新命令，清理未完成的航线传输，并让电脑端知道设备已离线。
- 不得把“WebSocket 已连接”误报为“飞行器已连接”。这两个状态必须分别报告。

---

## 6. 通用调用格式

电脑端调用手机端时发送一个 `command` 帧：

```json
{
  "type": "command",
  "id": "电脑端生成的唯一命令ID",
  "command": {
    "name": "命令名称",
    "其他字段": "命令参数"
  }
}
```

手机端必须返回一个 `command-result` 帧：

```json
{
  "type": "command-result",
  "id": "与请求完全相同的命令ID",
  "ok": true,
  "detail": "结果说明，必要时为JSON文本"
}
```

约定：

- `id` 是电脑端匹配结果的唯一依据，手机端不得修改、复用或省略它。
- `ok: true` 只表示该命令已经完成，不表示未来状态一定保持不变。电脑端应同时查看后续遥测。
- `ok: false` 表示命令没有完成。`detail` 必须说明人或电脑端下一步能做什么。
- 当前 v1 的结果错误信息通过 `detail` 传递，没有要求电脑端依赖某一段英文文本。电脑端应展示或分类处理，而不是用字符串猜测内部异常类型。
- `detail` 可以是简短文字，也可以是 JSON 对象的字符串表示。需要读取结构化结果的命令必须在自己的命令小节中约定字段。
- 未知命令必须返回失败结果，不能让 WebSocket 因此崩溃或断开。
- 命令处理失败不能泄露 Android 文件路径、原始 KMZ 内容、访问令牌、DJI 私有对象或异常堆栈。

---

## 7. 当前命令目录

以下是 v1 唯一允许的业务命令。新增命令必须先修改本文件，再修改双方实现和测试。

### 7.1 读取遥测

**命令名：** `telemetry.read`
**请求字段：** 无

作用：要求手机端立即返回一次当前遥测快照。手机端平时也会主动推送遥测，因此这个命令不是唯一的状态来源。

成功时，`detail` 是遥测 `payload` 的 JSON 文本；失败时返回原因。

### 7.2 开始配对

**命令名：** `pairing.start`
**请求字段：** 无

手机端在以下条件满足时才执行：

- DJI SDK 已注册并可用；
- 遥控器已连接；
- 电机未启动；
- 飞行器尚未连接。

成功时返回当前配对状态。配对通常还需要用户按设备要求完成物理操作，命令成功不等于飞行器已经连接。

### 7.3 停止配对

**命令名：** `pairing.stop`
**请求字段：** 无

请求 DJI 停止配对，并返回当前配对状态。没有正在配对时，手机端应返回稳定的当前状态或可理解的失败原因，不能崩溃。

### 7.4 查询配对状态

**命令名：** `pairing.status`
**请求字段：** 无

成功结果至少应包含：

```json
{
  "pairingState": "状态值",
  "aircraftConnected": false,
  "flightControllerConnected": false,
  "aircraftModel": "型号或UNKNOWN",
  "motorsOn": false,
  "sdkRegistered": true
}
```

### 7.5 根据航点生成航线

**命令名：** `wayline.generate`

```json
{
  "name": "wayline.generate",
  "fileName": "survey.kmz",
  "waypoints": [
    { "longitude": 120.123, "latitude": 30.123, "altitude": 80.0 }
  ],
  "speedMetersPerSecond": 5.0
}
```

手机端使用 DJI WPMZ 能力生成并校验 KMZ，然后把它放入“当前待上传航线”。它不负责地图规划，也不负责上传或执行。

要求：

- `fileName` 必须是安全的 `.kmz` 文件名；
- `waypoints` 至少包含一个航点；
- 每个航点必须包含经度、纬度和高度；
- 速度必须是可接受的正数；
- 生成能力或当前飞行器不支持时必须失败；
- 生成成功后，下一次 `wayline.upload` 默认使用这份待上传航线；
- 新的成功生成会替换旧的待上传航线，旧文件必须清理。

### 7.6 上传当前航线

**命令名：** `wayline.upload`

```json
{
  "name": "wayline.upload",
  "confirm": true
}
```

手机端把当前待上传航线交给 DJI 航线任务模块上传。没有待上传航线、飞行器未连接、设备不支持或 `confirm` 不是 `true` 时必须失败。

注意：电脑端发送 KMZ 分块并收到 `mission-result.ok=true`，只代表文件已完整到达手机并已暂存；它不代表 DJI 已经上传。仍必须单独调用 `wayline.upload`。

### 7.7 开始、暂停、恢复和停止航线

命令分别为：

```text
wayline.start
wayline.pause
wayline.resume
wayline.stop
```

四个命令都要求：

```json
{ "name": "对应命令名", "confirm": true }
```

手机端必须把命令交给 DJI 航线任务模块，并在 DJI 操作完成后返回结果。不得把“已发起调用”当成“任务已成功开始/暂停/恢复/停止”。

推荐的正常顺序是：

```text
生成或传输 KMZ
  -> mission-result(ok=true)
  -> wayline.upload
  -> wayline.start
  -> wayline.pause / wayline.resume（按需）
  -> wayline.stop（按需）
```

### 7.8 开始 RTMP 直播

**命令名：** `live-stream.start`

```json
{
  "name": "live-stream.start",
  "rtmpUrl": "rtmp://电脑端地址/live/device-1"
}
```

手机端负责：

- 校验地址是可用的 `rtmp://` 地址；
- 调用 DJI 直播能力；
- 把飞行器视频推到该地址；
- 在成功或失败后更新遥测。

手机端不负责接收、转码或播放视频。视频走 RTMP 通道，不走 WebSocket 命令通道。

### 7.9 停止 RTMP 直播

**命令名：** `live-stream.stop`
**请求字段：** 无

手机端停止直播并返回结果。重复停止应返回稳定结果，不得使连接断开。

---

## 8. 遥测契约

手机端在状态变化时主动发送：

```json
{
  "type": "telemetry",
  "payload": {
    "sdkRegistered": true,
    "remoteControllerConnected": true,
    "flightControllerConnected": true,
    "connected": true,
    "isFlying": false,
    "motorsOn": false,
    "flightMode": "状态值",
    "model": "机型",
    "pairingState": "状态值",
    "batteryPercent": 86,
    "altitude": 80.0,
    "latitude": 30.123,
    "longitude": 120.123,
    "liveStreaming": false,
    "missionExecuteState": "状态值",
    "missionUploadProgress": 0
  },
  "capabilities": {
    "liveVideo": true,
    "waypointMission": true,
    "waypointMissionSupport": "supported",
    "virtualStick": false
  }
}
```

### 8.1 `payload` 字段

字段按职责分组如下：

| 字段 | 含义 |
| --- | --- |
| `sdkRegistered` | DJI SDK 是否已经注册成功 |
| `remoteControllerConnected` | 遥控器是否连接 |
| `remoteControllerType` | 遥控器类型；未知时使用 `UNKNOWN` |
| `flightControllerConnected` | 飞控是否连接 |
| `pairingState` | 当前遥控器/飞行器配对状态 |
| `connected` | 飞行器是否连接；它不是 WebSocket 连接状态 |
| `isFlying` | 飞行器是否正在飞行 |
| `motorsOn` | 电机是否启动 |
| `flightMode` | DJI 当前飞行模式 |
| `model` | 飞行器型号 |
| `altitude` | 当前高度；无法读取时可省略 |
| `batteryPercent` | 电池百分比；无法读取时可省略 |
| `remainingFlightTimeSeconds` | 估计剩余飞行时间；无法读取时可省略 |
| `latitude` / `longitude` | 当前坐标；无法读取时必须省略，不能用 `0` 冒充有效位置 |
| `liveStreaming` | 是否正在直播 |
| `liveStreamNotice` | 直播状态说明 |
| `liveResolution` | 直播分辨率；无直播数据时可省略 |
| `liveFps` | 直播帧率；无直播数据时可省略 |
| `liveVbps` | 直播视频码率；无直播数据时可省略 |
| `liveRtt` | 直播往返时延；无直播数据时可省略 |
| `missionExecuteState` | 航线任务执行状态 |
| `missionUploadProgress` | 航线上传进度，范围 `0` 到 `100` |
| `uploadedMissionFileName` | 当前已上传或暂存的航线文件名 |

无法获得的值应省略或使用明确的空值约定，不能随意填入 `0`、`false` 或空字符串。电脑端必须允许后续增加可选字段。

### 8.2 `capabilities` 字段

| 字段 | 含义 |
| --- | --- |
| `liveVideo` | 当前设备是否支持视频直播链路 |
| `waypointMission` | 当前设备是否可执行航线任务 |
| `waypointMissionSupport` | 航线支持级别，例如 `supported` 或 `unsupported` |
| `virtualStick` | 当前版本固定为 `false`，因为手机端不提供虚拟摇杆 |

能力字段描述“现在能不能做”，不是对所有 DJI 机型的永久承诺。电脑端应根据实时能力决定是否显示或启用操作。

---

## 9. 航线文件传输契约

航线文件传输和航线任务控制是两个不同阶段。

### 9.1 传输流程

电脑端按以下顺序发送：

```text
mission-begin
  -> 一个或多个 mission-chunk
  -> mission-complete
  <- mission-result
```

示例：

```json
{
  "type": "mission-begin",
  "id": "任务传输ID",
  "fileName": "survey.kmz",
  "size": 2048,
  "sha256": "小写的64位SHA-256摘要"
}
```

```json
{
  "type": "mission-chunk",
  "id": "任务传输ID",
  "data": "Base64编码的文件分块"
}
```

```json
{
  "type": "mission-complete",
  "id": "任务传输ID"
}
```

手机端完成校验后返回：

```json
{
  "type": "mission-result",
  "id": "任务传输ID",
  "ok": true,
  "detail": "已暂存"
}
```

### 9.2 不可变规则

- 最大文件大小为 `100 MiB`。
- 当前电脑端使用的分块上限为 `48 KiB`；手机端必须接受不超过该上限的分块。
- `mission-begin` 中的 `size` 必须大于 `0`，且不能超过上限。
- 文件名必须是安全的 `.kmz` 基名，不能包含路径、`..`、控制字符或目录分隔符。
- 手机端必须根据实际收到的字节重新计算 SHA-256，不能只相信电脑端给出的摘要。
- `mission-complete` 前收到的字节数必须恰好等于 `size`。
- 分块 ID 必须与当前 `mission-begin` 的 ID 相同，不能交叉拼接不同任务。
- 同一时间只允许一个活动传输。新传输开始时，旧传输必须被取消并返回失败结果。
- 传输中断、校验失败或手机端重启时，临时文件必须删除，不能把半个 KMZ 当成可用航线。
- 只有 `mission-result.ok=true` 后，该文件才成为当前待上传航线。
- 传输成功不自动上传，不自动开始飞行。

更严格的帧校验规则见 [`protocol-core/CONTRACT.md`](relay-gateway/protocol-core/CONTRACT.md)。

---

## 10. 直播链路契约

直播包含两条不同的通道：

```text
电脑端 --WebSocket命令--> 手机端 --DJI视频/RTMP--> 电脑端媒体服务
电脑端 <--遥测和结果---- 手机端
```

因此：

- WebSocket 只传直播命令、结果和状态，不传视频帧。
- 电脑端必须提供可被手机访问的 RTMP 地址，并在调用 `live-stream.start` 时传入。
- 手机端必须在开始前校验 URL，在 DJI 确认成功后报告 `liveStreaming=true`。
- 手机端收到停止命令、直播失败或设备断开时，应把 `liveStreaming` 更新为 `false` 并填写说明。
- 电脑端媒体服务停止、地址不可达或 FFmpeg 不可用时，电脑端应在发送开始命令前提示问题。
- 直播停止后，电脑端如需再次直播，必须重新发送完整的开始命令；手机端不应依赖旧 URL 的隐式状态。

---

## 11. 错误处理

错误分为三类，电脑端展示时要区分：

### 11.1 连接错误

例如：电脑端服务未启动、地址错误、局域网不可达、握手超时、会话被替换。

这类错误没有可靠的命令结果，电脑端应把设备标记为离线，并让用户先恢复连接。不能把它显示成“航线上传失败”或“飞行器拒绝”。

### 11.2 命令拒绝

例如：未连接飞行器、未注册 SDK、没有待上传航线、缺少 `confirm: true`、当前机型不支持航线。

手机端返回 `ok: false` 和可理解的 `detail`。这表示手机端收到了命令，但根据前置条件没有执行。

### 11.3 DJI 或设备操作失败

例如：DJI SDK 超时、设备返回错误、直播地址不可达、任务上传失败。

手机端必须等待 DJI 操作的最终回调或明确超时后再返回结果。错误信息只保留必要的可诊断内容，不得返回堆栈和私有对象。

当前 v1 的底层协议错误分类见 [`relay-gateway/CONTRACT.md`](relay-gateway/CONTRACT.md) 和 [`protocol-core/CONTRACT.md`](relay-gateway/protocol-core/CONTRACT.md)。这些分类用于实现和测试；跨 WebSocket 的最小兼容结果仍然是 `id`、`ok`、`detail`。

---

## 12. 并发、顺序和重复调用

- 同一 WebSocket 会话中的协议帧必须按接收顺序解析。
- 手机端可以把耗时 DJI 操作放到后台执行，但必须保持结果与命令 ID 一一对应。
- 同一航线传输 ID 不能同时存在两个活动传输。
- `wayline.upload`、`wayline.start`、`wayline.pause`、`wayline.resume`、`wayline.stop` 需要由航线模块判断当前状态；gateway 不替业务模块猜测状态。
- 对不支持的重复调用返回稳定结果或清晰失败，不能因为重复调用断开连接。
- 旧会话的异步回调不得发布到新会话。重连后，电脑端只接受新会话的数据。
- 连接断开后，未返回结果的命令视为未确认。电脑端不得自动假定它成功并继续执行下一步。

---

## 13. 安全和隐私

- `deviceId`、会话 ID、命令 ID 和传输 ID 必须有长度上限。
- 文件名只能表示文件名，不能让调用方控制手机端保存路径。
- 错误结果不得包含完整 Android 路径、原始 KMZ、RTMP 密码、直播令牌、DJI SDK 对象或完整异常堆栈。
- SHA-256 只证明文件传输完整，不证明航线内容符合飞行安全要求；DJI WPMZ 模块仍必须做自己的合法性检查。
- 手机端的日志可以记录用于排查的摘要，但不得把敏感数据完整写入日志。
- v1 当前没有把 pairing token 纳入已固定帧模型。若未来需要认证，必须在协议变更记录中明确认证流程、失败行为和兼容策略。

---

## 14. 兼容性和改动规则

### 14.1 可以直接增加的改动

- 在 `telemetry.payload` 中增加可选字段。
- 在 `capabilities` 中增加可选能力字段。
- 在结果 JSON 中增加电脑端可以忽略的可选字段。
- 增加不影响现有命令含义的日志和内部模块。

### 14.2 必须先修改契约并同步双方的改动

- 修改已有字段的含义、单位、类型或是否必填。
- 修改命令名称、命令顺序或成功条件。
- 修改航线大小、分块大小、文件名规则或摘要算法。
- 修改 `ok/detail` 的含义。
- 删除命令或停止支持现有机型能力。
- 增加需要电脑端配合的新必填字段。

### 14.3 版本规则

- v1 的协议版本是字符串 `"1"`。
- 新增可忽略字段不必提升主版本，但必须更新本契约和测试。
- 改变现有行为或消息结构必须提升协议主版本，或通过双方明确协商的兼容字段完成迁移。
- 不认识的消息类型可以忽略；认识的消息类型但字段非法时必须拒绝，不能静默当成成功。
- 电脑端和手机端不能各自维护一份互相矛盾的命令目录。本文件是唯一的业务目录。

---

## 15. 手机端实现的验收标准

手机端每个一级模块都必须有自己的模块契约，至少写清楚：

1. 模块负责什么，不负责什么。
2. 调用方如何使用它。
3. 输入的必填条件、单位和边界。
4. 成功结果和失败结果。
5. 与其他模块的依赖关系。
6. 断开、超时、重复调用、设备未连接和数据损坏时的行为。
7. 足量测试，以及测试无法覆盖的真实设备条件。

手机端整体验收至少覆盖：

- WebSocket 建立、握手、正常断开、异常断开和重连。
- 未握手、重复握手、未知帧、非法帧和未知命令。
- 命令 ID 关联、命令超时、命令失败和旧会话结果隔离。
- 遥控器未连接、飞行器未连接、SDK 未注册和机型不支持。
- 配对开始、停止、查询和人工配对未完成。
- 遥测字段缺失、设备状态变化、位置不可用和能力变化。
- KMZ 正常传输、空文件、超大文件、错误分块、乱序 ID、断点、重复任务和 SHA-256 不匹配。
- 航线生成、暂存、上传、开始、暂停、恢复、停止和错误顺序。
- RTMP 地址非法、直播启动失败、直播停止、重复停止和设备断开。
- Android 权限、USB、前台服务和 DJI SDK 回调异常。

没有 Android 设备时，协议核心和各模块的业务规则必须仍然可以通过纯 JVM 测试验证；真实 DJI 行为则必须在有设备的集成测试中验证。

---

## 16. 给两个 agent 的协作规则

### 手机端 agent

- 先在对应模块目录写契约，再写实现。
- 先让契约中的状态、输入、输出和错误行为可测试，再接入 Android 或 DJI SDK。
- 不为了“方便电脑端”把业务逻辑塞进 gateway。
- 不改变本文件中的外部命令含义而只修改手机端代码。
- 每完成一个模块，更新本文件引用的模块契约和测试说明。

### 电脑端 agent

- 只依赖本文件和底层协议契约，不读取手机端内部实现来猜调用方式。
- 新增或调整调用前，先更新命令目录和示例。
- 不把 WebSocket 在线误认为飞行器在线，不把文件传输成功误认为 DJI 上传成功。
- 对所有命令使用唯一 ID，并正确处理未返回结果的断线情况。
- 电脑端 UI 应以遥测中的实时能力和状态为依据启用操作。

### 共同规则

- 契约变更先于代码变更。
- 每项行为变更必须同时增加正常、失败、边界和断线测试。
- 任何一方发现本文件与现有代码不一致，都必须先记录差异并决定“修代码”还是“修契约”，不能默默选择。

---

## 17. 最小可用闭环

手机端完成后，双方至少要能走通下面这条闭环：

```text
手机连接电脑
  -> hello / paired
  -> 看到遥控器和飞行器连接遥测
  -> pairing.start / pairing.status（需要时）
  -> 电脑端传输或生成 KMZ
  -> mission-result(ok=true)
  -> wayline.upload
  -> wayline.start
  -> 持续收到航线状态和飞行状态遥测
  -> live-stream.start / live-stream.stop（需要时）
  -> wayline.pause / resume / stop（需要时）
```

其中任何一步失败，都必须能明确知道失败发生在：网络连接、文件传输、设备前置条件、DJI 操作，还是电脑端媒体服务。只有这样，两个项目才能独立重构而不互相猜测。
