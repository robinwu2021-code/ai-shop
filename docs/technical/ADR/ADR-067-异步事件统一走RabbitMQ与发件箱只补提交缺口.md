# ADR-067 异步事件统一走 RabbitMQ 4，发件箱只补“提交与发送之间”的缺口

状态：**提案，待用户确认**（2026-11-27；“异步统一走 MQ”与“用 RabbitMQ 4”是用户当天的决定，写法待确认）
关联：[TDD-基础能力库对齐](../TDD-基础能力库对齐.md) §1.4–§1.5 ·
[异步与通知清单](../基础能力库对齐-异步与通知清单.md) §〇 ·
[ADR-039 Outbox 统一投递与双库适配](./ADR-039-Outbox统一投递与双库适配.md) ·
[ADR-062 服务注册与发现用 Nacos 原生客户端](./ADR-062-服务注册与发现用Nacos原生客户端.md) ·
[ADR-066 业务模块按配置选数据源](./ADR-066-业务模块按配置选数据源.md)

## 背景

所有异步工作今天都走一张发件箱表 `sys_outbox`，进程内投递器逐个调消费者。盘点 155 处发布（TDD §1.4）后看到：

- 约四分之一的发布没有消费者，已删；只发通知的 10 处已改成提交后发（`AfterCommitNotifications`）；同库的 2 处已改成直接调用。
- 默认的旧投递模式下，**一条事件的所有消费者在同一事务里、失败一起重投**。按消费者独立记进度的统一模式（ADR-039）已实现但默认没开。
- 通知消费者只对站内信去重，短信、企微群、邮件、推送、微信订阅每投一次发一次。别的消费者一失败，**商户可能收到同一单最多 10 条来单短信**。
- 拆库（ADR-066）之后，跨库的消费者越来越多；一个进程内的投递器无法横向扩，也不能让别的进程订阅。

用户 2026-11-27 决定：**异步统一走 MQ，用 RabbitMQ 4**，并要求区分“哪些只要 MQ、哪些还要发件箱”。

## 决策

1. **RabbitMQ 4.x，客户端用 Spring AMQP（`spring-boot-starter-amqp`，AMQP 0-9-1）。**
   它是 Spring 官方项目、版本由 Spring Boot 统一管理，不属于 Spring Cloud，不违背 ADR-060、ADR-062 “不引入 Spring Cloud”的约定；
   监听容器、发布确认、手动确认都现成，不必自己写。不用 Spring Cloud Stream。

2. **一个事件只发一条 MQ 消息，按事件类型、以最严格的那个消费者定走法**（逐处清单见异步与通知清单 §〇）：

   | 走法 | 条件 | 怎么发 | 处数 |
   |---|---|---|---|
   | MQ 直发 | 消费者都只是通知；或有按业务表扫描的兜底作业 | 业务事务**提交后**发 MQ，不落发件箱 | 45 |
   | 发件箱 → MQ | 有一个消费者丢了会造成钱、货、权益、出餐上的实际损失，又没有兜底 | 业务事务里写待投递记录，中转程序发 MQ，**收到发布确认**才标记已发 | 57（+ 迁移期 4） |
   | 源头改发 MQ | 现在只是把别处的事件再转发一次 | 撤掉中转，由源头按上两行之一发出 | 8 |

   混合事件不拆：消费者各自一条队列，彼此的失败与重试互不影响。

3. **拓扑**：
   - 一个持久的 topic 交换机 `shop.events`，**路由键 = 事件类型**（2026-11-27 实现时由“每域一个”收成一个：
     事件类型本身已全局唯一，按域分交换机只多一层映射；以后要按域隔离权限再拆）。
   - **每个消费者（现有 `handlerId`）一条仲裁队列**（quorum queue，RabbitMQ 4 默认的复制队列），按它 `supports` 的事件类型绑定。
   - 失败重投用仲裁队列的投递次数上限（`delivery-limit`），超限进该消费者的**死信队列**；退避用“死信 + 带过期时间的重试队列”转回。
   - **要按聚合保序的事件**（库存镜像、发货快照、子单状态、餐饮桌单等，清单里“顺序键”非空的）经**一致性哈希交换机**
     （官方自带插件 `rabbitmq_consistent_hash_exchange`）按聚合号分到固定几条分区队列，每条队列开**单活消费者**（single active consumer）。
   - 消息持久化；发布端开**发布确认**（publisher confirms）与不可路由退回（mandatory）。

