# Task56 设计与实施报告

日期：2026-10-23。当前阶段：IMPLEMENTED_REVIEW_READY（实施审查候选，尚未独立验收/提交）。唯一工作树 /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex；分支 codex/beauty-foundation；IMPLEMENTATION_BASE/当前HEAD=f33bf12cff693472330aa659b5d1615aee3e9717。55D已接受；57/58/59/60不由本步交付。

> §1–6保留设计阶段历史快照（当时BASE8946、只读/等待技术放行），不是当前实施状态。§7–17保留逐批真实RED/GREEN/setup历史；最终实现、验证边界和审查清单以§18–22及末尾追加结果为准。

## 1. 执行边界和已做工作

已读brief、preflight、整体checkout设计相关§、项目开发规范/Java、CLAUDE、ADR-045及相关源码。采用writing-plans做既有方案实施深化；档位2（新增表族/跨域合同，复用已有PRD/主TDD；ADR-046草案已由root落盘，非本agent owned）。只读审计以及写 `task-56-design.md` 和本报告，未改生产/测试/迁移，未执行测试、构建、schema、容器/native命令，未stage/commit/push/merge/deploy。没有派子agent。开始status只有root owned progress.md为M，保持不碰。

当前所有新测试selector均为设计合同，**执行数=未运行**，不是0通过。先行为RED/消融/原生race等要求详见设计§10。无需用户重复批准已有业务范围，等待root技术放行。

## 2. 零售/餐饮源码与remote证据

只读命令：`git status --short`、`git branch --show-current`、`git rev-parse HEAD`、`git for-each-ref`、`git log`、`git diff --numstat HEAD codex/food-next01 -- backend/shop-core/src/main/java/ai/neargo/shop/trade`、`git ls-tree`、`git show`、`git grep`。不fetch、不switch。

`git ls-remote --heads origin codex/food-next01 codex/ai-shop-codex codex/beauty-foundation` 经只读网络批准，exit0，仅返回：

```
96c94cd18efab05b613faff1111238e88f141b54 refs/heads/codex/ai-shop-codex
```

未发现远程food-next01/beauty-foundation。同名本地food HEAD `fbda26d2f8b7fb254e5e43ed8c6d9622f621c44c`，commit 2026-10-22 18:02:49 +0800，`feat(pricing): validate and atomically import draft seeds`。它不是当前HEAD祖先；merge-base=`95951839137ba5fdf066a155d1d2c9d8a0a60b76`。因此只称“已知本地food分支”，不凭日期声称远程最新/已整合。

四关键文件在HEAD和food分支完全同blob：

| 路径（backend下） | 两边相同Git blob |
|---|---|
| shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java | 9bfdafd01b57a221726ca3bffe50ff3a097c7664 |
| shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java | da9ff6dc9f136638007e3b23d468f709110144cc |
| shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java | 5ade97d4a6f804d86ef953d0ff76cc41488776cc |
| shop-app/src/main/java/ai/neargo/shop/portal/port/CouponPortRouter.java | 1c93c9f91aeb24a45d17e672d61beafc4f60382c |

`git diff --numstat HEAD codex/food-next01 -- backend/shop-core/src/main/java/ai/neargo/shop/trade`真实13项（全新增，无删除，共978行）：

| trade相对文件 | 新增行 |
|---|---:|
| dto/PricingConfigCommands.java | 52 |
| entity/OrdPricingTemplate.java | 26 |
| entity/OrdPricingTemplateRevision.java | 27 |
| entity/OrdStorePricingConfig.java | 24 |
| entity/OrdStorePricingRevision.java | 32 |
| mapper/TradeMappers.java | 9 |
| policy/PricingPublicationPolicy.java | 496 |
| service/PricingSeedService.java | 16 |
| service/PricingTemplateService.java | 19 |
| service/StorePricingConfigService.java | 25 |
| service/impl/PricingSeedServiceImpl.java | 136 |
| service/impl/PricingTemplateServiceImpl.java | 49 |
| service/impl/StorePricingConfigServiceImpl.java | 67 |

该13项对应 `9f5140dff feat(pricing): publish versioned pricing configuration atomically`、`fbda26d2f feat(pricing): validate and atomically import draft seeds`。另common/pricing有MoneyMinor/PricingFacts/PricingEngine/规则/税/benefit内核（d9be484d7→78eb6144d等提交），PricingSourceRef仅受权后的source引用，既非身份票据也非已接入writer。阅读PricingFacts/PricingEngine/StorePricingConfigService后，`git grep PricingEngine|PricingSourceRef`在food的生产core/app无消费接线；四writer/router未变，未解决B prepare显式buyer或PAY券remote路由。

整个food插件差异479 files，36709 insertions(+)、118 deletions(-)；其平台V532 pricing_configuration_publication/V533 pricing_seed_provenance与本分支已应用personnel/resource迁移重号。root已明确裁决：本次不合入大批food或CKO配置内核，按当前实际普通writer做最小buyer/local券接线，报告保留未整合边界。主分支后续整合需独立重编号/验收，56不自作处理。

## 3. 审计事实与主要风险闭合提案

* 当前V最大为V538 member_reference_actor_namespace，未发现CheckoutPort/ExpectedCheckoutQuote/fulfillment_authority实现。候选追加V539，仅文档DDL，未建migration文件。
* doCreate真实点位：1567定义；1678discounts；1816creationRecovery.register（REQUIRES_NEW）、1824enlist、1826slot、1827stock、1831主单、1937子单、1942LineShares、1963item、2025coupon、2034campaign。seal必须在register前，同次shares先算后复用，不能1963前才比价。
* CampaignPortRouter.candidates/flashPrices/giftRules隐式SecurityUtils当前人；B prepare需真实buyer参数，不能伪造C context。现GoodsQueryPortImpl.snapshot含flash，故必须一起显式接buyer。设计§4.1为真实现Port增量，不建第二价格引擎。
* CouponPortRouter→LegacyCouponRouteResolver→PAY registry/estimate在某些来源可远程；普通core OPEN/ABORT_DECIDED为本地。requireWritable已有MANDATORY事务/root锁；设计§5指定local-only quote/lock路径、精确state与复核次序。不得全禁正常金额券，也不得锁外估算授权锁内成交。
* StoreSalesCatalogServiceImpl.read的requireExternalScope明确禁止callerTX；设计新增同owner本地lockForCheckout，复用原版本选择与hash算法，最终锁catalog head，本地真实事实进入seal，公开read边界保留。
* 当前StockPort三实现均非HTTP：PLATFORM本库、DUAL本库+outbox、INVENTORY独立invTransactionManager；最后一种只保留既有creation intent补偿，不能称库存与ord原子。postseal attribution/card/receiver/campaign等亦已沿实际本地实现审计。
* doCreate尾detailOf无条件logisticsSyncOf→logisticsPort.summariesOf可能remote；managed尾仅本地payView/Ord结果，旧零售路径不改。完整IO矩阵见design§5。
* SecurityUtils.currentToken来自认证details；TokenStore.SessionData为TokenStore嵌套record（没有单独SessionData.java）；TokenStores为注入interface实例，不能写成静态API。MerchantOperatorAccessPort实际在spi.user，允许查询显式拒callerTX。当前C验证在锁外紧邻TX完成，不能持锁查Redis。
* strict customerRef调用现CustomerIdentityPort.resolveClaimReference，隐含CUSTOMER权限不得绕过；当前operator namespace与prepare权限在每次重放复核；同原payload重放先查owner持久结果，原cr1自然过期不重新签发身份；冻结命令无cr1/mc1/claimToken/contact。
* 最终COMPLETED应在MybatisStatusStore.cas订单分支CAS前查policy，StatusCasMapper条件写兜底。OrderMachine明确包含VERIFIED/DELIVERED_BY_MERCHANT/RECEIPT_CONFIRMED/RECEIPT_AUTO_CONFIRMED/INTERVENE完成分支；receipt current/auto/signed/legacy/ReceiptHandler路径均需测试。
* doMarkPaid现给所有sub出码并通知服务发货；managed必须由持久authority跳过这两处。旧offline直接信任在56先拒managed，57才正式补资金receipt，不提前假实现。
* RefundLedgerMapper明确注释“不动version”，实际addItemRefund/addSubRefund/updatePrepared也不增version。因此source hash必须包括真实数量/退款，不能只看Ord版本。设计以op最后已验证source观察hash+递增revision记录引用变化，不建金额账。
* creationRecovery.register旧key返回原payNo、RELEASED不删；op稳定key+永久CREATED/REJECTED映射保证缓存过期/重启不新建。失败后confirmation不消费；已持久旧意图无order时只恢复原意图，不另key重试。

