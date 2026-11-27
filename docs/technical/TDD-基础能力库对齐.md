# TDD-基础能力库对齐

状态：草稿（2026-11-26 勘查完成，待定事项见 §3）
关联：[ADR-066](ADR/ADR-066-业务模块按配置选数据源.md) · [TDD-平台基础库换表](TDD-平台基础库换表.md) · [TDD-登录与账号对齐平台基础库](TDD-登录与账号对齐平台基础库.md)
创建：2026-11-26 · 最后更新：2026-11-26

## 一句话

基础能力库 `n_common` 约 21 张表，分为权限、开放应用、事件、字典、审批、发号、审计、设置八组。
现在这些能力几乎每一组都有多套实现，散在 5 到 10 个模块里。对齐分两件事：
一是把每组收敛成一个唯一实现、一个端口；二是给它一个模块句柄，让它能像发票、平台基础库那样按配置换库。

## 验收标准

- K1：Given 公共能力的某一组 When 搜索它的旧表名 Then 只在该组的唯一实现里出现（用守卫扫描，同平台基础库的做法）。
- K2：Given 公共能力模块绑到 `n_common` When 跑权限、审计、设置的测试 Then 全部通过。
- K3：Given 运营后台的事务 When 扫描 Then 同一事务不同时写平台基础库和基础能力库。

## §1 现状（2026-11-26 盘点）

### 1.1 各组的旧表、读写面与重复实现

| 组 | 旧表数 | 读写文件数（量级） | 集中处 | 重复实现 |
|---|---:|---:|---|---|
| 权限 | 7 | 19 | `shop-core` 的 `platform/perm` | 网关 `iam` 的 `JdbcResourceStore` 管接口资源表；`FunctionModuleSync` 直接写功能点 |
| 开放应用 | 3 | 约 40 | 无 | 支付 `pay_app`、库存 `inv_open_credential`、运营 `sys_ops_api_key`（设计归密钥库） |
| 事件 | 24 | 约 234 | `shop-store-mybatis` 的 `event` 包 | 平台与库存两套前缀、支付权益、积分、业务通知、商城内核、物流推送等 7 套；消费去重 6 套，另有已退役的 `sys_idempotent`（约 50 个文件在用） |
| 字典 | 13 | 约 107 | 无 | 行业三套（`sys_industry`、`sys_store_industry`、`sys_business_type`）；渠道三套（`sys_pay_channel`、`PayChannels` 常量、`cim_provider`） |
| 审批 | 3 | 22 | `shop-opskit` 的 `approval_link` 与 `ApprovalPort` | 支付两人复核、储值活动审批、门店审核、改价 |
| 发号 | 7 | 34 | `shop-base` 的 `DocumentNumberService` | 一份仓储代码对应两组表（CORE、PAY）；`sys_sequence` 有两个 Mapper（`TradeMappers`、`SeqMapper`）；另有三种按范围计数 |
| 审计 | 4 | 约 107 | `AuditLogPort` | `OpsServiceImpl` 绕过端口直接写；各域自有操作日志（员工、支付商户状态、库存、美业、餐饮、打印） |
| 设置 | 3 | 58 | `SettingService` | 支付 `PaySettingService` 另一套 |

### 1.2 运营后台跨两个库的事务

`OpsServiceImpl` 有 19 个事务，其中 13 处同时写平台基础库（运营账号、会话、主体）和基础能力库（角色成员、审计、审批、发件箱）。
设置账号角色、新建账号、设置多角色这三处把角色**双写**：`ops_staff.roles` 的 JSON 用于判权，`sys_role_member` 用于菜单。
`PermConfigServiceImpl` 的 9 个事务只写基础能力库，例外是强制下线一个角色的账号（`forceLogoutRole`，会写运营会话，即登录 TDD 的 X5）。

### 1.3 事件：拆库的主要阻力

用发件箱的业务文件有 86 个、发布调用 152 处，都是“业务表与发件箱同一个事务”。
餐饮的发件箱配置明确要求“同一个主库事务和数据源”。基础能力库一旦和业务库分开，这些写法要么换语义，要么发件箱留在业务库。
先例：发票已改成提交后再发（`InvoiceServiceImpl` 的 `afterCommit`）；打印计划同样处理（发票打印 TDD 的 T5）。

### 1.4 事件发布逐处分类（2026-11-26）

用户的问题：「为什么需要发件箱，大多数业务都是同步调用」。发件箱只在两种情况下才必要：下游调外部系统，或者下游写另一个库，
而且业务事务提交后这件事必须做成。下游和发布方在同一个库的，同事务直接调用更简单。
所以先把每一处发布按下游去向分类，明细见 [基础能力库对齐-事件发布分类.tsv](基础能力库对齐-事件发布分类.tsv)：155 处，每处写明消费者、去向库和依据。

| 类 | 处数 | 含义 | 去向 |
|---|---:|---|---|
| E 无人消费 | 40 | 没有任何消费者。其中一部分走“只存档”通道（美业全部、餐饮大部分、商户的资源/班表/预约/人员变更等），一部分连存档都不是（例如餐饮预约变更每次都写一条永远不投递的行） | 删掉发布与事件类。只存档的那批，先确认没有人读这些存档行 |
| D 尽力通知 | 10 | 只有站内信、推送、打印故障提醒这类消费者 | 改成提交后再发，不落库 |
| A 同库直调 | 4 | 消费者只写与发布方同一个库的表，例如活动权益决定、储值充值准备、发票打印事实、微信发货回执 | 同事务直接调用，删事件 |
| 混合 | 34 | 一个事件挂着多类消费者，最常见的是“通知（D）＋写别的库（B）”：订单已付、发货、完成、售后各节点、SKU 变更等 | 按消费者拆开：通知走提交后再发；跨库的那一个保留可靠投递 |
| B 跨库必达 | 22 | 写另一个库，丢了会不一致：下单→裂变与风控、卡券占用、积分来源、库存镜像、物流快照→子单 | 保留可靠投递 |
| C 外部调用 | 33 | 物流 23 处（快递100、微信跟踪与绑定、渠道订阅）、NearPay 门店同步、审批送审、渠道插件的重读提示 | 保留可靠投递 |
| F 中继 | 12 | 支付权益、库存双写、打印、商户存档的转发与桥接代码 | 随收敛删掉或合并 |

结论：真正需要可靠投递的，是 B、C 两类加上混合里的跨库部分，大约 55 到 89 处，集中在订单、物流、卡券与储值、库存、打印这几条链；
其余约一半（E、D、A）可以直接删掉事件，或改成同步调用、提交后再发。

**E 类已删（2026-11-26）。** 40 处里删了 38 处、30 个事件类型，连同只为它们存在的发布器注入、辅助方法、只存档名单
（`application.yml`、`FoodOutboxRouting.ARCHIVE_ONLY`、美业存档开关）。菜单的两处保留：`FOOD_MENU_CHANGED`
在分类时记作无人消费，但餐饮目录消费者 `FoodCatalogSourceEventConsumer` 直接处理它，分类有误。
原来“发布失败则业务回滚”的测试，失败点改挂在同一事务里紧挨着的审计写入上。

**D 类已改（2026-11-26）。** 10 处只发通知的发布改走 `OutboxEventBus#notifyAfterCommit`：不写发件箱，

**发布入口已统一（2026-11-27，ADR-067 第 1 步）。** `notifyAfterCommit` 删掉，10 处改回 `publish`。消费者标 `afterCommitOnly()`（现为 `NotificationConsumer`、`PrintNotifyBridge`）即为通知类：发件箱投递与统一模式登记都跳过它，`publish` 按订阅者自动登记提交后通知；事件类型只有通知类消费者时不落发件箱，一个消费者都没有的照旧落库。外部事件号发布（`publish(eventNo, event)`）同样分流，并把事件号带给通知。

**外部通道已去重（2026-11-27，ADR-067 第 2 步）。** `EventIdempotency.firstSend(通道, 收件人)` 在 `sys_event_consumed` 记 `send:<通道>:<收件人>`（`REQUIRES_NEW` 单独提交，外部调用不进数据库事务）；事件号由提交后通知与发件箱投递经 `DeliveryContext` 设上，运营端测试发送等不在投递里的调用直接放行。接在 `WxSubscribeSender`、`PushSender` 的统一出口和 `NotificationConsumer` 的短信、邮件、企微群、收件人物流短信处。

**RabbitMQ 传输已接（2026-11-27，ADR-067 第 3 步）。** `shop.events.transport=local|rabbitmq`（默认 `local`）；`rabbitmq` 下通知类事件提交后发到交换机 `shop.events`，各通知消费者在自己的仲裁队列收，超投递上限进死信。连接配置在 `application.yml` 的 `shop.events.rabbitmq`，地址/虚拟主机/账号有默认值，口令只从 `SHOP_RABBITMQ_PASSWORD` 来。真 broker 集成测试待在服务器侧跑。

**中间件统一封装 + 发件箱改发中间件（2026-11-27，ADR-067 决策 9 与第 4 步）。** 与厂商无关的 `event.broker.MessageBroker` 接口，RabbitMQ 实现关在 `event.rabbit` 包（守卫 `BrokerIsolationTest`）。发件箱按 `shop.events.outbox-event-types` 名单逐类改发中间件（默认空），必达消费者以保序订阅收；真 broker 测试 `RabbitMessageBrokerIT` 覆盖两种订阅、按聚合保序、死信与无人订阅即失败，需在能连 RabbitMQ 的机器上跑。
由 `AfterCommitNotifications` 在提交后、在后台线程交给支持它的消费者（`NotificationConsumer`、`PrintNotifyBridge`），
失败只记日志不重投，回滚则不发。必须换线程：提交回调里本线程的数据库资源还绑着已提交的事务，直接写库会静默丢失。
测试配置 `shop.notify.after-commit.wait=true` 让提交线程等投递做完。混合类里的通知部分还没拆。