4. **发件箱只剩一个用处：补“业务提交”与“发出消息”之间的缺口。** RabbitMQ 的发布确认只保证“发出之后”不丢。
   待投递记录放在**产生它的业务库**，与业务表同一事务写入（TDD §3 Q1）。现在的 `OutboxDispatcher` 从“进程内调消费者”
   改成“发到 RabbitMQ、等确认”；重试、退避、租约沿用现有字段。

5. **消费者代码不改。** 消费端由一个适配器从队列收消息、还原成 `SysOutbox` 信封、调现有的 `OutboxConsumer.consume`，
   成功才手动确认（ack），抛异常则拒绝并按第 3 条重投或进死信。

6. **发布入口只有一个：`OutboxEventBus.publish`。** 由它按“这个事件类型有没有必达消费者”决定落不落发件箱。
   已改的 10 处 `notifyAfterCommit` 改回 `publish`。

7. **传输可切换，默认不变**：`shop.events.transport=local|rabbitmq`，默认 `local`。
   `local` 下，MQ 直发类由进程内线程池提交后投递（现在的 `AfterCommitNotifications`），发件箱类由现在的投递器投递；
   没部署 RabbitMQ 的环境行为与今天一致，也不建连接。

8. **所有消费者按事件号幂等**（RabbitMQ 是至少一次）。通知的外部通道要按“事件号 + 通道 + 收件人”去重，
   去重记录用统一防重能力，不在业务库建技术表。

9. **中间件统一封装，可无缝替换**（用户 2026-11-27 要求）。业务代码、发件箱、提交后通知、订阅编排只认
   `ai.neargo.shop.event.broker` 里与厂商无关的接口：`MessageBroker`（`publish` / `subscribe`）、`BrokerMessage`、
   `BrokerSubscription`（`ordered` = 同一聚合逐条按序）、`BrokerException`。接口注释里写明实现必须满足的五条语义
   （同步确认、至少一次、订阅彼此独立、保序订阅、启动时建资源），换实现时逐条对照。
   RabbitMQ 是其中一个实现，所有 AMQP 代码只在 `ai.neargo.shop.event.rabbit` 包；守卫 `BrokerIsolationTest`
   扫描后端全部模块的 `src/main/java`，AMQP/RabbitMQ 的 import 出现在别处即失败。
   换 MQ 或换成别的异步方案 = 新写一个实现包 + 一个按 `shop.events.transport` 取值装配的配置类，其余代码不动。

## 被否掉的方案

| 方案 | 为什么不选 |
|---|---|
| 全部 MQ 直发，彻底不要发件箱 | 提交后、发出前进程崩溃或 broker 不可用，这条就丢了。对通知可以接受，对扣库存、发权益、出餐不行 |
| RocketMQ 5.x（本 ADR 草稿的原选型） | 用户选 RabbitMQ 4。RocketMQ 原生有按键顺序消息与事务消息，但本方案不用事务消息（见下一行），顺序用一致性哈希 + 单活消费者也能做到 |
| 用 MQ 的事务消息代替发件箱 | 每类事件都要写一个“按业务状态回查”的接口，57 处各写一份；RabbitMQ 也没有这种机制。发件箱给了统一的补发依据 |
| RabbitMQ Streams / Super Streams | 适合大量回放与扇出；本场景是“每个消费者一条工作队列、失败重投、进死信”，仲裁队列更贴合 |
| Kafka | 运维与资源对这个规模偏重；重投与死信要自己拼 |
| Redis Streams | 生产上 Redis 目前也不是必选组件，同样要新部署；持久化与消费组语义弱于专用 MQ |
| 只开 ADR-039 的统一模式，不上 MQ | 能解决“消费者互相连累”，但仍是单进程投递、不能横向扩，也解决不了通知的重复外发 |
| RabbitMQ 原生 Java 客户端 / AMQP 1.0 客户端 | 监听容器、确认、重连都要自己写；Spring AMQP 已经覆盖且版本随 Spring Boot 管理 |

