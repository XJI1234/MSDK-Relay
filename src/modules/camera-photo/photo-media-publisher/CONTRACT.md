# photo-media-publisher 二级模块契约

## 1. 模块身份

- 模块名称：`photo-media-publisher`
- 所属一级模块：`camera-photo`
- 当前版本：0.1.0
- 状态：已实现
- Gradle 路径：`:camera-photo:photo-media-publisher`
- 唯一职责：把一份已在手机本地、已计算摘要的原图，按航线 KMZ 的反方向分块发给当前电脑会话，并只在匹配的 `media-result` 上结束。

## 2. 负责与不负责

### 负责

- 依次发送 `media-begin`、不超过 256 KiB 的 `media-chunk`、`media-complete`；
- 每个会话代次最多一个活动发送；
- 用注入时钟在 120_000 ms 内等待同一传输 ID 的 `media-result`；
- 会话失效、超时或电脑 `ok=false` 时中止，并删除调用方交给它的可读句柄。

### 明确不负责

- 不调用 DJI，不从飞行器拉文件；这属于适配器与 executor；
- 不解析命令；这属于 `photo-command-handler`；
- 不把照片写到电脑磁盘；这属于桌面 `photo-inbox`；
- 不创建 WebSocket；只使用注入的 gateway 发布与结果入口。

## 3. 对外接口

```text
publish(localFile, completion)
  -> Accepted | Rejected(BUSY | INVALID_FILE)

acceptResult(mediaResultFrame) -> void
abort() -> void
```

`localFile` 只含安全 `fileName`、`size`、小写 `sha256` 和抽象 `readable`。发送端为每次已接受发布生成新的传输 ID，发送前必须再读全部字节并核对大小与摘要；不符则拒绝且不发帧。`Delivered` 只表示同一传输 ID 的电脑 `media-result.ok=true`，不表示操作员已打开照片。

## 4. 状态和生命周期

```text
IDLE -> SENDING -> AWAITING_RESULT -> SUCCEEDED
SENDING | AWAITING_RESULT -> FAILED
任意非终态 -> ABORTED
```

`abort` 在会话离开 `ACTIVE` 时由组合根调用。迟到的 `media-result` 不得完成已中止的发送。进程内不重发；操作者需再次 `fetch`。

## 5. 数据所有权

发送开始时读取并校验完整字节，读取后立即关闭可读句柄。终态后由适配器删除临时文件。模块不保存跨请求的照片字节。

## 6. 依赖和替身

```text
本模块 -> protocol-core 的媒体帧
本模块 -> gateway 的媒体发布与 media-result 注册入口
生产环境 -> RelayGateway
测试环境 -> 记录型 publisher 与手动时钟
```

## 7. 错误、超时和并发

| 情况 | 对外结果 | 状态变化 | 是否可重试 |
| --- | --- | --- | --- |
| 已有活动发送 | `BUSY` | 不开始 | 当前结束后 |
| 编码或写入拒绝 | `TRANSFER_FAILED` | 中止 | 新的 fetch |
| 120 s 无结果 | `TRANSFER_FAILED` | 中止 | 新的 fetch |
| `media-result.ok=false` | `TRANSFER_FAILED` | 中止 | 新的 fetch |
| 会话失效 | `TRANSFER_FAILED` | abort | 重连后再 fetch |

分块顺序与 `mission-sender` 相同：写完一帧返回后才发下一帧；中止后不得再发后续 chunk。

## 8. 测试要求

覆盖完整 begin/chunk/complete、256 KiB 边界、摘要不符不发帧、BUSY、超时、会话 abort、电脑拒绝、迟到结果隔离，以及无 Android/DJI/路径泄漏。

## 9. 变更规则

改变分块上限、同时允许多个媒体发送、或把确认改成无需 `media-result`，必须先改双方 `protocol-core` 与根契约。
