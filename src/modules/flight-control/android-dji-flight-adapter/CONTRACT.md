# android-dji-flight-adapter 二级模块契约

状态：实施中
Gradle 路径：`:flight-control:android-dji-flight-adapter`

## 唯一职责

提供 `DjiFlightPort` 的 Android 实现，并且是整个手机端唯一可调用 MSDK 飞控动作键的模块。

| 动作 | MSDK 键 |
| --- | --- |
| `TAKEOFF` | `FlightControllerKey.KeyStartTakeoff` |
| `LAND` | `FlightControllerKey.KeyStartAutoLanding` |
| `RETURN_HOME` | `FlightControllerKey.KeyStartGoHome` |

它把 MSDK 成功/失败回调映射到端口完成回调，捕获同步异常，并隔离重复或关闭后的回调。它不检查 `confirm`、不超时、不排队、不读取遥测，且不得公开 `IDJIError` 原文。