几次探索路径猜测不存在（spi/merchant/MerchantOperatorAccessPort、transition/MybatisStatusStore、food-domain目录等）已按rg校正；这些是只读定位，无测试/编译失败含义。没有把不存在selector算通过。

## 4. 当前owned与验收状态

本轮唯一写入：

1. `.superpowers/sdd/beauty-backend/task-56-design.md`
2. `.superpowers/sdd/beauty-backend/task-56-report.md`

主TDD/progress/brief/preflight/整体方案/reviews均root owned；当前未stage任何路径。生产候选文件归属与七类AC选择器见design§9–10，下一步需root先核对并完成技术放行。没有生成物/SQL/hash运行证据；DDL文本hash可只读计算后由root独立固定。

| 里程碑 | 状态 |
|---|---|
| 读brief/规范/身份ADR | 已读 |
| 当前retail/food/ref审计 | 完成，root基线裁决已收到 |
| 六方法/HTTP/身份/锁/经济seal设计 | 已写候选，待root技术审阅 |
| 追加SQL与底层CAS设计 | 已写候选，未执行 |
| RED / GREEN / 消融 / native | 未执行 |
| API/method/schema生成与三语/架构/回归 | 未执行 |
| 独立spec/quality审查与产品commit | 未开始 |

保留Task60债务：OpsPoints、V506、beauty-domain和job.config注册、当前SQL gate仅backend扫描的插件定向覆盖；不得改known-*放宽，不宣称全backend通过。仍禁止shared autotest drop脚本、其它容器/VM/远程操作、push/merge/deploy。

## 5. 待root裁决/核对的具体项

* 接受显式buyer现Port增量、catalog本地锁读与coupon本地quote/lock三项必要边界；全部真实实现，旧API不重构价格算法。
* 确认source观察revision以及PaymentFact在57前缺paymentOperation/outTrade/receipt号时null的精确语义；未知资金保持nullable，普通已匹配PaymentReceipt按实际事实展示。
* 审核V539候选SQL后固定最终SQL SHA256；发布确认expire=300s、第一期每op只有quoteRevision1，scope/selection/过期重新prepare需新op。
* 精确owned已补ProductMappers nested catalog、StoreDirectory真实merchant实现、PMT CouponAllocService/ActivityPricingService与三语/权限/生成真源；无待定位的虚构mapper。追加的本地StoreDirectory事务能力与selected活动校验是窄范围真实实现成本，供root一次性审阅。


## 6. 根技术反馈闭合（本轮文档修订）

1. source改为op第一条SQL FOR UPDATE后，order/sub/item/refund/售后统一RR consistent read。无sub当前读混入。只有verified观察更新pair；UNKNOWN复用最后pair（首次0/null），BLOCKED、空行、资金null，58不得把旧pair当当前可消费。
2. Catalog方法显式buyer与当前真实CONSUMER principal校验；product和merchant新增本地事务事实方法均核平台DataSource绑定。原read禁TX保留；StoreDirectory原entity/store/ACTIVE语义复用；core不引用merchant impl，不造B授权凭证。catalog Definition无售价，经济事实仍来自GoodsQuery/writer。
3. quoteFor与doCreate一次共同pricing调用位置已列；createConfirmedFor不先quote再重算。原号段生成移至纯校验之后；managed全部持久字段与shares只取同次返回束。
4. 同一quote/op首个confirm/place key永久绑定，异keyCONFLICT，新增无alias表。place元数据claim短TX先永久登记key，最终成交失败也不丢key；RELEASED原intent使原op终止，不能复活。claim不产生订单/库存/资金事实。
5. SQL去除PREPARING/PLACING持久phase；current_quote_no生成后与quote同TX插入。PLACING只依据原intent派生恢复观察；详细op/quote/confirmation状态表已给。
6. root已接受56最小阻断旧managed offline直接收款捷径，不实现57 receipt/payment。档位纠正为2，ADR-046由root维护。
7. PMT真实allocate不处理scopeType/minQty，设计仅支持CASH/PERCENT、ORDER、单次、ALL无附加scope、无minQty/benefitRef的普通金额券；MKT FULL_CUT/DISCOUNT保留。COMBO可能只赠积分，需owner真实元数据校验，不能以金额0或AppliedActivity形状猜安全。

所有新增测试方法仍只是计划，未运行生产代码、测试、生成器、schema或容器。该修订只更新本报告与design，不表示root已技术放行。

8. C GET本地方法明确为CheckoutService.readQuoteForCurrentBuyer(OperationRef)，真实current buyer条件查op/quote；不伪造B scope，不增加第7个共享CheckoutPort方法。PaymentFact另明确既有真实tradeNo字段，outTradeNo仍nullable不冒充。

文档DDL候选SHA256（仅提取第一个sql代码块，UTF-8、LF、末尾一个换行；不是迁移执行证据）：`5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b`。root仍须在真实V539文件首次执行前独立固定最终SQL/hash。

文档编辑曾遇本环境elevated Python中文脚本解析问题，两次仅临时脚本解析失败，未更改文档或产品；转换ASCII-only脚本后成功。无自动审批拒绝。

9. SQL ck_sub_checkout_authority显式要求managed分支authority和fulfillment非NULL，堵住MySQL CHECK UNKNOWN通过；原生计划加入NULL/非NULL不配对反例。该文档修订发生在首次执行/冻结之前，不回改任何已执行migration。

## 7. 实施 checkpoint 1 — 首个真实 RED

IMPLEMENTATION_BASE=f33bf12cff693472330aa659b5d1615aee3e9717；根于设计技术核对后授权实施。V539按已pin首SQL块8444字节生成，SHA256=5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b，首执行前本agent与root分别复验；之后冻结。schema-test通过现gen-test-schema.py生成（360表/2400seed），未改生成器。

首RED命令：JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=ManagedServiceCompletionFlowTest -Dsurefire.failIfNoSpecifiedTests=false。日志/tmp/task56-managed-first-red.log，2026-10-23 17:48:16+08结束，exit1：7 tests/6 failures/0 errors/0 skips。真实StatusCasMapper.casOrder对持久SERVICE_EXECUTION返回1（预期0）；真实TradeStatusWriter的VERIFIED、DELIVERED_BY_MERCHANT、RECEIPT_CONFIRMED、RECEIPT_AUTO_CONFIRMED、INTERVENE五条均未拒绝。旧未管理SERVICE完成用例通过。是业务断言RED，无编译/setup错误。

目前仅新增该真实flow测试、固定V539、生成schema；RED后开始最小ManagedServiceCompletionPolicy+CAS条件及OrdSubOrder元数据映射/错误翻译。未宣称覆盖全部HTTP/receipt入口或native；其余AC仍待实施。

## 8. 实施 checkpoint 2 — 完成守卫 GREEN / 合同 RED

