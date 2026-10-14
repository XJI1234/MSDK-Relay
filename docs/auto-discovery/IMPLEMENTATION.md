# 正式 Relay 自动发现接线检查表

2026-10-14 晚实机阶段：APK 通过 USB 安装；校园网设备间不可达，用户随后选择热点对照与 USB 调试。热点单手机正式 AUTO/paired、Wi-Fi 重连、短时后台、电脑退出等待和重启恢复已有证据，两次恢复采样在 15 秒内，详见 [实机记录](../../../Sky-Command/docs/auto-discovery/DEVICE-RESULTS-20261014.md)。后续补锁屏/划卡等边界、多设备及校园网复测，电脑端启动交由用户手动操作；热点通过不能替代整体验收。

更新日期：2026-10-14。正式 App 的发现、无地址授权/进程容器及界面源码接线完成，编译/测试/打包通过，APK 已安装；热点单手机场景已有证据，校园网与其他场景待验收。共享协议见 Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md。

## 下一步实施顺序

1. **已完成**纯 Kotlin/JVM relay-discovery，按共享 JSON 示例验证 UDP 39082、512 字节上限、字段类型、3 秒观察/8 秒过期、多电脑冲突与容量保护；使用单调时钟，网络/选择令牌隔离迟到回调。接口见模块 CONTRACT，实际报告见本目录 README；探针 39081 未替换正式监听。
2. **已完成** relay-gateway 与 connection-session 契约/API 扩展：初始 endpoint 可为 null，selectConnection 支持等待、暂停和串行替换，同一选择对象不重置退避；ACTIVE 前同步门禁及结束事件的 endedGeneration 隔离迟到 paired/断线通知。见 [连接选择契约](../../src/modules/relay-gateway/connection-session/CONNECTION-SELECTION.md)。
   **已完成桥接并接入正式 App**：JVM `:app-runtime:discovery-session-bridge` 持有发现核心、稳定映射选择对象并组合 gateway 公开接口；22 项测试结果见 README。接口/锁顺序见 [桥接契约](../../src/modules/app-runtime/discovery-session-bridge/CONTRACT.md)。
3. **契约与正式 App 源码接线已完成，实机待验收**：[运行容器与无地址启动契约](RUNTIME-CONTRACT.md) 固定 applicationContext 对象归属、Activity attach/detach、权限拒绝恢复、停止收敛及 AUTO/MANUAL 独占控制。无候选时容器运行、gateway STOPPED；settings schema 1 不变，保存地址仅供手动调试。
4. **已完成 Android 接收与容器/graph 接线**：[Android WLAN 接收适配器](../../src/modules/relay-discovery/android-discovery-adapter/CONTRACT.md) 的 20 项测试和 Android Kotlin 编译通过，Lint 缺少离线依赖未执行。切换电脑只清理并替换 gateway 的连接配置，不关闭整个 graph、不重建 DJI/图传/任务对象，不恢复旧命令或任务传输。
   **已完成生命周期及模式控制接线**：权限 detach/onDestroy 和服务异常销毁 failed 已实现，新增 12 项测试、相关 56 项报告及 App Kotlin 编译通过（重跑范围见 README）。[RelayConnectionControl](../../src/modules/app-runtime/discovery-session-bridge/CONNECTION-CONTROL.md) 串行切换 AUTO/MANUAL、路由稳定门禁、隔离旧来源，15 项新测试通过；已接 applicationContext graph 与进程容器。
5. **源码已完成**主界面授权后自动启动、搜索/冲突/连接状态与隐藏手动入口，移除停止按钮；已接入同事对频迁移 a607399，本地提交 5aa0a9a。
6. **Debug APK 已构建，安装与实机验收待后续进行**：手机先开/电脑先开、后台/锁屏/划掉卡片、电脑退出再上线/IP 变化、Wi-Fi 重连/跨 AP、2 手机/2 无人机及 15 秒目标。

下一步执行第 6 项正式 APK 安装与现场验收；第 4 项运行容器与 graph、第 5 项 UI 已完成源码接线。桥接已实现：一个发现选择令牌对应一个 ConnectionSelection，重复公告/tick 复用对象；CONFLICT/WAITING/OBSERVING 暂停未 ACTIVE 会话。paired 在同步门禁按映射激活并记录实际代次，结束通知只清理匹配的获准代次；tick 和新代次门禁还会识别旧 ACTIVE 已结束，避免通知滞后时绕过最新冲突。状态锁不跨 session 控制调用，注销监听在控制锁外完成。容器通过稳定门禁路由器和串行模式切换独占 gateway；返回 AUTO 新建桥接。正式接收适配器提供接收子网和网络令牌，使用 513 字节缓冲检测超长报文；复用 socket 缓冲时仅传入本次实际长度的拷贝。真实服务销毁须停止网络/定时来源，STOPPING 收敛后释放 graph；Activity 销毁只 detach。

## 接线前核对的生命周期风险（历史记录）

- MainActivity 当前由 saveAndStart 输入 endpoint 后才建图；onDestroy 使用 RelaySurfaceRetention 与 RelayRuntimeHolder 保留部分运行状态，不是前台服务拥有完整图。
- MobileRelayGraph.create 同时创建 gateway、device、telemetry、stream、wayline，不能因 endpoint 改变重复建图。
- RelayBootstrapModule 启动设备后调用 startGateway；应将发现启动与 gateway 有地址后启动解耦，保留现有设备/遥测业务规则。
- RelayForegroundService 仍只管理通知，现已补齐非预期销毁 failed 和空 intent 无活动操作时退出；START_STICKY 不证明业务恢复。权限适配器已支持宿主 detach/onDestroy，应用级 USB 观察保留；完整容器释放与划卡保持运行仍待接线/实机验证。
- 当前用户确认的是产品行为；已实现的模块能力以契约及测试为准，待接线项不是完成或实机验收证据。

