# whip-stream-config 二级模块契约

状态：实验模块契约。

## 唯一职责

`whip-stream-config` 只校验实验 WHIP 地址并生成平台无关的已验证配置。它不访问网络、不调用 DJI、不保存 URL、不发送命令。

## 对外接口

```text
WhipStreamConfigValidator.validate(whipUrl: String) ->
  Valid(ValidatedWhipStreamConfig(whipUrl))
  | Invalid(WhipConfigRejection)
```

地址必须是 HTTP 或 HTTPS，包含主机和绝对 path，path 必须以 `/whip` 结尾；允许省略端口或使用 1..65535 端口，禁止用户信息、fragment、查询串、控制字符、无效端口和超长输入。IPv4、IPv6 和普通主机名均可接受。错误只能使用固定枚举，不得包含原始 URL 或解析异常。

固定拒绝枚举为：`EMPTY`、`TOO_LONG`、`MALFORMED`、`INVALID_SCHEME`、`MISSING_HOST`、`INVALID_PORT`、`MISSING_PATH`、`USER_INFO_NOT_ALLOWED`、`QUERY_NOT_ALLOWED`、`FRAGMENT_NOT_ALLOWED` 和 `CONTROL_CHARACTER`。校验器无状态、同步、线程安全，不标准化或保存 URL。

## 验收

覆盖 IPv4、IPv6、主机名、端口、路径、编码设备标识、空输入、超长、错误 scheme、凭据、查询、fragment、控制字符、畸形百分号转义、并发调用和冻结结果。