/tmp/task56-managed-green.log：同JDK21/offline reactor命令，selectors=ManagedServiceCompletionFlowTest,OrderReceiptWiringFlowTest,OrderReceiptConcurrencyFlowTest，exit0，2026-10-23 17:51:32结束，17 tests/0 failures/0 errors/0 skips（7+5+5）。真实SQL CAS与共享writer守卫阻止完成；当前C收货HTTP及旧收货并发回归通过。尚未native/消融，也未把这7条宣称为所有legacy入口覆盖。

新增CheckoutPort六方法及无校验record编译骨架后先跑CheckoutContractTest：/tmp/task56-contract-red.log，exit1，17:52:26结束，3 tests/3 failures/0 errors/0 skips。非法scope/数量未拒、重复/超限行未拒、调用方clear改变请求内容均真实断言RED。现开始加规范输入、不可变拷贝和敏感toString脱敏；没有实现假的未来payment或Remote方法。

## 9. 实施 checkpoint 3 — 当前身份/权限 RED

CheckoutContractTest GREEN：/tmp/task56-contract-green.log，exit0，2026-10-23 17:53:51，3/0/0/0。

/tmp/task56-authority-red.log：10 tests/4 failures/2 setup errors/0 skips。C真实TokenStores+登录/个人资料filter正例通过；无details伪principal、B realm、撤销token、token错配buyer四个真实业务RED。B夹具DelegatedIdentity参数顺序错误造成2 errors（不算RED）；源码已修但首次javac已先读取旧夹具，故显式重跑。另把MCH编号与所绑定USR编号分开，避免把真实双namespace身份当越权反例。

/tmp/task56-permission-red.log：2026-10-23 17:58:41结束，5 tests/4 failures/0 errors/0 skips；owner正例通过，未授权clerk、owner停用、跨店、错namespace均真实未拒绝。随后开始真实CheckoutAccessPolicy：token池/namespace/active user，以及现MerchantOperatorAccess+BizIdentityResolver当前权限/数据域。真实merchant SQL含MySQL JSON_CONTAINS/JSON_QUOTE，GREEN转已批准自有原生fixture，不造H2权限假实现。

新增scripts/verify-checkout-foundation.mjs：先核V539原hash/8444字节和Colima MemAvailable>=512MiB，自有UUID cached9.7.2普通磁盘64M，随机库/临时凭证仅env，V538生成结构fixture后执行原V539，finally仅清自有容器/临时目录；不宣称全历史Flyway replay。该runner首次运行结果待下一checkpoint。

## 10. 实施 checkpoint 4 — 当前身份 GREEN / 原生环境纠正

/tmp/task56-authentication-green.log：C实际TokenStores+登录filter，5/0/0/0，exit0，18:02:39。首次native runner用了停止的default Colima profile，在任何容器/SQL前退出；这是本agent runner配置错误，不是资源阻塞。已改为LIMA_HOME=/Users/robin/.colima/_lima limactl shell --instance colima-hxkey-tests cat /proc/meminfo，docker明确--context colima-hxkey-tests，未操作VM。

/tmp/task56-authority-native-green-r2.log：每批预检MemAvailable3651168KiB，cachedmysql9.7.2 DISK64M，自有UUID/随机库，V538结构夹具+原V539。CheckoutPermissionFlowTest5+CheckoutAuthenticationFlowTest5=10/0/0/0，18:04:04，Maven与runner exit0；自有容器finally清理。MySQL实际merchant JSON owner查询通过；单独inv profile仍H2，与平台测试数据库不同，不据此宣称库存独立库原生覆盖。V539未变。

runner已sanitize Docker异常stdout/stderr，避免argv里的随机MYSQL_PWD泄漏；root反馈后将cleanup失败设置exitCode1并打印仅自有name，不吞掉原测试失败。未因此无风险重复整套验证。

开始真实报价AC：CheckoutPricingFlowTest用真实merchant/user、商品SKU、目录publish及MemberCodeResolve customerRef，不mock owner。CheckoutService/Port当前仅测试编译骨架（六方法尚未实现，不算交付），下一步先取prepare非空/实际303分/无订单意图库存副作用的业务RED，再实现共享writer定价。

## 11. 实施 checkpoint 5 — 报价 owner 真实 RED

/tmp/task56-pricing-first-red.log 的 1 error 为目录真实 publication 缺 registered PLATFORM source，属于 setup failure，不计 RED。沿 StoreSalesCatalogIT 使用 shop.catalog.publication.archive-only=true 专用不可变出版 fixture；没有 mock 发布或放宽生产 outbox。

/tmp/task56-pricing-red-r2.log：CheckoutPricingFlowTest 1 test / 1 failure / 0 errors / 0 skips，prepare 必须返回 owner-backed quote 的非空断言失败；真实商品、目录发布、strict customer reference 均成功。native fixture 仍为 V538 结构加原 V539，自有容器串行并 finally 清理。开始共享单 writer 定价提取及显式 buyer。

## 12. 实施 checkpoint 6 — 报价 GREEN / 优惠与确认递进 RED

/tmp/task56-pricing-green.log：首个真实 prepare native 1/0/0/0，Maven/runner exit0。编译前一次 Item 构造参数遗漏 goodsNo 是 setup 编译错误，修为原 command 的 null goodsNo + 实际 SKU（价格/最终 goods 身份仍来自 writer snapshot），不计 RED。quoteFor 与旧 doCreate 已共享 checkoutPricing，旧 scope=null 分支保留原算法；新 explicit buyer 方法接入实际商品和营销 owner。

/tmp/task56-ordinary-selection-red.log：7/4/0/0；普通 OPEN PMT 金额券、原操作报价重放与基础报价通过，GOODS scope、minQty/多次券用例首断言、DRAINING 错误映射及零金额积分 COMBO 的边界失败。随后 /tmp/task56-pricing-authorization-red.log：9/6/0/0，加当前 C confirmation=null 与超大 SKU 价格失败。当前 C 用实际 TokenStores issue/get 与真实 active user，认证 filter 已在 AuthenticationFlow 独立覆盖；本用例不是 HTTP 完成证明。

开始真实 quoteOrdinary/lockOrdinary 本地 capability、PMT/MKT owner 普通限制与选中活动 metadata 校验。managed 候选保留积分效果供 owner 明确拒绝，旧 API pointsRewards 开关保持原义。managed split 使用逐行 multiplyExact 和总额 addExact，在任何 group sum/discount 前失败关闭；不改旧零售金额算法。已实现 confirmation 的 op→quote 锁与永久首 key；尚未实现 place，下一轮必须观察确认成功之后的 place 业务断言 RED，不能把未触及断言当证据。

## 13. 实施 checkpoint 7 — 真正确认后 place RED → GREEN

/tmp/task56-place-first-red.log：9/1/0/0，真实 confirmation 已持久，唯一失败明确在 place 返回 null；后续建单断言此前没有被执行，不算先前 RED。随后 /tmp/task56-place-green.log：9/0/0/0，BUILD SUCCESS，runner exit0，自有容器清理完成。当前实际 C 确认→首 key claim→同 writer 最终 TX→seal→OrdItem/sourceLine→CREATED 全链路通过；尚非 HTTP/并发/全部 AC 证明。

最终 context 持目录 head 后锁真实 local coupon 与原报价 selected activity，再重入已持有的 head 读取当前生效版本；实际 pricing 一次执行，最终 fresh Clock/完整经济 hash seal 在 creationRecovery.register 之前。即将新增价格/同额出资/目录/券锁跨 expiry 负例；已有防线若首次即绿会如实记 firstGREEN，再用最小消融验证，不补造历史 RED。

## 14. 实施 checkpoint 8 — seal 负例 firstGREEN / source RED → GREEN / managed 支付边界 RED

