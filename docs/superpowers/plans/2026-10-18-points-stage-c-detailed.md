# 积分第三阶段完整接线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 延续已选择的当前会话内逐项执行方式；阶段末一次全阶段独立 Review。

**Goal:** 完成积分与订单、支付、售后的接线，支持运行时停止新增与重新接线，纯现金订单可独立运行，已受理义务可靠收尾。

**Architecture:** 积分继续是 pay/pay-domain 内业务模块。trade 拥有订单参与绑定、接线策略和源事实，pay 拥有积分账务及资金意图；shop-base 的 SPI 是边界。订单只经统一接入编排访问可选积分实现，源事务与 pay 事务分阶段提交、以不可变事实恢复，不使用跨库长事务或旧引擎兜底。

**Tech Stack:** Java 21、现有 Spring/MyBatis、源 transactionManager 与 payTxManager、现有 Outbox/Inbox、现有前端技术栈；不新增消息中间件或独立 jar。

**Spec:** `docs/technical/design/积分订单事件契约.md`（含接脱约束）、`docs/requirements/PRD-积分模块.md`、`docs/technical/TDD-积分域重整.md`、ADR-036。

## 当前基线与覆盖

基线提交 `ebe45b421`，分支 codex/points-v3；C1 契约提交24d64d378及9项测试作为既有成果，不重复开发。C1.1–C7均尚未实现；本文件是执行计划，不是完成报告。

| 原任务 | 详细任务 | 交付结果 |
|---|---|---|
| C1 | T01 | 契约补齐持久化序列化与扫描边界 |
| C1.1 | T02–T04 | 动态策略、参与绑定、可选实现与脱离门禁 |
| C2 | T05 | 门店、订单行、类型和原支付路由映射 |
| C3 | T06–T08 | 下单占用/取消、可靠源事件、可信支付与返分 |
| C4 | T09–T10 | 售后退款及责任关闭 |
| C5 | T11 | 一致性、恢复与告警 |
| C6 | T12 | 接脱联调、前端和旧路径清理 |
| C7 | T13 | 阶段验收、最终Review与修复 |

执行依赖：T01→T02→T03→T04→T05→T06→T07→T08→T09→T10→T11→T12→T13。源码存在交接依赖，逐项执行，避免多个任务同时修改订单主服务。

## Global Constraints

- 以门店为基本维度；商家只用于归属、签署和聚合，不能补默认门店参与积分。
- 不迁移旧积分余额、不启用双引擎；尚未切换的调用点保留至新链路替换完成。
- 使用合成商家、门店、订单与隔离资金网关；生产任务和真实资金能力默认关闭。
- 未参与积分的订单不调用积分实现、不读写积分专属表；接线策略读取属于订单基础配置，须单独统计其成本。
- DETACHED/ACTIVE/DRAINING 是订单接入状态，与类型生命周期和启动配置 shop.points.enabled 分开。
- 关闭新增不是清除历史绑定；占用、已承诺发放、退款、事件积压、结算及UNKNOWN须按原号收尾。
- hold未知不得降级纯现金、换号重扣或静默增加应付金额；支付后返分失败不撤销已确认现金支付。
- 源 transactionManager 与 payTxManager 不组成原子事务；远程资金不在上述长事务内。
- 本计划中“新增”路径是拟创建文件；已有文件按现状修改。DDL编号实施前重新检查冲突，当前预留V427–V430，不改历史迁移。
- 每任务先最小反例再实现，最后受影响集合；不跑全仓测试。不为文档/机械整理增加无意义测试。
- 测试runner：timeout=0、每15秒进度、慢用例>=1秒；记录全部运行及预期RED，区分构建、上下文与用例时间。
- 没有新的30分钟上限；任何无进展或反复失败先调查并汇报，不盲目重跑、无理由扩大范围。
- 每任务独立本地提交，保持台账；本计划不自动推送、合并、部署或开启生产任务。

## Review Focus

