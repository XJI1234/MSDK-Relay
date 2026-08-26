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

任何字段错误都在调用 DJI 前拒绝。写入必须等待所有目标键成功且重新读取完整快照后才成功；任一键失败、超时、取消、设备失效、同步异常、重复或迟到回调只产生一次安全失败结果。设备失效取消在途操作，且不会写入缓存或凭空恢复旧设置。

生产环境只有 `MobileRelayGraph` 可以把 `commandHandler()` 注册到 `RelayGateway`。本模块只接收 `DjiSettingsPort` 和共享操作协调器，绝不接收完整 gateway、桌面设置面板或 `DeviceConnection`；成功快照只能通过命令结果返回。
