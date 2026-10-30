# 下一阶段美业后端设计就绪审查 · 2026-10-26

## 结论与审查界线

- **Spec: PASS**，针对主计划 §0.4 的 C01～C18 排程、原非支付范围保留、真实所有者边界与统一测试/部署时序。
- **Architecture: NEEDS_FIXES**，针对立即发布 C02/Task68 的设计就绪度；**NOT_READY_FOR_C02_CODING**。整体推进顺序可批准，不需要重新设计整套美业架构。
- 0 Critical；2 Important（C02 发布前设计欠账）；2 Minor（C01 已知兼容问题及草稿执行文字同步）。后文单列后续合同准备，不将尚未发布的 C03+ 逐任务设计当成当前缺陷。
- 本报告为静态设计审查，不是 Task67 代码验收、Task68 实现验收、运行测试通过或可营业声明。没有构建、测试、迁移执行、Docker/native、网络或部署。

唯一工作树：`/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`。读取时 HEAD 实核为 `2c0708f752d747d88a9eda7478326a964d1984fa`，工作树含 Task67 当前实现/修复与最新排程；结论针对本次读取的这些文件，不把未提交内容冒称该提交内已有。未读取原餐饮工作树。唯一写入是本报告。

先读主计划 §0.4，再读 §2.43～2.45、Task68 DRAFT、Task65 §4/C1～C3、Task63 M3～M7、ADR050 更新；针对性核对实际 CampaignPort/Router/marketing owner、promotion owner、Checkout/Order/final guard、人工来源远程校验、资源 SPI/锁与资产 SERVICE_USE 所有者。Task67 R0 的触发器归一化、CREATED 恢复核验及其修复由另一审查席负责，本报告不重复裁决。

## Important findings：C02 发布前关闭

### I1 · 已知 preflight gap：显式选择与支持范围尚未形成可编码的完整合同

**位置：** `.superpowers/sdd/beauty-backend/task-68-preflight.md:12`、`:28`、`:29`；已审设计 `.superpowers/sdd/beauty-backend/bm05-m2-counter-source-design.md:206`、`:208`。

**源码证据：**

- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/CheckoutServiceImpl.java:202` 拒绝所有非空 activityChoices；`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:1669` 还有第二处同样的 counter 检查。
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:1875` 的 counterDiscounts 没有 choices 参数；`backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CampaignPort.java:26` 只有两参 counterCandidates；`backend/shop-app/src/main/java/ai/neargo/shop/portal/port/CampaignPortRouter.java:45` 向 promotion 传的是 Map.of()。
- `backend/shop-core/src/main/java/ai/neargo/shop/promotion/service/impl/ActivityPricingServiceImpl.java:47` 仍拒绝全部非空 choices；`:54` 的保守 scoped 查询不区分活动时间/订单门槛。它是当前 M2a 关闭能力的实现，不能直接作为 M2c「otherwise-applicable」语义已经具备的证明。
- `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java:108` 沿默认 candidates→autoDiscount，`:164` 每组只取一个 bestFullCut；这不支持显式选择合格但非最优的普通满减。
- `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/impl/CampaignServiceImpl.java:117` 可保存 FULL_CUT goodsNos，而 `backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java:274` 的 bestFullCut 不评估该字段。

**后果：** 只开放 prepareCounter 或套用 pick 会出现合法 NONE/显式非最优活动仍被拒绝、商品范围被误用、或 unsupported 检查失去原选择语义。当前零优惠分支 fail-closed 本身不是新代码缺陷；缺的是 Task68 要替换它的精确合同。

**最小处理：** root 发布 Task68 addendum：冻结 choices 在入口、writer、router、实际 legacy/promotion owner 的准确参数；列明 store/entity key 优先级、未知/外店 key、NONE、显式活动、遗漏的结果与错误码；给出 eligible FULL_CUT 枚举与 owner 支持判定；明确第一片是否仅接受空 goodsNos。建议采用仅空 goodsNos 的子集，并对否则适用的有范围规则明确拒绝，保留原 retail/M1 定价。FLASH/BUY_GIFT 始终按已审约束拒绝，NONE 不消除商品价格/赠品效果。列齐实际受影响文件及 API/Java 真源后即可编码；测试场景随代码编写和编译，执行留到阶段B。

### I2 · 已知封印目标的新增落地缺口：持久载体、最终校验输入和旧意图兼容尚未冻结

**位置：** `.superpowers/sdd/beauty-backend/bm05-m2-counter-source-design.md:210` 要求冻结 rule versions/values、最终锁内核验及旧 M2a 原经济结果恢复；`.superpowers/sdd/beauty-backend/task-68-preflight.md:14`、`:28`、`:30` 只陈述要求，尚未给出可实施载体。

