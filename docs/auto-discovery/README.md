# 手机自动发现与连接任务索引

更新日期：2026-10-13。当前状态：校园 Wi-Fi 当前位置初测完成，公告协议与生命周期接线设计已收敛；正式手机自动发现尚未实现。

任务采用第一阶段最小方案：一台电脑、多台手机，双方无线连接当前校园 Wi-Fi；授权后打开 App 自动发现并连接，电脑离线时低频等待、上线后自动重连；计划移除手机“停止中继”按钮。不扩展航线规划，不改变既有飞行、航线和退出处理。

共同方案的维护位置在同级 Sky-Command 仓库，避免两端各保存一份容易冲突的设计：

- [任务与已确定范围](../../../Sky-Command/docs/auto-discovery/TASK.md)
- [最小方案草案与待确定边界](../../../Sky-Command/docs/auto-discovery/DESIGN.md)
- [双端公告 v1 协议与候选规则](../../../Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md)
- [正式手机接线检查表](IMPLEMENTATION.md)
- [进度、证据与待办](../../../Sky-Command/docs/auto-discovery/PROGRESS.md)
- [校园 Wi-Fi 广播测试](../../../Sky-Command/docs/auto-discovery/BROADCAST-TEST.md)
- [当前校园位置实测结果](../../../Sky-Command/docs/auto-discovery/NETWORK-RESULTS.md)
- [GitHub 交付与排除范围](../../../Sky-Command/docs/auto-discovery/GITHUB-DELIVERY.md)

以上跨仓库链接适用于两个仓库并排克隆的本机工作区；在 GitHub 单仓库页面需要到 Sky-Command 对应开发分支查看同名文件。分享两端分支时，应在 PR 描述中填写实际的配套分支/PR 链接。

手机端设计方向：无 endpoint 先授权并启动前台运行容器，容器唯一拥有发现监听和 graph；Activity 只绑定状态。发现选择候选，session 唯一负责连接/退避，地址变化只切换电脑连接。下一步先实现纯 JVM 解析/候选模块，再扩展 session 与生命周期契约和接线，详见 IMPLEMENTATION.md。当前前台通知服务的 START_STICKY 不能证明完整业务对象能在进程重启后恢复。

当前本地 MainActivity 仍有两个对频按钮，组长称迁移由其他开发者处理；接线前核对配套分支，不重复实现。

本阶段新增独立 [前台网络测试 App](../../tools/network-probe/README.md)，包名 com.skycommand.networkprobe。它监听测试 UDP 39081、验证现有二进制 hello/paired；不依赖 DJI，也不修改正式 Relay 的运行行为。2026-10-13 离线编译并无线安装完成，当前位置三轮广播存在实际接收且明显丢包，完整计数见配套实测结果。锁屏/后台不是此探针的测试范围。中文提交、开发分支与配置排除要求沿用 docs/LOCAL_DEVELOPMENT.md。
