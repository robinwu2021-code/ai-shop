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

**RabbitMQ 传输已接（2026-11-27，ADR-067 第 3 步）。** `shop.events.transport=local|rabbitmq`（默认 `local`）；`rabbitmq` 下通知类事件提交后发到交换机 `shop.events`，各通知消费者在自己的仲裁队列收，超投递上限进死信。连接配置在 `application.yml` 的 `shop.events.rabbitmq`，地址/虚拟主机/账号有默认值，口令只从 `SHOP_RABBITMQ_PASSWORD` 来。真 broker 集成测试 `RabbitNotificationIT` 待在服务器侧跑。
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

## §3 待你定

| # | 问题 | 建议 |
|---|---|---|
| Q1 | 按 §1.4 先删掉 E、把 D 改成提交后再发、把 A 改成直接调用之后，剩下需要可靠投递的那部分，待投递记录放哪：放在产生它的业务库（门店交易库、平台服务库、行业库各一张，同一份投递代码）；还是只放基础能力库、改成提交后写入（会丢） | 放在产生它的业务库，投递代码只有 `shop-store-mybatis` 一份；基础能力库只放投递结果与死信。这与设计“公共事件只在基础能力库”的定稿不同，要改设计 |
| Q1a | 只存档的那批事件，存档行有没有人读 | 没人读就整批删掉 |
| Q2 | 公共能力是新建一个 `common` 句柄，还是先挂在主库句柄下 | 新建句柄，未绑定时指向主库，同平台基础库 |
| Q3 | `ops_staff.roles` 何时停写 | 随第 1 步一起停 |
| Q4 | 基础能力库设计评审 R1–R6（事件号长度、`sys_dict_item` 与 `sys_sequence` 的唯一键等），以及是否授权改 61 上已建的表 | 先在本机测试库的补充脚本里改，61 不动 |
| Q5 | 各域自有的操作日志（员工、餐饮、美业、打印、库存）是否并入统一审计 | 不并：它们是业务流水；只有“谁在运营端改了什么”进统一审计 |
| Q6 | PAY 还要不要支持单独部署（决定活动权益、储值充值能否直调） | 不支持则直调；支持则走 ③ |
| Q7 | 打印失败要不要挡住开票 | 不挡：发票打印留在 ③ |
| Q8 | 报表脏日丢一次能否靠每日重算兜底 | 有重算就降到 ②，十几个事件的跨库部分随之变少 |
| Q9 | 是否引入 MQ（已定 RabbitMQ 4）；生产由谁部署（.80 / UME 166 目前没有） | 引入，按 §1.5 默认 `local`；见 ADR-067（提案，待确认）。部署负责人待定 |
| Q10 | 商户来单通知（`SUB_ORDER_PAID`）改成提交后发、不落发件箱，接受“提交后、发送前进程崩溃漏一次” | **已定（2026-11-27）**：统一走 MQ，来单属 MQ 直发 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 跑过 |
|---|---|---|
| K1 | 待建守卫 | — |
| K2 | `OpsPermConfigFlowTest`、`OpsStaffWriteFlowTest`、`OpsPlatformConfigFlowTest` 等 | — |
| K3 | 待建守卫 | — |
