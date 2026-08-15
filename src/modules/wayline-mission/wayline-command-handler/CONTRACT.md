# wayline-command-handler 模块契约

状态：已实现并已验证；版本：1.0.0；所属一级模块：wayline-mission；Gradle 路径：:wayline-mission:wayline-command-handler

## 唯一职责与接口

本模块校验和解释五个航线命令并委托给已构建的航线能力；它不持有任务事实，也不执行 DJI 或网络操作。只支持 `wayline.upload`、`wayline.start`、`wayline.pause`、`wayline.resume`、`wayline.stop`。

已移除的 `wayline.generate` 和其他未知命令必须拒绝。

`handler.handle(command) -> Succeeded(detail) | Accepted(detail) | Rejected(reason)`。

上传和控制命令要求 `confirm=true` 并委托 `WaylineCommandActions`；`Accepted` 只表示提交，不表示 DJI 已完成。中继组合中 `handle(command, completion)` 把上传/控制终态交给 `WaylineActionCompletion`，处理器不得把接受操作伪造成成功。

处理器不得保留命令字段、字节、路径、异常或 DJI 对象。缺字段、类型错误、额外字段、`confirm=false` 和委托拒绝必须成为稳定枚举原因，不暴露原始细节。它无状态、线程安全；任务串行化属于 uploader、executor 和共享协调器。测试覆盖每个命令、确认与输入结构失败、委托、委托拒绝、已移除命令、其他未知命令和并发独立调用。
