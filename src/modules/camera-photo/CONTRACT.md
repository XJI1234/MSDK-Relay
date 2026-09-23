# camera-photo 一级模块契约

状态：已实现
Gradle 路径：`:camera-photo`

## 唯一职责

`camera-photo` 负责两件互相独立的事：让飞行器拍下一张相机原图，以及把手机已从飞行器取到的那张原图分块回传到电脑。拍照成功只表示 DJI 已把文件写入飞行器存储；回传成功只表示电脑已按摘要收完整文件。两者都不经过图传、航线、飞控或设备设置。

它不负责 RTMP/WHIP 生命周期、视频截帧、航线 KMZ、飞控动作、相册浏览、桌面 UI，也不自动停止或重启图传。DJI 在下载原图时是否卡住图传，由飞机射频决定；本模块失败时只报告稳定结果，绝不调用 `live-stream.stop`。

## 对外命令

```text
camera.photo.capture    fields: {}
camera.photo.fetch      fields: {}
```

两个命令都不接受额外字段。`capture` 成功时 `command-result.result` 为：

```json
{
  "domain": "photo",
  "outcome": "CAPTURED",
  "fileName": "安全图片基名",
  "index": 非负整数
}
```

`fetch` 成功时 `command-result.result` 为：

```json
{
  "domain": "photo",
  "outcome": "DELIVERED",
  "fileName": "安全图片基名",
  "size": 1..104857600,
  "sha256": "64 个小写十六进制字符"
}
```

`fileName` 必须是 `1..128` 个 Unicode 码点的基名，不含 `/`、`\`、`..` 或控制字符，并以 ASCII 大小写不敏感的 `.jpg`、`.jpeg` 或 `.dng` 结尾。`index` 只标识本次拍照在适配器观察中的文件序号，不是相册浏览器。旧电脑端可忽略未知 `result`；未实现本命令的旧手机端按未知命令拒绝，不得断开会话。

`ok: true` 的含义：

| 命令 | 成功只表示 | 不表示 |
| --- | --- | --- |
| `camera.photo.capture` | DJI 已确认 `KeyStartShootPhoto`，且适配器已得到本次文件身份 | 电脑已有文件、图传未受影响、下一张也能拍 |
| `camera.photo.fetch` | 飞行器文件已下到手机，分块已发完，且电脑 `media-result.ok=true` | 操作员已打开照片、图传码率未变 |

## 二级模块

| 模块 | 唯一职责 | 不负责 |
| --- | --- | --- |
| `photo-command-handler` | 严格解析两个命令、校验空字段，并定义平台无关的拍照/回传请求 | 状态、线程、DJI、分块发送 |
| `photo-executor` | 经拍照域 DJI 操作协调器执行一次拍照或一次从飞行器下载，统一超时、取消和终态 | 协议解析、Android SDK 类型、WebSocket |
| `android-dji-photo-adapter` | 唯一调用 `CameraKey` 拍照和 `IMediaManager` 下载 | 命令校验、排队、超时、网关分块 |
| `photo-media-publisher` | 把已在手机私有缓存中的原图按 `media-begin/chunk/complete` 发给电脑，并等待 `media-result` | DJI 调用、命令解析、桌面落盘 |

## 失败与生命周期

任何字段错误都在调用 DJI 或发送分块前拒绝。同一 `CameraPhoto` 门面最多保留一个已接受但未到终态的命令；第二个 `capture` 或 `fetch` 必须在触及拍照域协调器或媒体发送前以稳定的本地 `OPERATION_REJECTED` 拒绝。此规则只防止陈旧拍照意图排队，不检查硬件 Key，也不把 DJI 的异步拒绝改写为本地拒绝。

`fetch` 只能针对本门面当前代次里最近一次 `capture` 成功得到的文件身份。没有该身份、身份已被成功回传并清除、或设备失效后，必须拒绝 `fetch`，不得扫描飞行器相册、不得猜最新一张。新的成功 `capture` 替换尚未回传的旧身份；被替换的旧文件不再由本模块回传。

拍照域只串行本域的 DJI 写操作（模式切换、快门、媒体下载），不得因超时隔离拒绝飞行、航线、配对、设置或图传提交。下载完成后的 WebSocket 分块不属于 DJI 操作槽位。`capture` 超时 15_000 ms，`fetch` 的 DJI 下载超时 60_000 ms；均仍受协调器 `1_000..60_000` ms 限制。电脑确认分块的等待另计，由 `photo-media-publisher` 使用注入时钟，上限 120_000 ms。超时、取消后不得假装 DJI 已拍下或电脑已收到。

真实 DJI `onFailure(IDJIError)` 必须在 Android 边界清除控制字符并限制为 128/512 个 Unicode 码点后，以 `{ "domain": "photo", "outcome": "ACTION_REJECTED", "errorCode", "errorDescription" }` 回传；不得泄露 `IDJIError` 对象或堆栈。同步调用异常不得伪造为 DJI 拒绝，必须标为 `{ "domain": "photo", "outcome": "INVOCATION_FAILED" }`；超时或取消使用 `RESULT_UNCONFIRMED`。媒体发送失败、电脑 `media-result.ok=false`、会话失效使用 `{ "domain": "photo", "outcome": "TRANSFER_FAILED" }`，且不得把已拍成功改写成未拍。

设备失效或组合根关闭必须取消在途拍照/下载/分块，清除未回传身份，丢弃迟到回调。电脑会话离开 `ACTIVE` 时必须中止分块并失败在途 `fetch`，但保留最近一次已确认拍照身份，以便重连后再次 `fetch`；不得自动重试快门或回传。

## 与图传的边界

拍照可以按 DJI 要求把相机模式切到 `PHOTO_NORMAL`。Mini 4 Pro 是单镜头，快门使用不带镜头参数的 `KeyStartShootPhoto`，不得改成变焦或广角镜头键，也不得为了拍照拆掉图传解码 Surface 或停止 RTMP。本模块不拥有图传，不得启动、停止或恢复 RTMP/WHIP，不得调用 `ILiveStreamManager`。快门成功或失败后必须把 `KeyCameraMode` 设回切换前的值；切换前未知或仍是 `PHOTO_NORMAL` 时设 `VIDEO_NORMAL`。回传拉完原图后必须 `IMediaManager.disable`，并且只在该 `disable` 回调返回后才完成下载，以便相册占用的图传链路先释放，再走 WebSocket 分块。
