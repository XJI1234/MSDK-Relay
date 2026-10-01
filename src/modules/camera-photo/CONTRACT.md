# camera-photo 一级模块契约

状态：已实现
Gradle 路径：`:camera-photo`

## 唯一职责

`camera-photo` 负责两件互相独立的事：让飞行器拍下一张相机原图，以及把手机已从飞行器取到的那张原图分块回传到电脑。拍照成功只表示 DJI 已把文件写入飞行器存储；回传成功只表示电脑已按摘要收完整文件。两者都不经过图传、航线、飞控或设备设置。

它不负责 RTMP/WHIP 生命周期、视频截帧、航线 KMZ、飞控动作、相册浏览、桌面 UI，也不自动停止或重启图传。DJI 在下载原图时是否卡住图传，由飞机射频决定；本模块失败时只报告稳定结果，绝不调用 `live-stream.stop`。

## 对外命令

```text
camera.photo.capture    fields: {}
camera.photo.fetch      fields: { knownPhotos: [{ fileName, sha256 }] }
```

`capture` 不接受额外字段；`fetch` 只接受当前电脑的 `knownPhotos` 清单。`capture` 成功时 `command-result.result` 为：

```json
{
  "domain": "photo",
  "outcome": "CAPTURED",
  "fileName": "安全图片基名",
  "index": 非负整数
}
```

`fetch` 成功时 `command-result.result` 为最后一张已确认照片，并带本次批量数量：

```json
{
  "domain": "photo",
  "outcome": "DELIVERED",
  "fileName": "安全图片基名",
  "size": 1..104857600,
  "sha256": "64 个小写十六进制字符",
  "count": 正整数
}
```

没有待传照片时，`fetch` 仍为成功命令，但结果为 `{ domain: "photo", outcome: "NONE", count: 0 }`。`knownPhotos` 是调用电脑当前收件箱清单；手机逐张下载并等待电脑 `media-result.ok=true` 后继续，直到 `NONE`。手机端持久发送账本不能替代本次电脑清单。

