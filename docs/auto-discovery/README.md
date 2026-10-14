# 手机自动发现与连接任务索引

更新日期：2026-10-14。当前状态：电脑公告器、手机纯 JVM 解析/候选核心、gateway/session 连接选择 API 及发现到会话桥接已实现并通过自动化测试；正式 Android 监听、权限与前台运行容器接线尚未完成。

任务采用第一阶段最小方案：一台电脑、多台手机，双方无线连接当前校园 Wi-Fi；授权后打开 App 自动发现并连接，电脑离线时低频等待、上线后自动重连；计划移除手机“停止中继”按钮。不扩展航线规划，不改变既有飞行、航线和退出处理。

共同方案的维护位置在同级 Sky-Command 仓库，避免两端各保存一份容易冲突的设计：

- [任务与已确定范围](../../../Sky-Command/docs/auto-discovery/TASK.md)
- [最小方案草案与待确定边界](../../../Sky-Command/docs/auto-discovery/DESIGN.md)
- [双端公告 v1 协议与候选规则](../../../Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md)
- [正式手机接线检查表](IMPLEMENTATION.md)
- [运行容器、无地址启动与模式切换契约（接线待实现）](RUNTIME-CONTRACT.md)
- [手机发现核心接口契约](../../src/modules/relay-discovery/CONTRACT.md)
- [发现到会话桥接契约](../../src/modules/app-runtime/discovery-session-bridge/CONTRACT.md)
- [进度、证据与待办](../../../Sky-Command/docs/auto-discovery/PROGRESS.md)
- [校园 Wi-Fi 广播测试](../../../Sky-Command/docs/auto-discovery/BROADCAST-TEST.md)
- [当前校园位置实测结果](../../../Sky-Command/docs/auto-discovery/NETWORK-RESULTS.md)
- [GitHub 交付与排除范围](../../../Sky-Command/docs/auto-discovery/GITHUB-DELIVERY.md)

以上跨仓库链接适用于两个仓库并排克隆的本机工作区；在 GitHub 单仓库页面需要到 Sky-Command 对应开发分支查看同名文件。分享两端分支时，应在 PR 描述中填写实际的配套分支/PR 链接。

手机端设计方向：无 endpoint 先授权并启动前台运行容器，容器唯一拥有发现监听和 graph；Activity 只绑定状态。发现选择候选，session 唯一负责连接/退避，地址变化只切换电脑连接。2026-10-14 运行容器契约已明确 Activity detach、无地址权限恢复、AUTO/MANUAL 独占控制与停止收敛，接线尚未实现；下一步独立实现 Android 接收适配器，详见 IMPLEMENTATION.md。源码核对发现权限适配器仍保留 Activity、服务非预期销毁尚未报告 failed，均列为接线待修复。START_STICKY 不能证明完整业务对象能在进程重启后恢复。

2026-10-14 桥接验证：新增纯 JVM `:app-runtime:discovery-session-bridge`，不拥有网络/线程/定时器；稳定映射发现令牌与 ConnectionSelection，同步门禁和 endedGeneration 防止旧握手/断线影响新会话。tick/新代次门禁能发现旧 ACTIVE 已实际结束，不因延迟通知跳过冲突或重新观察；状态锁不跨 session 控制调用，监听注销在控制锁外进行；控制部分成功后抛错也不沿用旧应用缓存。22 项新测试全部通过，含真实 session、公开 gateway 适配器、两类竞态各 30 轮及门禁提交窗口。5 模块共 142 项报告零失败/错误/跳过，本轮重跑 22 项、已有回归报告 120 项 UP-TO-DATE；App Kotlin 编译检查通过（UP-TO-DATE），App 尚未依赖桥接，没有构建/安装新版 APK，不能作为正式自动连接或现场验收证据。

2026-10-13 会话扩展验证：新增 21 项连接选择测试和 4 项 gateway 测试，支持 null endpoint 等待、同选择保留退避、暂停/替换时旧代次清理、入站队列优先级、ACTIVE 前握手门禁及同选择重连后的迟到结束通知。8 模块 JUnit 报告共 167 项，零失败/错误/跳过；其中本轮重跑 gateway/session 与相关回归 130 项，发现/地址校验的 37 项为已有通过报告（Gradle UP-TO-DATE）。`:app:compileDebugKotlin` 通过。契约见 [CONNECTION-SELECTION.md](../../src/modules/relay-gateway/connection-session/CONNECTION-SELECTION.md)；未构建/安装新 APK，尚无正式手机自动连接证据。

2026-10-13 核心验证：`:relay-discovery:test` 的 34 项 JVM 测试全部通过（12 项解析、22 项协调），包括严格报文校验、3/8 秒边界、冲突/容量恢复、ACTIVE 保持、旧网络/选择令牌以及并发握手/冲突顺序；另有 2000 个随机报文、3000 步随机事件和 100 轮并发对照。电脑真实编码器输出与手机 JSON 解析样例逐字一致。运行依赖仅 Kotlin/JVM 与 Jackson，无 Android/DJI，测试报告和 JAR 留在忽略的 build/。没有增加正式监听，也未安装新版 App。

配置 JDK 17 及本文档引用的本地 Java 临时目录后，可离线执行 `./gradlew.bat :relay-discovery:test --offline --no-daemon --console=plain`。当前手机主界面继续使用原手动地址流程，不能把核心测试当作自动连接/后台/15 秒验收。

当前本地 MainActivity 仍有两个对频按钮，组长称迁移由其他开发者处理；接线前核对配套分支，不重复实现。

本阶段新增独立 [前台网络测试 App](../../tools/network-probe/README.md)，包名 com.skycommand.networkprobe。它监听测试 UDP 39081、验证现有二进制 hello/paired；不依赖 DJI，也不修改正式 Relay 的运行行为。2026-10-13 离线编译并无线安装完成，当前位置三轮广播存在实际接收且明显丢包，完整计数见配套实测结果。锁屏/后台不是此探针的测试范围。中文提交、开发分支与配置排除要求沿用 docs/LOCAL_DEVELOPMENT.md。