**源码证据：**

- `backend/shop-base/src/main/java/ai/neargo/shop/spi/marketing/CampaignPort.java:424` 的 AppliedActivity 只有活动号、主体、金额、数量、平台份额、报名号、名称、门店；没有规则 version/threshold/time/scope digest。
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutEconomicSnapshot.java:10`、`:32` 也无活动规则封印。`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:1974` 重新构造 AppliedActivity 并去掉名称，`:2047` 的 COUNTER_MANUAL_QUOTE_V1 对 money 求哈希；单个商品的 productHash（`:1941`）不含活动规则。门槛、有效期或规则 version 改动但折扣金额相同，可以产生同样的现有经济结构。
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CheckoutQuoteGuard.java:144` 最终调用只把 original.activity() 交给 requireCounterSelection；既无原始 groups/choices，也无冻结规则证据。`backend/shop-core/src/main/java/ai/neargo/shop/marketing/port/CampaignPortImpl.java:123` 的普通锁检查只锁选中活动并检查类型；不能直接复用成 counter 的范围/门槛/时效封印。
- 真实 version 是 `backend/shop-store-mybatis/src/main/java/ai/neargo/shop/common/BaseEntity.java:46` 的 @Version Long，由 `backend/shop-core/src/main/java/ai/neargo/shop/marketing/campaign/entity/MktCampaign.java:22` 继承，不能编造独立 activityVersion。
- `backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/CounterOrderPlan.java:24` 内嵌经济结构；`backend/shop-core/src/main/java/ai/neargo/shop/trade/order/service/create/impl/CounterOrderPlacementServiceImpl.java:672` 反序列化后重新序列化哈希对账。因此直接给共享 record 增加可空字段，也必须证明旧 JSON/hash 不变。`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java:2257` 会按当前策略重新算价，再在 `:2424` 比较冻结经济结果；新自动优惠不能被无意套入旧 pending M2a intent。

**后果：** 仅比较金额/现有 quoteHash 不能满足「规则变更使已接受报价失效」；直接扩展共享序列化又可能使合法旧 planHash 无法恢复。只锁 selected 行还没有回答 NONE/无候选及新出现 unsupported 效果的最终核验边界。以上是发布前合同缺口，并非断言尚未实现的 M2c 已发生竞态。

**最小处理：** 冻结 counter 专用、版本化的 owner rule seal 及其存储/传输位置，包含实际 version、金额规则、主体/门店/商品范围、有效期与选择语义；明确 `CheckoutQuoteGuard → CampaignPort → marketing` 最终核验所需输入和排序锁。对于 NONE/无选中行及 unsupported 效果，明确 owner 观察/锁的线性化点与相反写者，而不是假定空查询已有稳定锁；必要的 owner 写者协调列入精确清单。保持普通/M1 字节不变，为旧 M2a quote/plan 明确版本识别、序列化与原零优惠策略恢复；不得修改旧 hash 或悄悄重价。最小场景必须包括「规则变化但最终金额未变」、旧 pending plan 在 M2c 策略下恢复、NONE/遗漏的最终 owner 校验。此处只要求设计及后续测试源码，不要求现在运行。

## Minor findings

### M1 · C01 已登记问题：未成单人工来源的合法空金额被远程投影拒绝

**位置：** `backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteManualServiceAdmissionPort.java:207`；既有记录 `docs/technical/TDD-美业系统-后端开发计划.md:1242`，当前 C01 明确收尾。

实际 owner `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java:1072` 在 parent 不存在时给 dueMinor=null，但 `:1088` 仍返回真实 AWAITING_RECEIPT 展示对象；远程 sourceDisplay 无条件要求非空 dueMinor。合法未成单查询会变成依赖不可用。

**最小处理：** 仅按真实未成单/未声明形态允许空金额，保留身份、operation、订单引用、版本、币种与已声明金额一致性检查；不放宽 ADMITTED/有声明事实，不造零金额。这是既有 Minor 的 C01 关闭项，不计新增 Task67 R0 缺陷；静态复审和编译后进入统一测试队列。

### M2 · Task68 草稿的执行文字仍沿逐片 GREEN/ablation gate

**位置：** `.superpowers/sdd/beauty-backend/task-68-preflight.md:22`；旧 Task65 设计 `.superpowers/sdd/beauty-backend/bm05-m2-counter-source-design.md:264`、`:270`。当前权威覆盖规则在 `docs/technical/TDD-美业系统-后端开发计划.md:119`、`:121`、`:148`。

**后果：** 草稿转成执行 brief 时若原文照搬，会恢复用户已撤销的逐片运行测试门禁。当前 DRAFT 和主计划优先级已足以阻止误执行，故非架构阻断。

