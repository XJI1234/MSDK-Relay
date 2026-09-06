# settings-executor 二级模块契约

状态：实施中
Gradle 路径：`:device-settings:settings-executor`

该模块把单个 `SettingsRequest` 提交到共享 DJI 操作协调器，负责 1,000 至 60,000 毫秒的超时、取消、异常和一次性终态映射。它不解析 JSON，也不依赖 Android 或 DJI SDK 类型。端口成功时必须附带完整、已确认的对应设置快照。

端口失败可携带受限 `SettingsDjiFailure(errorCode, errorDescription)`，且该值只能来自 Android 适配器对真实 MSDK `onFailure(IDJIError)` 的归一化。执行器必须原样带入失败终态；错误详情为 `null` 只表示 DJI 未提供可用回执。端口在尝试调用 DJI 时同步抛出的异常不得由执行器改写为失败回调，必须让共享协调器隔离该次未确认写操作；向上游交付的终态仍为无 DJI 错误详情的 `Failed`。只有真实 DJI `onFailure` 才是可显示为 `ACTION_REJECTED` 的明确拒绝。
