# settings-executor 二级模块契约

状态：实施中
Gradle 路径：`:device-settings:settings-executor`

该模块把单个 `SettingsRequest` 提交到共享 DJI 操作协调器，负责 1,000 至 60,000 毫秒的超时、取消、异常和一次性终态映射。它不解析 JSON，也不依赖 Android 或 DJI SDK 类型。端口成功时必须附带完整、已确认的对应设置快照。
