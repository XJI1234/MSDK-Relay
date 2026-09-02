# android-dji-sdk-adapter 模块契约

状态：已实现并已验证
版本：1.0.0
所属一级模块：device-connection
逻辑 Gradle 路径：:device-connection:android-dji-sdk-adapter

## 唯一职责

本模块是由 `sdk-lifecycle` 持有的 `DjiSdkPort` 接缝的 Android DJI MSDK v5 实现。它初始化 DJI MSDK，在 MSDK 初始化完成后请求应用注册，并只报告该注册是否可用或失败。

它不观察遥控器、飞行器、飞控、相机或电池状态，不发起配对，不执行 DJI 操作，不发布遥测数据，不控制图传或航线任务，不建立中继连接，不持久化设置，不拥有 Activity，也不渲染用户界面。

## 对外接口

```text
AndroidDjiSdkPort.create(applicationContext) -> DjiSdkPort

port.initialize(callbacks) -> PortStartResult
port.close() -> Unit
```

该模块还提供唯一的 Android 进程初始化入口：

```text
DjiSdkApplication.attachBaseContext(baseContext) -> 安装 DJI 运行时
```

宿主应用的 Manifest 必须将 `application` 声明为 `com.skycommand.relay.device.sdk.android.DjiSdkApplication`。该 Application 只调用当前 MSDK 发行版要求的 `com.cySdkyc.clx.Helper.install(this)`，不注册 DJI 应用、不持有 Activity、不注册设备监听器，也不包含任何中继业务状态。

`create` 必须接收应用级 Context。它返回既有的平台无关 `DjiSdkPort`；不得向调用方暴露 Android Context、`SDKManager`、DJI 错误、产品标识、异常或 MSDK 回调。

本适配器仅有一个直接协作者，即内部 MSDK 管理器桥接层。该桥接层不是对外接口；DJI 变更 API 时可以替换它，而无需修改 `sdk-lifecycle` 或其他业务模块。

## 宿主应用前置条件

最终 Android 应用在调用 `create` 或 `initialize` 前必须：

1. 在合并后的应用 Manifest 中声明 `com.dji.sdk.API_KEY`，并将应用专用 DJI App Key 作为其值。
2. 在访问任何 DJI MSDK API 前，于 `Application.attachBaseContext` 调用 DJI 要求的运行时安装器。
3. 应用模块必须 `packaging.jniLibs.useLegacyPackaging = true`，且合并后的 Manifest 必须 `android:extractNativeLibs="true"`，让 DJI 原生库解压到 `nativeLibraryDir`；否则 `Helper.install` 无法绑定 JNI。
4. 打包所选 DJI MSDK v5 发行版要求的原生库和 ABI 配置：仅 `arm64-v8a`，保留 MSDK `doNotStrip` 列表中的 `.so`，并对 `.so` / `zip` 禁用压缩。
5. 通过独立的 app-runtime 权限流程请求网络和设备权限。本适配器既不请求也不解释权限。

本适配器不得存储、记录或创建默认 App Key。宿主配置缺失、无效或不可用时必须成为安全的初始化失败，绝不能伪造就绪状态。

## 生命周期规则

1. 每次 `initialize` 创建一个回调代际，并且只向 MSDK 管理器桥接层委托一次。
2. 桥接层每次开始本地代际前必须查询 `SDKManager.isRegistered()`。若它为 `true`，说明同一 Android 进程中的 DJI SDK 已注册；适配器必须直接调用 `DjiSdkCallbacks.onReady`，不得再次调用 `init` 或 `registerApp`，也不得等待新的 `onInitProcess` 回调。
3. 只有 `isRegistered()` 为 `false` 的首次进程初始化路径才调用 `init`；MSDK 初始化完成会触发 `registerApp`，只有 MSDK 注册成功才能调用 `DjiSdkCallbacks.onReady`。
4. 任一 MSDK 初始化或注册失败，对有效代际最多调用一次 `DjiSdkCallbacks.onFailure`。
5. 桥接层拒绝或同步异常必须成为带稳定安全原因的 `PortStartResult.Rejected`。原始 DJI 错误、异常消息和堆栈不得越过 `DjiSdkPort`。
6. 重复 MSDK 回调、旧代际回调和 `close` 后回调必须忽略。
7. `close` 是幂等操作。它只使本地回调代际失效并释放适配器本地监听器引用；它不得尝试全局关闭 DJI MSDK，因为 MSDK 是进程级资源，可能被后续应用工作共享。
8. `close` 后的下一次 `initialize` 使用新代际，必须先重新查询进程级注册事实，且不得接受旧代际捕获的注册结果。
9. 用户回调在适配器锁外运行。用户回调异常必须被隔离，不能阻止清理或影响其他代际。

## 安全与依赖规则

- 直接 DJI MSDK 依赖只能存在于此 Android 模块。
- 本模块只使用应用级 Context，且不得保留 Activity、Fragment、View、Intent、产品 ID、序列号、App Key 或设备型号。
- 本模块只报告 `ready`、`failure` 和安全拒绝原因。
- 网络端点、桌面协议、遥测载荷、任务内容、视频帧或配对身份不得进入本模块。
- `sdk-lifecycle`、`device-connection`、遥测、图传、航线、relay-gateway 和 app-runtime 不得依赖 DJI 类。

## 验证要求

JVM 测试必须覆盖初始接受、桥接层拒绝和抛出、同步和异步成功/失败、重复回调、`close` 后的过期回调、重复 `close`、回调隔离以及使用新代际重新初始化。

Android Debug 构建必须编译 MSDK v5 桥接层和合并后的 Manifest。真实设备集成验证必须覆盖有效 App Key 注册、缺失/无效 App Key、网络不可用、MSDK 延迟注册、应用重建以及物理遥控器/飞行器连接。产品状态验证属于后续设备观察适配器，不属于本模块。
