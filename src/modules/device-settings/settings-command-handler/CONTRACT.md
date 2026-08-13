# settings-command-handler 二级模块契约

状态：实施中
Gradle 路径：`:device-settings:settings-command-handler`

该模块把四个协议命令转成不可变的 `SettingsRequest`，并在调用动作端口之前验证所有字段、值类型、令牌格式、数值范围和读写域。它不保存快照，不创建线程，不触碰 DJI 类型。每个下层终态最多向上游转发一次。