/tmp/task56-pricing-seal-first.log 为可控 Clock 与 CheckoutClock 默认 bean 名冲突的 setup 错误，未触及业务；组件命名为 checkoutTimeSource 后可注入实际 checkoutClock。/tmp/task56-pricing-seal-first-r2.log 为14/1/0/0：价格变更、同金额出资变更、目录新版本、普通 PMT 券成交首次即绿，拒单负例核订单/意图/stock/sourceLine均无新增；只有 INNODB_TRX 未显 LOCK WAIT 的观测断言失败，expiry断言未执行。复用已审55D模式：独立真实 JDBC root FOR UPDATE，worker精确 lockPromo + CouponPortRouter.lockOrdinary + sun.nio.ch.Net.poll 栈及unfinished future作屏障，随后推进Clock/解锁；这是耦合真实调用点的测试屏障，不改performance_schema。/tmp/task56-source-green-first.log 中 Pricing 14/0/0/0，包含该真实券锁等待跨expiry拒且无意图/库存/订单；已有防线首次GREEN，消融仍待。

/tmp/task56-source-first-red.log 中 source7/7/0/0（source=null及foreign sub未拒，后续历史退款断言当时未触及）；附带旧锁观测失败不算expiry RED。source最小实现后 /tmp/task56-source-green-first.log source7/1/0/0，外层TX测试先在TX内建delegation被既有安全守卫挡住，测试调用次序错误；移为先delegation再TX后，/tmp/task56-managed-source-red.log source7/0/0/0。source资金fixture是SQL设置现ord历史状态及stl_payment SUCCESS，真实SettlePort/PaymentLedger只读查询，不代表init/settle/回调真实支付流程完成；57/60边界保留。原生实际RefundLedgerMapper不增version的退款，source hash/revision变化且行available从3到2；UNKNOWN保持last-verified pair并空行/空payments。

同 /tmp/task56-managed-source-red.log：ManagedLifecycle3/3/0/0（原paid出verifyCode、旧B offline不拒、共享offline trust不拒），FrozenEconomicEvidence1/1/0/0（pricing_json缺evidence）。总11/4/0/0，均业务断言RED。开始最小managed payment投影守卫与经济证据持久字段，不新增57支付能力。source RR并发/消融、HTTP及其余AC仍待。

## 15. 实施 checkpoint 9 — HTTP、首键与 source 并发

/tmp/task56-http-native-red.log：14/6 failures/1 error。HTTP四条真实404是未装controller业务RED；expiry-before-place仍CONFIRMED为真实RED；authority CHECK实际3819拒绝但测试期待错误Spring异常类型、shipping断言使用不存在sub_order_no列均是测试错误，不计RED。Evidence已GREEN。/tmp/task56-http-native-green-first.log：22/0/0/0 BUILD SUCCESS、runner exit0（source9、HTTP4、native6、managed3）。新增source RR测试在实际Order SELECT后屏障，另一事务真实RefundLedger提交、另一source等待op锁，原source返回旧303/3，后source202/2且revision递增；不是拼接业务事实的mock。

/tmp/task56-buyer-numeric-red.log：10/1 failure/3 errors；字符串COUNT "3"真实HTTP接受200为RED，小数3.5原本拒绝；活动fixture漏entity_no造成3 setup errors。补fixture并只给CheckoutLineReq数量增加局部严格整数反序列化后，/tmp/task56-buyer-numeric-red-r2.log：10/1/0/0，HTTP7 GREEN；显式buyer的GOLD受众flash+CUT及gift拒绝firstGREEN；CUTOFF flash在managed路径未拒为真实RED。最小新增ActivityPricingService.ordinaryFlashPrices复用原flash循环，保留原API CUTOFF语义，仅managed获胜的复杂触发拒绝。未增加第二价格引擎或新的阻塞锁。

## 16. 实施 checkpoint 10 — 原生恢复与时效补测

/tmp/task56-deep-native-green-first.log 是缺BizException/ErrorCode imports的编译失败，未跑测试，不计RED。/tmp/task56-deep-native-green-r2.log：43/1 failure/1 error（source9 GREEN，最低managed7 GREEN，native8中1 fixture错误，pricing19中1时钟fixture失败）。新复杂flash已GREEN、controlled ord_item trigger使实际writer回滚并进入RELEASED，首place key保留、新key拒绝、同keyREJECTED不复活，首次GREEN。scheduled catalog跨真实coupon锁后刷新当前版本并拒绝firstGREEN。

两个fixture修正：过期identity同时将issued_at提前，保持expires_at>issued_at真实CHECK；普通目录变更的effectiveAt使用同一个测试Clock，避免wallclock推进导致发布的新版本对冻结测试Clock尚未生效。/tmp/task56-entries-restart-first.log：38/0 failures/7 errors；native8 GREEN、pricing19 GREEN、RestartNative1 GREEN；实际managed新入口7条均在ord_status_log fixture错误updated_at列处中断，未到业务，不计RED。

CheckoutRestartNativeTest实际SpringApplicationBuilder启动首个完整ShopApplication与独立Clock，完成真实prepare/confirm/place，关闭整个ConfigurableApplicationContext并断言inactive；第二个独立完整上下文使用同一native数据库，断言新CheckoutPort bean不同对象，重新签发真实当前C session，过期后原key返回原order、B find同order、source稳定sourceLine，订单仅1条。首confirmation丢响应同key恢复亦保持原确认。删除sys_idempotent只为同时排除临时缓存，不能代替此真实context close/restart证据。source金额仍明确为历史SQL stl receipt fixture，不是Task57真实支付流程。

## 17. 实施 checkpoint 10后的历史收尾边界

正在补实际service入口、原PREPARED/RECOVERING/RECOVERY_REQUIRED意图、当前B显式grant/revoke/courier、catalog capability、真实coupon migration双方先获锁、shares余数及最小消融。新增已有防线用例如首跑GREEN均只记firstGREEN，之后消融单列，不倒造TDD历史。root已要求新增Java FQN/import/120列/public Javadoc全面清理后再交独立审查，尚未清理完，不宣称完成。

coupon peer正常writer审计：marketing/coupon/impl/CouponServiceImpl.java:119、497两处领取/补发仅BizKey.COUPON=CP；promotion/service/impl/PmtCouponServiceImpl.java:278、586仅BizKey.PROMO_USER_COUPON=PU。无支持writer在另一owner中新建相同user_coupon_no；已存在同号alias两侧root均按MKT→PMT锁。迁移LegacyCouponSourceFenceServiceImpl.transition与requireWritable同一平台mapper lockLegacy/lockPromo root；状态读回和实际owner实例锁在最终TX，普通quote/markUsed继续实际owner分摊。root接受不为不受支持的直接SQL伪造peer新增扩大旧retail重构；若独立审查发现新的支持写路径，需据实处理。实际transition竞争证据待下一checkpoint。

## 18. 实施 checkpoint 11 — 真实入口、首键 namespace 与恢复状态

/tmp/task56-actor-entries-red.log 为27 tests/0 failures/3 errors：prepare 同键被另一合法 MCH actor 使用时错误抛 CONFLICT，是实际 owner 行为缺口；另外两条 managed 断言引用不存在的 shipment received_at 列，属于测试错误。prepare key hash补完整 realm/subjectKind/actorNo；fixture改为真实 shipment status/version。/tmp/task56-boundary-green-r2.log 为25/0/0/0（Native10、ManagedLifecycle10、OwnerBoundary5），具体时间以日志为准。早一次 boundary 首跑是泛型 assert 编译错误，不算 RED。

真实完成入口包括 FulfillmentQueryPort.complete、PickupService.verify（历史核销码）、MerchantOrderService.markDelivered/intervene、OrderService.confirmReceipt、OrderReceiptCompleter.autoLegacy、包裹当前C/auto receipt。拒绝后检查主子状态、completed_at、完成状态日志与事件outbox；包裹入口检查预先发生的 shipment 写回滚为 SHIPPED/version0。signed 入口对合法 managed STORE_VERIFY 本来就不适用 EXPRESS，返回false且不改shipment；明确此用例没有到达最低CAS，最低CAS另有真实直接反例。C旧收货端点另在 HTTP test 实测20018。

