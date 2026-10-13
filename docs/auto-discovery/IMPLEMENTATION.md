# 正式 Relay 自动发现接线检查表

更新日期：2026-10-13。状态：接线设计已核对现有源码，以下正式手机能力尚未实现。共同协议以 Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md 为准；两个仓库并排克隆时可访问[协议](../../../Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md)和[设计](../../../Sky-Command/docs/auto-discovery/DESIGN.md)。GitHub 单仓库阅读需查看配套电脑开发分支。

## 下一步实施顺序

1. 增加纯 Kotlin/JVM 公告解析与候选协调模块，按共享 JSON 示例验证 UDP 39082、512 字节上限、字段类型、3 秒观察/8 秒过期、多电脑冲突和缓存容量保护；全部使用单调时钟。探针 39081 不替换正式监听。
2. 更新 relay-gateway 与 connection-session CONTRACT，为未选 endpoint、暂停旧地址重试、串行替换 endpoint 定义入口/结果和清理行为；补充旧代次回调、同时断线/换地址、重复公告不重置退避的测试。现有 config.endpoint 为不可变必填字符串，不能宣称已支持热更新。
3. 更新 app-runtime、foreground-service 与 settings 契约，令首次授权和前台服务启动不依赖电脑地址；无候选时容器运行、gateway STOPPED。UDP 监听/网络回调归唯一前台运行容器，Activity 只显示状态并重新绑定权限适配器。
4. 实现 Android Wi-Fi socket/网络代次、容器与 MobileRelayGraph 接线。切换电脑只清理并替换 gateway 的连接配置，不关闭整个 graph、不重建 DJI/图传/任务对象，不恢复旧命令或任务传输。
5. 调整主界面自动授权后启动、搜索/冲突/连接状态与隐藏手动入口，移除主界面停止按钮；核对同事的对频迁移分支后再改相关布局，避免重复实现。
6. 构建并安装正式 Relay，完成手机先开/电脑先开、后台/锁屏/划掉卡片、电脑退出再上线/IP 变化、Wi-Fi 重连/跨 AP、2 手机/2 无人机及 15 秒目标验收。

## 已核对的生命周期风险

- MainActivity 当前由 saveAndStart 输入 endpoint 后才建图；onDestroy 使用 RelaySurfaceRetention 与 RelayRuntimeHolder 保留部分运行状态，不是前台服务拥有完整图。
- MobileRelayGraph.create 同时创建 gateway、device、telemetry、stream、wayline，不能因 endpoint 改变重复建图。
- RelayBootstrapModule 启动设备后调用 startGateway；应将发现启动与 gateway 有地址后启动解耦，保留现有设备/遥测业务规则。
- RelayForegroundService 目前只管理通知；START_STICKY 不证明空 intent 下能恢复业务。第一版不承诺进程自动恢复，服务真实销毁时须统一清理资源，划掉卡片保持运行需实机验证。
- 当前用户确认的是产品行为；本检查表属于实施约束，不更改现有模块 API，也不是完成或验收证据。