**最小处理：** Task68 发布时明确「当前编写/编译这些场景和 runner；原生、协议/架构测试与消融统一阶段B执行」。旧历史证据和真实测试覆盖不删，不让修改后的版本继承旧通过状态。

## 阶段就绪与 C03～C07 依赖

| 阶段 | 当前判断 | 下一发布前必须有的合同；不是现在运行测试的要求 |
|---|---|---|
| C01 | 可继续授权范围内收尾；本报告不替代独立代码复审 | 关闭 M1；保留 Task67 R0 修复席结论；静态对账及受影响源码编译。C02 BASE/精确清单以收尾后的实际状态冻结。 |
| C02 | **NOT_READY_FOR_CODING** | 关闭 I1/I2，发布准确 brief/inventory/真源增量。无需重复 M1/M2a 或执行单片测试。 |
| C03/M3 | 可进行详细设计；数量授权可先编码，开工继续关闭 | 冻结 ServiceExecutionPort/反向 source proof 的实际 DTO、原号/作用域/任务永久关联、单位序号、版本、授权目的及恢复状态。给出 Core child/line/grant 撤销串行化与 unknown 不释放契约，并预先确定 C04 资源 outcome 证明形状，避免 markStarted/releaseUnstarted 先接受无含义字符串。 |
| C04/M4 | 应紧随 C03 合同；实际开工启用前置 | 扩展共用资源 journal 的五种实际动作、typed target/result、OCCUPATION_V1、预约单位永久交接；明确 person→resource、owner/command、inventory 各自域内锁序及相反写者，禁止跨域持锁等待。库存头/行问题按 C04 已列前置处理，不把库存与资源误当一套账。 |
| C05/M5 | 依赖 C03 数量+ C04 真实资源双回执 | START 最终应用、人员自身权限/资格、接单不算参与、暂停保占、交接双方证据、已结束后恢复新占用、步骤 CAS 与完成量唯一计数。所需回执及失败恢复须先冻结；不能用 Task62 人员查询结果当容量许可。 |
| C06/M6 | 依赖完成证据及资源/授权终态 | completionNo 本域一次计量和 Core 一次履约 ACK 分离；未服务撤项证明、关闭冻结集合/版本与相反写者完整列举。允许 C03 先声明接口后在 C06 实现 fulfill，但未实现入口明确关闭，不能空成功。 |
| C07/M7 | 读模型可按上述合同开发，完整任务日历依赖实际事实 | 一个授权窗口快照下的 cursor/版本/到期与迟到投影；预约任务、实际占用、清洁、阻断、pending 原号全部有明确来源。跨店 busy 匿名化，动作后刷新，日历不能充当可约或开工许可。 |

上述分工符合 `.superpowers/sdd/beauty-backend/bm05-offline-core-design.md:97`～`:112`、`:140` 及 Task61 资源 §6。实际 `backend/shop-base/src/main/java/ai/neargo/shop/spi/resource/ResourceSourceAuthorityPort.java:32` 仍只有八种预约动作，`backend/shop-base/src/main/java/ai/neargo/shop/spi/resource/ResourceBookingPort.java:19` 仍为 hold target；不能声称实际占用合同已实现。已有 `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/ResourceBookingServiceImpl.java:332` 拒绝 executionStartedAt 非空的旧整 hold 改动，`:374`、`:381` 体现 person→resource 锁序。C04 必须保留此安全边界，通过专用剩余 group/occupation 行为完成扩展。

不要求提前完成 C03～C07 全部详细文件清单。上述是各自启动前需落实的承重合同，主计划已明确每片发布前深化，缺少这些后续计划本身不使当前排程 FAIL。M3–M7 的恢复、outbox、pending/stuck 观测随各片编写，C08/C09 是汇总和跨模块收口，不能把原号恢复全部留到 C08。

## C08～C18 范围和支付边界核对

主计划 §0.4 与 §0.1/§0.2 原任务族一致，没有以现金优先删除 P2～P4。当前只是排程摘要，后续精确映射仍须回到原矩阵/API/Java/SQL真源。