实际 source-fence 双向竞争：真实 transition BEGIN_DRAIN先赢，place明确拒且无intent；place先锁root后通过真实Goods mapper屏障暂停，transition worker在实际 lockPromo/socket read等待，place提交USED后transition才进入DRAINING。未改performance_schema。禁RPC测试在真实ordinary coupon checkout期间核 EntitlementOwnerRegistryPort、LegacyCouponUsePort、LogisticsPort、AppointmentSlotPort 均无调用。默认不支持的 StoreDirectory capability 直接fail closed；仓库不存在RemoteStoreDirectory实现，不把dynamic default capability反例描述成已部署remote集成。

原intent PREPARED/RECOVERING/RECOVERY_REQUIRED均查询原意图并返回恢复观察，不建新单。RELEASED用实际ord_item失败trigger导致writer回滚和恢复后，原place key仍保留；不同key冲突、同keyREJECTED。删除24h cache和过期strict reference不影响已提交同请求的原buyer/quote/订单长期映射。首confirm key、首place key均永久绑定，无key alias表。

/tmp/task56-final-price-http.log：HTTP8+Pricing20=28/0/0/0。新增等权两SKU分摊用真实行顺序核due179落90/89，真实OrdItem、clientLineRef映射与merchant discount23相同；不是只比preview总额。

## 19. 消融与最终原生验证

已有防线初次即GREEN的负例如实记 firstGREEN；以下为后续最小消融，不倒造前置RED。脚本均保存原始bytes并在finally逐文件恢复：

* /tmp/task56-ablation-a-red.log：6 tests/6 failures/0 errors/0 skips。移除writer最终seal，变价/同额换出资/目录变更三个实际place均错误成交；explicitbuyer snapshot改回隐式buyer，GOLD买家应80却得到101；source RR改RC后读到不存在的拼接退款事实；最低CAS managed predicate移除后实际affected=1而应0。
* A四文件before/after JSON完全相同：OrderServiceImpl 471f4d520e82dfc70e573ce97edc33eb97ee29fafb86d17d5db40c6a85c3240d；CheckoutServiceImpl 84ba0d51c1b51b6a1fe5f55e3c87463ab54af3a3f9f1286c9d1c1236223337eb；GoodsQueryPortImpl 840ab786c5878407b71ae20dd5a0e170961f9a0923d7b7385f868faf205009db；StatusCasMapper 605eaaebf15dc6ca300f2f5ef0a84ff87341b31b0a41413a347429ae0c8e5911。证据 /tmp/task56-ablation-a-before.json、/tmp/task56-ablation-a-after.json、/tmp/task56_ablation_a.py。
* /tmp/task56-ablation-b-red.log：2/2/0/0。去掉最终post-lock expiry并只沿用coupon锁前catalog，真实coupon锁等待跨expiry及scheduled catalog validFrom均错误接受。CheckoutQuoteGuard finally还原SHA 9efdd9ee48df666ee489ed2a893448defd539fec12f425959a6c345d1197f323，/tmp/task56-ablation-b-hashes.json与脚本保留。
* /tmp/task56-final-native-all.log：19:40:51 BUILD SUCCESS，runner exit0。shop-base CheckoutContractTest **4/0/0/0**；shop-app **83/0/0/0**（Source9、HTTP8、Native11、Permission7、最低Managed7、ManagedLifecycle10、OwnerBoundary5、Restart1、Authentication5、Pricing20）。不把历史批次累计成更多覆盖。
* 这轮是还原之后完整GREEN，但它编译的是旧ClockConfig注入形状。随后只改测试类显式 @SpringBootTest(classes={ShopApplication,ClockConfig})，/tmp/task56-clock-config-final.log 的 Pricing分摊1+真实Restart1=2/0/0/0，消除本任务新引入的Spring7.1 ignored默认配置WARN。已有Flyway H2版本提示、HOST mapper重名、测试禁OTP、未配真实外部渠道、测试故障注入WARN不声称清零。
* 之后新增Java规范修正只有imports/Javadoc、≤120列、4空格与必要braces；/tmp/task56-braces-proof.json及multiline脚本对比确认其它token相同。冻结V539从未改变。后述owner职责迁移属于实际产品增量，单列真实RED与定向GREEN，不把83冒充修改后重跑。

所有native批次均检查MemAvailable≥524288KiB，cached mysql:9.7.2、DISK64M、自有UUID串行、finally清理；未动VM/别人容器/共享库。正常runner exit0包含cleanup结果；随机凭证未输出。原生平台fixture为V≤538结构加精确原V539，不是完整历史Flyway replay。inventory profile仍H2，未声称DUAL/INVENTORY独立库原生或部署验证。

## 20. 相关旧路径、架构纠正与规范gate

旧路径命令使用JDK21/offline M2，selectors如下，完整日志 /tmp/task56-existing-regression-final.log：

```text
CheckoutOffersFlowTest,StoreOrderRoutingTest,OrderReceiptWiringFlowTest,
OrderReceiptDeadlineFlowTest,OrderReceiptConcurrencyFlowTest,
OrderAutoReceiptTransactionFlowTest,BizOrderFulfillFlowTest,M4FulfillmentFlowTest,
OfflinePayFlowTest,LegacyCouponCheckoutJournalFlowTest,LegacyCouponCheckoutRecoveryFlowTest,
FoodEmbeddedWiringTest,ArchitectureTest,BackendI18nParityTest,
ConditionalBeanWiringTest,SchemaParityTest,BizRoleSeedTest,ErrorCodeUniqueTest
```

实际129 tests/3 failures/0 errors/1 skipped，不能称整个回归命令GREEN。其中普通routing7、offers5、收货5+8+5+4、B履约15、M4履约22、offline11、legacy journal6+recovery5全部通过；BackendI18n6、Conditional1、Schema1、BizRole3、ErrorCode3通过。Food6中5通过，standalone专用项因实际food.build.shape=retail不匹配standalone而skip；不声称Task60 standalone构件测试。

架构16项有3失败，其中两条为implementation base已有OpsPoints全限定PathVariable和store包未登记；第三条controllersMustNotTouchMappers的3条violations **由本任务新增** CouponPortRouter直接使用LegacyCouponFenceMapper造成。此为真实RED，已修，不能作为旧债：

* 查询/原owner状态验证/根锁迁回既有LegacyCouponSourceFenceServiceImpl。实际接口增加 ordinarySource(tenant,buyer,userCouponNo):SourceSystem 与 lockOrdinarySource(...):SourceSystem；后者MANDATORY且核真实平台DataSource绑定，仍是同一LegacyCouponFenceMapper与MKT→PMT锁序、锁后重读；无registry/PAY RPC。
* Router只消费实际owner能力、随后选定PMT/MKT实例锁和同一分摊算法；不是把Mapper换名包装成另一web入口。
* /tmp/task56-owner-architecture-final.log：shop-base Contract4；shop-app18/0/0/0（HTTP8、OwnerBoundary5、Pricing普通券+两锁等待4、实际失败architecture rule1），BUILD SUCCESS/runner exit0。这是最终owner迁移后的GREEN，原生真实双向transition/expiry/catalog时效/noRPC均复验。
* 后续仅补原AC“合法取消/未付超时不完成服务”的实际入口正例，无生产修改；结果在末节记录。

Java增量人工/脚本范围是全部owned新增及diff新增Java行，跨shop-base/core/merchant/app；不靠ArchitectureTest只扫app来宣称其余模块。历史大文件未全量重排；公开SPI/HTTP说明当前授权边界和拒绝原因，private计算继续共享writer。git diff --check通过。node scripts/check-sql-portability.mjs exit0（162处/51文件扫描，54条既有debt，无新增方言）；未改known-*或checker。插件SQL定向gate、JobLock/beauty/store域登记与真实部署仍归60。

