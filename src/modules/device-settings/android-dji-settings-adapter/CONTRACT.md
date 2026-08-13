# android-dji-settings-adapter 二级模块契约

状态：实施中
Gradle 路径：`:device-settings:android-dji-settings-adapter`

这是唯一允许接触 DJI `CameraKey` 与 `AirLinkKey` 的模块。

相机读取 `KeyAELockEnabled`、`KeyCameraFocusMode` 和主相机索引；图传读取 `KeyFrequencyBand`、`KeyChannelSelectionMode`、`KeyBandwidth`、`KeyDynamicDataRate`。写入只使用可写的前三类键或相机两类键。多字段写入按稳定顺序串行执行，全部成功后重新读取快照；不泄露 `IDJIError`、异常或 SDK 枚举对象。
