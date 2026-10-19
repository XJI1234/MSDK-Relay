# MSDK Relay 测试记录约定

先读根 `CONTRACT.md` 及任务模块契约。用户已要求从 2026-10-19 起保留所有测试证据；不能仅在聊天中报告结果。

Gradle 测试/构建使用 `powershell.exe -NoProfile -File scripts/Verify-Recorded.ps1 <原有任务参数>`。其它测试从原工作目录通过 `node ../XJICloud/scripts/test-records/run.mjs --label <测试名> -- <可执行文件> <参数...>` 运行。保留真实退出码，不为通过测试删断言或关闭门禁。

档案默认保存到 `D:\XJI-Test-Records`，不删除旧记录。真机 SDK 诊断由电脑网关归档；记录 SDK 原始回执与飞机实际动作时必须区分，人工观察通过 XJICloud 的 `view.mjs mark` 补充，截图/录像通过 `attach`。软件源码版本不等于手机已安装版本；未知固件或 APK 不得推断。详细契约在相邻 XJICloud 仓库的 `scripts/test-records/CONTRACT.md`。
