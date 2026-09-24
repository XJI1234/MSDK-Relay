# android-dji-stream-adapter 模块契约

状态：已实现
版本：1.1.1
所属一级模块：live-stream
逻辑 Gradle 路径：`:live-stream:android-dji-stream-adapter`

## 唯一职责

本模块是 `DjiStreamPort` 的 DJI MSDK v5 Android 实现。它只把已校验 RTMP 地址配置给 DJI 直播管理器，提交开始或停止操作，并把终态、`LiveStreamStatus` 原始推流状态、运行期失效和安全指标转换为平台无关回调。它不校验 URL，不保存公开直播状态，不决定超时、重试或并发策略，不处理电脑端命令，不注册 SDK，不管理权限，也不渲染界面。

## 对外接口

```text
AndroidDjiStreamPort.create(diagnosticSink?) -> DjiStreamPort
port.start(config, status, runtimeFailure, completion) -> Unit
port.stop(completion) -> Unit
```

每个开始或停止调用必须至多完成一次。只有 MSDK `CompletionCallback.onFailure(IDJIError)` 才映射为 `completion.fail(failure)`；适配器调用 MSDK 方法时发生的同步异常可能已经越过 DJI 调用边界，必须原样抛回图传专用 `dji-operation-coordinator`，绝不能伪造 `completion.fail()`。同步异常后，Android 端口必须仅释放本地 `platformOperationInFlight` 标记，使协调器能够提交同一代次的恢复性 `stopStream`；有效状态监听和 `active` 代次仍须保留，直到真实 MSDK 终态回调或 `close()`。业务启动不得借此绕过协调器的未确认结果隔离。对于 MSDK `CompletionCallback.onFailure(IDJIError)`，必须在手机边界立即读取 `error.errorCode()` 和 `error.description()`，经过控制字符清理和有界复制后，以 `StreamDjiFailure` 随 `completion.fail(failure)` 逐层传递；它必须最终作为中继结构化结果 `{ domain: "live-stream", outcome: "ACTION_REJECTED", errorCode, errorDescription }` 到达桌面。只有 MSDK 实际调用 `onFailure` 才能使用该结果，绝不能以本地异常、超时、取消、断线或中继失败伪造它。成功开始后，`LiveStreamStatusListener` 的每个 `isStreaming` 值均经 `status` 回调逐值交给上层；`true` 同时携带指标，`false` 不携带旧指标。开始完成前的 `false` 只是启动前基线，不得误报失败；开始完成前的最新 `true` 必须在成功回调已交付后补发，不能丢失。运行期 `onError(IDJIError)` 只调用该代次的 `runtimeFailure`，并用同一规则复制原始错误码和说明；它不是开始或停止命令的完成回调，不得伪造 `isStreaming=false` 或覆盖相应按钮的命令结果。停止、失败或新开始后到达的旧状态、指标和错误必须忽略。

固定按以下顺序调用 `ILiveStreamManager`；这是 Mini 4 Pro 的 Full HD 已验证调用配方，不得调换、遗漏或以看似等价的设置替换：

```kotlin
manager.setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)
manager.setLiveStreamSettings(
    LiveStreamSettings.Builder().setLiveStreamType(LiveStreamType.RTMP)
        .setRtmpSettings(RtmpSettings.Builder().setUrl(url).build()).build(),
)
manager.setLiveStreamQuality(StreamQuality.FULL_HD)
manager.setLiveVideoBitrateMode(LiveVideoBitrateMode.MANUAL)
manager.setLiveVideoBitrate(4_096_000)
manager.addLiveStreamStatusListener(listener)
manager.startStream(completion)
```

不得调用 `setLiveStreamScaleType`；`FULL_HD + FIX_XY + AUTO` 会让 Mini 4 Pro 的 `startStream` 成功、状态显示推流，却持续上报 `0 fps / 0 bps`。也不得把手动码率任意改成 3 Mbit/s 或 8 Mbit/s：这两个值同样会推流成功但没有视频包。恢复配置后仍须以 DJI 状态的正 FPS/码率和桌面首个关键帧共同确认实际 1080p 出画。