## 后果

- **多一个要运维的中间件**。仲裁队列要求至少 3 个节点才有复制意义；单机部署时它能跑但不容灾。
  .80 与 UME 166 目前都没有 RabbitMQ，部署、监控与备份由谁负责另定；部署之前一切照 `local` 跑。
- **保序是分区级的**：同一聚合号永远进同一分区、同一时刻只有一个消费者在处理；分区数决定这类事件的并发上限，扩分区要重新分配。
- **队列不宜长期大量堆积**：堆积时仲裁队列占内存与磁盘，消费者宕机要有告警；死信队列要有人看。
- `local` 模式下，MQ 直发类仍是“进程内、失败只记日志”，可靠性要到切成 `rabbitmq` 才提升。这是有意的过渡。
- 兜底作业成为 MQ 直发类的安全网，必须按业务表扫，而不是按发件箱扫。**2026-11-27 核实**（清单 §〇“兜底作业核实结果”）：
  A7–A11 都按业务表扫、能补；A12（审批对账不补提交、生产不跑）与 A13（门店同步游标在内存）不行，移到发件箱。
  但 **v2 生产 `SHOP_JOB_ENABLED=false`，这些作业目前一个都没跑**，所以 A7–A11 要等作业在生产上跑起来再改 MQ 直发。
- 外部通道去重是这次顺带要补的缺陷修复；不补的话，MQ 重投照样会重复发短信。
- 撤回代价低：传输切回 `local` 即可；发件箱表与投递器始终保留。
- 守卫要加：发布入口只有 `publish`；每个事件类型的走法与清单一致（以现状为基线的守卫测试）。

## 部署与连接（2026-11-27 用户提供）

| 项 | 值 |
|---|---|
| 地址 | `192.168.3.200:5673`，AMQP 0-9-1，内网 TCP |
| 虚拟主机 | `neargo` |
| 应用账号 | `neargo_app` |
| 口令 | 不进仓库。放在运维机的应用凭据文件（`…/env/ume/rabbitmq/192.168.3.200-5673-neargo-app.env` 的 `RABBITMQ_PASSWORD`），部署时注入环境变量 |

应用侧配置只读环境变量、不写默认值：`SHOP_RABBITMQ_HOST`、`SHOP_RABBITMQ_PORT`、`SHOP_RABBITMQ_VHOST`、
`SHOP_RABBITMQ_USERNAME`、`SHOP_RABBITMQ_PASSWORD`；`shop.events.transport=rabbitmq` 时漏配即启动失败，`local` 时不读。

**连接在服务器上部署时打通**（用户 2026-11-27 定）：开发机不连这个实例（探测也不通），凭据只在服务器侧注入。
本机集成测试用本机单机 RabbitMQ。节点数（是否 3 节点集群）待确认，关系到仲裁队列能否真正复制。

## 实施顺序