### 20.1 共享静态检查基线（不归为Task56 GREEN）

当前 /tmp/task56-shared-gates-r2.log 与只读HEAD隔离抽取 /tmp/task56-shared-gates-head.log 均为4 suites、19 tests、11 passed/8 failed，失败名称及缺项一致。HEAD=f33bf12cff693472330aa659b5d1615aee3e9717；使用git archive在独立临时目录取backend、shared/tests、脚本真源及B/C locales，调用已有cached Vitest，finally只删该临时目录。没有切分支/还原当前树。首次pnpm exec未找到vitest，产生自身untracked lock已删除；首次HEAD抽取Python旧版filter参数及随后缓存权限失败是setup，最终直接node/elevated执行取得真实相同8失败，不计RED。

| 文件/测试（8项） | 当前与HEAD相同的精确缺项 | 归属 |
|---|---|---|
| biz-perm-label：zh-CN、en、ar集合3项 | biz:booking:manage、biz:booking:view缺locale | 本美业分支既有booking接线债，Task60收口，不称仓库无关历史 |
| biz-perm-label：中文同字1项 | 上述两码后端有文案、locale undefined | 同上 |
| i18n-parity：B三语key1项 | b-app/en缺common.delete | HEAD已有，Task60部署gate债 |
| entity-alignment：实体不缺列1项 | ent_migration_alias的AND、mch_resource的AND/AND被DDL parser当列 | HEAD已有真实CHECK解析边界，未放宽parser |
| entity-alignment：每表有实体1项 | sys_outbox_stream/delivery/operation、prd_stock_sync_operation/alignment_batch/alignment_line、ord_refund_coupon_scope/obligation、inv_outbox_stream/delivery/operation/event_consumed共12 | HEAD已有owner表与实体清单债，Task60统一gate |
| message-placeholder：反向1项 | InventoryWritebackService:159、StockReconcileService:156给STOCK_SHARED_SOURCE传参但文案无占位符 | HEAD已有，Task60部署gate债 |

新trade:checkout:prepare三语及shared mock逐字登记；原biz-perm-label扫描器仅识别biz:前缀，不能以该测试11pass宣称覆盖新trade:码。真实B grant/revoke和默认角色反例另由native7证明，新增LABELS被BizRoleSeedTest全量核对；新增ErrorCode 20015–20020三语由BackendI18nParityTest/唯一性检查覆盖。

### 20.2 生成物

执行既有gen-backend-layers、gen-table-inventory、gen-biz-role-matrix、gen-biz-feature-perm-matrix、gen-perm-domain-matrix.py、gen-glossary，保留9份Task56 reference产物；schema-test来自既有gen-test-schema.py。/tmp/task56-generated.log保留执行输出。

/tmp/task56-generated-check.log 全生成check exit1，只有已登记两项基线漂移：ops-role-endpoint-matrix.json中旧OpsPoints四路由×两角色8行；docs/technical/README.md四处旧Task55/TDD日期状态。还有同一ops generator写出的、checker没有快照登记的运营端-角色×端点矩阵.md副产物（旧OpsPoints和elec登记变化）。三文件在生成前clean，仅精确还原自身生成字节；/tmp/task56-generated-baseline.patch、/tmp/task56-generated-ops-side-effect.patch保存完整差异。最终不在owned中；不改checker，不称全生成gate绿；Task60已有OpsPoints/V506边界保留。

## 21. AC → 实际方法与证据

下表类均在shop-app scenario，Contract在shop-base；完整native日志83和后续定向日志覆盖对应方法，不依赖不存在的selector。所有source receipt均为SQL历史owner资金种子加真实SettlePort读取，**不是Task57真实init/settle/金融业务链路**。

| brief/设计AC | 实际测试方法或定位 | 证据/边界 |
|---|---|---|
| 六方法/四B方法，无未来支付 | CheckoutContractTest反射合同方法 | Contract4；无第7公共SPI |
| MAIN/CNY/COUNT/20行/999/重复/不可变 | CheckoutContractTest输入校验；CheckoutHttpFlowTest.fractionalCountAndQuotedCountAreRejectedByActualInternalHttp | contract+真实HTTP |
| 拒EA/分钟/多余单位，不静默drop | CheckoutHttpFlowTest.internalExtraUnitIsRejectedInsteadOfImplicitCount | 真实内部JSON400 |
| B原actor namespace/权限/数据域 | CheckoutPermissionFlowTest全部7；CheckoutNativeMysqlTest.prepareKeyIsScopedByActualMerchantActorNamespace | 无默认角色加权 |
| 真HOST C session/revocation | CheckoutAuthenticationFlowTest全部5 | 真TokenStore+filter；手造principal/B/错buyer均拒 |
| C端只读/确认/下单，禁止自报authority | CheckoutHttpFlowTest.consumerReadsConfirmsAndPlacesThroughRealAuthenticatedHttp、unknownConsumerMoneyAndActorFieldsNeverBecomePurchaseAuthorization、foreignConsumerCannotReadConfirmOrReplayOrder | 真实HTTP与owner |
| B internal transport/version/bare result | CheckoutHttpFlowTest.internalPrepareIsVersionedBareAndDoesNotEchoIdentityCredentials、internalProtocolAndTransportCannotImpersonateCurrentBuyer | 不提供C internal RPC |
| strict identity首次/原键重放/不存秘密 | CheckoutNativeMysqlTest.prepareReplayUsesOriginalResolvedBuyerAfterReferenceExpiry、frozenOwnerRowsAndDiagnosticRequestsDoNotRetainIdentityCredentials | 不重签引用，不保存token/contact |
| 无副作用报价/实际商品目录 | CheckoutPricingFlowTest.prepareQuotesActualServicePriceWithoutCreatingOrdersOrReservingStock | 真实303/订单意图库存无新增 |
| 显式buyer同算法 | merchantPrepareUsesBuyerAudienceForFlashAndDiscount；buyerAudienceGiftCannotSilentlyDisappearInMerchantPrepare；explicitBuyerFlashCannotAcceptCutoffPriceWhileOldOrdinaryApiKeepsIt | GOLD实际促销；old CUTOFF保留 |
| 普通本地券/不支持选择 | ordinaryLocalAmountCouponIsQuotedWithoutConsumption、ordinaryLocalCouponStillUsesOwnerAllocation、unsupportedCouponScopeCannotBeSilentlyIgnoredByOrdinaryAllocator、unsupportedQuantityAndMultiUseCouponsAreRejected、zeroMoneyPointsComboCannotDisappearFromTheConfirmedContents | 当前真实PMT金额券与owner；普通MKT路由实现/既有journal回归 |
| exact金额溢出 | overflowThatWrapsPositiveIsRejectedBeforeQuoteOrIntent | Long.MAX_VALUE×3拒绝，无intent/stock |
| 同次最终经济seal | priceMutationAfterPrepareIsRejectedAtWriterSealBeforeStock、sameAmountDifferentDiscountFundingFailsSeal、catalogMutationIsRecheckedInFinalWriterTransaction | firstGREEN+消融A3真实RED；无订单/intent/stock/sourceLine |
| 锁后新Clock/catalog | couponLockWaitAcrossExpiryRejectsBeforeIntent、couponWaitCrossingScheduledCatalogVersionRequiresNewQuote | 实际JDBC锁+worker socket屏障；消融B2；owner迁移后再次GREEN |
| 同次shares与稳定sourceLine | persistedItemSharesKeepActualLineOrderForEqualRemainders、currentBuyerConfirmationAuthorizesExactlyOneActualOrder | OrdItem真实写入ID同TX映射；179→90/89 |
| 永久首key/并发消费 | CheckoutNativeMysqlTest.sameKeyCreatesOneOrderAndStableSourceLines、differentKeysCannotConsumeConfirmationTwice、confirmationFirstKeyIsPermanent | native真实唯一键/锁竞争 |
| cache丢失/响应丢失/真重启 | expiredIdempotencyCacheDoesNotCreateAgain；CheckoutRestartNativeTest.lostConfirmationAndPlaceResponsesRecoverOriginalOrderAfterContextRestart | 两个完整Spring上下文关闭/重启、新bean、同DB原单/原来源行 |
| 过期不能建意图 | expiryBeforePlaceRequiresRequoteAndNeverRegistersIntent | phase REQUOTE_REQUIRED、无intent |
| 原恢复意图与RELEASED | preparedAndRecoveringOriginalIntentNeverAllocateAnotherOrder、placeClaimSurvivesWriterRollbackAndReleasedIntentCannotRevive | 原意图稳定，不产生第二单 |
| SQL authority配对 | nativeConstraintsRejectInvalidAuthorityPairs | MySQL CHECK4个非法组合实际拒绝，含NULL三值逻辑 |
| source未知金额与lastpair | CheckoutSourceFactsFlowTest.unknownReceiptNeverBecomesZeroMoney、unknownKeepsLastVerifiedPairAndReturnsNoConsumableLines、mismatchedReceiptDoesNotBecomeReceivedMoney | 初次(0,null)；后续lastverifiedpair+BLOCKED/空行/资金null |
| source退款/取消/售后 | refundWithoutVersionIncrementChangesSourceHashAndAvailableQuantity、cancelledOrderHasZeroAvailableQuantity、openAfterSaleBlocksExecutionAndCorruptedMappingBecomesUnknown | 真实owner表/ledger事实，未运行退款支付外部链路 |
| source串行RR/准确scope | refundBetweenFactReadsAndSerializedObservationNeverMixesSnapshots、sourceCannotSubstituteAnotherSubOrderOrJoinAnOuterSnapshot | op首SQL锁、后全事实consistent read；消融A RR→RC失败 |
| local-only/TM/禁止RPC | CheckoutOwnerBoundaryFlowTest全部5 | 同平台TX、本地能力、双向迁移/禁registry/PAY/logistics/appointment |
| managed最低CAS/旧未管理不变 | ManagedServiceCompletionFlowTest.directCasCannotCompletePersistedManagedOrder、sharedWriterRejectsEveryManagedCompletionEvent(5)、legacyUnmanagedServiceStillCompletes | 7native；消融最低CAS1RED |
| managed实际legacy入口 | CheckoutManagedLifecycleFlowTest实际pickup/merchant/currentC/auto/package/signed入口方法；CheckoutHttpFlowTest.actualConsumerReceiptHttpCannotCompleteManagedLegacyService | 10入口/付款边界+真实HTTP，signed限制说明见§18 |
| 付款不发旧核销码/旧offline拒 | managedPaidTransitionDoesNotIssueLegacyVerificationCode、oldMerchantOfflineShortcutCannotInventManagedReceipt、sharedOfflineTrustBranchCannotBypassManagedGuard | 3真实owner路径，不实现57现金收款 |
| 取消/未付超时仍合法 | currentBuyerCanCancelUnpaidManagedOrderWithoutCompletingService、ordinaryExpirySweepCanCloseUnpaidManagedOrderWithoutCompletingService | 新增实际入口正例结果见§22 |
| 未管理零售/餐饮回归 | §20具体18 selectors | 原retail/food retail形态，standalone项1skip明确保留60 |