Mini 4 Pro 的 Full HD 实机基准（2026-09-24）已验证这条配置的完整链路：手机依次记录 `CAMERA_FIRST_FRAME codec=H264;width=1920;height=1080`、`FIRST_STATUS` 的正 FPS/码率和 `FIRST_VIDEO_OUTPUT`，中继记录 RTMP 已发布，桌面记录 `VIDEO_FIRST_FRAME_RENDERED width=1920;height=1080`。同一次运行中拍照和照片回传均成功；照片回传期间原始相机帧可短暂停顿，但恢复后桌面仍持续得到 1080p 首帧。未来不得仅凭 `startStream` 成功、`isStreaming=true` 或 RTMP 连接建立而替换该配置。任何替换必须在同型号真机上重新满足以上手机、RTMP 和桌面三端证据，并在拍照及照片回传后确认图传恢复；同时更新本契约和 `MsdkV5LiveStreamApiContractTest` 的配置断言。

部署记录（2026-09-24）：以上配方与同步异常恢复修复已构建并安装为 `src/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 为 `345D630F544412A38A17AB91851132CE0426B8DBE0B65C6200B2DB96346EE7B1`；ADB 从设备安装路径回读的 SHA-256 完全一致。本次操作员已观察到桌面出画。该记录用于排除“安装了旧 APK”的错误，不替代上述手机、RTMP 和桌面三端验收证据。

`SDK_READY`、注册回调或设备状态通知不得提前调用 `ILiveStreamManager`、`setCameraIndex` 或触发 MRTC 原生库初始化。2026-09-24 的失败包曾在 `LiveStreamManagerPreheater` 中这样做，直接导致 `libmrtc_core_jni` 的 `JNI_OnLoad` 失败；之后即使 `startStream` 成功并连接 RTMP，也只有 `0 fps` 和空媒体。相机选择及其他直播管理器调用只能在图传专用操作协调器已通过相机源门禁后，按本节的 `start` 顺序执行。

可选 `diagnosticSink` 仅在设置完成/失败、发起开始、同步调用异常、真实开始回调、首个状态、首次报告正 FPS 的状态及首次运行期错误时发出固定事件码、尝试序号和 FPS/码率数值；同一尝试不得逐状态刷日志。它不得接收 RTMP URL、视频字节、DJI 错误文本或堆栈。sink 异常必须隔离，不得改变任何 DJI 方法的顺序、回调或异常语义。

`ILiveStreamManager` 是本模块唯一持有的 MSDK 管理器。`ICameraStreamManager` 属于本地预览和原始帧读取：本模块不得取得 `cameraStreamManager`、增删 `Surface`、切换相机流启用状态或后台解码；也不得调用 `MediaDataCenter.mediaManager`、`KeyManager` 或任一相机模式 Key。相册、相机模式和本地预览分别由对应模块拥有，Activity 不得通过全局 Surface 参与 RTMP 开始或停止。这样预览或拍照转换就无法改写 RTMP 输入生命周期。

适配器必须在图传操作协调器提交的同一线程同步调用 `ILiveStreamManager`，不得自行 post 到 `Looper.getMainLooper()` 后等待。这会保持图传模块的接口与 UI 解耦，避免引入另一个未受协调器控制的 MSDK 初始化路径。`LiveStreamStatus` 的全部 v5.17 字段均一对一进入平台无关事实：`isStreaming`、resolution、FPS、vbps、packetLoss、packetCacheLen、RTT。分辨率仅在宽高均为正数时输出 `宽x高`；其余整数指标仅在非负时输出；`packetLoss` 和 `packetCacheLen` 保持 DJI 原始整数值，不擅自解释为百分比或时间。调用方异常必须隔离。

DJI 直播管理器只有一个状态监听槽位，本适配器必须进程内独占。每次开始建立一个代次；失败或成功停止时释放监听器。停止失败时保留当前监听器。模块仅依赖 `:live-stream:dji-stream-adapter` 和 DJI MSDK v5.17。

DJI 开始或停止操作从提交到真实终态回调期间属于平台操作占用期。占用期内到达的任何新开始或停止请求必须立即失败且不得再次调用 DJI，避免旧停止在上层超时后误停新流。同步异常后不得把业务操作视为已完成：协调器仍隔离未确认结果；但端口必须释放本地操作标记，以便协调器排队提交恢复性停止。恢复停止成功、真实终态回调或 `close()` 才能结束该代次。

运行期错误或 `isStreaming=false` 只通知上层图传适配器；本模块不得在监听回调中自行调用 `stopStream`。所有后续停止必须由上层经图传专用 DJI 操作协调器排队提交。

JVM 测试覆盖配置、开始/停止成功和失败、同步异常、重复终态、指标归一化、运行期错误、迟到回调及监听释放。Android Debug 构建必须编译真实 `ILiveStreamManager`；真机仍需验证 RTMP 推流、指标单位、断网错误和相机源可用性。
