# stream-config-validator 模块契约

状态：已实施并已验证；版本：1.0.0；所属一级模块：live-stream；Gradle 路径：:live-stream:stream-config-validator

## 唯一职责与接口

本模块只校验一个 RTMP 目标配置；不启动/停止图传、不访问 DJI 或 Android、不解析 DNS、不建立 Socket、不持久化 URL，也不发布遥测。

```text
StreamConfigValidator.validate(rtmpUrl) -> Valid(ValidatedStreamConfig) | Invalid(reason)
```

`rtmpUrl` 必须非空白、最多 2048 个 Unicode 码点，scheme 必须为 `rtmp`，必须有主机、不得有用户信息，端口可省略或为 1..65535，且必须具有以 `/` 开头的非空白路径。查询参数为 DJI 认证兼容性保留，fragment 必须拒绝。回环地址（`127.0.0.1`、`localhost`、`::1`）必须拒绝。校验器不得标准化或记录秘密。`ValidatedStreamConfig` 不可变且只含原 URL，是未来 DJI 图传适配器唯一可接受的配置。

失败只能为 `EMPTY`、`TOO_LONG`、`MALFORMED`、`INVALID_SCHEME`、`MISSING_HOST`、`INVALID_PORT`、`MISSING_PATH`、`USER_INFO_NOT_ALLOWED`、`FRAGMENT_NOT_ALLOWED`、`CONTROL_CHARACTER` 或 `LOOPBACK`；失败不得含原 URL 或解析异常。校验同步、无状态、线程安全、确定且无副作用。

测试必须覆盖主机名、IPv4、IPv6、端口、路径、查询、空输入、空白、长度边界、错误 scheme、缺主机/路径、无效端口、凭据、fragment、回环地址、畸形百分号转义、控制字符和并发调用。
