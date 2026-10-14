# 发现到会话的运行桥接

版本：1.1.0，2026-10-14。Gradle 路径 `:app-runtime:discovery-session-bridge`。纯 JVM 组合模块，依赖 relay-discovery 与 gateway 的公开接口；不依赖 Android/DJI，不拥有 socket、线程或计时器，正式 App 尚未接线。新增 [RelayConnectionControl 模式控制扩展](CONNECTION-CONTROL.md)，唯一控制 gateway、稳定路由门禁并组合外部发现来源。

## 所有权与入口

`DiscoverySessionBridge(clock)` 唯一持有 DiscoveryCoordinator；单独使用时先将 pairingAdmission 注入空 endpoint 的 gateway 配置，再 bind，最后由获准且前台就绪的运行容器调用 start。正式模式组合改由 RelayConnectionControl 的稳定 pairingAdmission 注入 gateway，由控制器在 AUTO 中创建/绑定桥接；手动模式先撤销旧路由门禁再关闭旧来源/桥接，不能并行控制。bind 只能一次，start 幂等，关闭后不可恢复；重复关闭无副作用。桥接运行期间外部不能直接 start/stop/selectConnection 同一 gateway。Android 服务/进程容器归属按 RUNTIME-CONTRACT.md 接线，尚未实现。

- `networkGeneration / advanceNetwork / receive / tick` 供正式网络适配器调用；receive 只接受运行期间的包，旧网络令牌沿用核心规则。网络变化保持健康 ACTIVE，但清空未 ACTIVE 候选并暂停旧尝试。至少每秒 tick 驱动过期、观察及状态校正；不得用频繁 start 代替 tick。
- `snapshot()` 只返回最后计算的发现快照，不创建线程或主动重连。成功连接仍以真实 paired 为准，SELECTED 只是连接意向。
- `close()` 先撤销运行及门禁，随后暂停并停止唯一 gateway、注销状态监听；不关闭整个业务 graph，不重放旧业务。注销不在控制锁内等待，避免已启动的监听器回调与注销互相等待。运行容器负责停止网络来源/定时器并释放 Android 资源。

## 选择、门禁与结束

一个有效发现选择令牌映射一个 ConnectionSelection；重复包/tick 复用对象，不重置 session 退避。WAITING/OBSERVING/CONFLICT 输出 null 暂停旧目标；新令牌即使 URL 相同也新建选择。控制请求按串行顺序调用 session，每次调用完成后重新检查最新意向，使控制调用期间的新冲突/关闭最终收敛；不创建并行连接。

合法 paired 在 session 同步门禁中按实际选择映射调用 activate；拒绝后不产生 ACTIVE 或业务帧。记录获准的 generation。异步 endedGeneration 仅在匹配当前获准代次时结束发现 ACTIVE；旧通知不清除新 ACTIVE。

若旧断线通知尚未送达而同一选择已开始新代次，门禁必须先结束旧 ACTIVE 并按最新候选重新观察，不能利用核心 ACTIVE 的幂等 activate 绕过已出现的冲突/过期。tick/控制协调也通过非阻塞 session snapshot 识别已结束的获准代次：仅 generation 不匹配时清除，不把门禁到 session 提交 ACTIVE 之间同代次的 AWAITING_PAIRING 当成断线。清除后重新观察 3 秒，待目标有效才连接。

## 并发与依赖边界

状态锁只保护核心/映射/获准代次，不在该锁内调用 session 控制或注销监听；独立控制锁串行化 start/select/stop，调用时释放状态锁。门禁只短暂获取状态锁、读取本地核心，不获取控制锁、不调用 gateway 或外部回调，不等待 IO、线程任务或控制完成。状态监听异步调用协调；快照 getter 必须无回调、无等待。依赖不得在控制调用中内联投递状态监听。

调用选择控制前即撤销“已应用”缓存，控制抛错不缓存为已应用选择，也不能继续相信此前的缓存；适配器可能已经切换再抛错，之后的冲突仍须发出暂停。下次 tick 可重试；启动失败撤销运行并尝试停止，错误交由调用方处理，不记录地址或第三方异常。关闭清理即便某项抛错仍尝试其他步骤并注销。生产异常应由运行容器显示脱敏失败状态；不在本模块伪造成功。

## 验证边界

使用真实 ConnectionSession、可延迟通知器与手动单调时钟测试无地址等待、观察/冲突/过期、退避保持、地址/网络替换、门禁拒绝、通知滞后及并发控制；另验证 gateway 公开适配器。正式 Android UDP、权限、前台服务、UI、锁屏和两手机/两无人机/15 秒目标仍待接线与实机验收。
