# 正式 Relay 自动发现接线检查表

更新日期：2026-10-14。状态：纯 JVM 公告解析/候选核心、gateway/session 可选连接选择 API 及发现到会话桥接已完成；独立 Android WLAN 接收适配器已完成；无地址权限/前台运行容器契约已确定，正式 App 接线待实施。共同协议以 Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md 为准；两个仓库并排克隆时可访问[协议](../../../Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md)和[设计](../../../Sky-Command/docs/auto-discovery/DESIGN.md)。GitHub 单仓库阅读需查看配套电脑开发分支。

## 下一步实施顺序

1. **已完成**纯 Kotlin/JVM relay-discovery，按共享 JSON 示例验证 UDP 39082、512 字节上限、字段类型、3 秒观察/8 秒过期、多电脑冲突与容量保护；使用单调时钟，网络/选择令牌隔离迟到回调。接口见模块 CONTRACT，实际报告见本目录 README；探针 39081 未替换正式监听。
2. **已完成** relay-gateway 与 connection-session 契约/API 扩展：初始 endpoint 可为 null，selectConnection 支持等待、暂停和串行替换，同一选择对象不重置退避；ACTIVE 前同步门禁及结束事件的 endedGeneration 隔离迟到 paired/断线通知。见 [连接选择契约](../../src/modules/relay-gateway/connection-session/CONNECTION-SELECTION.md)。
   **已完成桥接子阶段**：独立 JVM `:app-runtime:discovery-session-bridge` 持有发现核心、稳定映射选择对象并组合 gateway 公开接口；22 项测试结果见 README，正式 App 尚未依赖该模块。接口/锁顺序见 [桥接契约](../../src/modules/app-runtime/discovery-session-bridge/CONTRACT.md)。
3. **契约已完成，接线待实现**：[运行容器与无地址启动契约](RUNTIME-CONTRACT.md) 固定 applicationContext 对象归属、Activity attach/detach、权限拒绝恢复、停止收敛及 AUTO/MANUAL 独占控制。无候选时容器运行、gateway STOPPED；settings schema 1 不变，保存地址仅供手动调试。
4. **已完成接收子阶段**：[Android WLAN 接收适配器](../../src/modules/relay-discovery/android-discovery-adapter/CONTRACT.md) 的 20 项测试和 Android Kotlin 编译通过，Lint 缺少离线依赖未执行；接着实现容器与 MobileRelayGraph 接线。切换电脑只清理并替换 gateway 的连接配置，不关闭整个 graph、不重建 DJI/图传/任务对象，不恢复旧命令或任务传输。
   **已完成生命周期准备**：权限 detach/onDestroy 和服务异常销毁 failed 已实现，新增 12 项测试、相关 56 项报告及 App Kotlin 编译通过（重跑范围见 README）。下一子阶段实现模式门禁/连接控制，再接 applicationContext graph 与进程容器。
5. 调整主界面自动授权后启动、搜索/冲突/连接状态与隐藏手动入口，移除主界面停止按钮；核对同事的对频迁移分支后再改相关布局，避免重复实现。
6. 构建并安装正式 Relay，完成手机先开/电脑先开、后台/锁屏/划掉卡片、电脑退出再上线/IP 变化、Wi-Fi 重连/跨 AP、2 手机/2 无人机及 15 秒目标验收。

下一步接第 4 项中的运行容器与 graph。桥接已实现：一个发现选择令牌对应一个 ConnectionSelection，重复公告/tick 复用对象；CONFLICT/WAITING/OBSERVING 暂停未 ACTIVE 会话。paired 在同步门禁按映射激活并记录实际代次，结束通知只清理匹配的获准代次；tick 和新代次门禁还会识别旧 ACTIVE 已结束，避免通知滞后时绕过最新冲突。状态锁不跨 session 控制调用，注销监听在控制锁外完成。容器通过稳定门禁路由器和串行模式切换独占 gateway；返回 AUTO 新建桥接。正式接收适配器提供接收子网和网络令牌，使用 513 字节缓冲检测超长报文；复用 socket 缓冲时仅传入本次实际长度的拷贝。真实服务销毁须停止网络/定时来源，STOPPING 收敛后释放 graph；Activity 销毁只 detach。

## 已核对的生命周期风险

- MainActivity 当前由 saveAndStart 输入 endpoint 后才建图；onDestroy 使用 RelaySurfaceRetention 与 RelayRuntimeHolder 保留部分运行状态，不是前台服务拥有完整图。
- MobileRelayGraph.create 同时创建 gateway、device、telemetry、stream、wayline，不能因 endpoint 改变重复建图。
- RelayBootstrapModule 启动设备后调用 startGateway；应将发现启动与 gateway 有地址后启动解耦，保留现有设备/遥测业务规则。
- RelayForegroundService 仍只管理通知，现已补齐非预期销毁 failed 和空 intent 无活动操作时退出；START_STICKY 不证明业务恢复。权限适配器已支持宿主 detach/onDestroy，应用级 USB 观察保留；完整容器释放与划卡保持运行仍待接线/实机验证。
- 当前用户确认的是产品行为；已实现的模块能力以契约及测试为准，待接线项不是完成或实机验收证据。
