# 手机自动发现与连接任务索引

更新日期：2026-10-14。当前状态：电脑公告器、手机纯 JVM 解析/候选核心、gateway/session 连接选择 API 及发现到会话桥接已实现并通过自动化测试；独立 Android WLAN 接收适配器已完成；正式 App 权限与前台运行容器接线尚未完成。

任务采用第一阶段最小方案：一台电脑、多台手机，双方无线连接当前校园 Wi-Fi；授权后打开 App 自动发现并连接，电脑离线时低频等待、上线后自动重连；计划移除手机“停止中继”按钮。不扩展航线规划，不改变既有飞行、航线和退出处理。

共同方案的维护位置在同级 Sky-Command 仓库，避免两端各保存一份容易冲突的设计：

- [任务与已确定范围](../../../Sky-Command/docs/auto-discovery/TASK.md)
- [最小方案草案与待确定边界](../../../Sky-Command/docs/auto-discovery/DESIGN.md)
- [双端公告 v1 协议与候选规则](../../../Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md)
- [正式手机接线检查表](IMPLEMENTATION.md)
- [运行容器、无地址启动与模式切换契约（接线待实现）](RUNTIME-CONTRACT.md)
- [手机发现核心接口契约](../../src/modules/relay-discovery/CONTRACT.md)
- [独立 Android WLAN 接收适配器契约](../../src/modules/relay-discovery/android-discovery-adapter/CONTRACT.md)
- [发现到会话桥接契约](../../src/modules/app-runtime/discovery-session-bridge/CONTRACT.md)
- [自动/手动模式的唯一连接控制器](../../src/modules/app-runtime/discovery-session-bridge/CONNECTION-CONTROL.md)
- [进度、证据与待办](../../../Sky-Command/docs/auto-discovery/PROGRESS.md)
- [校园 Wi-Fi 广播测试](../../../Sky-Command/docs/auto-discovery/BROADCAST-TEST.md)
- [当前校园位置实测结果](../../../Sky-Command/docs/auto-discovery/NETWORK-RESULTS.md)
- [GitHub 交付与排除范围](../../../Sky-Command/docs/auto-discovery/GITHUB-DELIVERY.md)

以上跨仓库链接适用于两个仓库并排克隆的本机工作区；在 GitHub 单仓库页面需要到 Sky-Command 对应开发分支查看同名文件。分享两端分支时，应在 PR 描述中填写实际的配套分支/PR 链接。

手机端设计方向：无 endpoint 先授权并启动前台运行容器，容器唯一拥有发现监听和 graph；Activity 只绑定状态。发现选择候选，session 唯一负责连接/退避，地址变化只切换电脑连接。2026-10-14 运行容器契约已明确 Activity detach、无地址权限恢复、AUTO/MANUAL 独占控制与停止收敛，接线尚未实现；独立 Android 接收适配器已完成，下一步接运行容器与 graph，详见 IMPLEMENTATION.md。已实现权限宿主 detach/onDestroy 和服务非预期销毁 failed；完整进程容器与 App 启动仍待接线。START_STICKY 不能证明完整业务对象能在进程重启后恢复。

2026-10-14 桥接验证：新增纯 JVM `:app-runtime:discovery-session-bridge`，不拥有网络/线程/定时器；稳定映射发现令牌与 ConnectionSelection，同步门禁和 endedGeneration 防止旧握手/断线影响新会话。tick/新代次门禁能发现旧 ACTIVE 已实际结束，不因延迟通知跳过冲突或重新观察；状态锁不跨 session 控制调用，监听注销在控制锁外进行；控制部分成功后抛错也不沿用旧应用缓存。22 项新测试全部通过，含真实 session、公开 gateway 适配器、两类竞态各 30 轮及门禁提交窗口。5 模块共 142 项报告零失败/错误/跳过，本轮重跑 22 项、已有回归报告 120 项 UP-TO-DATE；App Kotlin 编译检查通过（UP-TO-DATE），App 尚未依赖桥接，没有构建/安装新版 APK，不能作为正式自动连接或现场验收证据。