**顺带发现的问题**

| 现象 | 例子 |
|---|---|
| 同一事件多处重复发布 | 子单完成 5 处、旧券结账决定 5 处、发货 3 处、下单 3 处；卡券占用由两个服务并行发布 |
| 两层发件箱 | 积分来源事实本身已经是一张待投递表，又为它另发一个事件 |
| 配置自相矛盾 | `CATALOG_SOURCE_CHANGED` 在 `application.yml` 里列为只存档，插件又订阅了它；只存档通道发现有消费者就抛错，所以插件一启用就会出问题 |
| 打印事实写错了地方 | 打印事实表在交易事务里写，拆库后就跨库了；应由打印一侧的消费者在平台服务库落事实 |
| 分类依赖开关与部署形态 | 卡券、储值在 `shop.pay.deployment=embedded` 时同库，`standalone` 时变成 HTTP 调用；报表、渠道插件、餐饮渠道事件各有开关，关掉时对应的消费者不存在 |
| 餐饮与 KDS | 出品事件和 KDS 事件在新设计里是同一张表，可以同事务直接写；standalone 时这两个处理方跑在平台进程里、经 HTTP 回调餐饮服务，要先放回餐饮进程 |

**由此改写 Q1。** 可靠投递只剩上面那一部分，问题不再是“发件箱放哪”，而是“这部分的待投递记录放哪”：
它必须和发布方的业务表在同一个库，同一个事务里写，否则业务提交了、事件却丢了。会产生这类记录的库，
主要是门店交易库（订单、售后、卡券占用）、平台服务库（物流、打印）、行业库（餐饮）。

### 1.5 引入 MQ 后的去向（草稿，2026-11-27）

> **2026-11-27 用户定：异步统一走 MQ。** 按事件类型、以最严格的消费者定走法：MQ 直发 45 处，
> 发件箱 → MQ 57 处（另加迁移期库存双写 4 处），源头改发 MQ、撤中转 8 处。
> 最终分工以 [异步与通知清单 §〇](基础能力库对齐-异步与通知清单.md) 为准；本节下面的四分法是此前草稿，作依据保留。
> Q10 随之定为“来单走 MQ 直发”。选型与写法见 [ADR-067](ADR/ADR-067-异步事件统一走RabbitMQ与发件箱只补提交缺口.md)（提案）。

前提：MQ 选 RabbitMQ 4.x（2026-11-27 用户定，原草稿为 RocketMQ），用配置 `shop.events.transport=local|rabbitmq` 切换，**默认 `local`，即现在的进程内投递**；
不部署 MQ 的环境行为不变。MQ 只是传输：它解决“投递之后”的重试、按消费者隔离、削峰、跨进程，
**解决不了“业务提交与发消息”之间断开**，所以必须做成的事件仍要先在业务库里落一条待投递记录。

除去已删的 E 类和已改的 D 类，剩 105 处，按下游要求分成四个去向：

| 去向 | 条件 | 怎么发 | 处数 |
|---|---|---|---|
| ① 直接调用 | 消费者和发布方写同一个库 | 同一个事务里调端口，不发事件 | 4 + 混合里的同库部分 |
| ② MQ 提交后直发 | 丢了可以接受（通知）；或业务表本身记着“待办”，且有独立的补偿扫描兜底 | `notifyAfterCommit` / 提交后发 MQ，**不落发件箱** | 10（已改）+ 21 处混合的通知部分 + 27 |
| ③ 发件箱 → MQ | 必须做成，且没有别的兜底 | 业务事务里写待投递记录，中转程序发到 MQ，消费者订阅 | 22 + 混合里的跨库部分 |
| ④ 中转器撤掉 | 只是把别处的事件再转发一次 | 由源头直接发 MQ，或删掉 | 12 |

#### ① 直接调用

| 事件 | 位置 | 状态 |
|---|---|---|
| `WX_SHIPPING_UPLOADED` | `WxShippingUploadService.upload` | **已改（2026-11-27）**：上传成功后同一事务里调 `WaybillWxUploadPort.markUploaded`（实现 `logistics.port.WaybillWxUploadPortImpl`），事件类删掉；`WxUploadedConsumer` 只留着消化存量 |
| `MERCHANT_COLLECTION_RECEIVED` 的挂单部分 | `PaymentCollectionServiceImpl.receive` | **已改（2026-11-27）**：收款码收款在写回执的同一事务里调 `attachCollection`，删掉 `StoreOrderCollectionConsumer`；收款流投影仍走发件箱，见清单 N9 |
| 餐饮 KDS 投影（7 处混合的 KDS 部分） | `DiningCheckout`、`DiningOrder`、`KitchenTask`、`Check`、`BusinessReturn`、`ProductionConsumption`、`DiningChannelOrderBinder` | 要等 `fnb_kds_event` 在新库里并进 `fnb_service_event`，才成同表 |

读代码后改判、移出 ① 的：

| 事件 | 原因 |
|---|---|
| `ACTIVITY_BENEFIT_DECIDED`、`STORED_VALUE_RECHARGE_PREPARED` | 消费者是无事务的补偿流程（`recover`，跨 PAY 调用、`externalWorkflow=true`），不是同库写入，不能并进发布方事务；见清单 R5、R6 |
| `BUSINESS_PRINT_FACT_READY`（发票） | 发票已绑平台服务库，打印还在主库（`application.yml` 只绑了 `invoice`），现在是跨库；打印绑过去后再议，见清单 R7 |

异步与通知部分逐条分析见 [基础能力库对齐-异步与通知清单](基础能力库对齐-异步与通知清单.md)。

#### ② MQ 提交后直发（不落发件箱）

**通知类**：已改的 10 处只换传输；21 处混合事件的通知消费者在发件箱投递时跳过、改走这里
（交易订单 19、收款到账 1、运单签收 1）。失败由 MQ 重试，耗尽进死信，不再“只记日志”。

**有独立兜底的必达事件**：业务表里记着待办状态，补偿任务按业务表扫描补发，MQ 只是快路径：

| 事件 | 处数 | 兜底 |
|---|---|---|
| `LGS_WAYBILL_REGISTERED`、`LGS_WX_FOLLOW_REQUESTED`、`LGS_KD100_SUBSCRIBE_REQUESTED`、`LGS_WX_BIND_REQUESTED` | 20 | `CompensationSweeper`（扫 PENDING 超 10 分钟的订阅）、`WxWaybillBindJob` |
| `WX_SHIPPING_UPLOAD_REQUESTED` | 2 | `WxShippingUploadJob` |
| `CARD_SALE_CANCELLED` | 2 | `CardSaleRecoveryJob` |
| `ORDER_POINTS_SOURCE_FACT` | 1 | `OrderPointsRecoveryJob` |
| `APPROVAL_SUBMIT_REQUESTED` | 1 | `OpsKitReconcileJob` |
| `MERCHANT_STORE_CHANGED` | 1 | `EntityRegistrationProjectionTimer` |

> 待核实：上面每个兜底任务都要确认**扫的是业务表而不是发件箱**。`CompensationSweeper` 现在也读 `SysOutboxMapper`
> （处理“发件箱重试耗尽”的情况），改走 MQ 后这条分支要换成读死信。核实不过的，退回 ③。

#### ③ 发件箱 → MQ

待投递记录放在**产生它的业务库**（即 Q1 的建议）；`OutboxDispatcher` 从“进程内调消费者”改成“发到 MQ，发成功就标记已发”；
消费者改成 MQ 订阅，**每个消费者一个消费组**。按消费者隔离后，库存出库失败不会再拖着开票、报表一起重来，
这是现在“同一事件所有消费者同一事务”的问题的根治办法。去重仍按事件号，语义仍是至少一次。

| 组 | 事件 | 处数 | 下游 |
|---|---|---|---|
| 订单创建 | `ORDER_CREATED` | 3 | 裂变邀请/发券（会员营销库）、风控（平台服务库） |
| 权益核销 | `ENTITLEMENT_ORDER_USE_PREPARED/DECIDED` | 4 | 会员营销库 |
| 旧券结账 | `LEGACY_COUPON_CHECKOUT_DECIDED` | 5 | 会员营销库 + 门店交易库 |
| 旧券历史退回 | `LEGACY_COUPON_HISTORY_RETURN_DECIDED` | 1 | NearPay |
| 打印事实 | `ORDER_PRINT_FACT_READY`、`ORDER_PRINT_CONTROL_CHANGED`、`BUSINESS_PRINT_FACT_READY` | 3 | 平台服务库打印 |
| 库存镜像 | `INV_MIRROR_RESTORE_ORIGINAL`、`INV_MIRROR_ALLOCATE` | 3 | 库存库；**按 SKU 顺序消费** |
| 发货快照回写 | `LGS_SHIPMENT_SNAPSHOT` | 2 | 门店交易库子单状态；**按子单顺序消费** |
| 商城渠道源 | `CATALOG_SOURCE_CHANGED`、`PRICING_TAX_SOURCE_CHANGED`、`FOOD_DISH_CHANGED`、`FOOD_CONFIG_PUBLISHED`、`FOOD_SERVICE_CALENDAR_SAVED`、`FOOD_STORE_POLICY_PUBLISHED` | 8 | 商城渠道插件 |
| 混合的跨库部分 | 交易 19 处（报表脏日、库存出库、自动开票、卡券、风控、运单登记）、商品 5 处（库存投影、渠道源）、餐饮 7 处（打印、报表、渠道回传）、菜单停用 1 处、运单签收调微信确认 1 处 | 33 | 各自的库或外部系统 |

