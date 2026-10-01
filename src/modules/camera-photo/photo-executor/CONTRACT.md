# photo-executor 二级模块契约

## 1. 模块身份

- 模块名称：`photo-executor`
- 所属一级模块：`camera-photo`
- 当前版本：0.1.0
- 状态：已实现
- Gradle 路径：`:camera-photo:photo-executor`
- 唯一职责：把单个拍照或下载请求提交到拍照域 DJI 操作协调器，统一超时、取消、异常和一次性终态。批量回传由上层逐次调用下载；本模块一次只允许一个 DJI 下载。

## 2. 负责与不负责

### 负责

- 经注入的 `photoOperations()` 提交一次 `Capture` 或 `Download` 动作；
- `Capture` 超时 15_000 ms，`Download` 超时 60_000 ms；范围外的自定义超时在提交前拒绝；
- 把协调器的 `SUCCEEDED` / `FAILED` / `TIMED_OUT` / `CANCELLED` 映射为一级模块规定的终态；
- 超时或取消后仅中止本次端口会话；中止请求不是硬件已稳定的证据，不调用 `confirmHardwareSettled()`；新请求可替换已超时隔离的槽位，由 DJI 实际调用和回调裁决；
- 成功时附带适配器给出的文件身份（拍照）或本地可读句柄（下载）。

### 明确不负责

- 不解析 JSON；这属于 `photo-command-handler`；
- 不依赖 Android 或 DJI SDK 类型；这属于 `android-dji-photo-adapter`；
- 不向电脑发分块；这属于 `photo-media-publisher`；
- 不跨域占用飞行、航线、设置、图传或配对槽位。

## 3. 对外接口

```text
execute(request, completion) -> 提交接受 | 提交拒绝
```

端口成功：

```text
CaptureSucceeded(fileName, index)
DownloadSucceeded(fileName, size, sha256, readable)
```

`readable` 是可重复打开的抽象流句柄，不是 `File`、`Path`、`Uri` 或绝对路径字符串。调用方必须按固定小缓冲区读取，不得要求适配器返回整张原图 `ByteArray`。失败可携带受限 `PhotoDjiFailure(errorCode, errorDescription)`，且只能来自 Android 适配器对真实 `onFailure` 的归一化。

## 4. 状态和生命周期

执行器不保存“最近一张照片”。超时或取消后本次结果仍未确认，协调器可接受新的拍照或下载尝试；旧请求的迟到回调不能推进新请求。关闭时取消尚未开始的提交。

## 5. 数据所有权

不拥有文件字节。下载成功后流句柄所有权交给调用方（门面再交给 `photo-media-publisher`）；调用方关闭句柄后，适配器必须释放临时文件。不得缓存上一张照片的句柄或整张照片字节。

## 6. 依赖和替身

```text
本模块 -> dji-operation-coordinator 的 photo 域
本模块 -> PhotoHardwarePort
生产环境 -> android-dji-photo-adapter
测试环境 -> 记录型端口与手动调度器
```

## 7. 错误、超时和并发

| 情况 | 对外结果 | 状态变化 | 是否可重试 |
| --- | --- | --- | --- |
| 协调器拒绝入队 | 本地失败 | 不开始 DJI | 槽位空闲后可重试 |
| DJI `onFailure` | `ACTION_REJECTED` | 槽位释放 | 可由操作者重试 |
| 同步抛出 | `INVOCATION_FAILED` | 本次结果未确认 | 可重新尝试，由 DJI 裁决 |
| 超时/取消 | `RESULT_UNCONFIRMED` | 本次结果未确认 | 可重新尝试，由 DJI 裁决 |

协调器不会同时启动两个仍在等待回执的本域请求；超时请求可能仍在 DJI 内部执行，重试不把这种未知状态伪装为已稳定。分块发送不得占用该槽位。

## 8. 测试要求

覆盖拍照成功、下载成功、DJI 拒绝、同步异常、超时隔离、取消、重复回调、迟到回调，以及无 Android/DJI 类型泄漏。

## 9. 变更规则

改变超时、成功载荷或把分块发送并入本模块，必须先改一级契约。
