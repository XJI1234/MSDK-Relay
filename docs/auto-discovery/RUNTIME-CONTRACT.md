# 自动发现运行容器与无地址启动契约

2026-10-14。状态：正式 App 已接线并通过源码验证与 Debug 打包，实机待验收；不作为后台、自动连接或 15 秒目标的验收证据。共享公告协议见配套 Sky-Command 的 WIRE-PROTOCOL.md。

## 对象归属

应用组合层提供唯一进程级 Relay 运行容器，使用 applicationContext 创建长期对象。前台服务提供该容器运行所需的系统前台保障，AppRuntime 仍唯一负责权限 → 前台服务 → bootstrap 的启动顺序；通知服务不重复创建 DJI 或启动业务模块。服务实际启动成功后才能启动发现来源。

容器持有一个 MobileRelayGraph、稳定 deviceId、权限适配器、连接控制器与网络来源。Activity 只 attach/detach UI 订阅及权限请求宿主。旋转、返回桌面、锁屏、划掉任务卡片不关闭容器，不重复建图、注册 DJI 或绑定 UDP 端口。旧 RelayRuntimeHolder 的 Activity 保留策略须被容器替代。

graph 中诊断、文件、缓存、DJI、图传、航线及前台端口均使用 applicationContext；需要界面的权限操作通过当前宿主执行。权限适配器新增按宿主身份 detach：只解除匹配的 Activity、registry、lifecycle、launcher 和 UI 回调，迟到的旧 detach 不能解除新宿主。USB 接收器与权限历史使用 applicationContext，脱离界面后继续观察 USB；不得存留已销毁 Activity。detach 时取消未结束的系统运行权限等待，使启动收敛；新 Activity attach 后重新检查授权并继续，不能凭旧回调启动模块。

## 无地址启动与权限

App 打开时取得 deviceIdentity，默认 AUTO，不要求保存过 endpoint、不填伪地址。graph 的 gateway 初始 endpoint=null；权限完成、前台服务就绪、bootstrap 启动后容器保持 RUNNING，gateway 可保持 STOPPED。RUNNING 表示运行容器就绪，连接成功仍只以合法 paired / session ACTIVE 为准。

运行权限沿用现有 Android/DJI 权限策略，发现本身只需清单中的 INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE；新增 Wi-Fi MulticastLock 时声明 CHANGE_WIFI_MULTICAST_STATE。发现启动不等待 USB_ACCESS 或 DJI 注册成功。USB 接入授权沿用设备协调器，不改既有设备及遥测启动规则。connectedDevice 前台类型的 Android 14+先决权限、通知授权及实际 SDK/OS 行为须在接线时核对。

仅可见 Activity 发起系统授权。拒绝时显示缺少权限，不循环弹窗；允许用户重试或到系统设置授权。返回前台/新宿主 attach 时重新读取权限，已授权自动继续；不能在后台擅自弹窗。启动重入由 AppRuntime 的迁移状态控制，不并行 start。初始化失败逆序释放已创建对象；模块启动失败按现有 bootstrap 回滚并停止服务。

## 连接控制与隐藏手动模式

容器的连接控制器是 gateway 唯一控制入口，串行化 AUTO/MANUAL 切换、start/stop/selectConnection。bootstrap 的 startGateway/stopGateway 接缝改接该控制器，不能与发现桥接同时直接控制 gateway。网络地址变化只替换连接选择，保留 graph、SDK、deviceId、命令注册、图传和任务对象。

- AUTO：新建 DiscoverySessionBridge，将其 pairingAdmission 交给稳定门禁路由器，再 bind 唯一 gateway；启动桥接和网络来源，每秒 tick。重复公告复用选择对象，session 独自负责退避。
- MANUAL：显式从隐藏调试入口进入，先校验手动地址，失败保持当前模式。切换先撤销旧门禁，停止旧网络/计时来源并关闭旧桥接，暂停和停止旧 session；随后为手动地址创建稳定 ConnectionSelection 并启动 session。仅接受当前手动选择及模式代次的 paired。重复应用同地址复用对象；用户显式换地址创建新对象。
- 返回 AUTO：先撤销手动门禁、暂停并停止旧 session，创建新桥接和网络代次，从空候选重新观察 3 秒。旧桥接永久关闭，不复用已关闭对象。迟到包、tick、paired 和结束通知按来源/模式代次丢弃。