报表脏日（`ReportCorrectionConsumer`）出现在十几个事件里。报表模块若有每日重算兜底，它可以降到 ②（Q8）。

为什么还要发件箱：RabbitMQ 的发布确认只保证“发出之后”不丢，补不上“业务提交与发出之间”的缺口；
发件箱给了统一的补发依据，而且 `local` 模式不需要 MQ 也能跑。

#### ④ 中转器撤掉

| 中转 | 处数 | 改成 |
|---|---|---|
| `PayEntitlementOutboxRelay`、`EntitlementEventReceiverServiceImpl`、`LocalPayEntitlementEventBridgePortImpl` | 4 | PAY 从自己的发件箱直接发 MQ，商城订阅；NearPay 推送改为发 MQ 或由接收端直接投 MQ |
| `PlatformInventoryEventSink`（进销存事件回写门店交易库） | 1 | 库存库发件箱 → MQ，交易侧订阅 |
| `DualWriteStockPort`（库存镜像双写） | 4 | 迁移期按 ③ 走；库存全部归库存库后整体删掉 |
| `PrintingConfiguration`、`FoodWriteGuardConfiguration`（包装转发） | 2 | 随消费者改订阅一起去掉 |
| `PlatformPublicationPublisher` | 1 | 已无调用方，删掉 |

#### 主题与顺序

- 每个域一个 topic 交换机（通知、交易、库存、物流、权益、商城渠道源、餐饮），路由键就是事件类型；
  每个消费者（`handlerId`）一条仲裁队列，按它支持的事件类型绑定。
- 需要保序的（库存镜像、发货快照、子单状态）经一致性哈希交换机按聚合号分到固定几条分区队列，每条单活消费者；其余普通队列。

#### 实施顺序

1. 混合类的通知部分并进 ②（`local` 传输即可，与 MQ 无关）。
2. ① 里无条件的两处。
3. 传输层抽象加 RabbitMQ 实现（默认 `local`），② 先切。
4. ③ 的中转程序改发 MQ、消费者改订阅，按域逐个切。
5. ④ 撤中转。

## §2 顺序（建议）

| 步 | 做什么 | 依赖 |
|---|---|---|
| 1 | **权限**：加公共能力模块句柄 `common` 与标记 `CommonTableMapper`；`platform/perm` 7 张表的 Mapper 与网关 `iam` 两个类接进来；拆掉运营后台的角色双写（判权改读 `sys_role_member`，`ops_staff.roles` 停写）；强制下线改成提交后再吊销会话 | Q2、Q3 |
| 2 | **审计、设置**：`OpsServiceImpl` 改走 `AuditLogPort`；审计改为业务提交后写；`pay_setting` 并进 `SettingService` | Q5 |
| 3 | **字典、审批、开放应用**：一组一个端口，删掉重复的常量与表 | — |
| 4 | **发号**：合并两份 `SeqMapper`；仓储去掉按归属切表 | — |
| 5 | **事件与去重**：按 Q1 的结论收敛；删 `Source` 枚举与 `inv_` 前缀，支付权益、积分、商城内核三套并入；`sys_idempotent` 的使用方改用统一防重 | Q1 |

可以先做、不依赖任何决定的：合并两份 `SeqMapper`；`OpsServiceImpl` 的审计改走端口（行为不变）；
新增“公共能力表只经唯一实现访问”的守卫，以现状为基线，只准变短。

已做（2026-11-26）：

| 项 | 结果 |
|---|---|
| 两份 `SeqMapper` | `TradeMappers` 里那份没有调用方，删掉；号段只剩 `trade.order.mapper.SeqMapper` |
| 审计写入 | `sys_audit_log` 的写入从 `OpsServiceImpl` 收进 `AuditLogPortImpl`，运营后台反过来转调端口；仍在调用方事务内，行为不变。原来 `AuditLogPortImpl → OpsServiceImpl` 的依赖环随之消失 |

### 2.1 剩余任务（2026-11-27 汇总，v2 @ 9f7090530 之后）

> 按能力分的完成状态见 [基础能力功能清单](基础能力功能清单.md)。

本会话已做：E 类删除、D 类提交后发、同库 2 处直调、发布入口统一、外部通道去重、中间件统一封装（`MessageBroker`）
与 RabbitMQ 实现、发件箱按名单改发中间件、商品→库存域进名单、兜底作业核实。下面是还没做的，按“卡在哪”分组。

**A. 等你决定**

| # | 事项 | 卡住什么 |
|---|---|---|
| A1 | ADR-067 由“提案”转“已接受” | 正式按它推进 MQ 切换 |
| A2 | 本 TDD §3 的 Q1a、Q2–Q8（Q1 已由 ADR-067 定为“放产生它的业务库”，但与“公共事件只在基础能力库”的设计定稿冲突，要改设计） | §2 第 1、2、5 步；活动权益/储值（Q6）、发票打印（Q7）、报表脏日（Q8）的去向 |
| A3 | [TDD-平台基础库换表](TDD-平台基础库换表.md) §2 的 Q1–Q10 及状态映射（NORMAL→ACTIVE、BANNED→DISABLED、DEREGISTERED→CLOSED） | 平台基础库换表整体 |
| A4 | RabbitMQ 生产部署与运维由谁负责；实例是否 3 节点 | 切 `rabbitmq` 上线；仲裁队列是否真正容灾 |
| A5 | 生产是否打开定时作业（v2 现为 `SHOP_JOB_ENABLED=false`，独立调度器未跑） | 清单 A7–A11 能否改 MQ 直发；这些兜底作业本身在生产上的作用 |

**B. 要在服务器上做**

| # | 事项 |
|---|---|
| B1 | RabbitMQ 启用 `rabbitmq_consistent_hash_exchange` 插件（保序订阅依赖） |
| B2 | 确认 `neargo_app` 在虚拟主机 `neargo` 上有声明交换机/队列与读写权限 |
| B3 | 在能连 broker 的机器上跑 `RabbitMessageBrokerIT`（两种订阅、按聚合保序、死信、无人订阅即失败） |
| B4 | 设 `SHOP_EVENTS_TRANSPORT=rabbitmq`、`SHOP_RABBITMQ_PASSWORD` 后观察商品→库存域，再逐域加名单 |

**C. 可以直接做（不等决定）**

| # | 事项 | 出处 |
|---|---|---|
| C1 | 继续按域加中间件名单，每个域先查“哪些消费者写同一份数据、要不要同订阅组”：~~打印事实（B9）~~ **已加（2026-11-27）** → ~~发货快照回写（B11）~~ **已加** → ~~订单类（B1–B5）~~ **已加** → ~~权益/旧券（B6–B8）~~ **已加** → 餐饮（B14）；商城渠道源（B13）先要解决消费者是 `OutboxHandler` 的问题 | 清单 §〇 B |
| C2 | **已做（2026-11-27）**：对账对 PENDING_SUBMIT 行（超 2 小时）经同一幂等路径补投；改为带作业声明的 `JobHandler`。**未对真实 OpsKit 客户端编译**（`-P opskit` 的依赖本机拉不到，只在桩上跑过 7 个单测）；生产仍待 A5 | 清单 A12 |
| C3 | **已做（2026-11-27）**：某店发布失败即停在它前面、下一轮重试，游标只越过已持久接收的门店；A13 可改 MQ 直发（near-pay 侧按修订号幂等待确认） | 清单 A13 |
| C4 | `CompensationSweeper` 的“卡住数”改读死信队列（现读发件箱 `FAILED`），在物流事件切中间件前做。**暂缓**：物流事件要等 A5（生产作业）才切中间件，现在改无处可用 | 清单 A8 |
| C5 | **已做（2026-11-27，69b2bedd8）**，见 §2.2 1f；`FoodWriteGuardConfiguration` 的包装含餐饮路由决定，留给餐饮模块。`PlatformPublicationPublisher` **已删（2026-11-27）** | 清单 §〇 C |
| C6 | **已做（2026-11-27）**：22 个测试去掉无人读取的 `*.publication.archive-only` 属性。`BeautyOutboxConfiguration` 仍在用（美业服务单独启动时补发件箱门面），保留。`CATALOG_SOURCE_CHANGED` **不矛盾**：只存档名单是默认值，商城插件装上时经 `OutboxRouteContribution` 把路由撤出名单。**但查出新问题**：商城渠道源与餐饮渠道源的消费者（`CommerceProductSourceEventConsumer`、`CommerceTaxSourceEventConsumer`、`FoodCatalogSourceEventConsumer` 等）实现的是统一模式的 `OutboxHandler` 而不是 `OutboxConsumer`，默认旧投递模式下不运行，`BrokerSubscriptions` 也不接管 —— 这些事件类型（清单 B13）进中间件名单前，要先把它们接到 `OutboxConsumer` 或让订阅编排认 `OutboxHandler`。**后者已做（2026-11-27，db01df756）**：统一投递模式的处理者可经中间件投递；商城、餐饮各自要不要切，由各模块定 | §1.4 |
| C7 | **已做（2026-11-27）**：收成 `trade.event.SubOrderCompletion.publishIfCompleted`；逐字段比对 5 处未发现缺漏，统一为“子单已 COMPLETED 才发” | 清单 N4 |
| C8 | **已做（2026-11-27）**：拆成“短事务校验 → 无事务调微信 → 短事务按版本条件收尾并记运单已上传” | 清单 S2 |
| C9 | 存量投完后删 `WxUploadedConsumer`（只为消化切换前的 `WX_SHIPPING_UPLOADED`） | §1.5 ① |
| C10 | 本 TDD §2 第 3 步（字典、审批、开放应用各收成一个端口）、第 4 步剩余（仓储去掉按归属切表）；“公共能力表只经唯一实现访问”的守卫 | §2 |

