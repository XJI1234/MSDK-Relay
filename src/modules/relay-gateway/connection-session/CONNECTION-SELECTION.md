# 可选连接选择扩展契约

2026-10-13，connection-session 0.2.0。本文补充 CONTRACT.md 的固定 endpoint 规则；冲突时以本文的可选选择与串行替换语义为准。WebSocket hello/paired、五态、设备身份、旧代次隔离及清理顺序不变。

## 数据与入口

- SessionConfig / RelayGatewayConfig 的初始 endpoint 允许 null；非 null 必须通过已有 endpoint-settings 校验，非法地址在创建前拒绝。null 代表尚未选择电脑，不是空字符串或伪地址。
- `ConnectionSelection.create(endpoint)` 返回 `Valid(selection)` / `Invalid`。selection 持有规范化、已校验地址；身份按对象引用比较，同一 URL 的两次 create 也为不同选择。toString 与失败结果不暴露地址，不持久化或传给网络。
- `selectConnection(selection?) -> SelectionResult.Changed / Unchanged`：在优先会话事件序列中完成选择替换、取消旧定时器和旧代次清理。非 null 的选择对象不可被调用者改写。
- `SessionStateEvent.connectionSelection` 携带该事件所属的选择对象；结束事件属于旧选择，之后新 CONNECTING 事件属于新选择。监听器仍异步顺序投递，不能把迟到事件当作当前事实；运行容器还应核对会话代次与发现选择令牌。
- gateway 转发 selectConnection，并提供只读 connectionSnapshot，供运行容器核对迟到状态事件；不开放 transport 或可变队列。
- `SessionPairingAdmission.admit(selection, generation)` 是合法 paired 进入 ACTIVE 前的同步选择门禁，默认 true 以兼容现有手动流程，gateway 配置可注入。运行容器通过当前 selection 映射到发现令牌，调用发现 activate，并记录获准的 generation；不能只在异步状态监听器里拒绝旧选择，否则业务帧可能先被处理。门禁只检查本地选择，不提供网络认证；不得阻塞、调用网络或反向调用 session/gateway/运行容器控制入口。
- `SessionStateEvent.endedGeneration` 携带本次实际清理的代次；普通转换以及无代次的 RECONNECT_WAIT → STOPPED 事件为 null。运行容器只在它匹配当前已激活代次时通知发现 disconnected；同一 selection 可以自动重连多次，仅比较 selection/发现令牌不足以区分旧结束通知与新 ACTIVE。结束快照仍为 null generation，不把已结束代次重新作为当前代次。

## 运行意图与五态

1. create 始终 STOPPED，不连接；start 无选择时记录希望运行并返回 StartAccepted，状态仍 STOPPED，没有定时器/状态通知/代次。重复 start 无选择返回 StartAccepted 且无副作用；有选择时原 start 语义不变。
2. STOPPED 下 stop 始终撤销希望运行（即便此前无地址已 start），返回 AlreadyStopped、不发布事件；之后 selectConnection 仅更新配置，必须再次 start 才连接。stop 不清空已选地址，原手动 restart 保留。
3. 同一选择对象的重复 selectConnection 返回 Unchanged，不 open、不 start、不取消/改写重连定时器、不重置失败计数；当前选择 null 时重复 null 同样幂等。
4. 撤销选择 null 保留原希望运行，取消重连令牌/定时器，现有代次按固定次序清理后进入 STOPPED（CONNECTION_PAUSED）；没有代次时不重复清理。无选择期间不重连，迟到旧回调不能恢复连接。
5. 新选择先失效/清理旧代次（ENDPOINT_CHANGED），撤销旧重连；然后替换选择，清零失败计数。希望运行为 true 时立即创建全新代次连接新地址；false 时保持 STOPPED。同 URL 的新选择也执行替换；此入口是显式替换，不会自行因公告变化调用。
6. healthy ACTIVE 的保持由发现协调器决定，它持续输出同一选择；运行容器不得为重复公告/第二台电脑/缓存过期创建新 selection。显式撤销或新 selection 可以清理 ACTIVE（用于手动调试切换或拒绝迟到 paired），不会停止 DJI/运行图。
7. ENDPOINT_CHANGED / CONNECTION_PAUSED 为本地会话结束原因，不发新增协议帧；复用命令、任务传输与发送清理，不触发新飞行动作、不重放旧命令。新地址同步 open 被拒仍返回 Changed，当前快照为 RECONNECT_WAIT，失败计数从首轮开始。
8. selectConnection 与 start/stop/transport/timer 的竞态按优先会话序列线性化；选择更新优先于未开始的入站帧，但不能中断已执行的业务帧。清理依赖异常不跳过后续清理/新选择；状态通知器不在内部锁或会话执行器直接调用用户代码。
9. 门禁返回 false 或抛出异常时，不发布 ACTIVE、不交付业务帧，清理旧代次并撤销当前选择，保留运行意图进入无选择 STOPPED（CONNECTION_PAUSED），不自动重试该旧目标。异常只记固定脱敏诊断。只有当前合法 paired 调用门禁，重复/非法/旧代次帧不得调用或绕过门禁。

## 实施/验证边界

测试覆盖无地址 start/stop、显式停止后选择不自动启动、重复选择保持退避、各状态换地址/撤销、同 URL 新选择、旧 opened/paired/close/failure/handshake/retry、清理失败、新地址 open 拒绝、并发选择/stop、优先清除排队入站帧、握手门禁拒绝/异常和同选择重连后的迟到结束通知，以及 gateway 保留命令注册、丢弃旧完成回调和中止旧传输。运行容器的发现令牌与 selection 映射及 Android 生命周期另行接线；本文 API 测试不代表正式 App 自动连接完成。
