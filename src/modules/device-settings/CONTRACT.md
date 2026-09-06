# device-settings 模块契约

状态：已实现；实机待验证
Gradle 路径：`:device-settings`

## 唯一职责

`device-settings` 负责读取和修改飞行器当前已确认的相机设置与图传设置，并把 DJI 成功回调后的完整快照作为结构化命令结果返回。

它不负责 RTMP 推流生命周期、视频接收、飞控动作、设备连接、遥测发布、航线或桌面 UI。`live-stream` 仍独占 RTMP 推流；本模块只读写 DJI 相机和 AirLink 配置键。

## 对外命令

```text
device.settings.camera.read              fields: {}
device.settings.camera.write             fields: CameraSettingsPatch
device.settings.transmission.read        fields: {}
device.settings.transmission.write       fields: TransmissionSettingsPatch
```

相机快照：

```json
{
  "autoExposureLockEnabled": false,
  "focusMode": "AUTO",
  "cameraIndex": "LEFT_OR_MAIN"
}
```

图传快照：

```json
{
  "frequencyBand": "BAND_2_DOT_4G",
  "channelSelectionMode": "AUTO",
  "bandwidth": "BANDWIDTH_20MHZ",
  "dynamicDataRateMbps": 12.5
}
```

字符串枚举只能是 `1..64` 个大写字母、数字、下划线且首字符为大写字母。写入补丁必须非空，并且只能携带可写字段：相机的 `autoExposureLockEnabled`、`focusMode`，图传的 `frequencyBand`、`channelSelectionMode`、`bandwidth`。`cameraIndex` 和 `dynamicDataRateMbps` 只读。

成功结果使用 `command-result.result`：`{ "domain": "camera" | "transmission", "settings": { ...完整快照... } }`。成功只表示 DJI 已确认此次读写调用并且手机重新读取到完整快照，不表示桌面端已经持久化显示它。旧电脑端可安全忽略该可选字段；桌面端协议适配器完成升级前，设置 UI 不得显示为已联调可用。

## 二级模块

| 模块 | 唯一职责 | 不负责 |
| --- | --- | --- |
| `settings-command-handler` | 严格解析四个命令、校验字段并定义平台无关的设置模型 | 状态、线程、DJI 调用 |
| `settings-executor` | 通过共享 DJI 操作协调器执行一次读写请求，统一超时、取消和终态 | 协议解析、Android SDK 类型 |
| `android-dji-settings-adapter` | 唯一读写 `CameraKey`、`AirLinkKey` 的 Android 实现 | 命令校验、排队、超时、网关结果 |

## 失败与生命周期

任何字段错误都在调用 DJI 前拒绝。写入必须等待所有目标键成功且重新读取完整快照后才成功；任一键失败、超时、取消、设备失效、同步异常、重复或迟到回调只产生一次安全失败结果。设备失效取消在途操作，且不会写入缓存或凭空恢复旧设置。真实 DJI `onFailure(IDJIError)` 的错误码和说明必须在 Android 边界清除控制字符并限制为 128/512 个 Unicode 码点后，以 `{ "domain": "settings", "outcome": "ACTION_REJECTED", "errorCode", "errorDescription" }` 回传桌面；不得泄露 `IDJIError` 对象或堆栈。同步调用异常不得伪造为 DJI 拒绝，必须传播给共享协调器并由命令结果如实标为 `{ "domain": "settings", "outcome": "INVOCATION_FAILED" }`；超时或取消使用 `RESULT_UNCONFIRMED`。设置读写都是普通 DJI 操作，未确认结果期间不得绕过共享协调器重入。

同一 `DeviceSettings` 门面最多保留一个已接受但未到终态的读写请求。第二个读写必须在触及共享协调器前以稳定的本地 `OPERATION_REJECTED` 拒绝；前一个请求收到成功、明确失败、超时或取消后才可接受下一项。此规则只防止陈旧设置意图排队，不检查任何硬件 Key，也不把 DJI 的异步拒绝改写为本地拒绝。
