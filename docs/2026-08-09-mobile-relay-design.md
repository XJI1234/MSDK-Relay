# MSDK Relay 手机端架构设计

状态：已修订，待审阅
版本：0.2.0
日期：2026-08-09

## 1. 目标

MSDK Relay 是 Sky Command 的 Android DJI MSDK 网关。手机端只负责连接 DJI 设备、读取设备状态、接收电脑命令并调用 MSDK，业务操作由电脑端发起。

本阶段保留的业务范围：

- 遥控器和飞机连接、对频；
- DJI 状态和遥测发布；
- 相机直播配置、启动、停止和状态发布；
- KMZ 航线接收、校验、暂存、上传、开始、暂停、恢复、停止和状态发布。

本阶段明确不负责：

- 起飞、降落、返航和其他直接飞行动作；
- 航线规划和航线编辑；
- 地图、桌面 UI 和电脑端媒体接收；
- 旧 localhost HTTP 控制接口的长期保留。

## 2. 一级模块

```text
app-runtime       Android 生命周期、前台服务、权限和 USB 入口
relay-settings    电脑地址、设备身份和本地配置
relay-gateway     电脑 WebSocket 会话、协议和命令分发
device-connection MSDK 初始化、设备连接、对频、状态唯一来源和 DJI 操作调度
telemetry         DJI 状态聚合、即时遥测查询、遥测快照和发布
wayline-mission   KMZ 传输、校验、任务上传和任务控制
live-stream       直播配置、推流控制和直播状态
```

`app-runtime` 是组合根，不拥有业务规则。旧 HTTP 接口若暂时保留，放在独立的 `legacy-local-api`，不得成为上述模块的依赖。

## 3. 依赖规则

```text
app-runtime
    -> relay-settings
    -> relay-gateway
    -> device-connection
    -> telemetry
    -> wayline-mission
    -> live-stream

telemetry
    -> device-connection
    -> relay-gateway

wayline-mission
    -> device-connection
    -> relay-gateway

live-stream
    -> device-connection
    -> relay-gateway
```

### 3.1 二级模块设计

`relay-gateway`：

```text
protocol-core          协议帧模型、编码、解码和校验
transport-adapter      网络库适配，不理解业务
connection-session     握手、会话代次和生命周期
command-dispatcher     命令名路由、命令 ID 关联和错误转换
mission-transfer       航线帧顺序、大小、摘要和取消
outbound-publisher     所有发送帧的顺序和当前会话校验
```

`device-connection`：

```text
sdk-lifecycle              DJI SDK 注册、初始化和注销
dji-operation-coordinator  所有 DJI 操作统一串行、超时和取消
device-state-store         SDK、遥控器、飞行器和配对的唯一状态来源
remote-controller-link     遥控器连接、型号和固件
aircraft-link              飞行器和飞控连接、型号
pairing-controller         开始、停止和查询配对
device-capability-reader   根据型号和连接状态计算设备能力
```

`telemetry`：

```text
snapshot-assembler       组装一次不可变遥测快照
capability-calculator    生成对外能力字段
telemetry-command-handler 处理 telemetry.read
telemetry-publisher      持续发布、合并和节流遥测
```

`wayline-mission`：

```text
wayline-command-handler  解释 wayline.* 命令
mission-staging           保存已校验的 KMZ 字节和文件生命周期
wpmz-generator            根据航点生成和校验 WPMZ/KMZ
mission-uploader          上传当前暂存航线并报告进度
mission-executor          开始、暂停、恢复和停止任务
mission-state-store       保存文件元数据、上传进度和执行状态
```

`live-stream`：

```text
stream-command-handler    解释 live-stream.* 命令
stream-config-validator   校验 RTMP 配置
dji-stream-adapter        适配 DJI 直播 SDK
stream-state-store        保存直播状态和指标
```

`app-runtime` 和 `relay-settings` 的二级模块分别负责应用组装/前台运行/权限，以及设置存储/电脑端地址/设备身份。它们不拥有任何 DJI 业务状态。

### 3.2 关键不变量

- `device-state-store` 是设备连接事实的唯一来源；遥测只能读取它，不能复制一份设备真相。
- `dji-operation-coordinator` 是所有 DJI SDK 操作的唯一执行调度入口；直播、航线和配对不能各自创建执行器。
- `telemetry-command-handler` 只处理即时查询；`telemetry-publisher` 只处理持续发布。
- `mission-staging` 只拥有文件字节；`mission-state-store` 只拥有任务元数据和状态。
- `connection-session` 唯一创建和失效会话代次；`outbound-publisher` 只能验证代次。
- 文件传输成功、DJI 上传成功和任务开始执行是三个不同的状态。
- 电脑 WebSocket 通道只传命令、结果和状态；视频通过 RTMP 通道传输。

### 3.3 业务覆盖

设计必须覆盖：连接、配对、遥测持续发布、`telemetry.read`、RTMP 开始/停止、KMZ 接收与校验、航线生成、上传、开始、暂停、恢复和停止。起飞、降落、返航、虚拟摇杆、地图规划和电脑端媒体接收明确排除。