**D. 已知、与本任务无关的问题（未处理）**

| # | 事项 |
|---|---|
| D1 | `ArchitectureTest.noFullyQualifiedReferences` 失败：商城会话合入的 `MallGateSubject.java` 用了全限定类名 |
| D2 | `StoreSalesCatalogIT` 期望 Flyway 版本 536，迁移已到 V596（本会话之前就如此） |
| D3 | 餐饮等集成测试要 Docker MySQL 8.4，本机只有 MySQL 9、无 Docker，跑不了；`StoreOrderMysqlTest` 等要 `COMMERCE_MYSQL_URL` |

### 2.2 执行计划（2026-11-27，v2 @ 6957a7251）

> **2026-11-27 重整**：第 1 阶段 9 项与 2a 已完成；还剩的事项连同状态统一列在 [基础能力功能清单 §〇 任务总表](基础能力功能清单.md)（R1–R20），以那张表为准。

按依赖分四个阶段。第 1 阶段不等任何决定、各项互不相干，可并行；第 2 阶段等决定；第 3 阶段在服务器上；第 4 阶段归各业务模块。
状态与落点见 [基础能力功能清单](基础能力功能清单.md)。

**第 0 阶段：要你定的（与第 1 阶段并行推进，不阻塞它）**

| # | 决定 | 解锁 |
|---|---|---|
| D1 | Q1：待投递记录放产生它的业务库（改设计定稿“公共事件只在基础能力库”）；Q1a：只存档行有没有人读 | 2b |
| D2 | Q2：公共能力用独立句柄 `common`（未绑定时指向主库）。**已定（2026-11-27）：照建议** | 2a |
| D3 | Q3：`ops_staff.roles` 随权限一步停写。**已定（2026-11-27）：照建议** | 2a |
| D4 | Q5：各域操作日志不并入统一审计 | 1i 的范围 |
| D5 | A4：RabbitMQ 运维负责人、节点数 | 3a |
| D6 | A5：生产打开定时作业（`SHOP_JOB_ENABLED` + 独立调度器） | 3d |
| D7 | ADR-067 转“已接受” | 3c 上线 |

**第 1 阶段：可立即做（建议分 3 个会话并行，各自写不同文件）**

| # | 事项 | 产出与验收 | 量级 |
|---|---|---|---|
| 1a | 公共能力表访问守卫 | **已做（2026-11-27，1309e568a）**：`CommonTableAccessTest`，基线 `backend/known-common-table-access.txt` 200 条、只准变短；后续各项让基线变短 | 小 |
| 1b | 字典收敛 | **已做（2026-11-27，10ead5ef7）**：`spi.dict.DictionaryPort` 一个端口，唯一实现 `DictionaryPortImpl` 按类型汇集三个来源；行业实为两个概念（经营类型树、进件行业），`cim_provider` 是销售渠道方、不并；三个旧端口方法与四处渠道常量别名删掉，见下方“1b 字典” | 中 |
| 1c | 设置收敛 | **已做（2026-11-27，77d04fc9c）**：结算、提现改经 `SettingPort` 读写 `sys_setting`，删 `PaySettingService`/`PaySetting`；pay-svc 桩清单加回 `SettingPort`。`pay_setting` 不再读写、未删表；上线前查生产 `sys_setting` 有无 V285 前的四个旧键 | 小 |
| 1d | 发号仓储 | **已做（2026-11-27，44d906f85）**：仓储不再按归属切表，CORE/PAY 共用 `sys_sequence` + `core_document_number`（支付数据源与主库同一个库，只分事务，所以不跨库）；订单号段 `SeqSegmentSource` 改调仓储 `claimSegment`，删第二份 `SeqMapper` 与 `SysSequence`。**新增迁移 V706**（见下）。「三种按范围计数」逐个核过，只有订单号段是重复的发号，已收；另三处不属发号：`ord_points_source_counter`（积分来源事实的流序号，属事件组）、`mkt_counter_campaign_fence`（营销活动并发围栏，靠同事务行锁，不能挪进独立事务的发号）、`pay_sequence` / `pay_checkout_display_revision`（本仓库没有读写代码，只在库里） | 中 |
| 1e | 审批端口 | 支付两人复核、储值活动审批、门店审核、改价收成一个审批端口（OpsKit 已有的 `ApprovalPort` 为准）。**部分完成（2026-11-27，a24af565d 等）**：运营侧 `ApprovalPort`（门店审核、退款两人复核），店内 `LightApprovalPort` + `sys_approval`（改价）；储值活动审批随储值迁出（Q17 已定，不在本项）。见下方“1e 审批” | 中 |
| 1f | 撤中转 | **已做（2026-11-27，69b2bedd8）**：PAY 权益中转、NearPay 接收端、进销存回写按原事件号经 `OutboxEventBus.forward` 直发中间件；餐饮包装含路由决定未删，见下方“1f 撤中转” | 中 |
| 1g | 接口级防重迁移 | `sys_idempotent` / `IdempotencyService` 的 50 个使用文件按域分批改用统一防重，每批一提交。**已做（2026-11-27）**：Q11 定 A，统一端口 `RequestIdempotencyPort`；端口与主干 18e27062a、美业 2821a4f2f、餐饮 288d997c9、删转发器 260735a0b，做法见下方“1g” | 大 |
| 1h | 开放应用 | 三套应用凭据（`pay_app`、`inv_open_credential`、`sys_ops_api_key`）收敛；若设计已定归密钥库，则做迁移接口，不在本库落表。**已做（2026-11-27）**：统一凭据接口 `CredentialVerifier` 与摘要档；运营接口密钥、库存开放凭证都已改走密钥库；`pay_app` → `sys_open_app` 随基础能力库换库（Q2，第 2 阶段）。见下方“1h 开放应用” | 中 |
| 1i | 审计提交后写 | **已做（2026-11-27，267f86839）**：`AuditLogPortImpl` 提交后在后台线程写 `sys_audit_log`，回滚不写、失败只记日志；只动统一审计，各域操作日志不动（D4 未定，不影响） | 小 |

建议分组：会话甲 1a、1c、1i、1d；会话乙 1b、1e、1h；会话丙 1f、1g。

**1d 的库表变更（V706，2026-11-27）**：`V706__merge_pay_document_numbers.sql` 把 `pay_document_sequence` 的游标并进 `sys_sequence`（同名时取较大值）、
`pay_document_number` 的登记行并进 `core_document_number`。单据类型前缀在枚举里唯一，登记行不会撞键。旧两表不删、不再读写，删表另起迁移。
上线即生效：V706 跑完、新代码起来之后，支付单据的历史号照常能查、新号接着旧游标往后发。已在本机 MySQL 9.7.4 临时库实跑；生产正式库 `pay_document_number` 94 行、`pay_document_sequence` 13 行（清单盘点数）。

**第 1 阶段收尾（2026-11-27，bad490ded）**：守卫基线 197 → 194。审计列表移到 `AuditLogService`；`DevSeeder` 设置种子改经 `SettingService`；
营销活动并发围栏 `mkt_counter_campaign_fence` 移出发号组（同事务行锁，不是计数；设计清单待一次性更正，见功能清单第 6 项）。
没做的：字典组 55 处（支付渠道主数据、区划、主体类型、市场、计量单位、商城行业、渠道接入方）是 1b 的延续，量大，单独做；
删 `WxUploadedConsumer` 要先在生产确认存量 `WX_SHIPPING_UPLOADED` 已投完（发件箱里没有这个类型的待投递行）；
删 `pay_setting`、`pay_document_number`、`pay_document_sequence` 要等 V706 上线并核对后另写迁移。
**顺带发现**：`OpsStaffWriteFlowTest.disableKicksLiveSession` 现红：停用账号时 `OpsApiKeyStore.revokeAll` 走密钥库摘要验证，测试环境没配 `shop.secret.env`（1h 引入）。**已修（2987d6ab2）**：没配时摘要凭据的列出、吊销、验证当作没有凭据，签发与登记仍拒绝；复跑 8/8。

**第 2 阶段：等决定后做**

| # | 事项 | 依赖 | 产出 |
|---|---|---|---|
| 2a | 权限：`common` 句柄与 `CommonTableMapper`；`platform/perm` 7 张表与网关 `iam` 接入；拆运营角色双写；强制下线改提交后吊销。**已做（2026-11-27）**：6e1b04fd6 句柄与接入、ebbed63c6 角色只在 `sys_role_member`（新增迁移 V721）、9b9740747 强制下线不开 common 事务；见下方「2a 权限」 | D2、D3 | §2 第 1 步 |
| 2b | 事件收敛：待投递表随业务库；删 `Source` 枚举与 `inv_` 前缀；支付权益、积分、商城内核三套并入 | D1 | §2 第 5 步；需先改设计文档 |
| 2c | 审计并入范围按 D4 落地 | D4 | — |

**第 3 阶段：服务器上做**