## 22. 审查候选与精确owned

implementation base固定f33bf12cff693472330aa659b5d1615aee3e9717，当前仍同branch/HEAD，未stage/commit/push/merge/部署。V539为8444 bytes，SHA256 5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b；首次执行前root pin，此后冻结。beauty V1–V7/ready7/27及其它已执行migration无变动。

精确产品owned清单 /tmp/task56-owned-files.txt（85文件）；逐最终字节SHA256 /tmp/task56-owned-sha256.json；tracked patch /tmp/task56-owned-tracked.patch；未跟踪文件完整diff /tmp/task56-owned-new.patch。额外ignored owned文档仅本design/report两份。根progress/mainTDD/brief/preflight/overall design/ADR046均排除；当前有改动的根文件只有progress和主TDD，未纳产品manifest。

85文件包含上述共享owner合同/实际HTTP/普通writer与本地owner接线、四实体/mapper/policy、V539/schema、实际测试/runner、权限三语/shared mock及9份生成reference。新增根锁能力的两既有source-fence service文件已列owned。不得用git add -A；待根独立spec/quality审查后才精确stage/commit。

审查关注边界：这是56 shared owner候选，57支付receipt/现金处理、58美容来源绑定账、59consumer wire、60模块部署与所有基线gate债尚未完成。资金只读fixture不证明真实金融链路；外部通道未部署；food standalone与库存独立库未验。不通过的基线检查如实保留，不放宽ratchet。首次冻结的经济快照与持久identity摘要不可当C授权；UNKNOWN lastpair不可当当前可服务事实。

### 22.1 最后定向验证

/tmp/task56-managed-terminal-final.log：2026-10-23 20:00:04，2 tests/0 failures/0 errors/0 skips，BUILD SUCCESS、native runner exit0。真实当前C取消未付managed单、真实closeExpiredOrders扫描过期单均进入合法CANCELLED，未写completed_at/COMPLETED日志；超时主单CLOSED。两项是既有路径firstGREEN，无生产逻辑修改，不称前置RED。

/tmp/task56-food-embedded-final.log：实际命令在前述offline reactor参数后加 -Pfood-embedded -Dtest=FoodEmbeddedWiringTest，20:02:21 BUILD SUCCESS，6 tests/0 failures/0 errors/1 skipped。5项确实在embedded classpath验证food-web/food-core与配置开启/关闭；唯一skip是standaloneIngressHasNoFoodCoreJar的standalone专用条件。此前默认retail也是5项通过；两次形态不同，不把skip当通过。standalone真实JAR/部署仍由Task60处理。

### 22.2 最终字节冻结

最后两处修正仅是新增else大括号/同一行样式，没有算法变化；/tmp/task56-final-byte-compile.log在最终产品字节上重新编译全部reactor依赖，并执行shop-base Contract4 + shop-app controllersMustNotTouchMappers1，0 failures/errors/skips，20:04:02 BUILD SUCCESS。此后产品文件不再修改。完整83原生恢复GREEN→owner迁移后18定向GREEN→合法终态2 GREEN→最终字节compile/contract/rule GREEN的边界分别明确，不把任何一批称“全部最终文件全量重跑”。

最终85产品文件中49 tracked、36 new；另2 ignored设计/报告。/tmp/task56-owned-sha256.json 文件自身SHA256 = a2ab6e23fd17e0b95c32b10488484a0242de62e4fdfd7dd4c3d46bab7e3bd8bd。根可据 /tmp/task56-review-files.txt 精确stage87文件；本agent未stage，index核为空。/tmp/task56-review-sha256.json同时记录这87份字节，供独立review识别冻结候选。两个patch包含产品tracked和new全量，不包含根文档。最终git diff --check通过，新Java增量width/wildcard/FQN注解扫描0 findings，跨域类型另按实际imports人工核对。

状态：REVIEW_READY，等待根独立审查；没有需用户重新授权的阻点。Task57–60与§20列出的基线gate债保持原边界。

## 23. 独立审查修复 R1/5 — I1 native 测试入口隔离

