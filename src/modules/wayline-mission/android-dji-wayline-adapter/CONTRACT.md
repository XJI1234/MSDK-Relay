# android-dji-wayline-adapter 模块契约

状态：已实现
版本：1.0.0
所属一级模块：wayline-mission
逻辑 Gradle 路径：`:wayline-mission:android-dji-wayline-adapter`

## 唯一职责

本模块是 `MissionUploadPort` 与 `MissionControlPort` 的唯一 DJI MSDK v5 Android 实现。一个进程级实例负责把已暂存 KMZ 字节安全写入应用缓存、上传给飞行器、保存本次成功上传的精确文件名，并提交开始、暂停、继续和停止命令。它不解析、生成或校验航线内容，不保存公开任务状态，不决定超时、取消或并发策略，不处理网关命令，不注册 SDK，不管理权限，也不渲染界面。

## 对外接口

```text
AndroidDjiWaylineAdapter.create(context) -> AndroidDjiWaylineAdapter
adapter as MissionUploadPort
adapter as MissionControlPort
adapter.close() -> Unit
```

同一个实例必须同时注入上传器和执行器。只有上传成功终态可替换当前文件名；失败上传不得破坏此前成功文件名。开始和停止使用该精确 basename，缺少成功上传文件名时同步失败；暂停和继续不需要文件名。

输入文件名必须是单一 `.kmz` basename，禁止绝对路径、父目录、分隔符、空白、控制字符和超过 255 个码点的名称。每次上传写入 `cacheDir/dji-waylines/<唯一代次>/<原始文件名>`，上传路径的 basename 必须与后续控制使用的任务名完全一致。写入失败必须删除该代次的目录和部分文件。

DJI 上传没有取消接口，因此新上传不得删除仍可能被旧上传读取的输入文件；每个上传终态只清理自己的目录，只有最新上传代次的成功终态可以替换当前文件名。上传进度 `Double` 四舍五入并限制到 `0..100`，非有限值忽略。控制提交、关闭和代次变更必须串行化；关闭后迟到控制回调不得完成上层。每个回调至多一次；`close()` 幂等，使全部迟到回调失效并清理所有剩余目录。

模块仅依赖 `mission-uploader`、`mission-executor` 和 DJI MSDK v5.17。JVM 测试覆盖文件名防穿越、写入失败、进度、成功/失败、重复和迟到回调、当前文件名替换规则、四种控制、同步异常、关闭与清理。Android Debug 构建必须编译真实 `IWaypointMissionManager`；真机仍需验证 `init()`、KMZ 上传、文件名匹配和飞行器控制。