| # | 事项 | 依赖 |
|---|---|---|
| 3a | RabbitMQ 启用 `rabbitmq_consistent_hash_exchange`、核对 `neargo_app` 权限，跑 `RabbitMessageBrokerIT` | D5 |
| 3b | `-P opskit` 对真实客户端编译，跑 `OpsKitReconcileJobTest`、`OpsKitApprovalFlowTest`、`ScheduledJobConventionTest` | 能拉到 `opskit-approval-client` |
| 3c | 设 `SHOP_EVENTS_TRANSPORT=rabbitmq`、`SHOP_RABBITMQ_PASSWORD`，观察已切的 5 个域（看死信队列与发件箱 FAILED） | 3a、D7 |
| 3d | 定时作业跑起来后：清单 A7–A11 改 MQ 直发；物流“卡住数”改读死信 | D6、3c |

**第 4 阶段：各业务模块自定（不在本会话）**

餐饮（统一模式处理者，基础能力已支持）、商城渠道源各自决定哪些事件进名单、怎么分组；规则见 ADR-067“一个消费者收的类型全进或全不进”。

**每项的完成标准**：先写失败测试再改；相关测试与 `ArchitectureTest`、`BrokerIsolationTest` 通过；
功能清单与本节状态同步更新；按模块提交、`git push --no-verify origin v2`。

#### 1f 撤中转（会话丙，2026-11-27）

调用链与事务边界逐处核过，结论与做法：

- **PAY 权益（C1、C2）**：PAY 发件箱 `pay_ent_outbox` 在支付库，用独立连接池与 `payTxManager`（`PayDataSourceConfig`：跨域事务刻意写不出）。
  所以源头**不能**在支付事务里直接写平台发件箱 —— 那会先于支付提交落库，支付回滚时下游收到不存在的事实；也**不能**去掉 `pay_ent_outbox`，
  否则“支付提交后、发出前”崩溃即丢。它正是 ADR-067 决策 4 说的“产生它的业务库里的待投递记录”，`PayEntitlementOutboxRelay` 是它的中转程序。
  撤掉的是第二跳：新增 `OutboxEventBus.forward(eventNo, event)`，名单内类型（13 个权益/旧券类型已在默认名单）**直发中间件**、确认即返回，
  不再先落 `sys_outbox` 再由平台投递器转一次；不在名单（含 `transport=local`）时同 `publish(eventNo, event)` 落平台发件箱，进程内投递仍靠它。
  内嵌部署删掉 `LocalPayEntitlementEventBridgePortImpl`，`EntitlementRuntimeRouting` 直接把 `events::forward` 交给中转；
  独立部署的 pay-svc 没有平台库，仍经 HTTP 推到 `EntitlementEventReceiverServiceImpl`，接收端（内部调用校验后）同样 `forward`。
  原事件号全程不变，下游三个消费者本就按它核对；顺手修掉中转测试构造器里 `bus.publish(event)` 另发新号的写法。
  不丢：中间件确认或平台行落库才标 `SENT`，否则 PAY 侧照原退避重发；不重：重发同号，中间件路径下游按事件号幂等，落库路径 `insertIfAbsent` 去重。
- **进销存回写（C3）**：`PlatformInventoryEventSink` 原来每投一次 `publish(event)` 新发一个号，`inv_outbox` 在标 `SENT` 前失败重投会在平台侧多出一条。
  改为 `forward("INV-" + 原号, …)`：同样名单内直发中间件、否则落平台发件箱按号去重。加 `INV-` 是因为两边的键都是 `EVT` + 时间、格式相同。
- **包装转发（C4）**：`PrintingConfiguration` 的 `printingOutboxPublisher` 已随 cf7eea13b 删掉。
  `FoodWriteGuardConfiguration.foodOutboxPublisher` **不是纯转发**：它按 `FoodOutboxRouting.ARCHIVE_ONLY` 丢弃只存档类型、并要求统一 `PLATFORM` 源已注册，
  是餐饮的路由决定，归餐饮模块（第 4 阶段），**未删**。
- `DualWriteStockPort`（F3）迁移期保留，不在本项。

#### 1g 接口级防重：要保留的语义与统一端口（会话丙，2026-11-27）

现状：`IdempotencyService` + `sys_idempotent`，生产代码 47 个文件（主干 `shop-core` 9、`shop-merchant` 3、装配 2、美业 5、餐饮 24 个服务 + 4 个配置），
调用约 100 处（`execute` 69、`findCompleted` 33），含下单 `POST /mp/order`、定价发布/税设置/手工调价、门店资源/排班/人员、餐饮全部配置写入。

迁移后必须逐条保持（每条要有测试）：

| # | 语义 |
|---|---|
| S1 | 同键 + 同端点 + 同人：只执行一次，之后返回**首次结果原样**（`result_json` 反序列化），不再执行 |
| S2 | 同键、请求内容不同（`requestHash` 不同）→ `CONFLICT` |
| S3 | 同键、换了人 → `NOT_FOUND`（不泄露别人的结果） |
| S4 | 首次还没完成时的并发重复 → `ConcurrentRequestException`，绝不放行第二次 |
| S5 | 不带键 → 不防重，直接执行 |
| S6 | 回执与业务写在**同一事务**：业务回滚则回执回滚 |
| S7 | `findCompleted`：只读已完成回执，不占位、不加锁 |
| S8 | 回执写入时记 `expire_at` = 24 小时后。**但现在没有任何清理，重放也不看 `expire_at`，回执实际永久有效**；`PricingSeedServiceImpl` 用确定键 `PRICING_SEED:<版本>`，正依赖这一点防止种子重跑。迁移保持“永久有效”，不加清理 |

`EventIdempotency` 不能直接当“统一防重”：它的键是事件号 + 处理者、不存结果、永不过期（类注释写明刻意不合并：24 小时过期会清掉事件记录，积压重投时同一笔钱记两遍）。

**冲突**：[平台基础库-表清单与说明](design/平台基础库-表清单与说明.md) §6.9、§7.6（用户 2026-11-25 定）是“请求防重不建表、`sys_idempotent` 退役，
靠业务表唯一键，重复请求只能重读业务数据拼返回值”。照它改，S1–S4、S7 要逐接口重做：每张会被重复提交的表加来自请求的唯一键（迁移，契约档 1），
每个接口写“按键重读并重建响应”，原始响应与 24 小时语义都没有了；餐饮、美业的接口还归各自模块。见 §3 Q11。

**已定 A（用户 2026-11-27）**，落地：

- 端口 `ai.neargo.shop.spi.idempotency.RequestIdempotencyPort`（`shop-base`）：`execute`（带/不带请求摘要）、`findCompleted`、`ConcurrentRequestException`，语义即 S1–S8。
  唯一实现 `shop-store-mybatis` 的 `idem.port.RequestIdempotencyPortImpl`（`ArchitectureTest` 要求端口实现在 `.port` 包），逻辑原样搬自 `IdempotencyService`；
  回执表仍是 `sys_idempotent`（在基础能力的 `idem` 包里，随 2a 的 `common` 句柄一起换库）。迁移期 `IdempotencyService` 只是转发给实现，最后一批迁完即删。
- 调用方只依赖端口；守卫 `RequestIdempotencyPortGuardTest` 扫描后端与插件的 `src/main/java`，与 `backend/known-idempotency-service-refs.txt` 比，引用 `IdempotencyService` 的文件只准变少。
- 分三批：主干（`shop-core`、`shop-merchant`）→ 美业 → 餐饮；只换类型与装配，不动各模块的业务逻辑与键、端点、摘要的取法。
  **已完成**：端口 + 主干 18e27062a、美业 2821a4f2f、餐饮 288d997c9、删转发器 260735a0b；守卫名单已清空。
- 清理不加（见 S8）。将来要清，须先逐个端点核对是否有确定键依赖永久回执。
- **2a 换库时的约束**：S6 要求回执与业务同一事务。回执表换到独立的基础能力库后，同一事务不再成立，届时要另定（例如回执先于业务单独提交、业务失败时删回执），不能静默放弃 S6。

#### 1b 字典（会话乙，2026-11-27）

档位：1（新增 spi 端口、删 spi 上的常量与方法；不动库表、端点、权限码、i18n）。

**读写面盘点**（`backend/` 主工程；`backend/commerce` 与 `backend-plugins` 另算）：

| 旧件 | 写 | 跨域读 | 所在 |
|---|---|---|---|
| `sys_industry`（7 行） | `IndustryServiceImpl`（运营端） | `MasterDataPort.industryKnown` ← `MerchantScopeFactsPortImpl` | `shop-core` platform |
| `sys_store_industry`（35 行） | `StoreIndustryAdminServiceImpl`（运营端） | `MerchantQueryPort.industries()` / `IndustryRow` ← `BizMerchantController`、`BizFoundationController`、`OpsStoreSetupQuery`；域内 `MerchantPortImpl.validateInitialStore` | `shop-merchant` dictionary |
| `sys_business_type` | 商城 `SysBusinessTypeRepository` | 商城自己；商城新代码已读 `sys_dict_item`（`BUSINESS_TYPE`） | `backend/commerce`，商城库 |
| `sys_pay_channel` | `PayChannelMasterServiceImpl`（支付数据源） | `PayChannelMasterPort.channelName` ← `OrderServiceImpl`、`MerchantPaymentServiceImpl`×2 | `pay-channel` |
| 渠道常量 | — | `PayChannels.FREE`、`FreePayGateway.CHANNEL`（转引 FREE）、`MerchantQueryPort.CHANNEL_FREE_PAY_CHANNELS`、`StoreOrderSettlementServiceImpl.CHANNEL_OFFLINE`、`TestPayGateway.CHANNEL` | 散在 4 个模块 |
| `cim_provider` | 渠道接入插件 | 插件自己 | `backend-plugins/shop-commerce-integration` |

**哪些是同一概念**（按设计 `sys_dict_item` 的字典类型判，不按行数或引用数）：