`relay-gateway` 不得依赖 DJI、Android、Activity、直播、航线或遥测实现。业务模块只能通过 gateway 的公开接口发送结果和注册命令处理器。

### 3.4 状态归属

每一种业务事实只能有一个状态拥有者，其他模块只能读取不可变快照：

| 状态事实 | 唯一拥有者 | 其他模块的使用方式 |
| --- | --- | --- |
| SDK 是否可用、遥控器/飞行器是否连接、配对状态 | `device-state-store` | 通过 `device-connection` 只读接口读取 |
| 电脑 WebSocket 会话、会话代次、握手状态 | `connection-session` | gateway 内部通过公开会话接口读取 |
| 当前直播状态和直播指标 | `stream-state-store` | `telemetry` 读取，`stream-command-handler` 更新 |
| 当前航线文件元数据、上传进度、执行状态 | `mission-state-store` | `telemetry` 读取，航线命令模块更新 |
| KMZ 文件字节和临时文件生命周期 | `mission-staging` | 通过暂存接口交接，不复制到状态仓库 |
| 对外遥测发送节奏和最近一次发布记录 | `telemetry-publisher` | 其他模块只调用发布接口 |

状态的“拥有者”负责状态变更、并发控制和清理；状态的“读取者”不得通过 DJI SDK、文件系统或网络库绕过拥有者重新读取同一事实。

关键状态机只允许由对应拥有者推进：

```text
设备 SDK：未注册 -> 初始化中 -> 可用 / 初始化失败
配对：    未开始 -> 请求中 -> 等待人工操作 -> 已配对 / 失败
直播：    已停止 -> 启动中 -> 推流中 -> 停止中 -> 已停止 / 失败
```

状态机中的“请求中”只表示模块已经接受请求；只有 DJI 最终回调确认成功，才能进入“可用”“已配对”或“推流中”。

### 3.5 航线工作流

航线工作流必须按以下阶段区分，阶段之间不能用一个布尔值代替：

```text
没有当前航线
  -> 已生成或已完整暂存
  -> 已上传到 DJI
  -> 执行中
  -> 已暂停 / 已停止 / 已完成 / 已失败
```

其中：

- 电脑端负责地图规划和航点业务规则；
- `wpmz-generator` 只把电脑端提供的完整计划转换成 DJI WPMZ/KMZ，不做地图规划；
- `mission-staging` 只接收完整文件并保证文件生命周期；
- `mission-uploader` 只负责 DJI 上传；
- `mission-executor` 只负责开始、暂停、恢复和停止；
- 任一阶段失败都必须保留可理解的失败状态，不得把“请求已发出”当成“DJI 已确认成功”。

### 3.6 推荐实施顺序

每个二级模块都必须先完成自己的 `CONTRACT.md` 和纯规则测试，再接入真实 Android 或 DJI 适配器。推荐顺序如下：

1. `relay-gateway/protocol-core`：稳定帧模型、固定限制、编码、解码和单帧字段校验；不保存会话或任务传输状态。
2. `relay-gateway/connection-session`：握手、会话代次、断线和旧会话隔离。
3. `relay-gateway/outbound-publisher`、`command-dispatcher`、`mission-transfer`：发送顺序、命令关联和 KMZ 传输交接。
4. `device-connection`：SDK 生命周期、统一 DJI 操作调度、设备状态唯一来源和设备能力。
5. `telemetry`：快照组装、即时查询和持续发布，三者分别测试。
6. `wayline-mission`：暂存、WPMZ 生成、上传、执行和任务状态。
7. `live-stream`：配置校验、DJI 直播适配和直播状态。
8. `app-runtime`、`relay-settings`：最后完成应用组装、前台服务、权限和设置接入。

每一步只依赖前一步已经批准的公开接口；真实 DJI 回调只允许出现在适配器内部。

## 4. 第一实施单元

第一个一级模块是 `relay-gateway`，第一个二级模块是 `protocol-core`。

`protocol-core` 必须是纯 Kotlin/JVM 模块，不使用 Android SDK、DJI SDK、OkHttp、WebSocket 类型或具体 JSON 库类型。它先定义电脑与手机之间的稳定消息模型和校验规则，再由后续 adapter 连接真实网络。

首个实施单元完成前，不创建 MSDK、WebSocket 或 UI 实现。

## 5. 验收标准

- 契约可以让调用方不阅读实现即可理解连接和消息行为；
- 协议帧的正常、非法、边界、重复和错序情况都有接口级测试要求；
- 单元测试可以在没有 Android 设备和 DJI SDK 的环境运行；
- 更换 JSON 库、WebSocket 库或重连策略不需要修改协议模型；
- 所有 DJI 操作通过统一调度入口串行执行，业务模块不自建 DJI 执行线程；
- 即时遥测查询和持续遥测发布分别有独立测试入口；
- 文件字节和任务状态分别由不同模块拥有；
- 协议错误不会泄漏原始任务内容、路径、令牌或第三方异常堆栈。
- 每个已实现的二级模块都有独立 `CONTRACT.md`，且契约中的接口、状态、错误和测试要求与上级契约一致；
- 航线生成、文件暂存、DJI 上传和任务执行可以分别验证，任一阶段失败不会被伪装成其他阶段成功。
