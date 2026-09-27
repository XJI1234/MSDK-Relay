# android-dji-photo-adapter 二级模块契约

## 1. 模块身份

- 模块名称：`android-dji-photo-adapter`
- 所属一级模块：`camera-photo`
- 当前版本：0.1.0
- 状态：已实现
- Gradle 路径：`:camera-photo:android-dji-photo-adapter`
- 唯一职责：唯一允许接触 DJI 拍照键和媒体下载 API 的 Android 实现。

## 2. 负责与不负责

### 负责

- 拍照：先监听并异步读取主相机 `CameraKey.KeyCameraMode`。只有异步 `getValue` 明确返回 `PHOTO_NORMAL` 时才可立刻进入最高画质设置。否则必须 `setValue` 为 `PHOTO_NORMAL`，并且只在这次切模式之后 listen 报出该模式才继续。开始读取模式时同时计 6 秒；到点仍未进入 `PHOTO_NORMAL` 且尚未按快门，必须失败为 `CAMERA_MODE_NOT_PHOTO`（说明为「相机没有进入拍照模式」）。不得把这次失败写成图传停止。不得拆掉图传解码 Surface，不得 `stopStream`。不得把 listen 的首次缓存值当成已切到拍照模式。不得在模式 `setValue` 的 `onSuccess` 里立刻拍摄。模式 `setValue` 失败时必须带着该 `IDJIError` 失败，不得再按快门。确认 `PHOTO_NORMAL` 后、按快门前，必须依次把 `KeyPhotoRatio=RATIO_4COLON3`、`KeyPhotoSize=SIZE_LARGE`（用 `CAMERA_LENS_DEFAULT` 的整数键，对应 Mini 4 Pro 的 48MP）、`KeyPhotoQuality=SFINE`、`KeyPhotoFileFormat=JPEG` 设到主相机。不得调用 `KeyPhotoResolution`，不得设 `SIZE_EXTRA_LARGE`。任一画质键 `onFailure` 或 2 秒内没有回调，仍继续下一键并最终按快门，同一键只继续一次。Mini 4 Pro 是单镜头，快门只能 `createKey(KeyStartShootPhoto, LEFT_OR_MAIN)`，不得 `createCameraKey`，不得传 `CAMERA_LENS_ZOOM` 或 `CAMERA_LENS_WIDE`。按快门前必须异步读取 `KeyCameraStorageInfos`。没有一块存储同时满足已插入且 `availablePhotoCount > 0` 时（含 SD 未插入、内部存储可拍张数为 0），必须带着 `STORAGE_NOT_READY` 失败，不得按快门。不得因为内部存储还有剩余容量就按快门。快门 `onFailure` 必须附带当时的 `cameraMode` 和存储摘要。不得用无回调的缓存 `getValue` 当作当前模式。
- 从 DJI 快门成功和 `KeyNewlyGeneratedMediaFile` 取出本次文件的安全基名与序号。拍照路径不得调用 `pullMediaFileListFromCamera`。`KeyNewlyGeneratedMediaFile` 的 listen 在快门 `onSuccess` 之前到达的值必须丢弃，不得把上一张照片的缓存身份当成这次快门成功。
- 回传下载：先监听主相机 `CameraKey.KeyIsPlayingBack`，再按当前 `KeyCameraStorageInfos` 调用 `setMediaFileDataSource`（机内为 `INTERNAL`，否则 `SDCARD`，载荷 `LEFT_OR_MAIN`）。已在回放则立刻拉列表；否则 `enable`。`enable` 成功后必须等 `KeyIsPlayingBack == true` 才 `pullMediaFileListFromCamera`，不得在 `enable` 的成功回调里直接拉列表。`enable` 的 `onFailure` 若当时还没进入回放，必须带着该 `IDJIError` 失败，不得再拉列表。列表过滤必须用 `MediaFileFilter.ALL`，不得用 `PHOTO`。再按文件身份匹配并拉原图到应用私有缓存，计算大小与 SHA-256，经端口返回抽象可读句柄。下载结束、失败或 `abort` 必须 `stopPullMediaFileListFromCamera` 并 `disable`。
- 把真实 `onFailure(IDJIError)` 立即读成受限 `PhotoDjiFailure`。DJI 成功/失败回调必须先投递到名为 `photo-delivery` 的单线程执行器，再读缓存文件、计算 SHA-256 或调用 executor 完成回调。不得在 DJI 回调线程或主线程上 `readBytes` 或触发媒体发送。

