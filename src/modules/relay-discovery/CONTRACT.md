# 手机公告解析与候选协调核心

2026-10-16 `Ipv4Subnet.containsHost(ipv4)` 校验来源是否为本子网内有效单播主机，拒绝非法文本、网络地址和广播地址；供 Android 请求提示地址筛选使用，不产生或激活候选。

版本：1.0；Gradle 路径 `:relay-discovery`。2026-10-13。纯 Kotlin/JVM，无 Android、DJI、socket 或 gateway 依赖。双端网络格式见配套 Sky-Command/docs/auto-discovery/WIRE-PROTOCOL.md。

## 公告边界

2026-10-14 新增独立 [android-discovery-adapter](android-discovery-adapter/CONTRACT.md) 子模块，持有 WLAN socket、网络观察与计时来源，仅通过 sink 交付网络代次、包和 tick；本核心保持纯 JVM、无平台依赖，正式 App 容器尚未接线。

`AnnouncementParser.parse(bytes, sourceIpv4, receiverSubnet?)` 返回 `Accepted(announcement)` 或封闭 `Rejected(reason)`，不抛出第三方解析异常、不返回原始 JSON。仅接受 1–512 字节严格 UTF-8、单一 JSON 对象；拒绝重复字段、尾随 JSON、超过 16 层嵌套及错误字段类型，不把字符串/小数转换为整数。未知扩展字段允许忽略。

公告字段按 v1 校验；endpoint 仅由来源 IPv4、relayPort 与固定 /relay 生成。来源必须是规范点分十进制单播 IPv4，排除 0/8、127/8、组播、保留高地址和有限广播。若调用方提供当前接收网络的 Ipv4Subnet，还拒绝该子网网络地址及定向广播地址；仅来源 IP 本身不能识别任意掩码的定向广播，不能以末字节 255 代替子网判断。公告不持久化，身份不作为认证，原始内容不记录到日志。

## 协调器接口与所有权

`DiscoveryCoordinator(clock)` 使用外部单调毫秒时钟，所有入口同步串行、无回调/线程/定时器：

- `networkGeneration()`：取得当前监听的不可伪造网络令牌。
- `receive(generation, bytes, sourceIpv4, receiverSubnet?)`：旧网络令牌的包静默忽略；仅 Accepted 刷新缓存，返回当前不可变快照。
- `snapshot()`：计算过期/观察窗口并返回快照，未来运行容器至少每秒调用一次。时钟倒退不使内部时间倒退。
- `advanceNetwork()`：返回新网络令牌，清空缓存、观察与未 ACTIVE 的选择；已经 ACTIVE 的电脑保持，直至 transport 真正断线。
- `activate(selectionToken)`：仅当前有效 SELECTED 令牌可进入 ACTIVE；先处理过期/冲突，旧令牌/网络/endpoint 的迟到 paired 返回 false。
- `disconnected(selectionToken)`：仅结束匹配的 ACTIVE；旧会话的断线不清理新选择。结束后重新观察存活候选 3 秒。

快照只含阶段 WAITING / OBSERVING / CONFLICT / SELECTED / ACTIVE、候选数量、可选目标（serverId/instanceId、来源 endpoint、选择令牌）及是否容量隔离。令牌只比较对象身份，不放入 UDP/WS/持久化或日志。无目标意味着调用方须暂停旧连接；相同目标令牌表示重复公告不能再次 start 或重置退避。ACTIVE 目标和令牌不因候选变化或过期改变。协调器不调用 start/stop、更改 SDK 或重放命令。

## 有限缓存与确定性

- 按 `(serverId, instanceId)` 去重；每个来源 IP/端口 endpoint 记录本地接收时间。8 秒无有效公告过期（等于 8000ms 即过期）。同实例多个 endpoint 取最新，时间相同按 IPv4 字典序、再按端口排序。
- 唯一候选持续观察 3 秒（等于 3000ms 可选）；候选身份变化或冲突消退重新计时。两个及以上候选 CONFLICT，不选第一台。未 ACTIVE 选择遇到冲突/过期立刻失效。
- 上限 32 个候选、每候选 8 个 endpoint。达到上限后再收到新候选/新 endpoint 触发容量隔离：清空选择，已有缓存停止刷新直到隔离开始 8 秒后全部过期；期间有效包均丢弃，不排队。到期清空后从新的有效包重新观察。ACTIVE 会话仍保持；断线时若隔离未结束则冲突等待。
- 每个公开方法以其调用时的单调时间作为事件顺序；activate 与新公告的竞态按线性顺序解释。选择令牌在恢复/替换时重新生成，不复用；异常时间回退不延长已观察时间或刷新过期包。

## 验证范围

测试共享 JSON 示例、512 字节边界、UTF-8/JSON/类型/来源校验、双广播去重、多地址选择、3/8 秒边界、冲突消退/电脑重启、容量隔离与恢复、ACTIVE 保持、过期/冲突/网络变化后的迟到激活和旧断线，以及有界缓存。测试使用纯 JVM 和手动时钟，无真实 UDP 或飞行行为。

2026-10-14 gateway/session 选择 API 和独立 [discovery-session-bridge](../app-runtime/discovery-session-bridge/CONTRACT.md) 已实现；桥接持有本核心并映射选择令牌、握手门禁与结束代次，核心自身仍无 gateway 依赖。Android 监听和服务归属另行扩展契约后接线；核心/桥接测试不证明正式 Relay 已自动连接、后台可用或 15 秒实机目标达标。