2026-10-14 Android 接收适配器验证：新增 `:relay-discovery:android-discovery-adapter`，通过 WLAN NetworkCallback、LinkProperties、Network.bindSocket 与 Wi-Fi MulticastLock 提供独占 UDP 39082 来源，每秒 tick、网络换代及 1/2/4/8 秒错误退避；接收缓冲 513 字节、包队列上限 64。20 项新测试（15 项生命周期替身、5 项数据/真实 UDP 与资源回滚）零失败/错误/跳过，Android Kotlin 编译通过；发现核心 34 项、桥接 22 项既有报告为 UP-TO-DATE，共 76 项报告通过。本机缺少 lint-gradle:31.7.0 缓存，离线 Lint 未执行。验证命令为 `./gradlew.bat :relay-discovery:android-discovery-adapter:testDebugUnitTest :app-runtime:discovery-session-bridge:test :relay-discovery:test --offline --no-daemon --console=plain`，JDK/Java 临时目录沿用本机启动说明。正式 App 未依赖接收模块、未构建/安装 APK，真实 Android WLAN 广播、锁屏、划卡及 15 秒仍待验收。

2026-10-14 生命周期准备验证：权限平台新增按身份 detach/onDestroy，应用级 USB 接收/授权与检查保留；运行权限用独立请求 key/令牌隔离取消、重绑和重复结果，解绑释放宿主引用并使权限等待 Failed。通知服务通过真实使用的终态持有器在非预期销毁报告 failed，显式停止只报告 stopped，启动失败不重复；空 intent 没有活动操作时退出。新增 12 项测试，替换 1 项与契约相反的源码字符串断言；两适配器共 42 项、运行时/权限/服务核心 14 项报告零失败/错误/跳过，总计 56 项。App Kotlin 编译通过；最后一轮权限测试及核心报告 UP-TO-DATE，服务测试重跑。Lint 沿用离线缓存限制未运行；未构建安装 APK，完整容器、正式无地址启动和手机后台验收尚未完成。

2026-10-14 模式控制验证：桥接模块新增纯 JVM RelayConnectionControl，创建稳定 pairingAdmission 路由、独占 gateway 控制、按每轮 AUTO 新建桥接/外部来源；MANUAL 校验后切换且同地址保留退避，返回 AUTO 重新观察。来源及模式迟到事件被隔离；失败先撤销门禁并清理，资源关闭失败保留句柄，完成清理前阻止第二来源。新增 15 项真实 ConnectionSession 组合测试，包括慢清理期间门禁撤销、部分启动/选择失败、暂停及来源关闭异常、旧包/paired/结束事件、停止重启和永久关闭。桥接原 22 项随模块重跑，共 37 项零失败/错误/跳过，发现核心 34 项报告 UP-TO-DATE，共 71 项报告通过。正式 App 仍未依赖桥接/控制器或接收模块；无新 APK、实机或后台验收。下一步接 graph/applicationContext 和进程容器。

2026-10-13 会话扩展验证：新增 21 项连接选择测试和 4 项 gateway 测试，支持 null endpoint 等待、同选择保留退避、暂停/替换时旧代次清理、入站队列优先级、ACTIVE 前握手门禁及同选择重连后的迟到结束通知。8 模块 JUnit 报告共 167 项，零失败/错误/跳过；其中本轮重跑 gateway/session 与相关回归 130 项，发现/地址校验的 37 项为已有通过报告（Gradle UP-TO-DATE）。`:app:compileDebugKotlin` 通过。契约见 [CONNECTION-SELECTION.md](../../src/modules/relay-gateway/connection-session/CONNECTION-SELECTION.md)；未构建/安装新 APK，尚无正式手机自动连接证据。

2026-10-13 核心验证：`:relay-discovery:test` 的 34 项 JVM 测试全部通过（12 项解析、22 项协调），包括严格报文校验、3/8 秒边界、冲突/容量恢复、ACTIVE 保持、旧网络/选择令牌以及并发握手/冲突顺序；另有 2000 个随机报文、3000 步随机事件和 100 轮并发对照。电脑真实编码器输出与手机 JSON 解析样例逐字一致。运行依赖仅 Kotlin/JVM 与 Jackson，无 Android/DJI，测试报告和 JAR 留在忽略的 build/。没有增加正式监听，也未安装新版 App。

