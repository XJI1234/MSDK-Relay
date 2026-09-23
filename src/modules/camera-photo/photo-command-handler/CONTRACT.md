# photo-command-handler 二级模块契约

## 1. 模块身份

- 模块名称：`photo-command-handler`
- 所属一级模块：`camera-photo`
- 当前版本：0.1.0
- 状态：已实现
- Gradle 路径：`:camera-photo:photo-command-handler`
- 唯一职责：把 `camera.photo.capture` 与 `camera.photo.fetch` 转成不可变请求，并在调用动作端口之前拒绝一切非法字段。

## 2. 负责与不负责

### 负责

- 识别且仅识别这两个命令名；
- 要求 `fields` 为空对象：不得有键、不得有 `confirm`、不得有文件名；
- 把合法命令映射为 `PhotoRequest.Capture` 或 `PhotoRequest.Fetch`；
- 把下层终态最多转发一次为命令完成。

### 明确不负责

- 不保存最近一次拍照身份；这属于一级门面；
- 不创建线程，不触碰 DJI 类型；这属于 `photo-executor` 与 `android-dji-photo-adapter`；
- 不发送 `media-*` 帧；这属于 `photo-media-publisher`；
- 不注册到 WebSocket；这属于 `relay-gateway` 与 `app` 组合根。

## 3. 对外接口

```text
handle(command, completion) -> 至多一次 succeed | reject
```

- 未知命令名、非空字段、错误类型：立即 `reject`，不调用端口；
- 合法 `capture` / `fetch`：恰好调用一次注入的执行入口；
- 下层第一次终态获胜；重复完成丢弃。

失败 `detail` 使用固定短句；可选 `result` 只能是本一级模块规定的受限对象。不得把同步异常伪装为 `ACTION_REJECTED`。

## 4. 状态和生命周期

本模块无运行状态。无 start/stop。处理器实例可在命令之间复用。

## 5. 数据所有权

不拥有快照、文件或缓存。只读取当前 `CommandFrame` 的不可变字段。

## 6. 依赖和替身

```text
本模块 -> protocol-core 的 CommandFrame 与完成入口
生产环境 -> CameraPhoto 门面提供的执行入口
测试环境 -> 记录型执行入口
```

## 7. 错误、超时和并发

| 情况 | 对外结果 | 状态变化 | 是否可重试 |
| --- | --- | --- | --- |
| 未知命令或非空字段 | 本地拒绝 | 不开始 | 改正字段后可重试 |
| 下层失败 | 原样转发一次 | 无本地状态 | 依下层 |
| 重复完成 | 丢弃 | 无 | 否 |

本模块不串行化业务；门面保证同时最多一个已接受命令。

## 8. 测试要求

覆盖两个合法命令、任意额外字段、错误类型、未知命令名、下层成功/拒绝/异常、重复完成，以及不依赖 Android/DJI/网络。

## 9. 变更规则

增删命令名、允许新字段或改变成功 `result` 形状，必须先改根契约、本一级模块契约和电脑端契约。