gateway 创建时注入稳定的 SessionPairingAdmission 路由器，路由器持有可撤销的当前模式门禁；门禁只检查本地状态，不调用连接控制、不等待 IO、不获取控制锁。切换先失效旧门禁再清理，失败进入无目标等待/可读故障，不恢复旧会话资源。手动与自动不并行，避免错误 paired 交付业务。

2026-10-14 独立 RelayConnectionControl 已实现上述控制规则，见 discovery-session-bridge/CONNECTION-CONTROL.md；sourceFactory 只创建不激活来源，source.close 不等待回调。15 项新增真实 session 测试通过，Android 接收源到控制器、graph 与进程容器的正式接线仍待实施。

模式只保留在当前进程；进程重开默认 AUTO。settings schema 1 保持不变，保存的 endpoint 仅供隐藏调试入口预填，自动发现不持久化、不覆盖它；清空手动地址不得改变 deviceId。主界面显示等待权限、搜索、冲突、连接中、已连接及脱敏故障，移除停止中继入口；本任务不重复实现其他开发者的对频迁移。

## 网络来源生命周期

Android 接收适配器只依赖 relay-discovery 数据类型与调用方回调，使用 applicationContext，监听 WLAN IPv4/子网及 UDP 39082。socket 缓冲为 513 字节，超长包整体丢弃，合法包按本次长度复制并提供实际 IPv4 来源及接收子网；不使用旧缓冲尾部、不解析或连接 WebSocket、不操作 DJI。没有 WLAN IPv4 时保持等待。

网络切换先关闭旧 socket，释放 Wi-Fi 锁并失效接收代次，再调用 bridge.advanceNetwork、重新绑定和观察。重复的同网络/接口/IPv4/前缀通知不扰动健康会话；失效回调不能覆盖新网络。接收错误关闭旧来源，按受限延时重试并报告脱敏状态，不能忙循环或重置 session 退避。正常 ACTIVE 沿用桥接规则保持当前电脑，发现网络变化不等于停止整个业务图。

来源具有幂等 start/close、明确取消句柄，close 后不可重启；接收线程通过关闭 socket 退出，注销网络回调、释放锁、取消 tick/重试。网络事件、包交付、tick 与模式切换在容器的串行调度器协调，来源代次过滤迟到任务。计时使用单调时间，不能把墙钟变动当成候选到期。

## 服务停止、失败及进程边界

清单保持非导出 connectedDevice 服务、stopWithTask=false 和常驻低重要性通知。空 intent / START_STICKY 重建不创建缺失 graph、不报告业务 RUNNING；没有容器的服务应退出，用户再次打开 App 才按正常流程恢复。无开机自启，不承诺强制停止或系统杀进程后自动恢复。

显式运行停止/真正容器销毁先禁止新来源和门禁，关闭网络/tick/重试，再通过 AppRuntime.stop 逆序停止 bootstrap 与前台服务。等待 STOPPING 收敛后才关闭 graph、前台端口/接收器和 executor；不能先注销接收器使停止终态丢失。重复清理无副作用，失败清理仍尝试所有已创建资源。系统杀进程可能没有 onDestroy，依赖 OS 回收，不伪造可保证的恢复。

非预期服务 onDestroy（且仍有 activeStart、无 pendingStop）必须报告 failed，驱动 AppRuntime 停止业务并转 FAILED；显式停止只报告 stopped，启动失败只报告一次 failed。后续重试须重新检查前台/权限条件，不能持续留着后台孤儿业务图。

## 实施证据与验证门槛

已核对 MainActivity、RelayRuntimeHolder、MobileRelayGraph、RelayBootstrapModule、AppRuntime、AndroidPermissionPlatform 与 RelayForegroundService。2026-10-14 已实现权限宿主 detach/onDestroy（身份及操作令牌隔离，USB 用应用上下文保留）和服务非预期销毁 failed；这些是独立生命周期准备项。完整进程容器、无地址 App 启动、模式控制与现场后台行为仍待接线/验收。