| 字典类型 | 并入的旧件 | 判定 |
|---|---|---|
| `BUSINESS_TYPE` 经营类型与子行业 | `sys_store_industry`、`sys_business_type` | 同一棵树：行业行即经营类型（FOOD/RETAIL/SERVICE），子行业挂在下面并指明经营类型。两张表是同一概念的重复建模 |
| `ONBOARD_INDUSTRY` 进件行业 | `sys_industry` | **不同概念**：码是 CATERING/LIFE_SERVICE…，每行挂小微准入、积分强制，是通道准入口径（V360 注释明说不能为意向加行）。与经营类型不并，单独一个类型 |
| `PAY_CHANNEL` 支付渠道 | `sys_pay_channel` + 散落的渠道码常量 | 同一概念：表是运营维护的渠道，常量是代码里认得的码。`FREE/OFFLINE/STUB/TEST` 不进表（不是运营可开关的渠道），收成 `PayChannels` 一处 |
| `CHANNEL_PRODUCT` 渠道产品 | `cim_provider` | **不同概念**：销售渠道方（外卖、电商平台），与支付渠道无关。在插件工程里，本次不动 |

**做法**：

- 端口 `spi.dict.DictionaryPort`：`items(DictType)` 返回 `DictItem`（形状对齐 `sys_dict_item`：租户、类型、编码、上级、层级、多语言名、排序、启停、属性），
  另有 `item`、`known`、`nameOf` 与经营类型树的视图 `storeIndustries()`（原 `MerchantQueryPort.IndustryRow` 移来，改名 `IndustryRow`）。
- 唯一实现 `platform.port.DictionaryPortImpl`（`shop-core`）。三张旧表分在三个模块、支付那张还在独立数据源，
  所以实现按类型汇集 `spi.dict.DictionarySource`：现在每个旧表的属主各出一个（`OnboardIndustryDictionarySource`、
  `StoreIndustryDictionarySource`、`PayChannelDictionarySource`），同一类型两个来源启动即失败。
  **迁移路径**：基础能力库落地后换成一个读 `sys_dict_item` 的来源，三个旧来源删掉，调用方不动。
- 调用方改走端口：`MerchantQueryPort.industries()`、`IndustryRow`、`MasterDataPort.industryKnown`、
  `PayChannelMasterPort.channelName` 删掉。`PayChannelMasterPort` 余下的“可下单通道 = 开着 ∩ 有网关实现”是支付业务判断，不是字典，留在原处。
- 常量：`MerchantQueryPort.CHANNEL_FREE_PAY_CHANNELS` 移成 `PayChannels.NO_CHANNEL_ACCOUNT`，`PayChannels` 补 `OFFLINE`、`TEST`、`STUB`；
  `FreePayGateway.CHANNEL`、`TestPayGateway.CHANNEL`、`StoreOrderSettlementServiceImpl.CHANNEL_OFFLINE` 三个别名删掉，直接用它。
- 不在本项：`sys_business_type`（商城库、商城会话）、`cim_provider`（插件工程）；运营端维护两张旧表的写入口不动（写仍归属主，读走端口）。


#### 1b 续：字典组的守卫基线（会话甲，2026-11-27）

档位：0（不动库表、端点、权限码、i18n、配置；spi 上 `DictItem` 加一个属性键常量）。

- **守卫误报**（cff9f3a8e）：旧表名 `pay_channel` 同时是订单、收款记录的「付款渠道」列，`@TableField("pay_channel")`、取列值都被当成访问；
  任务说明、错误提示里写到表名也被算。守卫改为只认 SQL 表位置（FROM/JOIN/INTO/UPDATE/TABLE，相邻字面量拼起来看）和整段就是表名的字面量，
  `pay_channel` 只认表位置。去掉 15 条误报。
- **属主**（900f51439）：1b 定了「写归属主、读走端口」。每张旧表的属主就是它的维护入口，登记为字典组实现：进件行业 `IndustryServiceImpl`、
  主体类型 `MasterDataServiceImpl`、区划 `RegionService`/`RegionServiceImpl`、授权码与经营类型 `shop-merchant` 的 `merchant/dictionary` 包、
  支付渠道/产品 `pay-channel` 的 `master` 包、`PayChannelMasterPortImpl`、`DbChannelProductCatalog`、`ChannelPipelineConfiguration`、`ChannelMappers`，市场 `MarketServiceImpl`。
  区划 66 万行只能按层级懒加载，不进 `DictionaryPort.items()`，由 `RegionService` 专管。基础能力库落地后，来源换成一个读 `sys_dict_item` 的，属主收成一个维护入口。
- **读方改道**：主数据快照的进件行业改读 `DictionaryPort`（`OnboardIndustryDictionarySource` 带 `DictItem.ATTR_MICRO_ALLOWED`、按排序给出）；
  `BizRegionController`、`MpCatalogController`、`MasterDataPortImpl` 改用 `RegionService.LEVEL_*`，`EstateCacheServiceImpl` 回填坐标改调 `RegionService.recordCoordinate`；
  结算币种、积分资金适配改用 `PayChannelMasterService.brief` 返回的 `ChannelBrief`（不暴露实体）；`DevSeeder` 授权码种子改经 `AuthCodeAdminService`。
- **不动的 19 处**：渠道接入插件 13（`cim_provider`/`cim_product_profile`，插件工程自管，1b 已判为另一字典类型）；商城 `sys_business_type` 2（商城库，商城新代码已读 `sys_dict_item`）；
  支付产品的三个读方 `RouteSelectionPolicy`、`PayMerchantBackfillImpl`、`OnboardingServiceImpl`（商户产品按 `pay_channel_product.id` 关联，并进 `sys_dict_item` 前要先定产品编码与引用方式，属支付设计）；
  `OpsPayChannelAppServiceImpl`（运营端渠道维护拿实体组装视图，改视图要一并改维护接口）。

#### 2a 权限（会话甲，2026-11-27）

档位：1（新增库表迁移 V721、新增配置项 `shop.modules.common.datasource`；端点、权限码、i18n 不动）。

- **句柄**：`ModuleDataSources.COMMON`，三个 bean 名 `commonDataSource`/`commonSqlSessionFactory`/`commonTxManager`，另有 `commonJdbcTemplate`。
  不配 `shop.modules.common.datasource` 时都是主库的别名，行为与之前一个字节不差。标记 `CommonTableMapper`：全局扫描排除，`ModuleMapperScanConfig` 按标记收进 common 工厂。
- **权限表接入**：`PermMappers` 五个 Mapper 打标记；`PermConfigServiceImpl`、网关 `JdbcResourceStore` 的事务点名 `COMMON_TX_MANAGER`（`ModuleTxManagerConventionTest` 加了这两处）；
  `JdbcResourceStore`、`FunctionModuleSync` 直连时取 common 数据源（网关按端配置了自己的库时仍用那个，不变）。
- **拆角色双写**：新增 `platform.perm.OpsStaffRoles`，是运营账号角色唯一的读写入口（`sys_role_member`，按授予顺序）。读方 `OpsServiceImpl`（登录、me、账号列表、启停、免二次验证、数据域）、
  `OperatorIdentityLoader`、`StaffIdentityResolver`、`OpsStaffPortImpl` 全部改读它；写方 `setStaffRole`、`setStaffRoles`、`createStaff`、`DevSeeder` 只写它，`sys_ops_staff.roles` 停写（列不删）。
- **库表变更 V721**：`V721__align_ops_role_members.sql` 在切换前把 `sys_role_member`（OPS 端、未删除）对齐到 `sys_ops_staff.roles`：JSON 里有而成员表没有的补上（`granted_by='V721'`），
  成员表有而 JSON 没有的逻辑删除；JSON 不合法或为空按无角色。此前判权以 JSON 为准，对齐后每个人的权限与切换前相同；多出来、只影响菜单的成员行会被撤掉。
  已在本机 MySQL 9.7.4 临时库实跑。**迁移号**：另一条分支 `origin/codex/v2-member-marketing` 自用了 V706–V720，其中 V706 与 v2 上的 V706 撞号，它合入 v2 时要改号；本条取 V721 以免再撞。
- **强制下线**：`forceLogoutRole` 在基础能力库只读成员，写的是平台基础库的运营会话，审计已提交后写（1i），所以不再开 common 事务。
- **K3 已做（2026-11-27）**：建号 = 平台库事务写账号 + 基础能力库独立事务（`REQUIRES_NEW`）写角色；账号这边随后失败（如开通邮件发不出去）就在独立事务里撤回角色，撤回失败只记日志、不盖住原错误。改角色（`setStaffRole`、`setStaffRoles`）只写基础能力库，事务点名 `commonTxManager`。提交前的提交失败（MyBatis 写入即执行，约束错在插入时就抛）不在补偿范围内。测试 `OpsStaffCrossStoreFlowTest`（邮件失败不留孤儿角色、建号两边都写、改角色只开 common 事务）。

#### 1e 审批（会话乙，2026-11-27）

档位：1（新表 `sys_approval`、`ord_manual_price_adjustment` 删四列、新内部端点；V705 带 DDL，166 需 root 先执行）。Q12–Q14 已按建议定（2026-11-27）。

**四条审批逐条看**（设计：重审批走独立的审批与工单服务 OpsKit，业务表只存审批单号；店内快速审批是基础能力库的轻量审批 `sys_approval`）：

