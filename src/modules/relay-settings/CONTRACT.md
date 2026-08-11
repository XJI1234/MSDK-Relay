# relay-settings 模块契约

状态：门面实现进行中；版本：1.0.0；Gradle 路径：:relay-settings

`relay-settings` 持有持久化本地中继配置：已校验电脑端点与稳定移动端设备身份。它不创建网络会话、不将身份视为认证，也不持有 DJI/Android 业务事实。二级模块为 `endpoint-settings`（端点校验）、`device-identity`（稳定生成身份）和 `settings-store`（持久化读写、迁移、恢复）；gateway 只能消费本模块产生的已校验值。

## 门面接口

`RelaySettings.create(backend, generator?)` 是唯一生产构造点：它以 `settings-store` 作为持久化 `DeviceIdentityStorage`，Android 提供原子 `RelaySettingsBackend`，默认生成器留在 `device-identity`。

```text
settings.loadEndpoint() -> SettingsLoadResult
settings.saveEndpoint(value) -> EndpointSaveResult
settings.clearEndpoint() -> EndpointSaveResult
settings.deviceIdentity() -> DeviceIdentityResult
settings.connectionSettings() -> Available(RelayConnectionSettings) | StoreUnavailable(SettingsStoreFailure) | IdentityUnavailable(DeviceIdentityFailure)
```

`RelayConnectionSettings` 只含已校验的可选端点与有效稳定 `DeviceId`。`connectionSettings` 必须先读取设置再解析身份，故存储失败不调用身份生成/存储；它只在方法边界提供一致组合，不锁定后续设置更改，保留者拥有自己的快照。端点变更只报告持久化端点结果且不生成身份，因此成功保存端点不得被无关身份失败遮蔽。全部下层契约、线程安全、恢复及失败隐私规则经门面继续有效。测试覆盖构造、端点委托、有/无端点的组合设置、存储失败短路身份解析、身份失败映射及端点变更时并发组合。