| 排程 | 原范围及保留重点 |
|---|---|
| C08 | AFTER/RECOVER/HIST：未做撤项、争议、原号恢复、历史与对账；资金返还执行后置，业务异常和待办仍交付。 |
| C09 | MSG/OPS/DEVICE基础：事件、通知/打印内容与路由、原任务补打恢复、审计/权限/观测；真机依赖在统一部署验收中诚实标记。 |
| C10 | GOODS-01：实物零售复用原 SKU/订单/库存，不能套入仅 SERVICE 的人工服务准入绕过零售合同。 |
| C11 | ASSET-01/02：真实本人授权、门店隔离、合格次数/储值消费、释放/恢复及争议待办；资产账仍归共用所有者。 |
| C12 | CARE/QUEUE：护理/私有媒体、顾客协作、候位、回访；资料授权撤回/留存与负责范围在详细片中覆盖，不能进入公共日历、打印、通知。 |
| C13 | PERF/MARKET：真实产出分摊、提成及冲正、资格/范围/营销；矩阵 BTY-O05 的结算和已发放留痕并不等于银行代发，不应随支付后置删除非支付记录能力。 |
| C14 | MAT/FIN/RPT/DEVICE：耗材及原库存、班次/钱箱/营业日/水位/签核、分口径报表、终端治理；摘要“财务/对账”须在细化时映射这些原功能，不能只做一张现金合计表。 |
| C15 | PLAN/SALE：服务计划与销售/发行协调分离；真实现金来源可适用的权威合同须明确，不能把普通手工 PAID 当作任意资产发行凭证。 |
| C16 | ADV：保留多步骤/并行、团体/上门与复杂收银非支付编排；原后付/免单/临时改价/定金候选逐项有政策和错误合同，未获准能力显式关闭，不从本轮现金默认推导开放。 |
| C17 | ASSET-REV/MAT-02/EXT/EDGE：业务逆向和追溯、BOM成本、第三方/离线同步与冲突恢复；在线资金退款/补偿后置，不抹掉既有成功事实。 |
| C18 | 原 49 任务、矩阵 118 项、API/方法/结构及配置权限词条逐项映射；所有非支付代码/测试源码/脚本编译后冻结候选，阶段B统一测试修复，再阶段C统一部署。 |

范围证据：`docs/technical/TDD-美业系统-后端开发计划.md:75`～`:78`、`:91`～`:94`、`:135`～`:145`；`docs/requirements/美业系统-多端功能矩阵.md:206`～`:231`、`:284`～`:300`、`:337`～`:343`。原矩阵 P2 还列方案/充值查询及相关恢复，不能只看 C11 短标题后省略；详细方案可区分真实可复用非支付业务与只能凭正式支付执行的动作。

**C11 前必须解决的一条跨阶段合同（不阻断 C02）：** Task63 `.superpowers/sdd/beauty-backend/bm05-offline-core-design.md:97` 将 asset/funded 从新 manual execution prepare 排除是正确的 M3 限界，但文字“直到 deferred financial design”不能被解释成把全部 C11 推到本轮之外。实际已有 `backend/shop-base/src/main/java/ai/neargo/shop/spi/entitlement/EntitlementUsePort.java:15`～`:27` 的 prepare/commit/release/reverse/query，以及 `backend/shop-core/src/main/java/ai/neargo/shop/trade/entitlement/port/EntitlementUseSourcePortImpl.java:67` 的 CORE/SERVICE_USE 分派。`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/EntitlementServiceOrderServiceImpl.java:273` 保存真实授权 use 命令，`:286`～`:329` 执行受约束的消费决策/ACK状态；这不是要求新接支付网关。

C11 须冻结 SERVICE_USE 权威结果→美业来源/单位/任务→执行 grant 的明确适配，以及 prepare/commit/release 与已开工/已完成/撤项的竞争规则。不能把次数消费伪装成现金 MANUAL 声明，也不能使用普通订单 PAID 代替成功消费。`EntitlementUsePort.java:132` 和 `EntitlementUseSourcePort.java:44` 要求真实 USER，现有 guest 历史只读认领规则不能自动授予资产本人消费。资产域虽可能物理位于 pay 工程，其消费账能力与本轮禁止的 Pay 下单/网关/回调/退款执行必须按动作区分，不能按包名整体删掉。

## root 应作的发布裁决

1. C02：确定 choices 重载及 key/error 矩阵；确定空 goodsNos 子集；定义 activity seal 的具体类型、最终 owner 校验输入/并发边界和旧 quote/intent 兼容策略。按实际文件发布 Task68，不把五个已存在方法重新列成待新增。
2. C03/C04：先冻结 grant/resource outcome 的双向证明与释放条件，再分别实现所有者；开工保留关闭直至代码合同齐备，运行验证在统一阶段B。
3. C11：在既有资产消费所有者上补非支付服务执行接入；识别必须正式支付凭证的发行/逆向动作并登记后置，保留其本域校验、明确状态/错误和后续合同。
4. Task68 发布时同步草稿中的旧运行门禁；C18 逐项检查原功能映射，避免概要表代替全范围交付。

这些均可由现有已授权方向和实际源码作工程裁决，不存在必须向用户索取的新偏好。未提出 Pay 集成、美业重复账、假成功或修改冻结迁移。现有 V539～V542、inventory V14、beauty V1～V9 不在修订许可内；C03+ 新迁移版本在实际发布时重核。