1. 发布入口收成 `publish` + 按必达消费者决定落不落发件箱（`local` 传输，与 MQ 无关）。 **已做（2026-11-27）**：通知类消费者以 `afterCommitOnly()` 标记。
2. 通知外部通道按事件号去重。 **已做（2026-11-27）**：复用基础能力的事件幂等表 `sys_event_consumed`，键 `send:<通道>:<收件人>`，单独提交；事件号由投递方经 `DeliveryContext` 带给消费者。覆盖微信订阅、App 推送、来单/售后短信、来单邮件、企微群、收件人物流短信。
3. 传输抽象 + RabbitMQ 实现（默认 `local`），本机装单机版跑集成测试；先切 MQ 直发类。
   **已做（2026-11-27）**：`NotificationTransport` 抽象；`shop.events.transport=rabbitmq` 时
   `RabbitEventsConfiguration` 自建连接工厂（只引 `spring-rabbit`，不引 starter，`local` 下不建连接、不影响健康检查），
   通知类消费者（`afterCommitOnly()`）各一条仲裁队列 `shop.events.<handlerId>`、按 `eventTypes()` 绑定、
   投递上限 5 次后进 `shop.events.<handlerId>.dlq`；发送在后台线程等发布确认，失败本地重试 3 次后只记日志。
   **只切了通知类**；清单 A7–A13（有兜底作业的）仍走发件箱，待兜底作业逐个核实后再切。
   开发机没有 RabbitMQ（无 Homebrew/Docker/Erlang），连真 broker 的 `RabbitNotificationIT` 以环境变量 `SHOP_RABBITMQ_IT_*` 开启，
   在能连 RabbitMQ 的机器上跑。
4. 发件箱中转改发中间件，消费者改订阅，按域逐个切。
   **已做（2026-11-27）**：`OutboxDispatcher` 遇到 `shop.events.outbox-event-types` 名单里的类型，改调 `MessageBroker.publish`，
   中间件确认才标已发，没确认走原有退避重投；`BrokerSubscriptions` 把必达消费者按“名单 ∩ 它支持的类型”登记成保序订阅，
   收到后带事件号、调用来源调原 `consume`。RabbitMQ 下保序订阅 = 一致性哈希交换机（按头 `x-aggregate-id`）+ N 条单活分区队列
   （默认 4），**服务端需启用 `rabbitmq_consistent_hash_exchange` 插件**。名单默认为空，即全部仍进程内投递，按域逐个加；
   与统一投递模式（ADR-039）同开会在启动时拒绝。发布同时校验发布确认与不可路由退回：没有订阅能收的消息算发布失败。
   **首个切换的域（2026-11-27）：商品 → 库存**：`SKU_UPSERTED`、`SKU_RETIRED`、`GOODS_ON_SALE_CHANGED`、`SKU_INV_MODE_CHANGED`
   进默认名单（`SHOP_EVENTS_OUTBOX_TYPES` 可覆盖；`transport=local` 时名单不起作用）。库存的四个投影写同一份物料，
   新增 `OutboxConsumer.subscriptionGroup()` 让它们合成一个保序订阅 `inventory-product`，组内按原顺序逐个调 ——
   否则各自一条队列，同一 SKU 的建档与下架可能乱序。上下架按商品号发，与按 SKU 的三类不保证先后，与改造前按聚合分线的行为一致。
   **第二个域（2026-11-27）：打印事实**：`ORDER_PRINT_FACT_READY`、`ORDER_PRINT_CONTROL_CHANGED`、`BUSINESS_PRINT_FACT_READY` 进默认名单。
   事实建任务、控制变更作用于已建任务，同一订单控制若先到会给已取消的单子建出任务，所以两个消费者同组 `print-facts`。
   **第三个域（2026-11-27）：发货快照回写** `LGS_SHIPMENT_SNAPSHOT`：单消费者、按运单号保序；收敛锁行且只前进，不另设组。
   **第四个域（2026-11-27）：订单类** `ORDER_CREATED`、`ORDER_PAID`、`SUB_ORDER_SHIPPED`、`SUB_ORDER_COMPLETED`、`AFTER_SALE_REFUNDED`，
   加 `CARD_SALE_CANCELLED`（卡券消费者收的两类必须一起走中间件，否则一半进程内一半中间件，先后无保证）。
   物流登记与微信发货上传入队同组 `order-shipping`（原来同事务提交，上传事件提交后才发）。
   **同时补上**：必达订阅每条消息在一个 `REQUIRES_NEW` 事务里调消费者，与进程内投递一致，消费者多次写库同提交同回滚。
   **规则**：一个必达消费者收的所有类型要么都进名单、要么都不进；按域加名单时逐个核对。
5. 撤中转（清单 §〇 C 类）。
