# flight-command-handler 二级模块契约

状态：实施中
Gradle 路径：`:flight-control:flight-command-handler`

## 唯一职责

将协议 `CommandFrame` 转换为不可变的 `FlightAction`，并在调用动作端口前执行严格的字段校验。

## 输入和输出

仅接受下列精确形式：

```json
{ "name": "flight.takeoff", "fields": { "confirm": true } }
{ "name": "flight.land", "fields": { "confirm": true } }
{ "name": "flight.confirm-landing", "fields": { "confirm": true } }
{ "name": "flight.return-home", "fields": { "confirm": true } }
{ "name": "flight.stop-takeoff", "fields": { "confirm": true } }
{ "name": "flight.stop-auto-landing", "fields": { "confirm": true } }
```

返回 `Accepted` 只表示动作已被下层接收，不表示飞行已发生或完成。字段、确认、未知命令和下层拒绝均返回稳定、无 SDK 细节的拒绝类型。该模块不保留状态，不创建线程，不调用 DJI。

完成回调由本模块的单次包装器保护：下层的重复回调只向上游转发第一次。
