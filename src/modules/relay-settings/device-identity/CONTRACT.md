# device-identity 模块契约

状态：已批准实现
版本：1.0.0
所属一级模块：relay-settings
Gradle 路径：:relay-settings:device-identity

## 唯一职责

本模块创建并返回此移动端安装的稳定身份。它依照中继协议标识规则校验恢复值和新生成值，只缓存成功解析的值，并协调同一进程的并发调用。

它不持久化端点设置、不选择电脑、不建立连接、不认证用户、不记录身份、不访问 Android API、不修复损坏持久化存储，也不暴露存储异常。

## 对外接口

```text
DeviceIdentity.create(storage, generator?) -> DeviceIdentity
identity.identity() -> Available(DeviceId) | Unavailable(STORAGE_FAILURE | STORED_VALUE_INVALID | GENERATED_VALUE_INVALID)
DeviceIdentityStorage.readOrCreate(candidate) -> 已存储值
DeviceIdentityGenerator.generate() -> 候选值
```

`DeviceIdentityStorage.readOrCreate` 是唯一持久化接缝，其实现属于 `settings-store`。它必须对使用同一安装存储的所有读写原子且线性化：已有值时返回该值；为空时恰好存储并返回给定候选值。缺失/损坏记录是 `settings-store` 的恢复职责；I/O 或事务失败可抛出，但本模块必须映射为不暴露异常的 `STORAGE_FAILURE`。

`DeviceIdentityGenerator` 仅用于令测试生成可确定；默认使用随机 UUID 字符串，生产调用方不得提供可预测生成器。`DeviceId.value` 非空白、无 ISO 控制字符且为 1 至 128 个 Unicode 码点，与 `protocol-core` 当前 `deviceId` 约束完全一致。该值不透明：可以作为 `SessionConfig.deviceId` 传给 `relay-gateway`，不得推断设备元数据或将其作为凭据。

## 解析、失败与并发规则

1. 首次成功调用通过生成器创建一个候选值，并要求存储原子解析该值。
2. 返回值必须先校验才可见或缓存；其他进程存储的有效胜出值原样返回。
3. 一旦缓存有效值，后续调用必须返回同一 `DeviceId`，且不再调用生成器或存储。
4. 并发调用在进程内只进行一次解析；所有成功调用者获得相同值。失败不缓存，后续调用可重试。
5. 无效生成候选不得交给存储；存储返回无效值时本模块不得替换它。

| 情形 | 结果 | 存储/生成器调用 | 缓存 |
| --- | --- | --- | --- |
| 有效已有或新存储值 | `Available` | 最多一次解析 | 存储 |
| 存储抛出 | `Unavailable(STORAGE_FAILURE)` | 不隐式重试 | 不变 |
| 生成器抛出 | `Unavailable(STORAGE_FAILURE)` | 不调用存储 | 不变 |
| 生成候选无效 | `Unavailable(GENERATED_VALUE_INVALID)` | 不调用存储 | 不变 |
| 存储返回无效值 | `Unavailable(STORED_VALUE_INVALID)` | 不覆盖 | 不变 |

全部公开调用同步、线程安全；没有回调、线程、执行器、超时或取消。调用生成器或存储时不得持有内部锁，因此同步重入 `identity()` 不得死锁；原调用完成前的重入是独立竞争者，仍依赖同一存储原子性，只有有效后才缓存。失败结果不得包含完整设备 ID、候选值、存储返回值、异常消息或堆栈。

## 测试与兼容性

JVM 测试必须覆盖默认有效性、恢复值、一次创建和缓存、存储竞争胜出、生成器/存储失败、无效生成和存储值、所有边界约束、每种失败后的重试、并发调用及生成器/存储重入；并确认无效候选不会到达存储且失败不缓存。

`DeviceId` 与失败枚举是稳定公开协议边界。新增失败原因、改变标识约束、原子存储语义或暴露存储实现细节前，必须先更新本契约、父契约、消费者和测试。
