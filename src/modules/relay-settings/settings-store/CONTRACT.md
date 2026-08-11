# settings-store 模块契约

状态：已批准实现
版本：1.0.0
所属一级模块：relay-settings
Gradle 路径：:relay-settings:settings-store

## 唯一职责

本模块持有持久化中继设置记录：schema 版本、原子更新、迁移、端点恢复及 `device-identity` 使用的持久化端口。它是唯一允许了解持久化记录布局的模块。

它不建立中继连接、不生成设备身份、不自行校验端点、不使用 Android 持久化 API、不观察设置变化、不记录原始设置，也不决定应用生命周期。

## 对外接口

```text
RelaySettingsStore.create(backend) -> RelaySettingsStore
store.load() -> Available(RelaySettingsSnapshot) | Unavailable(SettingsStoreFailure)
store.setEndpoint(value) -> Saved(snapshot) | Rejected(EndpointRejection) | Unavailable(SettingsStoreFailure)
store.clearEndpoint() -> Saved(snapshot) | Unavailable(SettingsStoreFailure)
store.readOrCreate(candidate) -> 已存储设备 ID
```

`RelaySettingsStore` 实现 `DeviceIdentityStorage`，生产中只有 `device-identity` 应调用 `readOrCreate`。`RelaySettingsSnapshot` 只暴露已校验端点或 `null`，绝不暴露设备身份；根门面将其与 `DeviceIdentity.identity()` 组合。

`RelaySettingsBackend` 是唯一 Android/持久化接缝。其 `update` 必须对同一存储的所有进程原子且线性化：向转换函数提供当前可空记录，持久化提交一次返回记录并返回已提交记录。后端只能以抛出表示读/写/事务未成功；存储必须映射为 `BACKEND_FAILURE` 且不暴露细节。

持久化记录为 `RelaySettingsRecord(schemaVersion, endpoint, deviceId)`。当前 schema 为版本 `1`；支持相同字段的版本 `0` 并迁移至版本 `1`。未知、负数或未来版本返回 `UNSUPPORTED_SCHEMA`，且绝不覆盖。

## 数据、恢复与失败规则

1. 缺失记录表示空设置；`load` 返回空快照且不创建记录。
2. 每次变更必须先标准化现有记录；版本 `0` 在同一原子写入中变为版本 `1`。
3. 已存端点必须经 `endpoint-settings` 校验；无效、不安全或畸形端点在标准化时清除，绝不进入快照。
4. `setEndpoint` 必须在开启后端事务前校验参数；无效输入返回其原始 `EndpointRejection`，且不改变任何内容。
5. `clearEndpoint` 持久化 `endpoint = null`，同时保留设备身份。
6. `readOrCreate` 要求协议有效候选值，原子返回既有有效设备 ID，或用候选值替换缺失/无效已存 ID；这是 `device-identity` 所需的损坏恢复。
7. 设备 ID 有效当且仅当非空白、无控制字符且为 1 至 128 个 Unicode 码点；不得出现在快照或失败中。

所有公开方法同步、对给定后端结果确定且可安全并发调用。存储不缓存读取或写失败：其他进程更新必须对下一次调用可见，后端失败后允许后续调用重试。按后端线性化顺序，最后成功提交的端点变更胜出。

| 条件 | 结果 | 持久化状态 |
| --- | --- | --- |
| 提交端点无效 | `Rejected(reason)` | 不变；不调用后端 |
| 后端异常 | `Unavailable(BACKEND_FAILURE)` | 无本地状态/缓存 |
| 不支持 schema | `Unavailable(UNSUPPORTED_SCHEMA)` | 不变；不破坏性恢复 |
| 缺失记录 | 空成功快照 | `load` 不创建记录 |
| 已存端点无效 | 成功标准化时清除 | 恢复后的版本 1 记录 |
| `readOrCreate` 中设备 ID 缺失/无效 | 原子存储候选值 | 恢复后的版本 1 记录 |

结果、校验异常或诊断不得包含端点、查询、设备 ID、原始记录、后端异常文字或堆栈。

## 测试与兼容性

JVM 测试必须覆盖缺失数据、写入/清除/重新加载、有效端点保留、全部无需后端访问的校验拒绝、schema-0 迁移、无效端点恢复、未知 schema 保护、后端读写错误和重试、含损坏记录的身份 read-or-create、身份恢复期间端点保留、并发端点写入及并发身份竞争。

`RelaySettingsSnapshot`、结果/失败枚举、后端原子性、schema 版本含义和不透明记录接缝均为稳定接口。任何 schema 变更必须先增加迁移和测试；移除支持的迁移、通过快照暴露 `deviceId`，或允许原始端点离开本模块前，必须先更新契约和消费者。
