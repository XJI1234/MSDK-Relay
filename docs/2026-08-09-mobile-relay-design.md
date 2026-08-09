# MSDK Relay 手机端架构设计

状态：待审阅
版本：0.1.0
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
device-connection MSDK 初始化、遥控器、飞机、对频和能力
telemetry         DJI 状态聚合、遥测快照和发布
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

`relay-gateway` 不得依赖 DJI、Android、Activity、直播、航线或遥测实现。业务模块只能通过 gateway 的公开接口发送结果和注册命令处理器。

## 4. 第一实施单元

第一个一级模块是 `relay-gateway`，第一个二级模块是 `protocol-core`。

`protocol-core` 必须是纯 Kotlin/JVM 模块，不使用 Android SDK、DJI SDK、OkHttp、WebSocket 类型或具体 JSON 库类型。它先定义电脑与手机之间的稳定消息模型和校验规则，再由后续 adapter 连接真实网络。

首个实施单元完成前，不创建 MSDK、WebSocket 或 UI 实现。

## 5. 验收标准

- 契约可以让调用方不阅读实现即可理解连接和消息行为；
- 协议帧的正常、非法、边界、重复和错序情况都有接口级测试要求；
- 单元测试可以在没有 Android 设备和 DJI SDK 的环境运行；
- 更换 JSON 库、WebSocket 库或重连策略不需要修改协议模型；
- 协议错误不会泄漏原始任务内容、路径、令牌或第三方异常堆栈。
