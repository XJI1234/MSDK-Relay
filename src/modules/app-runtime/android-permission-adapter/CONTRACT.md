# android-permission-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：app-runtime
逻辑 Gradle 路径：:app-runtime:android-permission-adapter

## 唯一职责

`android-permission-adapter` 是 `app-runtime:permission-coordinator` 所拥有 `PermissionPort` 接缝的 Android 实现。它将平台无关的 `RUNTIME` 和 `USB_ACCESS` 请求转换为 Android 运行时权限检查、Activity Result 请求、USB 附件广播和 USB 授权请求。

除下文固定映射外，它不持有任何权限策略；不协调应用启动、不启动或停止前台服务、不初始化 DJI、不建立 WebSocket、不持久化中继设置，也不生成用户界面文字。

## 对外接口

适配器向运行时组合根暴露既有 `PermissionPort`，并提供 Android 专用生命周期操作：

```text
AndroidPermissionAdapter.attach(activity, activityResultRegistry, lifecycleOwner)
  -> AndroidPermissionAdapter

adapter implements PermissionPort
adapter.snapshot() -> PermissionSnapshot
adapter.request(required, callback) -> PermissionCancellation
adapter.onUsbPresenceChanged(listener) -> PermissionCancellation
adapter.close() -> Unit
```

必须在 `lifecycleOwner` 到达 `STARTED` 前调用 `attach`。`activity` 必须是持有 `activityResultRegistry` 的同一窗口，注册表必须存活至 `close`。适配器注册一个稳定的 Activity Result launcher 和一个非导出的 USB 广播接收器；USB 接收器从 Activity `STARTED` 起保持注册，直到 `close`，不得在 `onStop` 注销。调用方持有实例，并在所属 Android 生命周期销毁前调用 `close`。`onUsbPresenceChanged` 只通知附件接入或拔出，不完成权限请求。

适配器不得通过 `PermissionPort` 暴露 `Activity`、`Intent`、`UsbAccessory`、权限字符串、`Exception` 或 Android 回调对象。

## 固定 Android 映射

`PermissionKind.RUNTIME` 映射为移动端现有应用所需权限：

| Android 版本 | 请求权限 |
| --- | --- |
| API 33 及以上 | `ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`、`READ_PHONE_STATE`、`RECORD_AUDIO`、`POST_NOTIFICATIONS` |
| API 29 至 32 | 前四项（不含 `POST_NOTIFICATIONS`）及 `READ_EXTERNAL_STORAGE` |
| API 24 至 28 | 前四项（不含 `POST_NOTIFICATIONS`）及 `READ_EXTERNAL_STORAGE` |

请求前必须移除未声明或对当前 SDK 不适用的权限，且绝不请求已授予权限。

`PermissionKind.USB_ACCESS` 映射为当前接入的 USB 附件。存在附件且 `UsbManager.hasPermission` 为真时为 `GRANTED`；存在附件但 Android 未授权时为 `DENIED`；没有附件时为 `UNKNOWN`。缺失附件绝不能伪装为已授权。

## 请求、状态与安全规则

1. `snapshot` 无副作用，并返回运行时权限及 USB 附件状态的新不可变视图。
2. 仅含已授予种类的请求不得发送至 Android；即使绕过协调器直接调用，适配器也保持此结果。
3. 最多一个请求有效。协调器拒绝第二个请求；绕过协调器的集成可得到 `IllegalStateException`。
4. 运行时请求恰好启动一次；结果必须以最新权限检查为准，故不完整、乱序或重复结果表不能授予 Android 实际未授予的权限。
5. USB 请求使用显式、限定本应用包的 `PendingIntent`。没有附件时等待下次接入广播或取消；等待中断开时状态为 `UNKNOWN`，请求仍可取消。
6. 回调为终态且最多一次。全部请求种类为 `GRANTED` 时交付成功；任一请求种类为 `DENIED`/`PERMANENTLY_DENIED` 时交付拒绝。单独的 `UNKNOWN` USB 状态既不成功也不失败。
7. 取消幂等：注销有效操作、尽可能取消 Android 交付，并保证后续 Activity Result 或 USB 回调不能到达调用方。
8. `close` 幂等，注销 Android 监听器并取消有效请求；返回后不得再交付回调。
9. 广播和 Activity Result 可在任意 Android 回调轮次到达，但所有适配器状态迁移必须由适配器锁串行化；监听器和平台清理失败不得变成 Android 崩溃。

适配器仅记录“运行时权限是否曾被请求”，用于将先前拒绝后 `shouldShowRequestPermissionRationale=false` 分类为 `PERMANENTLY_DENIED`；首次拒绝为 `DENIED`。该历史是私有适配器元数据，不是中继配置。

## 生命周期、失败与验证

- 仅在给定生命周期有效时注册 Activity Result；USB 接收器非导出，从 `STARTED` 起保持注册直到 `close`，只接收适配器生成的授权 action 及 Android 附件接入/断开 action。
- USB 授权广播必须读取 Android 的 `EXTRA_PERMISSION_GRANTED`。仅值为真时才交付 USB 请求成功；值为假或缺失时必须交付失败，绝不能把“用户拒绝授权”当作成功。
- USB `PendingIntent` 必须显式、不可变，并使用由包名派生的唯一 action；`close` 只能由组合根调用，适配器不拥有 Activity 或进程生命周期。
- 不得记录权限名、附件身份、Intent 内容或异常消息。平台检查失败映射为协调器的 `PORT_FAILURE` 拒绝或终态 `Failed`；拒绝运行时请求产生 `DENIED` 或 `PERMANENTLY_DENIED` 快照；缺失 USB 附件始终为 `UNKNOWN`；完成、取消或关闭后的重复/延迟回调必须忽略。
- 测试必须覆盖 API 24/32/33 映射、已授权跳过、部分/完整授权、普通/永久拒绝、不完整与重复结果表、USB 接入/授权/拒绝/断开/请求后接入、每个平台回调前后取消、重复及延迟回调、活动请求中关闭及重复关闭、平台异常、监听器隔离、串行状态迁移，以及可用时非导出接收器和显式 PendingIntent 的 Android 仪表验证。