2026-10-14 graph 准备子阶段已完成：长期 Android 依赖使用 applicationContext，create 接受可空 endpoint 和 SessionPairingAdmission；当前 MainActivity 仍为手动流程。31 项 graph/Bootstrap 回归及 App Kotlin 编译通过。下一步包装 Android 发现来源、接唯一连接控制器，再实现停止终态收敛与进程容器；不能把此准备项当作无地址自动启动已完成。

2026-10-14 graph/来源控制接线已完成：Android 来源按 start 延迟创建，bootstrap 独占调用 RelayConnectionControl；默认无地址 AUTO，非空地址兼容 MANUAL，公开模式切换不会重建业务 graph。38 项 App 回归、Kotlin 编译与清单检查通过；57 项桥接/接收报告保持通过。下一步补 graph 异步停止终态收敛与初始化回滚，再替换 Activity 保留策略为进程容器，最后接无地址自动启动与隐藏调试界面。

2026-10-14 graph 生命周期收敛已完成：close 先永久撤销连接来源/门禁，再等待在途 start/stop/模式操作返回并请求 AppRuntime.stop；runtime 和 ForegroundServiceController 均为 STOPPED/FAILED 后才释放注册、Android 端口及执行器。两路状态通知驱动检查，不阻塞 UI 或服务回调；重复 close 幂等。组装过程中逐项登记已创建资源，失败逆序清理、保留原始错误与清理异常，成功才转移所有权；部分安装的状态注册也会解绑。新增停止 7 项（含真实 AppRuntime 延迟服务回调）、回滚 4 项，完整 App 98 项通过。全仓离线 test 与 assembleDebug、清单检查成功：JVM 718 项，Android Debug/Release 各 344 项报告零失败/错误/跳过（包括 App 98 项；部分既有模块 UP-TO-DATE）。新 Debug APK 已生成且保持忽略，未安装，未做现场权限/后台/15 秒验收。下一步进程容器与无地址 UI；已在本机远端引用 origin/xing-20261012 找到同事对频迁移 a607399，UI 前先核对该已有修改，避免重复实现。

2026-10-14 已核对并接入同事 origin/xing-20261012 中的对频入口迁移提交 a607399（只取该提交，不合并后续拍照改动）：删除手机对频按钮及其 UI 回调/专用文案，保留设备状态、电脑命令注册与业务规则。无冲突，App Kotlin 编译和完整 App 98 项回归通过；尚未重新打包安装，对频实际电脑链路仍待设备验收。下一步自动连接进程容器与 UI。

## 当前接续顺序（源码接线已完成）

源码提交 `c8b4c16`。用户已明确先完成源码及中文提交，实机调试后续进行；本轮不连接手机或安装 APK。以下步骤在后续调试阶段执行。

2026-10-14 正式无地址接线阶段：RelayProcessRuntime/ProcessRelayOwner 在进程内唯一持有 graph 和权限适配器，Activity 在可见 onResume 自动尝试启动，onDestroy 只取消 UI 订阅并按身份 detach；旧 RelayRuntimeHolder/RelaySurfaceRetention 已删除。默认 AUTO，历史地址仅预填长按标题打开的隐藏调试区；切换 MANUAL/返回 AUTO 只换 session，保留 deviceId 和业务图，主界面无停止按钮。拒绝后不循环弹窗，可明确重试或进入系统设置；授权后返回前台继续，权限等待被宿主解绑中断时允许下一宿主恢复一次。服务丢失会禁止来源；模块启动中服务失败等待该轮返回后清理，不发布 RUNNING，再释放图及权限适配器，后者在主线程关闭。新增进程/启动策略 8 项，替换旧保留策略的 3 项测试；新增 runtime 3 项服务启动竞态测试。完整 App 103 项、runtime 10 项通过，全仓离线 test/Debug 打包/清单检查通过：JVM 721 项，Android Debug/Release 各 349 项报告零失败/错误/跳过，未改动模块部分沿用 UP-TO-DATE。新 APK 已生成且被忽略；ADB 当前无已连接设备，mDNS 可见多台校园调试设备，尚未确认本人的当前连接地址，未安装或自动连接陌生设备。首次权限弹窗、旋转/后台/锁屏/划卡、正式校园 39082/hello/paired、电脑退出/重启/IP 变化、Wi-Fi 重连/跨 AP、2 手机/2 无人机及 15 秒目标均待实机验收；离线 Lint 仍缺依赖，未声称通过。

1. 确认本人手机当前 ADB 连接信息或 USB 连接，安装最新正式 APK；不能根据校园 mDNS 名称猜测设备或复用旧配对码。
2. 先验证打开后无地址授权/搜索、手机先开/电脑先开、二进制 hello/paired 和 15 秒计时，再验证电脑退出重启及网络切换；记录超时，不把持续等待当作达标。
3. 验证 Activity 重建、拒绝/系统设置授权、后台/锁屏/划卡和真实服务终止，检查只有一个发现来源/会话；最后补 2 手机/2 无人机及跨 AP 场景。
4. 按 GITHUB-DELIVERY 检查源码和安装包范围，再分享开发分支/PR；本轮尚未推送。
