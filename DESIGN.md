# 方案：最小完整遥测扩展

复用现有 KeyManager 订阅、设备事实、遥测快照和协议映射，通过可选字段增加机头航向及可靠的高度和新鲜度信息。完整设计见 [XJICloud DESIGN](../XJICloud/DESIGN.md)。

- 航向必须是机头朝向，缺失值不能变成北向。
- 相对高度不是海拔，三维显示必须明确高度基准。
- 位置新鲜度来自有效定位采样，不能由电量、图传等更新刷新。
- 飞控断连清理来源数据，防止旧会话覆盖新状态。
- 遵守模块契约和测试档案要求；不改变航线、飞控动作或手机操作流程。

接口选择及高度语义待调研后补充；真机效果不作为已验证结论。

## 已确定接口

MSDK 5.17.0 KeyCompassHeading 取 [-180,180] 度并归一化到 [0,360)，KeyCompassHasError 时隐藏航向；KeyGPSIsValid=false 不生成有效采样。KeyAircraftLocation3D.altitude 与 KeyTakeoffLocationAltitude 保留有限原始值，但公开文档未明确垂直基准，因此地图采用标注后的平面定位。调研来源见相邻 XJICloud/docs/AIRCRAFT_TELEMETRY_RESEARCH.md。

定位订阅及初始读取生成 UUID:递增序号，System.nanoTime 记录采样；snapshot 动态计算 positionAgeMillis。飞控断连清理事实，旧观察代次不回流；可选 Key 的不支持不阻断原遥测。新字段追加在构造参数末尾，兼容已有位置参数调用。序列化沿用协议 v1。

端到端模拟已验证新增字段经过真实网关到网页多机图层；完整报告与启动方式见相邻 XJICloud/docs/AIRCRAFT_MAP_DELIVERY.md。当前方案依然是最小完整方案；垂直基准未知时不升级为三维高度推断。