先独立验证网络适配器的重复通知、切换/失效代次、收包长度、来源子网、错误重试、初始化回滚及关闭资源，再接容器。容器验证无地址/权限拒绝与恢复、Activity 重建/detach、模式切换和迟到门禁、服务非预期销毁、启动/停止竞态。正式 APK 编译/安装后记录手机先开/电脑先开、Wi-Fi 重连、跨 AP、电脑重启/IP 变化、后台/锁屏/划卡及 2 手机/2 无人机/15 秒验收；JVM 测试或探针不能替代这些证据。

2026-10-14 graph 的 applicationContext、可空初始 endpoint、握手门禁注入已实现，31 项 graph/Bootstrap 回归与 Kotlin 编译通过。正式 App 尚未调用发现控制器；初始化回滚、停止终态释放及进程容器仍待实施。

2026-10-14 正式 graph 已依赖来源/桥接并注入内部唯一控制器门禁；bootstrap start/stopGateway 改接控制器，默认空地址 AUTO，非空入口 MANUAL，仅切换 session。Android 来源 FAILED 转控制失败，WAITING_WIFI/RETRYING 保留内部恢复；38 项 App 测试与编译/清单检查通过。进程容器、无地址 UI、停止终态释放与初始化回滚仍待实现；尚未实机验收。

2026-10-14 graph 生命周期收敛已完成：close 先永久撤销连接来源/门禁，再等待在途 start/stop/模式操作返回并请求 AppRuntime.stop；runtime 和 ForegroundServiceController 均为 STOPPED/FAILED 后才释放注册、Android 端口及执行器。两路状态通知驱动检查，不阻塞 UI 或服务回调；重复 close 幂等。组装过程中逐项登记已创建资源，失败逆序清理、保留原始错误与清理异常，成功才转移所有权；部分安装的状态注册也会解绑。新增停止 7 项（含真实 AppRuntime 延迟服务回调）、回滚 4 项，完整 App 98 项通过。全仓离线 test 与 assembleDebug、清单检查成功：JVM 718 项，Android Debug/Release 各 344 项报告零失败/错误/跳过（包括 App 98 项；部分既有模块 UP-TO-DATE）。新 Debug APK 已生成且保持忽略，未安装，未做现场权限/后台/15 秒验收。下一步进程容器与无地址 UI；已在本机远端引用 origin/xing-20261012 找到同事对频迁移 a607399，UI 前先核对该已有修改，避免重复实现。

## 2026-10-14 正式接线与验证结果

2026-10-14 正式无地址接线阶段：RelayProcessRuntime/ProcessRelayOwner 在进程内唯一持有 graph 和权限适配器，Activity 在可见 onResume 自动尝试启动，onDestroy 只取消 UI 订阅并按身份 detach；旧 RelayRuntimeHolder/RelaySurfaceRetention 已删除。默认 AUTO，历史地址仅预填长按标题打开的隐藏调试区；切换 MANUAL/返回 AUTO 只换 session，保留 deviceId 和业务图，主界面无停止按钮。拒绝后不循环弹窗，可明确重试或进入系统设置；授权后返回前台继续，权限等待被宿主解绑中断时允许下一宿主恢复一次。服务丢失会禁止来源；模块启动中服务失败等待该轮返回后清理，不发布 RUNNING，再释放图及权限适配器，后者在主线程关闭。新增进程/启动策略 8 项，替换旧保留策略的 3 项测试；新增 runtime 3 项服务启动竞态测试。完整 App 103 项、runtime 10 项通过，全仓离线 test/Debug 打包/清单检查通过：JVM 721 项，Android Debug/Release 各 349 项报告零失败/错误/跳过，未改动模块部分沿用 UP-TO-DATE。新 APK 已生成且被忽略；ADB 当前无已连接设备，mDNS 可见多台校园调试设备，尚未确认本人的当前连接地址，未安装或自动连接陌生设备。首次权限弹窗、旋转/后台/锁屏/划卡、正式校园 39082/hello/paired、电脑退出/重启/IP 变化、Wi-Fi 重连/跨 AP、2 手机/2 无人机及 15 秒目标均待实机验收；离线 Lint 仍缺依赖，未声称通过。
