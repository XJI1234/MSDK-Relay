# android-dji-stream-adapter 模块契约

状态：已实现
版本：1.0.0
所属一级模块：live-stream
逻辑 Gradle 路径：`:live-stream:android-dji-stream-adapter`

## 唯一职责

本模块是 `DjiStreamPort` 的 DJI MSDK v5 Android 实现。它只把已校验 RTMP 地址配置给 DJI 直播管理器，提交开始或停止操作，并把终态、运行期失效和安全指标转换为平台无关回调。它不校验 URL，不保存公开直播状态，不决定超时、重试或并发策略，不处理电脑端命令，不注册 SDK，不管理权限，也不渲染界面。

## 对外接口

```text
AndroidDjiStreamPort.create() -> DjiStreamPort
port.start(config, metrics, runtimeFailure, completion) -> Unit
port.stop(completion) -> Unit
```

每个开始或停止调用必须至多完成一次。DJI 同步异常和失败回调统一映射为 `completion.fail()`。成功开始后，状态监听器的运行期错误调用该代次的 `runtimeFailure`；停止、失败或新开始后到达的旧指标和旧错误必须忽略。

固定使用 `LiveStreamType.RTMP`、主相机 `LEFT_OR_MAIN`、`StreamQuality.HD`（1280×720）与 `LiveVideoBitrateMode.MANUAL`（约 220 KByte/s）。手机热点场景优先流畅，避免 `FULL_HD`+高码率导致卡顿；也避免 `AUTO` 为流畅反复降码。分辨率仅在宽高均为正数时输出 `宽x高`；FPS、DJI 5.17 定义的 kbps 和 RTT 毫秒值仅在非负时输出。调用方异常必须隔离。

DJI 直播管理器只有一个状态监听槽位，本适配器必须进程内独占。每次开始建立一个代次；失败或成功停止时释放监听器。停止失败时保留当前监听器。模块仅依赖 `:live-stream:dji-stream-adapter` 和 DJI MSDK v5.17。

DJI 开始或停止操作从提交到终态期间属于平台操作占用期。占用期内到达的任何新开始或停止请求必须立即失败且不得再次调用 DJI，避免旧停止在上层超时后误停新流。同步异常也必须结束占用期，使调用方可以重试。

JVM 测试覆盖配置、开始/停止成功和失败、同步异常、重复终态、指标归一化、运行期错误、迟到回调及监听释放。Android Debug 构建必须编译真实 `ILiveStreamManager`；真机仍需验证 RTMP 推流、指标单位、断网错误和相机源可用性。
