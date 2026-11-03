# T04 门店初始化、来源账与端排期

## 接入与切换边界

宿主先运行 V554，为 `mch_store` 增加可空的 `business_type_code`、`commerce_model`、`business_type_changed_at`。历史行维持原值，空模型按旧店处理。普通 MyBatis 更新不改写这三列。commerce 沿用独立的 V1–V5、33 张表与 `commerce_schema_history`。本次没有发布生产配置或执行数据迁移。

`commerce.enabled` 与 `commerce.store-creation.enabled` 都为 true 时，shop-app 才装配嵌入式建店能力。单独打开建店开关而缺少共享事务、宿主身份适配或配置会导致装配失败。默认关闭，旧客户端仍可走原流程。启用前需要由 T03 发布并启用目标经营类型的三档目录。宿主通过 MarketPort 在装配时读取市场与币种映射，开店时以主体的市场冻结来源币种。新增市场后需重载映射。正式切换还需完成后续准入、销售及运维验收。

| 宿主入口 | 新建或重放行为 |
| --- | --- |
| `POST /biz/merchant/quick-start` | 请求携带 `businessTypeCode`，账号来自已认证的 B 端消费者账号。在当前事务锁账号，再读待补证照主体和默认店。已有 STORE_V1 占位店只接受同类型，重放返回原店。LEGACY 占位店可用无类型的旧请求取回，指定类型返回 70199。缺少已发布类型返回 70198。 |
| `POST /biz/store/create` | 主体结构权限和目标主体归属仍由原控制器检查，仍遵守主体门店额度。STORE_ADMIN 在当前宿主是主体结构权限，内置角色中仅 OWNER 具备，自定义角色不能授予此码或通配符。适配器再核实目标主体的活跃 owner，兼容 USR、MCH 两种店主登录。普通店员角色无法新增分店。 |
| 审核 `activate`、资料 `save` | 已有店的审核和编辑继续使用原流程。启用新模式后，缺店时拒绝代建并要求先走显式建店入口。 |
| 平台自营、运营代开、开发种子 | 由原平台内部入口写 LEGACY。快速开店依旧保留待补证照、额度合同和支付治理的原语义。 |

账号、owner、经营类型、模型、初始化结果都来自受控边界。shop-merchant 的新增模块依赖只有 commerce-api。shop-app 连接已验证的 Spring Security 会话，传递令牌的 SHA-256 指纹。只在可信建店方法刚完成 INSERT 的回调期间绑定本次命令，退出即清除。模块要求调用者已持有相同 DataSource 的物理事务。宿主用该连接核验门店、真实 owner、经营类型及市场，能够读到刚插入且尚未提交的记录。迭代中若改为跨库或远程调用，需要重新设计交付协议。

分店新模式还要求 `operationNo`，为 1–64 位字母、数字或 `_.:-`。STORE_CREATION 收据按租户、真实主体种类、创建账号、目标主体及 operationNo 唯一，aggregate_no 绑定首次分配的门店号。内容摘要按顺序编码店名、可空地址、类型和货架类目列表。HTTP 未传类目和空列表都表示复制默认货架。重试先锁创建账号及目标主体／owner，以当前读取回原店，无需再次消耗额度。内容冲突返回 10409。同名店使用不同 operationNo 仍可分别创建。首次写收据、额度校验、建店、FREE 和货架初始化共享事务，失败后可沿用原 operationNo 重试。快速开店使用同一店主唯一待补证照主体进行业务去重。

## 初始化与来源

T04 只装配 `StoreProvisioningFacade.initializeFree(actor, command)`。原 `SubscriptionFacade` 继承此能力并保留后续赠送、撤销、查询声明。没有新增任意指定门店的 HTTP 赠权接口。

初始化锁店订阅账户，绑定当时已发布的 FREE Plan 版本。FREE 是一个零金额的永久父来源，永久片使用 NULL 终点。重试不读取新 FREE 目录，也不替换既有资格。后续调整存量 FREE 应由显式迁移策略处理。Plan 父来源只记录一份价值，独立端包、FREE 和赠送可折价基数为零。初始化返回每个父来源一次，Plan 由版本标识，各端归属在时间片中；独立端来源另带访问码。