| 审批 | 现在怎么做 | 谁批 | 节奏 | 落点 |
|---|---|---|---|---|
| 门店审核（开店） | `StoreOperationalReviewServiceImpl` 已走 `ApprovalPort`（OpsKit 未启用时走原流程） | 运营 | 异步 | **已收敛**，不动 |
| 支付两人复核 | `pay_approval` + `pay.approval.ApprovalService`，只有退款复核在用（`RefundServiceImpl.open/review`），运营端 `OpsPaymentController.reviewRefund` 调两次 | 运营，申请人、一审、二审三人互不相同（服务层 + 表 CHECK） | 同步逐步 | 设计定为退役、归 OpsKit（清单：`pay_approval` “由独立的审核与工单服务承担”）。做法同已接入的结算放款：网关 `paybridge.approval` 加 `PayRefundReviewApprovalSubject`（三级·资金，固定两步），退款建单后在平台事务里送审，生效调支付侧“按审批结果放行/驳回” |
| 储值活动审批 | `pmt_sv_approval`（`StoredValueActivityJournal`），B 端管理层在 App 里批 | 商户管理层 | 同步 | 审批人不是运营员工，OpsKit 的提交人/审批人是运营员工号 —— 现有 `ApprovalPort` 承载不了 |
| 改价 | `ord_manual_price_adjustment.approval_mode`：店主发起即批准，店员发起由店主复核 | 店主 | 店内即时 | 设计明说归轻量审批 `sys_approval`（“退菜、改价、退款、现金收支这类店内快速审批”），不是 OpsKit |

**结论**：“以 `ApprovalPort` 为准收成一个端口”只对运营侧审批成立（门店审核已是，支付两人复核可以并进来）。
储值活动审批与改价是商户侧审批，要么 OpsKit 支持商户侧审批人，要么由轻量审批承接 —— 后者就是第二个端口，与“一个端口”冲突。

**支付两人复核并入 `ApprovalPort`**（已做，a24af565d）：

1. 支付侧 `RefundService` 加“审批结果回写”（按退款单号放行或驳回，幂等，与 `review` 共用状态迁移），`PayOpsInternalApi` 加对应内部端点（独立部署时网关经它调）。
2. 网关加 `PayRefundReviewApprovalSubject` 与补送任务（同 `SettleApprovalSubmitter`：待复核却没有审批单的，重新送）。
3. 主题启用后，退款建单不再开 `pay_approval`；未启用时保持现状。存量 PENDING 的 `pay_approval` 走完旧流程后，删 `ApprovalService` 与 `pay_approval`。

落地要点：支付读审批服务自己的两个开关（`RefundReviewRouting`，`shop.opskit.enabled` + `enabled-subjects` 含 `shop.pay_refund_review`），不另设开关；
`ApprovalSubject.separateApprovers()` 新增（默认否），退款复核为是，`OpsKitProcessRegistrar` 据此登记 `selfApproval=false, crossStepExclusive=true`；
送审提交人 = 退款申请人；审批未通过（驳回、撤回、放弃）一律驳回退款 —— 待复核的退款不能无限挂着；回写对重投幂等（放行后已执行也算已生效）。
内部端点 `POST /internal/pay/ops/refunds/{payRefundNo}/approval-outcome`（独立部署时网关经它调）。

**轻量审批端口**（已做，5793cfc9d）：`spi.approval.LightApprovalPort`，唯一实现 `platform.port.LightApprovalPortImpl`，表 `sys_approval`（V705，字段照基础能力库字段设计，
另加“租户 + 对象 + 修订”唯一键作送审幂等）。每个方法一条写语句、不依赖调用方事务：业务库与本表可能不同库，业务回滚留下的审批单读不到业务对象，不会误放行。
审批人不得是申请人；过期不批；同一人同一结果可重放。

**改价改走轻量审批**（已做，5793cfc9d）：`ord_manual_price_adjustment` 删 `status`、`approval_mode`、`approved_by`、`approved_at`（V705，线上 0 行），
实体上这四个改成非持久字段，读出时由服务从审批单填上（`OWNER_ORIGINATED` ↔ `AUTO`，`OWNER_REVIEW` ↔ `MANUAL`），计价快照与校验照旧。
店主自己改价 = 自动批准；店员改价 = 待店主批。改价行批准后不再改写。

**储值活动审批：不并入 `sys_approval`**（与 Q13 的建议不同，偏差）：设计清单里 `pmt_sv_approval`、`pmt_sv_release_link` 归“储值迁到会员营销”，
并入 `ent_template(+version)` —— 活动审批会变成卡模板版本的发布状态，不是独立的审批单；而且它的列表按管理层、范围类型、品牌筛选，`sys_approval` 没有这些列。
所以它随储值迁出一起处理（另一条线），本项不动。Q17 已定（2026-11-27）：认可。

**待定**：

| # | 问题 | 建议 |
|---|---|---|
| Q12 | OpsKit 审批默认“一律可以本人审批”，而支付退款复核要求申请人、一审、二审三人互不相同。退款复核切到 OpsKit 时，OpsKit 要不要强制同一流程内不同人（**已定 2026-11-27：照建议**） | 要：资金类（三级）在 OpsKit 流程上设“审批人不得为提交人且两步不同人”；做不到就退款复核暂不切 |
| Q13 | 储值活动审批（商户管理层批）归哪（**已定 2026-11-27：照建议；落地见上方偏差与 Q17**） | 归轻量审批 `sys_approval`，与改价一起；不走 OpsKit |
| Q14 | 轻量审批要不要和 `ApprovalPort` 合成一个接口（**已定 2026-11-27：照建议，Q12、Q13 同**） | 不合：两者的审批人、节奏、存储都不同；`ApprovalPort` 管运营侧，另设轻量审批端口管店内。若坚持一个端口，就在 `ApprovalPort` 上加“同步裁决”一族方法，由两个实现按主题分派 |

#### 1h 开放应用（会话乙，2026-11-27）

档位：1（密钥模块 `sec_key_version` 加列，`db/secret` V3；运营接口密钥改存密钥库）。Q15、Q16 已按建议定（2026-11-27）。

**设计归属**（[基础能力库表说明](design/数据库设计/库/02-基础能力库.md) §2、清单复核 §“接入应用、密钥与支付运营权限的归属”、`db-split-manifest.csv`）：

| 旧表 | 去向 | 线上行数 |
|---|---|---:|
| `pay_app`、`pay_app_grant` | 基础能力库 `sys_open_app`、`sys_open_app_grant`（应用身份与授权）；密钥已在密钥库 `sec_key(PAY, PARTNER_APP, pay_app.id, INBOUND_HMAC)`（W5 AC12、开放 API 密钥迁入运维手册） | 2 |
| `inv_open_credential` | 应用身份并入 `sys_open_app`；凭据本身进密钥库 `sec_key` | 0 |
| `sys_ops_api_key` | 密钥库 `sec_key`（属于某个运营账号的一把密钥），不进基础能力库 | 0 |

所以开放应用**一半在基础能力库**（应用与授权，表已在设计里）、**一半在密钥库**（凭据）。本项不在基础能力库落表，做的是统一的凭据访问接口与迁移路径。

**三套凭据现状**：

| | 存什么 | 怎么验 | 读写面 |
|---|---|---|---|
| 支付 `pay_app` | 应用；密钥在 `sec_key`（或旧的部署配置，`shop.pay.open.key-source=config\|secret`） | HMAC 签名，需要**可还原的**密钥材料（`KeyRegistry.materialOf`） | `pay-domain` openapp、`pay-guard` 共约 22 个主代码文件 |
| 库存 `inv_open_credential` | `app_key` + `app_secret_hash` | 比对哈希（一次性看见原文） | `inventory-domain` 4 个、`shop-inventory` 运营端 1 个、`inventory-svc` 1 个 |
| 运营 `sys_ops_api_key` | `secret_hash` | 比对哈希，再关联运营账号状态 | `shop-core` platform 5 个、网关 3 个 |

**方案**：

- 统一接口（拟 `ai.neargo.secret.api.CredentialVerifier`，放密钥模块 `secret-api`，因为凭据归密钥库）：
  `issue(owner, purpose, label, validDays)` 返回一次性明文；`verify(namespace, presented)` 返回所有者；`revoke`；`list(owner)`。
  所有者用现有 `KeyOwner(namespace, ownerType, ownerId)`：支付 `PAY/PARTNER_APP`、库存 `INVENTORY/PARTNER_APP`、运营 `PLATFORM/OPS_STAFF`。
- 密钥库现在只存可还原的材料（HMAC、加密要原文）；API Key 类凭据只需要哈希。要么 `sec_key` 加“只存摘要”的存储档（`StorageTier`），要么 API Key 仍按可还原材料存。
- 迁移路径：库存、运营两套线上 0 行，不搬数据 —— 接口上线后旧表停写、新发的走密钥库，旧表在一个发布周期后删（新迁移）。
  支付已在密钥库，只把应用身份从 `pay_app` 换到 `sys_open_app`，随基础能力库换库（第 2 阶段，依赖 Q2）。

**待定**：

| # | 问题 | 建议 |
|---|---|---|
| Q15 | API Key 类凭据（库存开放凭证、运营接口密钥）进密钥库时只存摘要还是存可还原材料 | 只存摘要：`sec_key` 加一档 `StorageTier.DIGEST`，`materialOf` 对它拒绝；泄露库也拿不到可用密钥 |
| Q16 | 运营接口密钥 `ownerId` 是数字，而运营账号键是 `staff_no` 字符串；`KeyOwner.ownerId` 用运营账号的数字主键还是改成字符串 | 用 `sys_ops_staff.id`；`KeyOwner` 不改 |

**已做（2026-11-27，601e6a1b8）：运营接口密钥改走密钥库**

