# dji-flight-adapter 二级模块契约

状态：实施中
Gradle 路径：`:flight-control:dji-flight-adapter`

## 唯一职责

把一个 `FlightAction` 作为共享 DJI 操作协调器中的单个操作执行，并映射为稳定终态。它拥有超时和取消语义，但不解析协议、不决定用户确认，也不依赖 Android 或 DJI SDK 类型。

## 接口

```text
DjiFlightAdapter.create(port, coordinator, timeoutMillis) -> DjiFlightAdapter
adapter.execute(action, listener) -> FlightSubmissionResult
adapter.observeState(fact) -> Unit
```

`timeoutMillis` 必须在 1,000 到 60,000 毫秒之间。端口只能调用 `succeed` 或 `fail`；`fail` 可以附带已规范化的 DJI 错误摘要（错误码与说明），适配器必须与该次失败终态一同向上游转交。异常、超时、取消和重复/迟到回调都归一化为最多一次终态；只有 MSDK 失败回调携带的摘要才可被标记为 DJI 拒绝。协调器拒绝提交时不调用端口。

超时或已开始操作的取消并不证明飞控没有收到 DJI 调用。此时共享控制协调器保留当前操作槽位，普通新飞行写操作必须拒绝，直至原 DJI 回调或与该动作对应的权威状态观察确认硬件已稳定。唯一受限例外是单向收尾动作：`LAND`、`CONFIRM_LANDING`、`RETURN_HOME`、`STOP_TAKEOFF`、`STOP_AUTO_LANDING` 可声明为协调器的 `CONTAINMENT`，替换旧的未确认回调并尝试送达 MSDK；这不把旧动作标记为成功，也不允许 `TAKEOFF` 越过隔离。旧回调只作迟到完成处理，不能覆盖收尾命令结果。`observeState(fact)` 只接收由组合根从连续 MSDK `KeyIsFlying`、`KeyAreMotorsOn`、`KeyFCFlightMode`、`KeyIsLandingConfirmationNeeded` 监听映射的不可变事实；它不接收桌面消息、定时器或缓存读取。

每次飞行动作实际调用 DJI 前，适配器记录状态观察代次与修订号。只有同一观察代次、修订号严格更晚且满足该动作官方运行态结果的事实，才能在该操作超时/取消后释放协调器槽位：起飞为 `AUTO_TAKE_OFF` 或 `isFlying=true`；开始降落为 `AUTO_LANDING`、`CONFIRM_LANDING` 或从已飞行变为已落地；确认降落为从请求前已要求确认进入 `AUTO_LANDING` 或落地；返航为 `GO_HOME`；停止起飞或停止降落为对应自动飞行模式退出。任何观察代次变化、缺失值、错误动作状态或调用前状态都不得解锁。该恢复不把原命令的超时/取消改写为成功。

`STOP_TAKEOFF` 与 `STOP_AUTO_LANDING` 也经同一协调器串行提交，绝不绕过队列并发访问 `KeyManager`；调用方必须让操作者显式确认，且不得把已排队或已接受解释为飞控已经悬停。