1. 关开关与hold并发：只允许已持久化受理者继续，无绑定绝不扣分（T03/T06）。
2. pay已成功但源事务失败/进程崩溃：可按原号恢复，不重复占用或放大现金应付（T06/T08）。
3. 自增低序号晚提交：补扫不能永久遗漏已承诺事件（T07）。
4. 同商家多门店与历史路由：退款、结算、R不串店，不读取当前账户替代原路由（T05/T09）。
5. 无实现或消费者暂停：纯现金订单继续，存量积分交易明确待处理，不能no-op清偿（T04/T11/T12）。

## 文件职责与接口交接

新增源码根：`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/points/`，仅保存订单侧协调与状态；不把pay账务实现放进trade。

- `PointsIntegrationPolicyService.java`：运行时策略查询/版本化变更。接口 `IntegrationPolicy policy(String storeNo,String scene)`、`IntegrationPolicy change(IntegrationPolicyChange command,String actor)`；IntegrationPolicy包含mode、earnEnabled、redeemEnabled、version；IntegrationPolicyChange含requestId、storeNo、scene、expectedVersion、reason，操作人由登录态获取。
- `OrderPointsBindingService.java`：`Binding admit(Admission command)`、`Binding find(String subOrderNo)`、`Binding transition(Transition command)`。Admission含请求号、订单/子单/用户/门店/主体、类型/币种、场景、策略版本及冻结价格/规则/路由；Transition含bindingNo、expectedVersion、目标阶段、原pay标识。禁止任意覆盖快照。
- `OrderPointsCoordinator.java`：订单唯一积分编排入口，`Decision decide(Context context)`、`Participation admit(Admission command)`、`Participation resume(String requestId)`、`void onSourceEvent(PointsOrderEvent event)`；Decision为BYPASS/RECONFIRM/READY，承诺建立后状态为PENDING/BOUND/CLOSING/CLOSED，不把技术异常解释为零积分。
- `PointsOrderPlacementCoordinator.java`：事务外编排创建、hold、源订单可支付化及补偿；依赖source短事务服务和可选PointsRedeemPort。
- `OrderPointsTargetFactory.java`：`List<PointsRedeemPort.Target> targets(PlacementSnapshot snapshot)`，PlacementSnapshot为本类嵌套record，只从验证后的子单/行/路由快照构造。
- `OrderPointsFactService.java`：`PointsOrderEvent append(PointsOrderEvent draft)`、`SourceFactsPort.Page scan(String typeCode,long after,int limit)`；真实源事实持久化和有序扫描。
- `OrderPointsOutboxConsumer.java`：接现有OutboxConsumer；按持久化事件键获取冻结正文，投递现有PointsFactPort，不重新读取商品规则。
- `OrderPointsRefundCoordinator.java`：事务外编排原RefundCommand的prepare、补收/现金意图、可信完成、confirm和源记账；不让Controller直接构造成功事实。
- `OrderPointsRecoveryService.java`：恢复源侧PENDING/BOUND/CLOSING和未决投递；不代替pay原有资金恢复。
- `OrderPointsBindingMapper.java` / `OrderPointsFactMapper.java`：订单侧版本CAS、扫描、唯一键与短事务锁；统一归trade/order/mapper。
- 新建 `backend/shop-base/src/main/java/ai/neargo/shop/spi/points/OrderPointsSourcePort.java`，仅暴露源事件scan/find供pay适配；新增 `backend/shop-app/src/main/java/ai/neargo/shop/payclient/impl/OrderPointsSourcePortAdapter.java` 实现既有SourceFactsPort，避免pay-domain依赖trade实现。
- 新建 `backend/shop-base/src/main/java/ai/neargo/shop/spi/points/PointsObligationPort.java`，pay实现只读 `Obligations inspect(String storeNo)`，列出开放头寸、资金未知、队列与结算义务；脱离不能只靠订单表判断。

所有新增DTO放在各接口的嵌套record；实现前逐项锁定对应任务字段，并保持生产者与消费者同一次提交编译通过。已有 `PointsOrderEvent`、PointsRedeemPort/PointsEarnPort/PointsFactPort/PointsRefundPort 保持会计语义，不复制一套。

## 统一测试执行模板