配置 JDK 17 及本文档引用的本地 Java 临时目录后，可离线执行 `./gradlew.bat :relay-discovery:test --offline --no-daemon --console=plain`。当前手机主界面继续使用原手动地址流程，不能把核心测试当作自动连接/后台/15 秒验收。

当前本地 MainActivity 仍有两个对频按钮，组长称迁移由其他开发者处理；接线前核对配套分支，不重复实现。

本阶段新增独立 [前台网络测试 App](../../tools/network-probe/README.md)，包名 com.skycommand.networkprobe。它监听测试 UDP 39081、验证现有二进制 hello/paired；不依赖 DJI，也不修改正式 Relay 的运行行为。2026-10-13 离线编译并无线安装完成，当前位置三轮广播存在实际接收且明显丢包，完整计数见配套实测结果。锁屏/后台不是此探针的测试范围。中文提交、开发分支与配置排除要求沿用 docs/LOCAL_DEVELOPMENT.md。

2026-10-14 graph 接线准备：MobileRelayGraph.create 使用 applicationContext 创建长期 Android 对象，允许空初始 endpoint，并向 gateway 注入调用方的同步握手门禁；现有手动调用保持可编译。App Kotlin 编译与 graph 契约/Bootstrap 回归共 31 项通过（17 + 14，零失败/错误/跳过）；首次回归因断言旧 Activity 调用失败，已更新对应诊断断言并重跑通过。完整控制器/来源/进程容器及停止收敛仍待接线；未构建或安装 APK，未做现场验收。

2026-10-14 正式 graph 连接控制接线：App 已依赖 discovery-session-bridge 与 Android 接收模块，AndroidConnectionDiscoverySource 在 start 才创建接收器并转发来源/子网/令牌/tick，仅终态 FAILED 通知控制失败。关闭后禁止包/tick/failure，清理失败保留句柄重试；不等待异步回调。graph 默认空地址 AUTO，原非空地址调用走 MANUAL；bootstrap 唯一通过控制器 start/stop，模式切换保留业务图。7 项新来源测试与 graph/Bootstrap 共 38 项重跑通过；桥接 37 项、接收器 20 项已有报告仍通过，共 95 项零失败/错误/跳过。App Kotlin 编译、合并清单检查通过（MulticastLock 声明也是 Android 14 connectedDevice 先决权限之一）。下一步停止收敛、进程容器和日常 UI；未构建/安装 APK或现场验收。

2026-10-14 graph 生命周期收敛已完成：close 先永久撤销连接来源/门禁，再等待在途 start/stop/模式操作返回并请求 AppRuntime.stop；runtime 和 ForegroundServiceController 均为 STOPPED/FAILED 后才释放注册、Android 端口及执行器。两路状态通知驱动检查，不阻塞 UI 或服务回调；重复 close 幂等。组装过程中逐项登记已创建资源，失败逆序清理、保留原始错误与清理异常，成功才转移所有权；部分安装的状态注册也会解绑。新增停止 7 项（含真实 AppRuntime 延迟服务回调）、回滚 4 项，完整 App 98 项通过。全仓离线 test 与 assembleDebug、清单检查成功：JVM 718 项，Android Debug/Release 各 344 项报告零失败/错误/跳过（包括 App 98 项；部分既有模块 UP-TO-DATE）。新 Debug APK 已生成且保持忽略，未安装，未做现场权限/后台/15 秒验收。下一步进程容器与无地址 UI；已在本机远端引用 origin/xing-20261012 找到同事对频迁移 a607399，UI 前先核对该已有修改，避免重复实现。

2026-10-14 已核对并接入同事 origin/xing-20261012 中的对频入口迁移提交 a607399（只取该提交，不合并后续拍照改动）：删除手机对频按钮及其 UI 回调/专用文案，保留设备状态、电脑命令注册与业务规则。无冲突，App Kotlin 编译和完整 App 98 项回归通过；尚未重新打包安装，对频实际电脑链路仍待设备验收。下一步自动连接进程容器与 UI。
