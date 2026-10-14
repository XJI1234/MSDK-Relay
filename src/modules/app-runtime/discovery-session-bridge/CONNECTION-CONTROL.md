# 自动与手动连接的唯一控制器

2026-10-14，扩展版本 1.1.0；位于纯 JVM `:app-runtime:discovery-session-bridge`。此组合能力先独立测试，正式 App 接线另行记录。

`RelayConnectionControl(sourceFactory, clock, onChanged)` 默认 AUTO。创建 gateway 时注入稳定的 `pairingAdmission`，随后 bind 唯一 DiscoverySessionPort。start/stop/automatic/manual(endpoint)/close 为 gateway 唯一控制入口；stop 可再次 start，close 永久关闭。bind 只能一次；start 在权限/前台就绪后调用，不初始化 SDK 或权限。snapshot 提供模式、运行、失败及可选发现快照，不暴露手动地址或第三方异常。

sourceFactory 按每轮 AUTO 新建 DiscoverySource(sink)，create 只创建对象，不激活资源或调用 sink；source.start 激活网络/tick，source.close 撤销该来源，必须幂等且不能等待 sink 回调（Android 来源异步 close）。sink 的 advanceNetwork/receive/tick/failed 只影响当前 AUTO 代次，迟到来源无副作用；旧来源的 advanceNetwork 返回其已失效的令牌，不对新桥接换代。来源交付串行控制入口；同步 source.start 回调可重入，但不得额外开线程并阻塞等待控制器。

AUTO 新建桥接，将其本地门禁接入稳定路由器、bind/start 后启动来源。MANUAL 在清理前通过 ConnectionSelection.create 校验；无效地址返回 false 并保留现模式。相同手动字符串在健康模式下幂等复用选择（不重新 start 或重置退避）；新地址先撤销旧门禁/来源、关闭桥接或停止 session，再安装稳定手动选择并 start。返回 AUTO 同样先清理手动 session，再创建空候选桥接，从新包重新观察；不复用已关闭桥接。模式仅进程内保留，不读写 settings，不改 deviceId，不关闭 graph，不重放业务。

独立控制锁串行模式/start/stop，门禁路由使用独立状态锁；门禁只调用当前本地判断，不获取控制锁、不调用 session 控制/来源/外部监听。路由锁只在短本地门禁调用期间持有，撤销旧门禁后才做清理。手动门禁只接受当前选择对象；代次清理沿用 session。桥接监听注销不受门禁锁限制；source.close 不等待回调，避免控制锁与接收线程互相等待。

启动/切换/来源失败先撤销门禁，然后尝试来源关闭、桥接关闭或 select(null)/stop；某一步异常仍尝试其余清理，公开失败 snapshot，错误不越过回调入口。失败后显式 start 重试当前请求模式，创建新 AUTO 来源或新手动意向；不得隐式退回旧会话。失败清理不伪造成功；未确认停止的资源仍保留待下次清理，不能丢失句柄后开启第二个来源。stop/close 不持有门禁锁等待控制返回，onChanged 在控制锁之外隔离调用。

测试用真实 ConnectionSession 与可保留迟到回调的来源，验证无地址等待、AUTO/MANUAL 切换和重新观察、无效手动地址、同地址保留退避、旧包/tick/失败/paired/结束通知、来源部分启动失败、清理异常、停止重启与关闭永久性，以及门禁与控制并发。Android 资源、graph/进程容器和真机验收另行实施。