### 明确不负责

- 不校验命令字段、不排队、不定时；这些属于 handler 与 executor；
- 不发送 WebSocket，不写电脑目录；
- 不调用 `ILiveStreamManager`、飞控、航线或设置键，不停止图传，也不拆掉图传解码 Surface；
- 快门成功或失败后必须把 `KeyCameraMode` 设回切换前的值；切换前未知或仍是 `PHOTO_NORMAL` 时设 `VIDEO_NORMAL`。这是相机键恢复，不是图传启停。
- 原图下载结束或失败后必须 `IMediaManager.disable`，并且只在该回调返回后再完成下载。用于新连接的兜底先只读 `KeyIsPlayingBack`；明确为 `true` 才可调用一次 `disable`，并且必须读回 `false` 才报告恢复。`false` 或读取失败不得调用 `disable`，兜底不得修改 `KeyCameraMode`。

## 3. 对外接口

```text
PhotoHardwarePort.capture(completion)
PhotoHardwarePort.download(fileName, index, completion)
PhotoHardwarePort.abort(completion)
```

成功回调不得包含 Android `Context`、`File`、DJI 枚举对象或绝对路径。下载句柄只能被 `photo-media-publisher` 读成字节后删除。`abort` 必须尽力取消未完成的下载并删除未完成的临时文件。

## 4. 状态和生命周期

适配器不跨命令保存相册。每次请求持有独立 SDK 监听与可变状态；按本次 completion 中止，关闭或 `abort` 后到达的 DJI 回调必须丢弃，不得对新请求的 SDK 会话继续下载或恢复模式。私有缓存文件只存活到本次 `download` 成功交接或失败清理。`KeyNewlyGeneratedMediaFile` 不带请求身份，迟到的硬件媒体事件归属仍须实机验证。

## 5. 数据所有权

应用私有缓存中的临时照片由本模块创建和删除。公开结果只有基名、序号、字节数和 SHA-256。不得把 SD 卡路径或 DJI `MediaFile` 对象传出 Android 边界。

## 6. 依赖和替身

```text
本模块 -> PhotoHardwarePort
生产环境 -> MSDK v5 CameraKey 与 IMediaManager
测试环境 -> 不在 JVM 模块运行；契约行为由 executor 用替身覆盖
```

无法用纯 JVM 覆盖的部分：目标机型在图传开启时能否切到 `PHOTO_NORMAL`、下载是否被 DJI 拒绝、下载是否造成图传卡顿。这些必须在实机注明，不得在 JVM 里假装成功。

## 7. 错误、超时和并发

| 情况 | 对外结果 | 状态变化 | 是否可重试 |
| --- | --- | --- | --- |
| 模式或快门 `onFailure` | `PhotoDjiFailure` | 不留下半个文件身份 | 可由操作者重试拍照 |
| 下载 `onFailure` | `PhotoDjiFailure` | 删除临时文件 | 文件身份仍由门面保留，可再 fetch |
| 同步抛出 | 不调用失败回调，原样传播 | 由协调器隔离 | 否 |
| 缺少文件名或非法扩展名 | 端口失败，非 DJI 拒绝 | 无公开身份 | 否 |

适配器在新请求开始前先尽力中止旧请求；不能把 DJI 的异步中止视为硬件已停止，重试若被拒绝应回传 DJI 本身的失败。

## 8. 测试要求

Android 仪器测试覆盖：模式设置与快门的调用顺序、失败归一化、下载成功后句柄可读且路径不泄露、失败删除临时文件、abort 丢弃迟到回调。JVM 模块禁止依赖本适配器源码。

## 9. 变更规则

更换媒体 API、支持新扩展名，必须先改一级契约和根契约。相机模式恢复属于本适配器对一级契约「与图传的边界」的落实。
