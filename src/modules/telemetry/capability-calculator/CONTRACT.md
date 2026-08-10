# capability-calculator 二级模块契约

状态：已实施
版本：1.0.0
所属一级模块：`telemetry`
Gradle 路径：`:telemetry:capability-calculator`

## 1. 唯一职责

本模块只将内部 `DeviceCapabilities` 转换为电脑端遥测契约中的能力字段。它不重新判断设备状态，不读取 DJI，不执行操作，不保存状态。

## 2. 对外接口

```text
CapabilityCalculator.calculate(deviceCapabilities) -> TelemetryCapabilities
```

输出字段：`liveVideo`、`waypointMission`、`waypointMissionSupport`、`virtualStick`。

## 3. 规则

- `liveVideo` 直接来自 `canStreamVideo`。
- `waypointMission` 直接来自 `canRunWayline`。
- `waypointMissionSupport` 仅描述当前是否可执行，为 `supported` 或 `unsupported`。
- `virtualStick` 固定为 `false`，手机端契约明确不提供虚拟摇杆。
- 这是纯函数，不应引入 DJI 型号白名单或另一个能力判断系统。

## 4. 测试要求

覆盖全不可用、航线和直播分别可用、全部可用，以及虚拟摇杆恒为 false。
