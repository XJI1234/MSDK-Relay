# wpmz-generator 模块契约

状态：DJI 安全入场配置待按此契约重构；版本：2.0.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:wpmz-generator

## 唯一职责与接口

本模块将完整且已决定的航点计划转换为经 DJI WPMZ SDK 校验的 WPMZ/KMZ 字节序列。它负责把已经决定的安全入场配置精确写入 DJI WPML：`flyToWaylineMode=safely`、安全起飞高度和入场速度。电脑端持有路径规划、地图编辑、航点选择、相机动作策略、机型/负载选择、安全高度决策和面向用户的命名；本模块不规划、不改航点顺序、不自行选择安全高度、不上传/执行、不访问 Android 文件、不发送网络，也不写入 `mission-staging`。

```text
WpmzGenerator.create() -> WpmzGenerator
generator.generate(plan) -> Generated(artifact) | Rejected(reason)
```

`WaylinePlan`：安全 `.kmz` 基名 1..255 字符、1..10,000 个有序航点、有限速度 0.1..15.0 m/s、受支持的 DJI 飞行器/负载声明、有限安全起飞高度和 `SAFELY` 入场模式；航点经度 -180..180、纬度 -90..90、有限高度 0..10,000 米。安全起飞高度相对起飞点：遥控器任务为 1.2..1500 米，机场任务为 8..1500 米。当前业务只接受 `SAFELY`，不开放 `POINT_TO_POINT`。`Generated` 含文件名、完整 KMZ 字节而非路径、小写 64 字符 SHA-256 和精确字节数。

输出必须使用 DJI WPMZ SDK 生成并通过该 SDK 的本地 `checkValidation`；不得继续手写不完整 WPML。归档必须含 DJI 规定的 `wpmz/template.kml` 与 `wpmz/waylines.wpml`，后者必须包含 `missionConfig`、`flyToWaylineMode=safely`、`takeOffSecurityHeight`、全局入场速度、机型信息、负载信息、唯一连续的模板/航线标识、执行高度模式和保持原始顺序的航点。输出使用与当前 DJI WPMZ SDK 兼容的命名空间版本；版本变化只能由 SDK 升级驱动并须更新本契约。

模块创建后无状态；成功结果自包含，调用方拥有并交给 `mission-staging`。调用同步，受 10,000 航点限制，无生命周期、回调或取消；后台执行/取消由外部持有。

空白/含分隔符或控制字符/超 255/非 `.kmz` 名称，以及空/超量计划、未知机型/负载、缺失或非 `SAFELY` 入场模式、非有限或越界坐标/高度/速度/安全起飞高度均返回 `INVALID_PLAN`；WPMZ SDK 初始化、生成或校验失败返回 `GENERATION_FAILED`，不含异常、路径、部分归档或输入字节。无效输入不得调用 DJI 编码器或产生字节；暴露字节必须防御复制，并发调用互不污染。模块允许依赖 DJI WPMZ SDK，但不向调用者暴露 Android、DJI、WebSocket、文件系统或线程池类型。测试覆盖安全入场字段、全部安全起飞高度边界、机型和负载声明、SDK 校验失败、首点与安全高度的相对关系、航点顺序、拒绝无输出、字节防御复制和并发。改变入场模式、安全高度单位、SDK 版本、机型支持或输出 WPML 语义必须先更新本契约及电脑/手机集成契约。
