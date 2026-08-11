# wpmz-generator 模块契约

状态：已实现并已验证；版本：1.0.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:wpmz-generator

## 唯一职责与接口

本模块将完整且已决定的航点计划转换为兼容 DJI 的 WPMZ/KMZ 字节序列和安全元数据。电脑端持有路径规划、地图编辑、航点选择、相机动作策略和面向用户的命名；本模块不规划、不改航点顺序、不上传/执行、不访问 DJI 对象/Android 文件、不发送网络，也不写入 `mission-staging`。

```text
WpmzGenerator.create() -> WpmzGenerator
generator.generate(plan) -> Generated(artifact) | Rejected(reason)
```

`WaylinePlan`：安全 `.kmz` 基名 1..255 字符、1..10,000 个有序航点、有限速度 0.1..15.0 m/s；航点经度 -180..180、纬度 -90..90、有限高度 0..10,000 米。`Generated` 含文件名、完整 KMZ 字节而非路径、小写 64 字符 SHA-256 和精确字节数。归档必须且只能包含 `wpmz/template.kml` 与 `wpmz/waylines.wpml`；后者使用 DJI 命名空间 `http://www.dji.com/wpmz/1.0.6`，保持航点顺序，写入坐标、高度、文档和航点速度，并 XML 转义文本。

模块创建后无状态；成功结果自包含，调用方拥有并交给 `mission-staging`。调用同步，受 10,000 航点限制，无生命周期、回调或取消；后台执行/取消由外部持有。

空白/含分隔符或控制字符/超 255/非 `.kmz` 名称，以及空/超量计划、非有限或越界坐标/高度/速度均返回 `INVALID_PLAN`；ZIP/XML 生成失败返回 `GENERATION_FAILED`，不含异常、路径、部分归档或输入字节。无效输入不得调用编码器或产生字节；同输入生成确定（未暴露的 ZIP 元数据除外）；暴露字节必须防御复制，并发调用互不污染。模块只依赖 Kotlin/JVM 和标准 ZIP/XML 转义，不暴露 Android、DJI、WebSocket、文件系统或线程池类型。测试覆盖有效单点、顺序、速度、条目、XML 转义、全部数值边界、无效元数据/坐标/数量/非有限值、确定性、拒绝无输出、字节防御复制和并发。改变条目名、命名空间、数值限制、输入单位、XML 语义或输出元数据必须先更新本契约及电脑/手机集成契约。
