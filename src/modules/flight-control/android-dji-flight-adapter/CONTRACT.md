# android-dji-flight-adapter 二级模块契约

状态：实施中
Gradle 路径：`:flight-control:android-dji-flight-adapter`

## 唯一职责

提供 `DjiFlightPort` 的 Android 实现，并且是整个手机端唯一可调用 MSDK 飞控动作键的模块。

| 动作 | MSDK 键 |
| --- | --- |
| `TAKEOFF` | `FlightControllerKey.KeyStartTakeoff` |
| `LAND` | `FlightControllerKey.KeyStartAutoLanding` |
| `CONFIRM_LANDING` | `FlightControllerKey.KeyConfirmLanding` |
| `RETURN_HOME` | `FlightControllerKey.KeyStartGoHome` |
| `STOP_TAKEOFF` | `FlightControllerKey.KeyStopTakeoff` |
| `STOP_AUTO_LANDING` | `FlightControllerKey.KeyStopAutoLanding` |

它把 MSDK 成功/失败回调映射到端口完成回调，捕获同步异常，并隔离重复或关闭后的回调。收到 `onFailure(IDJIError)` 时，它必须立即读取 `errorCode()` 和本地化 `description()`，清除控制字符、限制长度并转换为纯 Kotlin DJI 错误摘要；不得跨此边界公开 `IDJIError` 对象、堆栈或其他 Android/DJI 类型。`KeyConfirmLanding` 仅继续 DJI 已明确要求人工确认的自动降落，绝不由本模块自行触发；它不是绕过视觉/降落保护的自动化手段。停止自动起飞/自动降落只表示请求飞控停止对应自动过程，官方结果为当前高度悬停；它们不是停机、立即落地或起飞前的替代操作。它不检查 `confirm`、不超时、不排队、不读取遥测。
