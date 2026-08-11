# android-mission-staging-adapter 模块契约

状态：实现中  
版本：1.0.0  
所属一级模块：wayline-mission

## 唯一职责

本模块实现 `StagingStorage` 和 `StagedMissionContentReader`，只负责在应用私有 `filesDir/relay-missions` 中写入临时 KMZ、刷盘、原子替换当前文件、按当前元数据读取和清理。它不校验摘要和大小、不上传或执行航线、不解释命令。

`beginTemporary` 必须关闭并清理旧临时文件；`append` 仅在活动写入期间有效；`flush` 必须刷新并同步文件描述符；`replaceCurrent` 必须先关闭临时流，再以原子移动替换当前文件，平台不支持原子移动时可退化为同目录替换。读取只接受最后成功替换的同一元数据，返回字节副本。所有路径均由模块生成，外部文件名不得参与目录解析。

JVM 测试覆盖写入、追加、刷盘、替换、读取、重复任务、取消、无活动操作和元数据不匹配。Android Debug 构建必须通过。
