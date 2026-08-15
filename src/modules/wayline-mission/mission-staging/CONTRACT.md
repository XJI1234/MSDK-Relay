# mission-staging 二级模块契约

状态：已实施并已验证
版本：1.0.0
所属一级模块：`wayline-mission`
Gradle 路径：`:wayline-mission:mission-staging`

## 1. 唯一职责

本模块只接收已经由 gateway 完成分块和摘要校验的完整 KMZ 字节，安全写入临时存储，并在成功后原子替换当前待上传文件。它不解析 WPMZ、不调用 DJI、不上传、不开始任务。

## 2. 对外接口

```text
MissionStaging.begin(metadata) -> Accepted | Rejected(reason)
MissionStaging.write(bytes) -> Accepted | Rejected(reason)
MissionStaging.complete() -> Staged(metadata) | Rejected(reason)
MissionStaging.cancel() -> Cancelled | AlreadyFinished
MissionStaging.current() -> StagedMetadata?
```

`StagingStorage` 是唯一文件系统 seam：负责临时写入、flush、原子替换和删除；模块不向调用方暴露路径。

## 3. 规则

- metadata 必须包含安全文件名、预期大小和 SHA-256；文件名只能是 1..128 个 Unicode 码点的 `.kmz` 基名，不能包含路径分隔符或控制字符。128 是共同中继协议的上限，暂存层不得接受后续无法通过协议回传状态的任务。
- 单次传输只允许一个活动 writer；新的 `begin` 不得覆盖活动传输。
- `write` 不能超出预期大小；写入顺序就是调用顺序。
- `complete` 必须同时满足字节数和 SHA-256；失败不得替换当前文件。
- 完成后先 flush 临时内容，再原子替换；替换失败保留旧的当前文件，清理临时文件。
- cancel、写入失败、摘要不匹配和模块异常都清理临时写入，不影响旧的当前文件。
- 结果只返回安全元数据，不返回文件路径、句柄、原始异常或字节。

## 4. 测试要求

覆盖正常暂存、空文件、超大文件、非法文件名、重复 begin、超出大小、摘要不匹配、存储异常、取消、原子替换失败、旧文件保留和完成后的重复调用。