审查输入 FIX_BASE TREE=deb79c20a5adc3a9da33898e454c6513a8d6a568；HEAD仍f33bf12cff693472330aa659b5d1615aee3e9717。独立审查 /tmp/review-bm04-56.md 为SPEC PASS / QUALITY NEEDS_FIXES（0Critical/1Important/0Minor）。仅修I1，未更改产品API、业务逻辑、schema、V539或设计合同；原87个staged文件保留，root文档排除。按receiving-code-review先核代码与正常入口，再复现再最小修复。

### 23.1 前置真实RED与同类范围

命令（未带native参数）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=CheckoutRestartNativeTest -Dsurefire.failIfNoSpecifiedTests=false
```

/tmp/task56-r1-default-red.log：22:50:05，**1 test/1 assertion failure/0 errors/0 skips**，expected "true" but was null，BUILD FAILURE，确认正常Surefire入口的新增确定失败。不是编译/setup或数据库连接错误。

root要求聚焦核对其它新增真实MySQL依赖：同样默认命令仅选择 CheckoutPermissionFlowTest#ownerHasNewPermissionByExistingWildcard,CheckoutPricingFlowTest#prepareQuotesActualServicePriceWithoutCreatingOrdersOrReservingStock。/tmp/task56-r1-json-default-red.log为**2 tests/0 failures/2 errors/0 skips**；实际MerchantMappers查询使用JSON_VALID/JSON_CONTAINS/JSON_QUOTE，H2报json_valid缺函数。Pricing错误发生在共用CheckoutFlowFixture.fixture的strict identity/merchant owner验证，因此六个具体fixture使用者确有相同native依赖。该2 errors是测试入口/SQL环境兼容性证据，**不记为产品行为断言RED**。

第一次把条件放到abstract fixture，当前JUnit条件注解不继承，隔离验证失败。/tmp/task56-r1-default-isolation-green.log虽然文件名含green，实际结果为**73 tests/7 failures/52 H2 errors/8 skips，BUILD FAILURE**；只有显式声明条件的Permission7和Restart1跳过。该结果完整保留，不伪称通过，亦不称native整套回归。随后删除无效fixture注解（fixture恢复原staged字节），把条件放到六个具体类。

### 23.2 最小最终修复

8个具体类各显式声明 @EnabledIfSystemProperty(named="checkout.test.native",matches="true")：

1. CheckoutRestartNativeTest
2. CheckoutNativeMysqlTest
3. CheckoutPermissionFlowTest
4. CheckoutPricingFlowTest
5. CheckoutHttpFlowTest
6. CheckoutManagedLifecycleFlowTest
7. CheckoutOwnerBoundaryFlowTest
8. CheckoutSourceFactsFlowTest

CheckoutAuthenticationFlowTest、ManagedServiceCompletionFlowTest、CheckoutContractTest已确实在H2/普通入口可运行，保持原行为，不按名字泛化隔离。CheckoutFlowFixture本身不改，未引入新framework/profile、未修改POM或全局测试排除。

Restart原先的native=true硬断言完整保留；显式native但缺owned loopback MySQL配置时立即失败，不fallback H2。两个独立Spring上下文每次autowire后都用真实DataSource connection metadata断言产品名MySQL，然后才运行原prepare/confirm/place、关上下文、重启恢复逻辑。没有删除真实restart或receipt/sourceLine断言。

runner继续明确传-Dcheckout.test.native=true和自有DB配置。新增检查范围是上述8个类的**逗号分隔精确短类名，可附#方法选择（方法以+组合）**；这不是任意wildcard/FQN或其它Maven suite的通用报告框架。对被选择的这8类，在启动Maven前删除其原Surefire XML；成功后必须有新XML，tests>0且failures/errors/skipped均0；缺报告、0执行、条件skip都会让runner非零失败。固定SQL校验、512MiB只读预检、cached9.7.2、自有UUID串行、凭证sanitize和finally清理保持原合同。

### 23.3 定向复验

普通selector只验证选择隔离：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=CheckoutRestartNativeTest,CheckoutNativeMysqlTest,CheckoutPermissionFlowTest,CheckoutPricingFlowTest,CheckoutHttpFlowTest,CheckoutManagedLifecycleFlowTest,CheckoutOwnerBoundaryFlowTest,CheckoutSourceFactsFlowTest -Dsurefire.failIfNoSpecifiedTests=false
```

/tmp/task56-r1-default-explicit-green.log：**73 tests/0 failures/0 errors/73 skips，BUILD SUCCESS**。8类在任何fixture/数据库连接前明确隔离。73项是预期条件跳过，不能计为业务/native通过。此前第一小步仅Restart+Native两类 /tmp/task56-r1-default-green.log为12项全skip，属于中间状态，不替代最终8类选择证据。

显式native但配置错误反例：

```sh
env -u CHECKOUT_NATIVE_MYSQL_URL JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=CheckoutRestartNativeTest -Dsurefire.failIfNoSpecifiedTests=false -Dcheckout.test.native=true
```

/tmp/task56-r1-invalid-native-red.log：**1 test/1 failure/0 errors/0 skips，BUILD FAILURE**，明确native配置硬断言失败；说明enabled不是错误配置的静默skip。

/tmp/task56-r1-runner-skip-guard.log：读取当前runner原文的实际XML检查代码块，用默认入口真实生成的Restart XML（tests1/skipped1）执行，预期捕获其zero skipped AssertionError；exit0表示反例验证成功，原native guard实际拒绝了skip。这是运行代码的最小校验，没有复制一套伪validator或启动第二容器。

实际native仅四个代表方法：

```sh
CHECKOUT_TESTS='CheckoutRestartNativeTest,CheckoutNativeMysqlTest#nativeConstraintsRejectInvalidAuthorityPairs,CheckoutPermissionFlowTest#ownerHasNewPermissionByExistingWildcard,CheckoutPricingFlowTest#prepareQuotesActualServicePriceWithoutCreatingOrdersOrReservingStock' node scripts/verify-checkout-foundation.mjs
```

日志 /tmp/task56-r1-native-green.log；最终结果及R1精确tree/hash在下节追加。未重跑原83/129/消融或部署形态。node --check scripts/verify-checkout-foundation.mjs、git diff --check通过；V539仍8444 bytes/SHA 5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b。

### 23.4 R1最终GREEN与精确交接

/tmp/task56-r1-native-green.log：**4 tests/0 failures/0 errors/0 skips，BUILD SUCCESS，runner exit0**。真实Restart1完成两个独立Spring上下文关闭/重启，两个实际DataSource均MySQL；Native CHECK1、实际merchant permission1、真实prepare1通过。runner随后分别输出四个Native execution verified（tests1/skips0）。本批只读MemAvailable=3686972KiB，固定原V539字节执行，自有cached mysql9.7.2 DISK64M，finally清理完成。不是73条默认skip冒充native通过，也未重跑完整83或129。

R1最终delta只有10个已属原87owned的文件：上述8个具体测试类、scripts/verify-checkout-foundation.mjs、本报告。CheckoutFlowFixture恢复原staged字节；设计无产品合同变化无需修改。未改任何production Java、POM、schema、migration、业务tests断言或根文档。其它77个原staged文件保留原字节。

精确R1清单 /tmp/task56-r1-owned-files.txt；该10文件逐字节SHA /tmp/task56-r1-owned-sha256.json；相对FIX_BASE TREE deb79c20a5adc3a9da33898e454c6513a8d6a568的fixdiff /tmp/task56-r1-fix.diff；冻结新tree /tmp/task56-r1-tree.txt；完整87文件更新SHA /tmp/task56-r1-review-sha256.json。只暂存这10个路径，不reset/unstage整树，不commit。node syntax与git diff --check通过，V539仍固定8444bytes/SHA。

R1状态：READY_FOR_NARROW_REVIEW；I1实施修复与定向验证已完成，等待根独立窄复审，未自行声明QUALITY APPROVED。Task57–60及此前已列baseline债不变。
