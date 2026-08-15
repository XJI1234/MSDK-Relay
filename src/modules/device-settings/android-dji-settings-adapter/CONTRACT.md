# android-dji-settings-adapter 二级模块契约

状态：实施中
Gradle 路径：`:device-settings:android-dji-settings-adapter`

这是唯一允许接触 DJI `CameraKey` 与 `AirLinkKey` 的模块。

相机读取 `KeyAELockEnabled`、`KeyCameraFocusMode` 和主相机索引；图传读取 `KeyFrequencyBand`、`KeyChannelSelectionMode`、`KeyBandwidth`、`KeyDynamicDataRate`。写入只使用可写的前三类键或相机两类键。多字段写入按稳定顺序串行执行，全部成功后重新读取快照；不泄露 `IDJIError`、异常或 SDK 枚举对象。

读取仅可使用 DJI 已提供的缓存值构造快照。任何必填值缺失、为 `UNKNOWN`、不满足中继令牌约束，或图传速率为负数/非有限值时，读取和写后回读都必须失败；不得把缺失布尔值补成 `false`，不得把缺失枚举补成 `UNKNOWN` 后报告成功。`dynamicDataRateMbps` 仅在 DJI 明确没有该值时允许为 `null`。
