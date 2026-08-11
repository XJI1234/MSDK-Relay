# android-settings-adapter 模块契约

状态：已批准实现
版本：1.0.0
所属一级模块：relay-settings
Gradle 路径：:relay-settings:android-settings-adapter

## 唯一职责

本模块使用一个私有 `SharedPreferences` 文件实现 `RelaySettingsBackend`，是唯一允许将不透明 relay-settings 记录转换为 Android 键值存储的适配器。

它不校验端点、不生成设备 ID、不决定设置恢复、不向调用方暴露 preferences、不连接电脑、不请求权限，也不访问 DJI。

## 对外接口与存储所有权

```text
AndroidRelaySettingsBackend.create(context) -> RelaySettingsBackend
backend.update(change) -> 已提交的 RelaySettingsRecord?
```

适配器只在应用私有存储中保存 schema 版本、端点和设备 ID。`RelaySettingsStore` 始终拥有 schema 迁移与恢复规则；适配器必须原样保留可空原始值，且不检查其内容。

应用必须只在一个 Android 进程内运行中继设置。`SharedPreferences` 不提供跨进程事务，因此禁止为应用、服务或会写入中继设置的接收器声明第二进程。同一应用进程中，`update` 必须串行化，转换一条完整不可变记录，并使用同步 `commit()`，使成功返回表示替换已持久化。

错误类型的 preference、存储不可用、提交失败或转换异常必须以通用适配器异常表示，且不得包含原始值或 Android 异常文字；`RelaySettingsStore` 负责映射为稳定失败结果。

## 测试

纯 JVM 测试必须覆盖记录编解码、null 处理、不透明值保留、原子更新串行化、提交失败映射、畸形存储映射和无原始值泄漏。发布前 Android 仪表测试必须覆盖真实 `SharedPreferences` 持久性和进程配置。