`SubscriptionService.deliver` 是供后续受信交付调用的内核批处理。输入已核验的单周期任务／赠送引用、目标 Plan 发布版本或独立端码、精确时长、现金与抵扣分摊、币种及赠送标志。套餐需先展开为各叶子周期，每个任务都有独立来源号。整个批次共用服务端时刻，锁住订阅账户后一次提交。当前实现拒绝任何已有数字占用的变更，待 T08 接入对应订单的占用校验和释放。

同门店实际永久拥有的 code 从付费 Plan 生成的有限端片中剔除。已由永久 FREE 提供的独立端不再单独发放。每个未结束的 BASE_PLAN 必须与新增 Plan 的稳定定义及档位相同。续期从交付时刻与既有有限 Plan 尾部的较晚者开始。只有固定 Plan 才携带可折价价值，升级、退款、抵扣余额或订单支付流程都需各自后续任务实现。

## 排期、覆盖与证据

每次交付取得当前读并以门店为写入串行化边界。内部操作回执使用模块 inbox 的租户、生产者和业务号，校验聚合身份和不可变参数摘要；单来源另有独立回执。相同身份携带冲突参数会回滚。重新执行已接受操作不递增权益版本。独立重建即使以新操作号调用，只要时间片没有变化也不产生新历史、权益版本或 outbox。拒绝早于该店已接受来源或排期历史的切点。

1. 对每个受影响的 code 保留固定 Plan 的真实区间和永久片。永久端的 NULL 终点不进入有限尾部计算。
2. 逐独立来源保留切点前已用前缀，按原始授予量扣除已用时长。按交付顺序把剩余时长排在不早于切点和交付时刻的空档。遇到固定 Plan 时可拆片，跨过后继续排同一来源。
3. 新旧片相同则保留原行。已被替换的片标 SUPERSEDED，必要时另记已用前缀，原购买时长、首次包络和金额保持可追溯。NORMAL 只表示未撤销。独立端的有效起止仅为包络，准入必须使用实际片。
4. 按来源核验已用、未来及原授予量。每个变化 code 的 `mch_end_schedule_event` 记录操作号、切点、前后快照、逐来源时长与 FIFO_V1 策略。账户每批增加一次 entitlement_version，并追加一条 ENTITLEMENT_CHANGED 到模块 outbox。失败时全部回滚。
5. CoverageCalculator 按单个 code 归并相交和相邻的左闭右开区间，保留真正空档。返回当前连续区间、最近已结束区间、下个可用性边界及下一次连续覆盖起点。无当前资格时也能查最近终点，供 D0 后续使用。空输入用显式 code 重载。

## 验证

JDK 21，专用 MySQL 8.4.13。原生用例只创建并清理独立的随机测试库。首次核心目标测试因缺少 T04 类编译失败，持久化用例因缺少 JdbcSubscriptionRepository 失败，宿主用例因旧开店合同不接收类型失败。实际宿主验收通过事务代理调用真实 MerchantPortImpl 和 StoreAdminServiceImpl，用 MyBatis 插入主体、owner 和门店，并由同一管理器驱动 commerce JDBC。测试注入 outbox 约束失败和外层回滚，也验证原有 RR 快照、并发重试、真实空档和每个来源的历史守恒。

```sh
mvn -f backend/commerce/pom.xml test
mvn -f backend/pom.xml -pl shop-app -am -Dtest=CommerceStoreCreationFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -f backend/pom.xml -pl shop-app -am -Dtest=QuickStartFlowTest,ErrorCodeUniqueTest,SchemaDriftTest,StoreCategoryFlowTest,SelfOperatedMerchantFlowTest,MerchantSelfOperatedFlowTest -Dsurefire.failIfNoSpecifiedTests=false test
```

2026-11-03 验证结果为 commerce 全量 149 项通过（API 5、core 53、persistence 82、starter 9），指定宿主建店 2 项通过，以上六组宿主回归 41 项通过，各组失败、异常、跳过均为 0。并发交付测试在三个独立 JVM 中写同一随机库；宿主场景在不同连接并发重放首次开店及分店创建。对来源策略证据缺失、无法解释既有有效片的门店，排期写入拒绝并保留原记录。
