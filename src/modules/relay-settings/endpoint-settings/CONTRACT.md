# endpoint-settings 模块契约

状态：已批准实现
版本：1.0.0
所属一级模块：relay-settings
Gradle 路径：:relay-settings:endpoint-settings

## 唯一职责

本模块校验一个电脑中继 WebSocket 目标。它不持久化设置、不解析 DNS、不连接 Socket、不记录端点数据，也不了解设备身份。

## 对外接口

`EndpointSettings.validate(value) -> Valid(ValidatedRelayEndpoint) | Invalid(reason)`。

有效端点最多 2048 个 Unicode 码点，使用 `ws` 或 `wss`，必须含主机，不得含用户信息或 fragment，端口只能省略或在 1 至 65535。路径可以为空或以 `/` 开头；查询参数为未来兼容认证而保留，但不得出现在失败数据中。有效结果原样保留输入值。

失败只能是 `EMPTY`、`TOO_LONG`、`MALFORMED`、`INVALID_SCHEME`、`MISSING_HOST`、`INVALID_PORT`、`USER_INFO_NOT_ALLOWED`、`FRAGMENT_NOT_ALLOWED` 和 `CONTROL_CHARACTER`。校验同步、纯函数、确定且线程安全。

## 测试

必须覆盖 ws/wss、DNS/IPv4/IPv6、可选路径/查询及端口边界；空值、错误 scheme、缺少主机、畸形端口/百分号转义、凭据、fragment、控制字符、长度边界和并发调用。
