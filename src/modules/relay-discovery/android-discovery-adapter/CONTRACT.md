# Android WLAN 公告接收适配器

2026-10-14，版本 1.0.0。Gradle 路径 `:relay-discovery:android-discovery-adapter`。实现后独立测试/编译，正式 App 接线另行记录。

`AndroidDiscoveryReceiver.create(applicationContext, sink)` 创建单一网络/计时来源；`start()` 幂等异步启动；`close()` 永久关闭、幂等，禁止新事件交付，已进入的回调可完成，资源清理在串行调度器收敛。调用方不拥有内部线程。sink 提供 `advanceNetwork(): DiscoveryNetworkGeneration`、`receive(generation, bytes, sourceIpv4, subnet)`、`tick()` 和脱敏状态通知。

只注册 WLAN NetworkCallback，不要求 INTERNET/VALIDATED 能力，不主动请求网络；无 WLAN IPv4 等待，多个 WLAN 保持当前可用链路，否则按 network handle 稳定选择。只接受有效 IPv4 及 1..30 前缀，接口/IPv4/前缀或网络 handle 改变时换代。重复同链路通知不重建；其他网络丢失不影响当前监听。使用 ConnectivityManager/LinkProperties、Network.bindSocket，将独占 UDP 39082 socket 绑定 WLAN，wildcard 本地绑定保留两类广播接收能力；不修改进程全局网络绑定。

创建 Wi-Fi MulticastLock 后 acquire，socket/线程部分初始化失败逆序 close/release。需要清单权限 INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、CHANGE_WIFI_MULTICAST_STATE；服务/运行权限由运行容器处理。本模块不依赖 Activity、前台服务、DJI、WebSocket 或 settings，不自建 DiscoveryCoordinator、不解析 JSON。

每轮 receive 将 DatagramPacket 长度复位到 513，零长度、超过 512、非 IPv4 来源丢弃；只复制实际长度并附接收子网。接收线程入队最多 64 个包，超量丢弃，避免公告洪泛形成无界队列。网络/接收/tick/重试在一个串行调度器执行，旧 reader/重试回调按对象代次过滤。关闭 socket 使阻塞接收退出，不在主线程 join。close 异步资源收敛后关闭自有 executor。

每秒 tick 驱动调用方观察和过期，与是否已有 Wi-Fi 无关；使用单调时间的调度，调用方桥接应采用单调 clock。网络丢失/替换或接收异常先撤销旧 reader，关闭 socket/释放锁，再 advanceNetwork。接收/绑定失败按 1、2、4、8 秒（上限）重试；正常收包后重置退避，成功 bind 本身不重置，以防反复接收失败忙循环。没有 Wi-Fi 不安排绑定重试；链路变化取消旧重试。状态 WAITING_WIFI/LISTENING/RETRYING/FAILED 不含 IP、包或异常。sink 控制回调抛错会使来源进入 FAILED 并释放来源；不伪造发现/连接成功，不继续失控重试。

验证覆盖启动幂等、同链路去重、候选网络丢失、IPv4/接口变化、旧包/重试失效、有效长度与实际拷贝、来源/子网、错误退避、监听注册失败、部分初始化回滚及永久关闭。Android API 路径编译不等于真机广播送达、Wi-Fi 重连/跨 AP 或后台验收。容器接线按 docs/auto-discovery/RUNTIME-CONTRACT.md；探针 39081 不变。