以下以选择器S表示每任务明确列出的测试类或单方法，工作目录为仓库根：

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home PYTHONDONTWRITEBYTECODE=1 /usr/bin/python3 scripts/run-backend-tests.py --timeout 0 -- mvn -o -f backend/pom.xml -pl shop-app -am test '-Dtest=S' -Dsurefire.failIfNoSpecifiedTests=false
```

实现时将S替换为该步骤的唯一选择器，不把字符串S直接提交执行。shop-base纯契约用`-pl shop-base`，shop-core纯策略用`-pl shop-core`；数据库并发用隔离Native MySQL参数，不用H2冒充MySQL锁语义。RED预期为指定断言失败或新接口缺失；GREEN必须退出0、执行数>0、失败/错误0。编译失败不计作业务失败用例。

每个提交前：检查git diff、`git diff --check`（现有生成OpenAPI格式单独核对），只暂存本任务文件；文档同步实现状态，不提前勾选后续任务。

## Task T01：完成源事件持久化契约

**目的/依赖：**复用C1，消除JSON往返、相同事件键和扫描序列的不确定性；不重复实现已通过的9项测试。

**Files：**修改`backend/shop-base/src/main/java/ai/neargo/shop/spi/points/PointsOrderEvent.java`（仅必要字段/校验）；新增`OrderPointsSourcePort.java`；测试`backend/shop-app/src/test/java/ai/neargo/shop/scenario/PointsOrderEventSerializationTest.java`。

**接口：**SourcePort提供`find(String eventNo)`返回Optional<PointsOrderEvent>和`scan(String typeCode,long after,int limit)`返回既有Page。业务eventNo与Outbox投递行号分离，JSON保留原occurredAt、sourceVersion、支付/行快照。

- [x] RED：新增序列化往返相等、空/多载荷拒绝、退款准备引用不变、同号改变正文冲突契约；先运行`PointsOrderEventSerializationTest`，确认具体失败原因。
- [x] 实现：确定序列化版本字段schemaVersion=1，未知版本拒绝；如加字段同步C1构造器和测试，不接受静默默认丢字段。
- [x] GREEN：运行新类及PointsOrderEventTest，预期全部通过；记录契约版本与兼容决策。
- [x] 提交：`feat(points): finalize durable order source contract`。

## Task T02：运行时接线策略与配置权限

**目的/依赖：**依赖T01；ACTIVE/DETACHED/DRAINING可按门店和ONLINE/OFFLINE场景改变，不依赖重启。

**Files：**新增上述PolicyService及`impl/PointsIntegrationPolicyServiceImpl.java`、`trade/order/mapper/OrderPointsBindingMapper.java`；新增`backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsOrderPointsIntegrationController.java`；新增V427__order_points_integration.sql。Ops调用经现有允许的应用门面包，禁止Controller直达Mapper。

**数据：**ord_points_integration_policy唯一键(store_no,scene)，mode默认DETACHED、earn/redeem默认false、version；审计/命令表保存requestId和正文摘要。缺配置即DETACHED，不自动按商家配置启用。GET/POST `/ops/points/order-integration`，分别FINANCE_SETTLE_READ/EXECUTE，沿用门店数据域校验。

- [x] RED：`PointsOrderIntegrationPolicyTest`：无配置DETACHED；ST1启用不影响同主体ST2；旧版本变更拒绝；重试幂等；未知场景拒绝。权限测试追加域外读写拒绝及操作人不可伪造。
- [x] 实现：短源事务CAS与审计同提交；关闭新增转DRAINING，有义务时禁止直接DETACHED（查询接口在T04补齐前保持保守拒绝）。
- [x] GREEN：本类+受影响权限类，Native验证策略并发CAS；生成对应API/权限资料。
- [x] 提交：`feat(points): add store scoped runtime integration policy`。

## Task T03：参与绑定、受理锁与原快照

**目的/依赖：**依赖T02；关开关与新增受理具有明确顺序，原交易不随开关变动。

**Files：**新增BindingService及impl、mapper语句；V428__order_points_binding.sql；测试`PointsOrderBindingTest`、`PointsOrderAdmissionRaceTest`。

**数据：**ord_points_binding唯一(sub_order_no)及唯一requestId；保存modeVersion、user/store/merchant/type/currency、参与项目EARN/REDEEM、原报价、规则/协议/路由正文摘要、holdNo、version和阶段。纯现金非参与订单不建绑定。PENDING为已受理但外部结果尚未确认，不能作为“没用积分”。

- [x] RED：同请求重放原绑定；同号改金额拒绝；同主体跨店拒绝；开关关闭前已受理继续、关闭后新增拒绝；旧快照不随规则变更。Native双连接验证受理/关闭竞争。
- [x] 实现：源短事务锁策略行，校验expectedVersion和门店，再持久化PENDING；释放策略锁后才访问pay。关闭同锁校验，避免报价查一次开关后无条件hold。
- [x] GREEN：两类定向测试；原子性失败不留下半个绑定；不跨源/pay事务持锁。
- [x] 提交：`feat(points): persist versioned order participation bindings`。

## Task T04：可选实现、统一门面和完全脱离门禁

**目的/依赖：**依赖T03；订单基础链路不硬依赖积分实现，已有义务不能no-op。

**Files：**新增OrderPointsCoordinator及impl；shop-base/PointsObligationPort.java；pay-domain/points/query/PointsObligationService.java及实现/适配；测试`PointsOrderDetachmentTest`。

**接口：**Coordinator通过ObjectProvider取得既有points SPI；未参与返回BYPASS，已参与缺实现返回明确待处理业务错误。Obligations按门店汇总openHolds/pendingPromises/openRefunds/openStatements/unknownFunds/pendingEvents；不足以归店的积压或未完成查询标为UNKNOWN，禁止据此完全脱离。

- [x] RED：无实现/DETACHED纯现金不调用积分；BOUND缺实现拒绝退款和释放；任何一项义务>0或UNKNOWN拒绝完全脱离；全部清偿才允许DETACHED；重新接线原绑定和幂等号保持不变。
- [x] 实现：ACTIVE→DRAINING立即禁止新增，DRAINING→DETACHED在策略锁下核对源义务与pay义务；新受理被禁止期间允许收尾只减少义务，遇查询失败保留DRAINING。实现从未开启时允许空义务判定，但不能据“Bean缺失”推定无义务。
- [x] GREEN：本类及策略/绑定小集合；不用启动真实资金任务来证明脱离。
- [x] 提交：`feat(points): isolate optional order integration and draining`。

## Task T05：门店、订单行与支付路由映射

**目的/依赖：**依赖T04；修复旧PointsPort.Target丢失storeNo的交接。

**Files：**新增OrderPointsTargetFactory.java；修改`trade/order/service/create/OrderSplitter.java`的积分调用边界（保留纯现金拆单语义）、`trade/service/impl/OrderServiceImpl.java`调用映射；测试`PointsOrderTargetMappingTest`，复用OrderSplitterTest。

**接口：**PlacementSnapshot从服务器验证后的拆单组取得root/sub/user/store/merchant、券后商品额（不含运费）、逐行分摊、market和可信路由；构造既有Redeem Target，不读取当前商家默认店或用户指定金额。

- [x] RED：同主体ST1/ST2产生两组；参与积分但缺店明确拒绝，纯现金旧路径仍可用；商品/库存/结算归属不一致拒绝；商品1000、券100、运费50，积分目标基数900而非950/1000；行分摊整数求和无差额。
- [x] 实现：对参与者严格校验门店；旧OrderSplitter缺店回落主体不得进入积分目标；保留原outTradeNo、账户版本及每门店路径。尚未有支付tradeNo时不能伪造已付号，明确采用已有可占用的受理路由快照。
- [x] GREEN：新类+OrderSplitterTest，涉及数据库归属时另运行单个场景；确保不全局收紧未参与订单。
- [x] 提交：`feat(points): map order lines to store bound redemption targets`。工厂在432f63ef6，真实订单入口随T06提交。

## Task T06：下单占用、确认价格、取消及崩溃恢复

**目的/依赖：**依赖T05；源订单与pay分阶段，任何中间失败可恢复。

**Files：**新增PointsOrderPlacementCoordinator.java与源短事务实现；修改OrderServiceImpl下单/取消/超时关闭调用点；必要扩展OrdOrder/OrdSubOrder及V428后续新迁移；测试`PointsOrderPlacementFlowTest`、`PointsOrderHoldRecoveryTest`。

**阶段：**源短事务保存积分参与受理及非可支付的创建阶段→事务外调用hold（pay内部短事务）→源短事务绑定hold结果/原积分分摊并发布正常可支付订单。未确认阶段不允许发起支付或触发“下单完成”通知；失败或超时由同一请求恢复，库存/券预占按原业务规则显式补偿。

- [x] RED：DETACHED和不使用积分原下单回归；政策改变导致应付变化返回RECONFIRM；hold成功后源提交失败可按原orderNo恢复；hold超时不切换纯现金；取消与支付竞争不重复释放；暂存订单失败不遗留库存/券占用。
- [x] 实现：复用现有创建/库存/券组件，禁止复制两套订单实现。参与分支移出长源事务；既有纯现金路径继续使用现有事务。源状态推进CAS，支付入口校验可支付状态。取消的权威未付事实持久化，远程/跨库调用不藏在AfterCommit不可恢复回调里。
- [x] GREEN：两类定向场景+既有下单/取消受影响类；Native只验证需要的竞争方法。记录跨域故障注入位置。最终整单Native竞争并入T13验收。
- [x] 提交：`feat(points): wire recoverable order redemption admission`（3a1e44563）。

## Task T07：源事实日志、Outbox与有序补扫

**目的/依赖：**依赖T06；事件与源状态同生共死，重投不变号，晚提交不漏扫。

**Files：**新增OrderPointsFactService/Impl、OrderPointsFactMapper、OrderPointsOutboxConsumer；shop-base新增OrderPointsSourceEvent实现现有DomainEvent；payclient新增OrderPointsSourcePortAdapter；新增V429__order_points_source_facts.sql。复用`shop-store-mybatis/.../OutboxEventBus.java`与OutboxConsumer，不重写平台投递器。

**数据：**ord_points_source_fact保存eventNo唯一、typeCode、sourceSequence、schemaVersion、正文/摘要和归属，唯一(typeCode,sourceSequence)；ord_points_source_counter按type串行分配序号。写业务事实、计数器更新、不可变正文与sys_outbox同源事务；系统Outbox随机投递行号不充当业务幂等键。

- [x] RED：订单事务回滚无日志/Outbox；相同事件正文重复返回原行，冲突拒绝；消费者成功但ACK丢失不重复记账；暂停消费者后补扫恢复；低序号事务阻塞、高序号不得越过形成永久空洞。
- [x] 实现：在事实追加短事务锁type计数器，分配序号并持锁至提交；回滚计数器一起回滚。draft的sequence固定0，append分配后持久化最终正文；幂等比较使用归一化sequence=0的摘要，重放返回原最终正文，不因传输序号变化误报冲突。相同事务有多事件按确定顺序追加；不扫描主表重建历史正文。scan只读已提交序列，limit1–200；adapter投递前校验冻结信封，再把原事实交给现有SourceFactsPort/PointsFactPort。
- [x] GREEN：`PointsOrderSourceOutboxTest`、Native `PointsOrderSourceScanConcurrencyTest`；检查所有Mapper确实参与源transactionManager。测计数器锁等待，记录每类型串行写的吞吐代价；超出实测需要时再设计分片水位，不能默默改为MAX(id)。
- [x] 提交：`feat(points): persist reliable ordered source facts for replay`。

## Task T08：可信支付确认、返分及终局接线

**目的/依赖：**依赖T07；以实际支付账本为证据，返分异步不阻断已确认现金。

**Files：**修改OrderServiceImpl支付/发放调用点、PointsRuleHandoff.java、现有支付完成事件消费实现；新增`PointsPaymentSnapshotPort`（shop-base及pay适配）只读原支付核验结果；测试`PointsOrderPaidFactFlowTest`。

**接口：**支付快照读取接口按orderNo返回原资金路径、商户单、实际交易、币种、金额、核验状态及分店路由。引用现有stl_payment与V418请求快照，不从客户端或当前账户生成。配置规则的“无配置”和“固定零”保持区别。

- [x] RED：仅前端支付成功不发分；金额/币种/子单路由错配拒绝；真实已核验成功写PAID和earning两个稳定事件；消费者失败现金订单仍已付、承诺待处理；停接线后旧承诺继续，新纯现金单不追补；规则变更不改变重试结果。
- [x] 实现：订单源事务记支付事实与冻结承诺，异步消费；支付结果UNKNOWN继续原单核验。每个参与子单冻结券后行金额与原规则版本，FINALIZED保留原范围，不能提前当REFUND_CLOSED。
- [x] GREEN：新类+既有支付核验/规则交接受影响测试；不在源事务调用银行。
- [x] 提交：`feat(points): connect verified payments and deferred earning facts`。

## Task T09：部分/全额售后退款执行阶段

**目的/依赖：**依赖T08；扩展现有纯现金退款状态机，禁止直接删除保护条件放行。

**Files：**新增OrderPointsRefundCoordinator；修改`trade/order/refund/service/impl/RefundExecutionServiceImpl.java`、对应RefundExecutionService/Mapper、AfterSaleServiceImpl；必要新增V432__order_points_refund_binding.sql（V430已用于下单状态，V431为源订单标记）；测试`PointsOrderRefundExecutionTest`。

**阶段：**源锁定并冻结原RefundCommand/版本→pay.prepare→持久化准备号/revision/inputHash→必要补收或失败修订→建立当前净现金退款意图→原资金执行/核验→可信退款完成事件/pay.confirm→源账本commit。资金调用均在事务外；offset、零现金、补偿分别保留证据。

- [x] RED：同店两行100/200，部分退第一行50仅影响对应累计范围；重复退款不重复扣回；两售后竞争不超原现金/积分额度；UNKNOWN不可修订或换号；补收成功现金失败进入补偿；有绑定但缺实现不能当纯现金成功。
- [x] 实现：原beginCash/commit的POINTS_CONFIRMATION_REQUIRED只在保存并验证当前准备/确认结果后替换为阶段条件；纯现金条件保留。净现金0走ZeroCashCompletionFact，不伪造退款支付号；现金SUCCESS必须核验金额/币种/原单及版本。保留同店R、B垫付、原路由分组，不拿当前账户覆盖。
- [x] GREEN：新类+RefundExecutionTest/AfterSaleRefundExecutionTest的受影响方法；Native单方法验证累计额度竞争。失败保留原事件和当前阶段。
- [x] 提交：`feat(points): integrate revision bound after sale refund execution`。

## Task T10：责任关闭、原承诺生命周期与结算关联

**目的/依赖：**依赖T09；支付/订单终局、资金清偿与退款责任关闭保持独立。

**Files：**修改源售后关闭/终局事件发布点，接既有PointsFactPort.acceptClosure、PointsSettlementHook；新增源闭合证明服务于order/points包；测试`PointsOrderClosureFlowTest`。

- [x] RED：订单FINALIZED但售后窗口未关不释放退款责任；还有OPEN售后或UNKNOWN资金拒绝closure；合法关闭重放幂等；停接线后的旧分仍正常激活/回流/退款/结算；ST1债务不抵ST2账单。
- [x] 实现：从权威源窗口与未结售后、真实资金终态组成closure，原发生时间/版本固定。沿用内核生命周期、两种门店结算与R抵销，不在订单侧写积分账户。
- [x] GREEN：新类+所改settlement hook方法；不重跑全部第一阶段验收。
- [x] 提交：`feat(points): connect source closure and settlement obligations`。

## Task T11：跨域一致性检查、恢复和告警

**目的/依赖：**依赖T10；源PENDING、已受理承诺和pay账务可检查、可原号恢复。

**Files：**新增OrderPointsRecoveryService及只读一致性报告；payclient接入现有PointsJobHandlers的独立受控声明或源任务宿主；运营查询门面补订单/绑定/事件关联DTO；测试`PointsOrderRecoveryFlowTest`。

**接口：**`recover(int limit)`有界扫描；`inspect(String subOrderNo)`返回源版本/绑定/原hold/事件/支付核验及差异代码，不返回任意payload或银行正文。恢复只重投原始命令、原事件与原票。

- [x] RED：每个T06/T09边界崩溃一次后恢复到正确状态；漏事件补扫、冲突事件留痕、版本旧记录不覆盖；缺实现时纯现金正常但存量告警；消费者恢复不重复付款。
- [x] 实现：源任务只推进源阶段，资金仍交现有pay恢复；停新增不关收尾能力。运行默认关闭、单轮最多200、并发租约、失败带明确分类；不吞异常返回成功。
- [x] GREEN：本类+有改动的job/DTO权限测试，记录队列积压/最老未决时长、无敏感正文日志。
- [x] 提交：`feat(points): add bounded order integration recovery and inspection`。

## Task T12：前端接线体验、联调与旧路径清理

**目的/依赖：**依赖T11；用户能理解不可用/重新确认/处理中，旧路径不再处理交易。

**Files：**修改`ops-web/app/points/types-tab.tsx`及`ops-web/lib/api/contracts/finance.ts`、`https/finance.ts`、mock/types/三语；C端修改`c-app/src/pages/order-confirm/index.vue`、`c-app/src/pages/orders/index.vue`、`c-app/src/pages/points/index.vue`、`c-app/src/pages/after-sale/index.vue`；B端修改`b-app/src/pages/after-sale/index.vue`；共享模型修改`packages/shared/src/types/user.ts`、`packages/shared/src/strategies/pricing/types.ts`及其调用方。实施前读各端AGENTS。后端修改MpPointsController、BizPointsController、BizPointsAppService；删除已无调用的旧PointsPolicyAppService、旧spi.settle.PointsPort/适配及PointsService旧引擎实现。

- [x] RED：默认未接线下单全额现金无积分请求；开启ST1不影响ST2；价格变更显式重确认；处理中不展示“已退”；Ops关开关须权限/原因/版本并显示存量义务，三语文案一致。共享pricing不得再自行使用固定POINTS.perMinor/maxDeductRatio推导权威金额：2分=1最小货币单位、使用20分时抵10货币单位，最终以服务端报价为准。
- [x] 实现：先替换C/B的读/写和售后补偿入口，报价DTO携带参与状态、服务端金额、汇率与版本，客户端估算不能决定支付金额；再用rg核对旧接口调用数归零后删除。旧种子/测试迁移到新语义而非直接删保护测试。禁止把未覆盖的compensate/revoke/refundConfirmed等旧路径悄悄丢弃；纳入新准备/调账权限流程。
- [x] GREEN：前端受影响Vitest+tsc，合成端到端场景（无实现、DETACHED、ACTIVE、DRAINING、重接、UNKNOWN）；源码架构/SQL/API守卫，三端浏览器局部验收。旧订单相关用例保持原业务断言。
- [x] 提交：`feat(points): complete detachable client integration and retire legacy paths`。

## Task T13：第三阶段验收、一次独立Review与交付

**目的/依赖：**依赖T12；证明完整接线和完全脱离均安全，阶段完成不等于生产上线。

**Files：**新增`docs/technical/积分第三阶段验收记录.md`；更新本计划、TDD、事件契约、API/权限/UI清单和源事件/接脱流转图；保留每任务ledger与日志。

- [x] 验收矩阵：同主体两店、跨主体、券后金额、非1:1比例、支付后返分、核销取消、部分/全退、补收/补偿、责任关闭、同店R、随单/定期、默认关闭、灰度、停新增、消费者停恢复、完全脱离/重接。每项关联真实测试与证据，不只写“已覆盖”。
- [x] 先跑最终受影响积分/订单集合，再仅对事务隔离与竞争方法跑Native MySQL；明确前端与Native重叠计数，不相加虚报。若失败，只重跑修复影响范围，必要时更新最终集合证据。
- [x] 阶段末一次只读独立Review：范围本计划BASE至最终实现HEAD，关注前述五风险、后端权限与敏感字段。Critical/Important做一次RED→GREEN修复，Minor逐项记录，不反复整分支Review。
- [x] 输出耗时分析：每命令/类/用例、慢日志、环境/夹具失败、有效RED、上下文开销；比较纯现金基线与接线关闭路径，不声称“零影响”。验证不访问积分实现/表的调用计数，不用不稳定毫秒断言当功能测试。
- [ ] 检查生产开关保持关闭、DDL仅结构与权限元数据、没有业务余额迁移；提交代码和验收资料到codex/points-v3。保留分支/工作树，不自动推送或部署；归档自有测试证据并停止自建服务。
- [ ] 提交：`docs(points): close stage C integration acceptance`（Review修复另列明确提交）。


## 计划自检与完成定义

本计划覆盖原C1–C7，并把用户新增的可接脱要求前置为T02–T04。现有纯现金退款保护、缺门店拆单回落和旧SPI丢店三处均有明确任务与失败用例；配置改变、事实冻结和扫描水位在接口交接中保持一致。

完成第三阶段需要T01–T13全部打勾并有最终通过证据，且参与路径不再引用旧积分引擎。完成某个DTO、接口、策略或某批测试不得宣称生产接线完成。银行认证、真实主体准备、生产任务启用及部署是独立上线动作，不包含在本计划自动执行范围。


## Task T14：生产部署（用户于 2026-10-18 追加授权）

依赖 T01–T13 完成、最终 Review 重要问题修复、同一提交通过发布闸门。目标为现有生产环境。

- [ ] 固定最终提交，核对 backend、ops-web、c-app、b-app 产物范围与 DDL；记录发布前版本及回滚包。
- [ ] 按仓库 scripts/deploy-backend.sh 和 scripts/deploy-frontend.sh 发布同一提交；不绕过版本、远端提交、发布锁和健康检查守卫。
- [ ] 核验后端 health/info 提交号、三端 VERSION 与关键资源；只读检查新接口鉴权与默认脱离行为。
- [ ] 记录生产结果与时间、实际版本及回滚方法；异常按脚本支持的方式回滚并核验。

本次部署授权替代此前计划“仅本地提交、不部署”的交付限制。发布代码不自动为任何门店签协议、制造积分余额或开启业务接线。


## T09 发布验收补充：普通现金已分账退款

全量闸门暴露出普通现金续跑仍需持久分账回退。已补齐：V438 `stl_cash_refund_reverse` 保存原请求、完整路由、金额和份额；原分账缺少证明时不发起回退。回退调用及回查均在源/pay 事务外，UNKNOWN 只查询原请求，成功回执与结算调整同事务提交后才允许源打开现金退款。待定回退阻止同账单并发新退款和旧回退入口绕行；SPLIT→SPLIT_CONFIRMED 的合法进展不改变冻结命令。

证据：CashRefundSplitExecutionTest、AfterSaleRefundExecutionTest、OpsFinanceGovernFlowTest；Native CashRefundSplitNativeRaceTest 单方法 1 项通过、0 跳过，验证外部等待期间账单锁已释放、并发续跑只回查、只提交一次。通道不支持原回退查询时保留 UNKNOWN 给人工处置，不自动重发资金命令。


## 2026-10-18 发布兼容补充（T14 阻断）

现有生产版本不在本分支祖先链上。只读合并预演发现 47 个冲突文件，迁移同号不同名 9 处，生产 jar 已确认包含 V407 与 V410–V423 的独立订单/物流迁移。本分支不能直接替换生产；当前完整测试只证明本分支行为，不证明与线上历史兼容。

- [x] 固定生产前版本、共同祖先与只读合并预演，保留原生产软链。
- [x] 将冲突范围、迁移清单和兼容整合步骤写入《积分第三阶段验收记录》。
- [ ] 确定以生产基线整合后部署，还是先交付分支并暂缓部署（已向用户请求选择）。
- [ ] 确认生产额外 recipient-salutation 补丁及 Flyway 历史；既有迁移 checksum 不得修改。
- [ ] 独立工作树兼容整合订单、现金退款和积分协议，保留线上未决指令及现有业务能力。
- [ ] 整合提交重新完成定向、原生、全量与三端验收，再执行 T14 发布步骤。

不因“积分未上线”而迁移或重置已上线订单/退款数据，也不为绕过冲突开启 outOfOrder 或修改 Flyway 历史。