- 统一接口 `ai.neargo.secret.api.CredentialVerifier`（实现 `secret-core` 的 `DigestCredentialVerifier`）：`issue`（返回一次性明文）、
  `register`（只交摘要的自助登记，幂等）、`verify(namespace, 出示串)`、`list(owner)`、`revoke`、`revokeAll`。
  摘要 = 含前缀整串 UTF-8 的 SHA-256 小写十六进制（与 `TokenHash` 一致）；用途固定 `KeyPurpose.INBOUND_API_KEY`（新增 `Usage.API_KEY`）。
- 存储档 `StorageTier.DIGEST`（Q15）：`KeyRegistry.materialOf` 对它抛 `WRONG_USAGE`。`db/secret` V3：`sec_key_version` 加 `material_digest`、
  `credential_key`、`label`、`scope` 与两个唯一键，`ck_sec_key_version_1` 放宽为“密文、公开材料、摘要至少一个”；凭据号存 `external_key_id`。
- 所有者 `KeyOwner(PLATFORM, "OPS_STAFF", sys_ops_staff.id)`（Q16）。同一账号可同时有多把：每把是所有者那把 `sec_key` 下一个 DIGEST 版本、各自 ACTIVE，
  不占“一把密钥一个 ACTIVE 版本”的名额。
- `OpsApiKeyServiceImpl`、`OpsApiKeyStore` 改走接口；认证仍核运营账号状态（ACTIVE、未删、不需改密）。对外端点与 JSON 不变。
  `sys_ops_api_key` 停写停读（线上 0 行），删表留后续迁移。
- **部署前提**：运营接口密钥依赖密钥模块，进程须配 `shop.secret.env`（DIGEST 档不需要主密钥）；未配时 `oak_` 认证会 500。上线前确认。
- `scripts/gen-test-schema.py` 支持 `ALTER TABLE … DROP CONSTRAINT`（V3 要在 H2 上直接跑，`DROP CHECK` 不通用）。

**已做（2026-11-27）：库存开放凭证改走密钥库**（Q18 照建议）

- 所有者：业主凭证 `KeyOwner(INVENTORY, "INV_OWNER", inv_owner.id)`，系统凭证 `KeyOwner(INVENTORY, "INVENTORY_SYSTEM", inv_client_system.id)`。
- 凭据号 = `appKey`（`AK…` 开放接口、`SK…` 服务），`credentialId` 现在等于 `appKey`；secret 由密钥模块生成，格式 `ivk_` + 43 位 Base64URL（原 64 位十六进制）。
  验证按 secret 找凭证再核对凭据号即请求里的 `appKey`，四种失败仍同一个错。
- `scope` 编码：分号分隔 `scopes=…;caps=…;owner=<owner_id>;system=<system_code>`（没有的省略）。
  电商、设备两套库存各有库、主键都从 1 起、共用一个密钥库，所以认证、列出、按号查、吊销都再核对本库那一行的 `owner_id` / `system_code` 与 scope 一致，对不上当不存在。
- `inv_open_credential` 停读停写（线上 0 行，上线后重新签发），实体与 Mapper 删掉，表待后续迁移删。
- `CredentialVerifier` 新增 `find(namespace, ownerType, credentialNo)`（含已吊销）。
- `inventory-svc` 自带密钥库数据源 `InventorySecretConfig`（不跑迁移，迁移归 shop-app），配置项 `inventory.secret.env`、`inventory.secret.datasource.url/username/password/max-pool-size`、
  `inventory.secret.flyway-enabled`（默认关，仅测试）；口令只从 `INVENTORY_SECRET_DATASOURCE_PASSWORD` 来、无默认值。嵌入部署用 shop-app 的 `DigestCredentialVerifier`。
- 偏差：`lastUsedAt` 恒为空（密钥库只有按所有者的最近使用，不按张；不为它建表），认证不再写库；不给期限按 100 年存；签发已过期的期限返回 400；
  `OpenApiCredentialService.issue/revoke` 无操作人参数，记 `SYSTEM`。
- **部署前提**：`inventory-svc` 须配密钥库连接（指向 ai-shop 主库，只授 `sec_key`、`sec_key_version`、`sec_key_event`、`sec_access_log` 的读写）与 `INVENTORY_SECRET_ENV`（与 shop-app 的 `shop.secret.env` 一致），否则起不来或验证一律不过。

## §3 待你定

| # | 问题 | 建议 |
|---|---|---|
| Q1 | 按 §1.4 先删掉 E、把 D 改成提交后再发、把 A 改成直接调用之后，剩下需要可靠投递的那部分，待投递记录放哪：放在产生它的业务库（门店交易库、平台服务库、行业库各一张，同一份投递代码）；还是只放基础能力库、改成提交后写入（会丢） | 放在产生它的业务库，投递代码只有 `shop-store-mybatis` 一份；基础能力库只放投递结果与死信。这与设计“公共事件只在基础能力库”的定稿不同，要改设计 |
| Q1a | 只存档的那批事件，存档行有没有人读 | 没人读就整批删掉 |
| Q2 | 公共能力是新建一个 `common` 句柄，还是先挂在主库句柄下 | **已定（2026-11-27）：照建议**。新建句柄，未绑定时指向主库，同平台基础库 |
| Q3 | `ops_staff.roles` 何时停写 | **已定（2026-11-27）：照建议**。随第 1 步（2a 权限）一起停 |
| Q4 | 基础能力库设计评审 R1–R6（事件号长度、`sys_dict_item` 与 `sys_sequence` 的唯一键等），以及是否授权改 61 上已建的表 | 先在本机测试库的补充脚本里改，61 不动 |
| Q5 | 各域自有的操作日志（员工、餐饮、美业、打印、库存）是否并入统一审计 | 不并：它们是业务流水；只有“谁在运营端改了什么”进统一审计 |
| Q6 | PAY 还要不要支持单独部署（决定活动权益、储值充值能否直调） | 不支持则直调；支持则走 ③ |
| Q7 | 打印失败要不要挡住开票 | 不挡：发票打印留在 ③ |
| Q8 | 报表脏日丢一次能否靠每日重算兜底 | 有重算就降到 ②，十几个事件的跨库部分随之变少 |
| Q9 | 是否引入 MQ（已定 RabbitMQ 4）；生产由谁部署（.80 / UME 166 目前没有） | 引入，按 §1.5 默认 `local`；见 ADR-067（提案，待确认）。部署负责人待定 |
| Q11 | **已定 A（2026-11-27）**，见 §2.2 1g；清理按 S8 修正为不加。接口级防重（1g）怎么统一：A. 做成基础能力的一个端口（语义即 §2.2 1g 的 S1–S8），回执存基础能力库一张请求回执表、24 小时清理，设计 §6.9 的“不建表”改为“不在业务库建表”，调用方迁移只是换端口；B. 照设计定稿逐接口加业务唯一键、重复时重读业务数据重建响应 | A：约 100 处调用机械可分批、对外语义不变；B 要约 40 个接口所在文件各加迁移与测试，原始响应重放与 24 小时语义消失，餐饮/美业要各模块做 |
| Q17 | 1e：储值活动审批按设计清单随储值迁到会员营销、并成卡模板版本的发布状态（不进 `sys_approval`），是否认可 | **已定（2026-11-27）**：认可；Q13 的“归轻量审批”作废，储值活动审批随储值迁出那条线处理，1e 不再动它 |
| Q12 | 1e：退款两人复核切到 OpsKit 时，OpsKit 要不要强制“审批人不得为提交人、两步不同人”（支付现行规则；OpsKit 默认可本人审批） | **已定（2026-11-27）**：要 |
| Q13 | 1e：储值活动审批（商户管理层批）归哪 | **已定（2026-11-27）**：归轻量审批；但见 Q17 |
| Q14 | 1e：轻量审批（店内、商户侧）要不要和 `ApprovalPort` 合成一个接口 | **已定（2026-11-27）**：不合，另设 `LightApprovalPort` |
| Q15 | 1h：API Key 类凭据（库存开放凭证、运营接口密钥）进密钥库只存摘要还是可还原材料 | **已定（2026-11-27）**：只存摘要 |
| Q16 | 1h：运营接口密钥的所有者用 `sys_ops_staff.id`（`KeyOwner.ownerId` 是数字）还是改 `KeyOwner` 收字符串 | **已定（2026-11-27）**：用 `sys_ops_staff.id` |
| Q18 | 1h：库存独立服务 `inventory-svc` 的凭据怎么接密钥库：给它装一套指向同一密钥库的 secret 数据源，还是经 shop-app 的内部验证端点；库存所有者映射成什么 | **已定（2026-11-27）**：照建议 —— 装 secret 数据源（密钥库本就是共享库，验证在本地少一跳）；所有者用 `inv_owner` 数字主键，系统凭证用 `owner_type=INVENTORY_SYSTEM`；`appKey` 作凭据号、capabilities 进 scope；存量 BCrypt 凭证 0 行，重新签发 |
| Q10 | 商户来单通知（`SUB_ORDER_PAID`）改成提交后发、不落发件箱，接受“提交后、发送前进程崩溃漏一次” | **已定（2026-11-27）**：统一走 MQ，来单属 MQ 直发 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 跑过 |
|---|---|---|
| K1 | `CommonTableAccessTest`（基线未清零前只保证不新增越界） | 2026-11-27 通过 1/1 |
| K2 | `OpsPermConfigFlowTest`、`OpsStaffWriteFlowTest`、`OpsPlatformConfigFlowTest` 等 | — |
| K3 | `OpsStaffCrossStoreFlowTest` | 3/3 通过（2026-11-27） |