`fileName` 必须是 `1..128` 个 Unicode 码点的基名，不含 `/`、`\`、`..` 或控制字符，并以 ASCII 大小写不敏感的 `.jpg`、`.jpeg` 或 `.dng` 结尾。`index` 只标识本次拍照在适配器观察中的文件序号，不是相册浏览器。旧电脑端可忽略未知 `result`；未实现本命令的旧手机端按未知命令拒绝，不得断开会话。

`ok: true` 的含义：

| 命令 | 成功只表示 | 不表示 |
| --- | --- | --- |
| `camera.photo.capture` | DJI 已确认 `KeyStartShootPhoto`，且适配器已得到本次文件身份 | 电脑已有文件、图传未受影响、下一张也能拍 |
| `camera.photo.fetch` | 本次批量中每个文件均已下到手机、分块已发完且电脑 `media-result.ok=true` | 操作员已打开照片、图传码率未变 |

## 二级模块

| 模块 | 唯一职责 | 不负责 |
| --- | --- | --- |
| `photo-command-handler` | 严格解析两个命令、校验空字段，并定义平台无关的拍照/回传请求 | 状态、线程、DJI、分块发送 |
| `photo-executor` | 经拍照域 DJI 操作协调器执行一次拍照或一次从飞行器下载，统一超时、取消和终态 | 协议解析、Android SDK 类型、WebSocket |
| `android-dji-photo-adapter` | 唯一调用 `CameraKey` 拍照和 `IMediaManager` 下载 | 命令校验、排队、超时、网关分块 |
| `photo-media-publisher` | 把已在手机私有缓存中的原图按 `media-begin/chunk/complete` 发给电脑，并等待 `media-result` | DJI 调用、命令解析、桌面落盘 |

## 失败与生命周期

任何字段错误都在调用 DJI 或发送分块前拒绝。同一 `CameraPhoto` 门面最多保留一个已接受但未到终态的命令；第二个 `capture` 或 `fetch` 必须在触及拍照域协调器或媒体发送前以稳定的本地 `OPERATION_REJECTED` 拒绝。此规则只防止陈旧拍照意图排队，不检查硬件 Key，也不把 DJI 的异步拒绝改写为本地拒绝。

`fetch` 按调用参数中的电脑清单扫描飞行器相册，不要求当前门面刚刚 `capture`。一次 `fetch` 只允许一个批量循环；每次只下载一张，必须在对应电脑 `media-result.ok=true` 后才选择下一张。传输失败、超时、取消或断线不得加入本次已确认集合，下一次显式 `fetch` 必须能重试。新的成功 `capture` 不会取消其它已在途批量；同一门面仍拒绝并发命令。

拍照域只串行本域的 DJI 写操作（模式切换、快门、媒体下载），不得因超时隔离拒绝飞行、航线、配对、设置或图传提交。下载完成后的 WebSocket 分块不属于 DJI 操作槽位。`capture` 超时 15_000 ms，`fetch` 的 DJI 下载超时 60_000 ms；均仍受协调器 `1_000..60_000` ms 限制。电脑确认分块的等待另计，由 `photo-media-publisher` 使用注入时钟，上限 120_000 ms。超时、取消后不得假装 DJI 已拍下或电脑已收到。

拍照或下载超时后可以再次尝试，不增加等待硬件恢复的本地门禁。旧请求的中止和迟到回调只归属于旧会话；重试若因 DJI 尚未就绪而失败，以该次 DJI 回调为准。旧请求在 DJI 内部是否仍执行、无请求 ID 的媒体事件归属，须实机验证。

真实 DJI `onFailure(IDJIError)` 必须在 Android 边界清除控制字符并限制为 128/512 个 Unicode 码点后，以 `{ "domain": "photo", "outcome": "ACTION_REJECTED", "errorCode", "errorDescription" }` 回传；不得泄露 `IDJIError` 对象或堆栈。同步调用异常不得伪造为 DJI 拒绝，必须标为 `{ "domain": "photo", "outcome": "INVOCATION_FAILED" }`；超时或取消使用 `RESULT_UNCONFIRMED`。媒体发送失败、电脑 `media-result.ok=false`、会话失效使用 `{ "domain": "photo", "outcome": "TRANSFER_FAILED" }`，且不得把已拍成功改写成未拍。

设备失效或组合根关闭必须取消在途拍照/下载/分块，清除未回传身份，丢弃迟到回调。电脑会话离开 `ACTIVE` 时必须中止分块并失败在途 `fetch`，但保留最近一次已确认拍照身份，以便重连后再次 `fetch`；不得自动重试快门或回传。

## 与图传的边界

拍照可以按 DJI 要求把相机模式切到 `PHOTO_NORMAL`。Mini 4 Pro 是单镜头，快门使用不带镜头参数的 `KeyStartShootPhoto`，不得改成变焦或广角镜头键，也不得为了拍照拆掉图传解码 Surface 或停止 RTMP。本模块不拥有图传，不得启动、停止或恢复 RTMP/WHIP，不得调用 `ILiveStreamManager`。快门成功或失败后必须把 `KeyCameraMode` 设回切换前的值；切换前未知或仍是 `PHOTO_NORMAL` 时设 `VIDEO_NORMAL`。回传拉完原图后必须 `IMediaManager.disable`，并且只在该 `disable` 回调返回后才完成下载，以便相册占用的图传链路先释放，再走 WebSocket 分块。

应用冷启动或视频源重新连接时，图传门禁先对 `KeyIsPlayingBack` 做一次只读检查。明确为 `false` 或读取失败时不得调用 `IMediaManager.disable`、不得修改 `KeyCameraMode`，并按原有视频源连接和在途照片任务规则放行。只有明确为 `true` 时，才可对该连接代际调用一次 `IMediaManager.disable`；必须读回 `KeyIsPlayingBack=false` 才放行，失败或仍为 `true` 时保持门禁关闭。该兜底只清理遗留媒体管理状态，不是相机编码器就绪证据，也不得替代 `fetch` 自身在所有终态的媒体退出。

启动后若 DJI 的该次 RTMP 首个状态同时明确报告 `fps=0`、`bps=0`，且只读 `CameraFrameObserver` 仍为当前监听代际的 `UNOBSERVED`，这是已经确认的编码输入零帧故障，不是启动前检查。组合根必须在任何恢复前只读 `KeyCameraMode` 与 `KeyIsPlayingBack` 并将两项结果作为该次零帧诊断事实记录；读取失败必须如实记录为未知，且不得据此改变门禁或跳过既有恢复。`CameraMediaReadiness` 只在启动前检查已完成、没有在途照片媒体操作时，允许该视频源连接代际执行一次专用恢复；该恢复直接调用 `IMediaManager.disable` 并读回媒体退出结果，不再以 `KeyIsPlayingBack` 为条件。它不得改变 `KeyCameraMode`、不得停止或重新开始 RTMP、不得影响正 FPS 或已观察到相机帧的启动。连接源断开后才可为下一连接代际重新获得一次机会。
