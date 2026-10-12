# 手机自动发现与连接任务索引

更新日期：2026-10-13。当前状态：校园 Wi-Fi 当前位置初测已完成，继续澄清边界；生产功能尚未实现。

任务采用第一阶段最小方案：一台电脑、多台手机，双方无线连接当前校园 Wi-Fi；授权后打开 App 自动发现并连接，电脑离线时低频等待、上线后自动重连；计划移除手机“停止中继”按钮。不扩展航线规划，不改变既有飞行、航线和退出处理。

共同方案的维护位置在同级 Sky-Command 仓库，避免两端各保存一份容易冲突的设计：

- [任务与已确定范围](../../../Sky-Command/docs/auto-discovery/TASK.md)
- [最小方案草案与待确定边界](../../../Sky-Command/docs/auto-discovery/DESIGN.md)
- [进度、证据与待办](../../../Sky-Command/docs/auto-discovery/PROGRESS.md)
- [校园 Wi-Fi 广播测试](../../../Sky-Command/docs/auto-discovery/BROADCAST-TEST.md)
- [当前校园位置实测结果](../../../Sky-Command/docs/auto-discovery/NETWORK-RESULTS.md)
- [GitHub 交付与排除范围](../../../Sky-Command/docs/auto-discovery/GITHUB-DELIVERY.md)

以上跨仓库链接适用于两个仓库并排克隆的本机工作区；在 GitHub 单仓库页面需要到 Sky-Command 对应开发分支查看同名文件。分享两端分支时，应在 PR 描述中填写实际的配套分支/PR 链接。

手机端待解决：无 endpoint 时的权限与前台服务启动、UDP 监听所有权、后台/锁屏运行、地址更新与既有 WebSocket 重试协调、设备身份隔离、Activity 重建及监听器清理。当前前台通知服务的 START_STICKY 不能证明完整业务对象能在进程重启后恢复。

当前本地 MainActivity 仍有两个对频按钮，组长称迁移由其他开发者处理；接线前核对配套分支，不重复实现。

本阶段新增独立 [前台网络测试 App](../../tools/network-probe/README.md)，包名 com.skycommand.networkprobe。它监听测试 UDP 39081、验证现有二进制 hello/paired；不依赖 DJI，也不修改正式 Relay 的运行行为。2026-10-13 离线编译并无线安装完成，当前位置三轮广播存在实际接收且明显丢包，完整计数见配套实测结果。锁屏/后台不是此探针的测试范围。中文提交、开发分支与配置排除要求沿用 docs/LOCAL_DEVELOPMENT.md。
