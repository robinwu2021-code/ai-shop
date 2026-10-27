# TDD-支付服务-I4进件与收款商户

状态：已实现
档位：2 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §5 I4 · [数据库现状对比与执行方案](./支付服务-数据库现状对比与执行方案.md) §3.2、§4.5（B1–B4、S5 比对、S6 读切）、§4.6、§5（Q2 / Q3 / Q4 / Q6）· [数据库表结构说明 v4](./TDD-支付服务-数据库表结构说明.md) §6.2、§8.1、§8.2 · [代码清单](./支付服务-代码清单-开放API刷卡POS内部调用.md) §2.4 · [ADR-043](./ADR/ADR-043-进件与收款商户归支付域.md) · [代码 Review](./支付服务-代码Review-2026-10-24.md) R01 / R02 / R04 / R06 / R23 / R24 / R26
创建：2026-10-26 · 最后更新：2026-10-26

把「收款商户」与「进件」从 `shop-merchant` 的一张 `mch_payment_merchant`（一行既是进件申请又是商户号，`store_no` 空串 = 主体级默认号）迁成支付域的模型：
进件单（多版本、资料一字段一行）成功产出**收款商户** `pay_merchant`；门店用哪个收款商户由**门店绑定** `pay_merchant_binding` 决定，**没有主体级默认、没有兜底**。
迁移按执行方案的四步走：**双写 → 回填 → 比对 → 读切**，每一步一个开关，出问题切回旧解析。**每片先写会变红的测试，修复后变绿，再撤修复做一次消融。**

## 切片

| 片 | 内容 | 提交 |
|---|---|---|
| S1 | 迁移 10（`db/pay` V10）：进件单 / 版本 / 资料，收款商户、门店绑定、开通产品、状态日志；7 个实体与 Mapper | `9453f2c76` |
| S2 | `spi.pay.PayMerchantQueryPort` + `PayRoute` + `PayMerchantQueryPortImpl`（只按门店绑定解析、无兜底）；`PayMerchantBindingService`（同主体、同市场、ACTIVE） | `41d7f7a57` |
| S3 | 回填 B1–B4：平台级商户（配置里的服务商号）→ 收款商户与进件 v1 → 门店绑定（主体级号逐店展开）→ 开通产品；三份冲突报告（Q2 无默认门店、Q3 两来源不一致、方式映射不到） | `13deab210` |
| S4 | 双写 `shop.pay.merchant.dual-write`：进件结果、门店绑定、资金路径在旧表落库的同时写新表 | `c072ca47e` |
| S5 | 比对 C07：每个门店 × 渠道，旧解析与 `routeOf` 逐条比，差异出报告；差异为 0 是读切门禁 | `93a25e803` |
| S6 | 读切 `shop.pay.merchant.read-from=legacy|pay`：旧端口的支付解析方法改由新模型回答；切回即回退 | `529393136` |
| S7 | 进件迁入支付：`OnboardingService`（每版本外部单号唯一 R04、在途唯一 R06、驳回原因随版本 R23）+ 轮询；通过即产出收款商户并绑门店。~~旧入口在 `pay` 读切后转调~~ → 随 I5 新接口（偏差 14） | `b39c46d28` |

## §0 对账一 · 需求 → 设计

| AC | 验收（开发计划 I4 测试与完成标准 · 执行方案 §4.5） | 片 |
|---|---|---|
| AC1 | 迁移 10 在 H2 与 MySQL 9.7 上都能执行；实体与表逐列对齐 | S1 |
| AC2 | `routeOf` 只按门店绑定解析：没绑定 = 不可收款，不取「主体的第一个可用号」（R01）；退款 / 查单用冻结的收款商户 id 解析 | S2 |
| AC3 | 绑定校验：收款商户 ACTIVE、市场与门店一致、开户门店与本门店同一主体；同一门店 × 渠道同时只有一条 ACTIVE | S2 |
| AC4 | 回填幂等（再跑不多）；主体级号逐店展开；Q2 / Q3 / 方式映射不到进冲突报告、不自动取舍 | S3 |
| AC5 | 双写：旧表写入成功即新表同步；新表写失败不挡旧路径、由比对兜底 | S4 |
| AC6 | 比对：差异为 0 才放行读切（门禁）；每条差异带门店、渠道、新旧两个号 | S5 |
| AC7 | 读切后旧端口的支付解析结果来自新模型；切回 legacy 即恢复；现有用例全绿 | S6 |
| AC8 | 进件：同门店同渠道在途只有一张（R06）；每次提交外部单号不同（R04）；驳回原因留在版本上、重提后当前原因清空（R23） | S7 |
| AC9 | 全量回归不新增失败 | 全部 |

## §1 现状与影响面

- **模型**：`mch_payment_merchant`（V1 / V14 / V37 / V280）一行 = 进件申请 + 收款商户号；`store_no = ''` 是主体级默认号。门店经 `mch_store.pay_merchant_no` 指定，空 = 用主体默认。`mch_entity.funds_mode` 是主体的资金路径（默认 AGGREGATED）。没有独立的进件表。
- **解析**：`MerchantPortImpl#accountOf` 一处收口（I1 F01 / F02）：门店显式绑定 → 门店自己的号 → 默认门店的绑定 → 主体级号。9 个支付解析方法（7 个在 `MerchantQueryPort`、`resolvePayment` 是私有、`accruePayQuota` 在 `MerchantAdminPort`）共 20 处主代码调用（shop-core 13、pay-domain 1、shop-app 6）。两处原生 SQL 在 shop-merchant 品牌包（`PaymentMerchantScopeServiceImpl`、`ManagementScopeServiceImpl`）。
- **进件**：`MerchantPaymentServiceImpl#submit / refresh / openForStore / pollApplying` + `ApplymentPollJob`；网关经 `PayApplymentGateway`（微信、STUB；支付宝暂未装配）。商家端 `/biz/merchant/payment*`，运营端 `/ops/onboarding*`。
- **影响面**：`pay-domain`（新 `merchant` / `onboarding` 子域）、`shop-base`（`spi.pay.PayMerchantQueryPort` 等）、`shop-merchant`（双写挂钩、读切）、`shop-app`（回填与比对任务）。读切把切换点放在旧端口实现里（一处、可回退），20 处调用方不在本迭代逐个改写（偏差见 §6）。

## §2 方案

### S1 · 迁移 10 与实体
- `db/pay` V10：7 张表，DDL 逐字取自 `pay-service-review.sql`；表注释里的半角括号换全角（H2 生成器，I3 同款）。
- **不建 `ck_pay_merchant_2`**（MERCHANT_OWN 必须有密钥）：密钥模块搁置，执行方案 §4.6 过渡期 CN 凭据仍在配置里，而 MySQL 9 执行 CHECK —— 建了之后微信直连商户一行都回填不进去。密钥模块就绪时另起迁移加回。
- 实体：`merchant.entity.{PayMerchant, PayMerchantBinding, PayMerchantProduct, PayMerchantStatusLog}` · `onboarding.entity.{PayOnboarding, PayOnboardingVersion, PayOnboardingMaterial}`；Mapper：`mapper.merchant.MerchantMappers` · `mapper.onboarding.OnboardingMappers`。

### S2 · 收款路由与门店绑定
- `spi.pay.PayMerchantQueryPort`（`routeOf` · `collectableRoutes` · `routeByPayMerchantId` · `capabilityOf`）+ `PayRoute` · `MerchantCapability`。
- `merchant.port.PayMerchantQueryPortImpl`：`routeOf` 按 `active_key = 门店|渠道` 取绑定，商户须 ACTIVE；**没有绑定就是没有，不兜底**。`collectableRoutes` 按市场过滤、按优先级排。`routeByPayMerchantId` 不看绑定、不看 ACTIVE（关闭的除外），给退款与查单用冻结值。
- `merchant.service.PayMerchantBindingService` / impl：`bind`（商户 ACTIVE、渠道一致、市场与门店一致、开户门店与本门店同主体；同一门店 × 渠道旧的 ACTIVE 先转 INACTIVE，同事务）· `unbind`。门店属于哪个主体、哪个市场经 `MerchantQueryPort#payStoreById`（新增，核心系统 Port）判断。
- 错误码 `PAY_BINDING_INVALID`（70089，回显原因）+ 三语。

### S3 · 回填 B1–B4
- 支付域 `merchant.backfill.PayMerchantBackfill` / impl（读不到 `mch_*`，只收解析好的行；一行一个事务）：
  - **B1** `platformMerchant`：平台级收款商户（`owner_type = PLATFORM`），按（渠道, 渠道商户号, 子商户号 ''）幂等；微信取 `shop.pay.wechat.mchid`，没配或别的渠道用 `PLATFORM-{渠道}` 占位。
  - **B2** `merchant`：进件单 `LEGACY-{旧行 id}` + v1（外部单号 `LEGACY-{id}-V1`），状态 NONE→DRAFT · APPLYING→CHANNEL_REVIEW · ACTIVE / FROZEN→APPROVED · REJECTED→REJECTED，在途键只给在途单；已开户（有号）才建收款商户，**沿用原号**，ACTIVE→ACTIVE · FROZEN→SUSPENDED · 其余 ONBOARDING。直连（MERCHANT_OWN）渠道商户号 = 旧子商户号；服务商模式渠道商户号 = 父商户号、子商户号另列、挂 B1 父商户。
  - **B4**：旧 `pay_methods` 逐项按（渠道, 接入模式, 渠道方式码）映射 `pay_channel_product`；映射不到 → `METHOD` 冲突；额度落 JSAPI 那一条；来源 CHANNEL_RECEIPT。
- shop-app `paybridge.PayMerchantBackfillRunner#run(entityNos)`（常驻组件，按主体跑；调度壳 `PayMerchantBackfillJob` `pay-merchant-backfill` 只手动触发、全量跑、有冲突记失败）：读旧三表，`store_no` 非空 → 该店，空串 → 主体默认门店，找不到 → **Q2**；接入模式按部署（微信 `ecommerce` → SERVICE_PROVIDER、`direct` → MERCHANT_OWN；支付宝 SERVICE_PROVIDER；测试 / 桩渠道 PLATFORM_SUB）。
  - **B3** 门店绑定：来源 = 门店上指定的号 + 门店自己那行（ACTIVE）的号；**两者都没有才展开主体级号**；两来源不同 → **Q3**、不绑；新模型里已有不同的绑定 → `BOUND`、不改。经 `PayMerchantBindingService#bind`，同主体 / 同市场 / ACTIVE 的校验照走。
  - 报告 `Report(merchants, bindings, conflicts)`，冲突一行一条、以类别打头（Q2 / Q3 / METHOD / UNKNOWN / BOUND / STORE / NO_SUB_MCHID / CURRENCY）。

### S4 · 双写
- 开关 `shop.pay.merchant.dual-write`（`application.yml`，环境变量 `SHOP_PAY_MERCHANT_DUAL_WRITE`，默认关）。
- **不逐个写点翻译，按主体重放回填规则**：`PayMerchantBackfillRunner#sync(entityNo)` 与回填同一套解析，只是**旧表说了算** ——
  支付域 `PayMerchantBackfill#mirror` 让已有进件单跟上状态与申请单号（换了申请单号 = 重提，另起一版，驳回原因留在上一版；在途键随状态进出），
  已有收款商户跟上状态、资金路径与资料，**状态变了、资金路径变了各写一条 `pay_merchant_status_log`**；门店绑定与旧表不一致就改绑（`bind` 同事务释放旧的），
  旧表已解析不出号就解绑；Q3 照样只报不取舍、不动原绑定。
- B3 的顺序改成与旧解析（`MerchantPortImpl#accountOf`）逐档一致：门店指定的号只有「主体级或本店专属、ACTIVE、同渠道」才算，否则往下落（旧解析也是落）；不认识的号报 `UNKNOWN` 但不再挡住展开。
- 挂钩：`shop-base` 新 `spi.pay.PayMerchantMirrorPort`（实现 `shop-app` `paybridge.port.PayMerchantMirrorPortImpl`，看开关后调 `sync`）；
  `shop-merchant` `service.PayMerchantMirror` / impl —— 有事务时登记到**提交之后**、同一事务同一主体只同步一次，没事务立即同步；**失败只记日志 `[pay-merchant-dual-write]`、不往外抛**。
- 写点（9 处）：`MerchantPaymentServiceImpl#refresh`（含提交、轮询，都经它）· `#openForStore` · `MerchantPortImpl#ensurePayment` · `#ensureDefaultStore` ·
  `StoreAdminServiceImpl#create` · `#setDefault` · `#setPayment` · `MerchantStoreServiceImpl` 门店保存 · `SelfOperatedServiceImpl` 建店 · `MerchantGovernServiceImpl#setFundsMode`。

### S5 · 比对 C07
- shop-app `paybridge.PayMerchantComparator#compare(entityNos, confirmed)`：每个主体的每个门店 × 渠道（旧表出现过的渠道 ∪ 新模型可收款的渠道；线下 / 免单 / 桩 / 测试渠道旧解析不按渠道过滤，不比）——
  旧 `MerchantQueryPort#payMerchantNoOf`（结算取号那条，ACTIVE）对新 `PayMerchantQueryPort#routeOf`：
  `NO_PAY` 旧有新无 · `NO_LEGACY` 旧无新有 · `NUMBER` 号不同；号相同再比 `SUB_MCHID`（旧 `sub_mchid` 对新「直连取渠道商户号、否则取子商户号」）与 `FUNDS_PATH`（主体 `funds_mode` 对 `pay_merchant.funds_path`）。
- 每条差异带主体、门店、渠道、新旧两个值；**人工确认按 `Diff#key()` = 门店|渠道|类别|旧值|新值** 认，任一侧值再变确认自然失效。
- **门禁** `Result#readSwitchAllowed()` = 全量比对 且 未确认差异为 0（按主体抽查只说明这几家对上了，不放行）。S6 读切以它为准。
- 调度壳 `PayMerchantCompareJob`（`pay-merchant-compare`，只手动触发、全量）：每条差异一行日志 `[pay-merchant-compare] … key=…` 留档；确认清单读 `shop.pay.merchant.compare.confirmed`（新配置项，逗号分隔）；放行记成功、否则记失败。

### S6 · 读切
- 开关 `shop.pay.merchant.read-from=legacy|pay`（`SHOP_PAY_MERCHANT_READ_FROM`，默认 legacy；其他值启动即失败）。
- **门禁写进开关**：`spi.pay.PayMerchantReadSwitch`，实现 `shop-app` `paybridge.port.PayMerchantReadSwitchImpl` —— 生效 = 配置要 pay **且** 启动完成后（`ApplicationReadyEvent`）跑一次全量比对 C07、`readSwitchAllowed()`；
  比对前、比对失败、有未确认差异都按旧解析，打 ERROR `[pay-merchant-read]`。`refreshGate()` 可重跑。
- **切换点在旧端口内部，一处**：`MerchantPortImpl` 的四个解析本体 —— `accountOf`（`payMerchantNoOf` · `accruePayQuota` · `payCapabilityOf`）、`paymentOf`（`settleCycleOf` · `feeBearerOf`）、`paymentRouteOf`、`activeChannelsOf`。
  读切后：门店（空 = 默认门店）→ `PayMerchantQueryPort#routeOf(storeId, channel)` 的号 → 旧行取资料（额度、账期、费用承担仍在旧行上，双写期两边一致）；
  `paymentRouteOf` 保留「门店与主体在营业」前提，子商户号与资金路径取新模型；`activeChannelsOf` 取 `collectableRoutes` 的渠道。**没绑定就是没有**。
- 线下 / 免单 / 桩 / 测试与空渠道不读切（旧解析本就不按渠道过滤，也不对应收款号）；`payCapabilityOf` 付款前不知道渠道，照旧口径。
- **比对必须永远拿旧解析**：新增 `MerchantQueryPort#legacyPayMerchantNoOf`（不看读切），`PayMerchantComparator` 改用它 —— 否则读切一开，比对就是新模型对新模型，门禁恒开。这一条是 S6 的红测试逼出来的。

### S7 · 进件迁入支付
- 支付域 `onboarding.service.OnboardingService` / impl：`open`（草稿，门店快照经 `payStoreById`；接入模式、父商户、币种走新抽出的 `merchant.MerchantDefaults`，回填同用）·
  `submit`（另起一版；**外部单号 = 进件单号-V{n}，每版唯一 R04**；同事务占 `inflight_key = 门店|渠道`，唯一键撞上 → `PAY_ONBOARDING_INFLIGHT`，整体回滚 **R06**；
  当前驳回原因显式置空、上一版转 SUPERSEDED 但原因留着 **R23**；渠道调用在事务外，渠道没收下就退回提交前状态、放开在途键、这一版记 `SUBMIT_FAILED`）·
  `refresh`（ACTIVE 且有子商户号 → 产出收款商户：新号 `PM…`、状态日志、开通该渠道 × 接入模式下启用的支付产品、进件单 APPROVED；门店还没有这个渠道的绑定才绑上，已有绑定不悄悄改；
  REJECTED → 原因落在进件单与当前版，没给原因写「通道未说明」兜底）· `poll(limit)`。
- **回填来的 `LEGACY-*` 进件单不碰**（提交、回查拒绝 `PAY_ONBOARDING_STATE`，轮询跳过）：它们由旧进件流程推进、经双写镜像过来，支付这边再去回查就会给同一个渠道子商户再开一个收款商户。
- 资料一字段一行（`pay_onboarding_material`）：联系人、手机、执照、结算账户类型照存；**结算账号只存掩码 `SETTLE_ACCOUNT_MASKED`**，明文只在提交时交给渠道。
- 新错误码 `PAY_ONBOARDING_INFLIGHT`（70090）· `PAY_ONBOARDING_STATE`（70091，回显原因）+ 三语；新业务键前缀 `BizKey.PAY_ONBOARDING = "PON"`。
- 调度壳 `PayOnboardingPollJob`（`pay-onboarding-poll`，与旧 `applyment-poll` 同为每 30 分钟）。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC2 / AC3 | `PayMerchantRoutingFlowTest`（5 条：`onlyStoreBindingResolves` 同主体没绑的店解析不到；`suspendedMerchantNotCollectableButRefundable`；`collectableRoutesByPriority`；`bindingRules` 别的主体 / 别的市场 / 非 ACTIVE / 渠道不符；`rebindKeepsOneActive`） | ✅ | ① 不看状态 + 不查同主体 → 两条红 ② 没绑定兜底取任一 ACTIVE（R01 的样子）+ 改绑不释放 → 两条红 ✅ |
| AC4 | `PayMerchantBackfillFlowTest`（3 条：`merchantsMigrated` 主体级号落默认门店、原号、资金路径取主体、默认额度落产品；`bindingsExpandedAndConflictsReported` 默认店 / 门店自己的号 / 主体级号展开 / Q3 不绑，Q2、METHOD 进报告；`idempotent` 再跑五张表行数不变） | ✅ 3/3 | ① 不展开 + Q3 自动取第一个 → `bindings…` 红 ② 进件与商户不按号幂等 → `idempotent` 红 ③ 映射不到时默默跳过 → `bindings…` 红 ✅ |
| AC5 | `PayMerchantDualWriteFlowTest`（5 条，走真旧入口：`onboardingResultMirrored` 审核中镜像成在途进件单、回查开户后 APPROVED + 收款商户 ACTIVE + 两店绑定；`storeBindingMirrored` 门店指定号 → 改绑、仍一条 ACTIVE；`fundsModeAndStatusMirrored` 冻结 → SUSPENDED、切资金路径，各一条状态日志；`switchOffWritesNothing`；`mirrorFailureDoesNotBlockLegacy` 同步抛错时开户照样落旧表） | ✅ 5/5 | ① 挂钩不吞失败 → `mirrorFailure…` 红 ② 双写当回填用（不跟、不改绑）→ 3 条红 ③ 不看开关 → `switchOff…` 红 ④ 切资金路径不报 → 2 条红 ✅ |
| AC6 | `PayMerchantCompareFlowTest`（5 条，漂移一律绕开双写直接改库：`consistentAfterBackfill` 回填后 3 店差异 0、但抽查不放行；`driftsReportedWithBothNumbers` 新店没绑 NO_PAY、新模型改绑 NUMBER，带门店渠道与两个号；`statusAndFundsPathDrift` 旧号冻结 NO_LEGACY、资金路径 FUNDS_PATH；`subMerchantDrift`；`confirmationIsBoundToBothValues` 确认后通过、值再变作废） | ✅ 5/5 | ① 号一致就不比子商户号与资金路径 → 2 条红 ② 确认只认门店 + 渠道 → 起初**没红**（同一步改名让别的店也出差异，`passed()` 本来就假），改成断言 s4 那一条的 `confirmed` 后红 ③ 抽查也放行 → `consistent…` 红 ④ 旧解析为空就不比 → `statusAndFundsPath…` 红 ✅ |
| AC7 | `PayMerchantReadSwitchFlowTest`（5 条；回填后绕开校验把 s2 的新绑定改到 s3 的号上，新旧对 s2 答案不同：`payModeAnswersFromBindings` 取号 / 默认门店 / 账期 / 费用承担 / `paymentRouteOf` / 可收渠道 / 额度累加都按绑定，新店解析不到；`switchBackRestoresLegacy`；`gateClosedStaysLegacy`；`gateFollowsFullCompare` 有未确认差异刷新后关门；`channelFreeStaysLegacy`）· 默认 legacy 下 Settle / Pay / Merchant / Store / Applyment + 守卫 984 条与基线比：7 条失败，6 条在 I0 基线；`BrandStoredValueHttpSelectionTest.chooseOneWallet…` 在 I0、I3 提交上同样失败（储值快照反序列化，与本迭代无关） | ✅ 5/5 | ① 不看门禁 → 2 条红 ② 没绑定兜底旧解析（R01）→ `payMode…` 红 ③ 比对拿会被读切的 `payMerchantNoOf` → `gateFollows…` 红 ④ 测试渠道也读切 → `channelFree…` 红 ⑤ 账期 / 费用承担不读切 → `payMode…` 红 ✅ |
| AC8 | `PayOnboardingFlowTest`（5 条，走真 `StubApplymentGateway`：`submitThenApprove` 审核中占在途键、v1 外部单号、资料逐字段、账号无明文 → 通过产出 ACTIVE 收款商户 + 状态日志 + 门店绑定；`onlyOneInflight` R06；`rejectedThenResubmitted` R23 + R04；`missingReasonFallsBack`；`pollLeavesLegacyOnboardingsAlone` 轮询推进支付单、LEGACY 单原样且回查被拒） | ✅ 5/5 | ① 外部单号不分版本（R04）→ 2 条红 ② 不占在途键（R06）→ 4 条红 ③ 重提不清当前原因（R23）→ 1 条红 ④ 不护 LEGACY 单 → 1 条红 ⑤ 通过不绑门店 → 1 条红 ✅ |
| AC9 | 全量 `mvn -o -pl shop-app -am test`（2026-10-26 07:24–08:07 +04）：4017 条，失败 34 / 错误 28，按方法去重 70 条；与 I0 基线比多 30 条，逐条归因 —— **无一由 I4 引起**：① 21 条是跑到末尾堆满（日志 18 处 `OutOfMemoryError`）上下文加载失败的连带：`SwitchToMerchantTest` 9 · `WxPhoneLoginFlowTest` 8 · `UnifiedActivityManagementFlowTest` 2 · `UnifiedOrderActivityTransactionTest` 2，单独新 JVM 重跑全绿；② `PrintDeliveryTest` 8 条 = 基线里的 `PrintDeliveryTest.setup`（shop-core 打印，H2 不认多列 `ALTER TABLE … ADD`），这次按方法报；③ `BrandStoredValueHttpSelectionTest.chooseOneWallet…` 在 I0、I3 提交上同样失败（储值快照反序列化）。I4 的 6 个场景类都复用支付共用上下文，日志里没有一次由它们启动新上下文 —— 本迭代没有给上下文缓存加量 | ✅ | — |
| AC1 | `MapperSmokeTest`（新 7 个 mapper）· `SchemaDriftTest` · `PayMigrationTest` · 本机 MySQL 9.7 临时库执行 V10 | ✅ | — |

## §6 对账二 · 设计 → 实现

`git diff --stat codex/pay-i3-acquiring..codex/pay-i4-merchant`：7 个提交、53 个文件（+4058 / −7），逐行对 §2：

| §2 设计 | 实际文件 | 对上 |
|---|---|---|
| S1 迁移 10 + 7 实体 + Mapper | `db/pay/V10__pay_merchant_tables.sql` · `merchant.entity.*`（4）· `onboarding.entity.*`（3）· `mapper.merchant.MerchantMappers` · `mapper.onboarding.OnboardingMappers` · `schema-test.sql`（生成） | ✅ |
| S2 收款路由与绑定 | `spi.pay.PayMerchantQueryPort` · `merchant.port.PayMerchantQueryPortImpl` · `merchant.service.PayMerchantBindingService` / impl · `spi.user.MerchantQueryPort#payStoreById` · `MerchantPortImpl`（实现）· `ErrorCode.PAY_BINDING_INVALID` + 三语 · `known-pay-reverse-deps.txt` · `PayMerchantRoutingFlowTest` | ✅ |
| S3 回填 B1–B4 | `merchant.backfill.PayMerchantBackfill` / impl · `paybridge.PayMerchantBackfillRunner` · `PayMerchantBackfillJob` · `TableHasProducerTest`（删 5 行）· `PayMerchantBackfillFlowTest` | ✅ |
| S4 双写 | `spi.pay.PayMerchantMirrorPort` · `paybridge.port.PayMerchantMirrorPortImpl` · `shop-merchant` `service.PayMerchantMirror` / impl · 9 个写点所在的 6 个类 · `PayMerchantBackfill#mirror` · `application.yml`（`dual-write`）· `PayMerchantDualWriteFlowTest` | ✅ |
| S5 比对 C07 | `paybridge.PayMerchantComparator` · `PayMerchantCompareJob` · `application.yml`（`compare.confirmed`）· `PayMerchantCompareFlowTest` | ✅ |
| S6 读切 | `spi.pay.PayMerchantReadSwitch` · `paybridge.port.PayMerchantReadSwitchImpl` · `MerchantPortImpl`（四个解析本体）· `MerchantQueryPort#legacyPayMerchantNoOf`（§2 S6 末条，红测试逼出）· `application.yml`（`read-from`）· `PayMerchantReadSwitchFlowTest` | ✅ |
| S7 进件迁入支付 | `onboarding.service.OnboardingService` / impl · `merchant.MerchantDefaults`（回填与进件共用，S3 的私有方法上提）· `paybridge.PayOnboardingPollJob` · `ErrorCode` 70090 / 70091 + 三语 · `BizKey.PAY_ONBOARDING` · `known-pay-reverse-deps.txt` 35 → 38 · `PayOnboardingFlowTest` | ✅ |

没有 §2 之外的文件；§2 列了却没动的只有「旧进件入口转调」（偏差 14，已从 S7 划出）。

### 偏差说明

1. **V10 不建 `ck_pay_merchant_2`**，理由见 §2 S1。
2. **反向依赖预算 MerchantQueryPort 31 → 35**：绑定校验要知道门店的主体与市场，新增 `payStoreById`（真的跨域调用，pay-svc 独立时要补一个 HTTP 客户端方法），已登记 `known-pay-reverse-deps.txt`。
3. **回填报告不数开通产品**：设计稿的报告有产品数，实现只回 `merchants / bindings / conflicts` —— 产品是商户的附属，五张表的行数由比对（S5）与幂等用例看。
4. **微信直连（`direct`）旧行按 MERCHANT_OWN 迁、渠道商户号取旧子商户号**：旧模型直连时实际收款用平台商户号、子商户号只作标识；这样迁可保住（渠道, 渠道商户号, 子商户号）唯一，但「收款到底用谁的号」要以旧解析为准核一遍。**S5 比对核不出这一点**：比的是数据（旧 `sub_mchid` 对新渠道商户号，按构造必然相等），不是网关实际拿哪个商户号下单 —— 留给 S6。**S6 已核**：`WechatDirectPayGateway` 下单、查单一律用配置 `shop.pay.wechat.mchid`（构造注入的 `mchId`），从不读解析出的子商户号；读切只改「记在哪个收款商户名下」，不改收款账户。
5. **市场 → 币种在回填里写死**（CN / AE / SA）：`pay_channel_product_market` 是按产品配的，没有「市场的本币」一处真源；其他市场进 `CURRENCY` 冲突、不迁。
6. **双写是「按主体重放」而不是逐写点翻译**：一个主体只有几行旧数据，重放比把 9 个写点各自翻成新模型的写法简单，也不会漏写点；代价是每次写都重读这个主体的旧三表。没经过这 9 个服务的写（手工 SQL、数据修复）不会被同步，由比对（S5）报出来。
7. **新增 `spi.pay.PayMerchantMirrorPort`**（`shop-merchant` → 支付方向，不计入支付反向依赖预算）。实现留在 `shop-app`：它要读旧表，拆 pay-svc 时改成调支付的内部接口。
8. **B3 顺序向旧解析看齐**（S3 时门店指定号只要是本主体 ACTIVE 号就算）：别家门店的专属号、非 ACTIVE 号旧解析会往下落，S3 那版会照绑，S5 比对必然报差异。`PayMerchantBackfillFlowTest` 3 条照样绿。
9. **比对不建表**：设计稿说「比对结果留档」，没有对应的表；留档 = 任务运行记录 + 每条差异一行日志，人工确认放配置 `shop.pay.merchant.compare.confirmed`（随配置变更走审计）。确认量大到配置放不下时再加表。
10. **旧解析只取 `payMerchantNoOf`**：执行方案列了三条旧解析，`paymentRouteOf` 只认门店显式绑定、不兜底（交易快照专用，空了不收款），与 `routeOf` 的语义不是一回事；`resolvePayment` 是私有方法，走的也是 `accountOf`。三者里只有 `payMerchantNoOf` 与新模型「门店绑定 = 收款商户」对应。
11. **AC7「现有用例全绿」按默认 legacy 验**：现有用例直接往旧表造数、不建门店绑定，在 pay 模式下整体跑没有意义（会全部「解析不到」，那正是 R01 要的行为）。pay 模式的正确性由 `PayMerchantReadSwitchFlowTest` 覆盖；默认 legacy 下行为零变化由回归验。
12. **读切后 `activeOnly=false` 的口径收窄**：旧的能力 / 额度解析不限状态（审核中的号也算），新模型只绑 ACTIVE 商户 —— 读切后审核中 / 冻结的号的额度与账期解析不到。额度累加在回调里「解析不到就不记」，不让回调失败。
13. **执行方案说「17 个调用方改走 `PayMerchantQueryPort`」，本迭代不改调用方**：切换点放在旧端口内部，一处、可回退；调用方逐个迁移等进件迁入（S7）后另起。`payCapabilityOf`（付款前不知道渠道）照旧口径，待调用方带上渠道或改用 `capabilityOf` 时再切。两处品牌包原生 SQL（`ManagementScopeServiceImpl` 按收款商户号反查主体做管理范围、`PaymentMerchantScopeServiceImpl` 列主体的收款商户）也未动：它们是「号属于谁」的归属查询，不是收款解析；读切期间旧行都在，结果不变，随旧进件入口一起迁（偏差 14）。
14. **旧进件入口（`/biz/merchant/payment*`、`/ops/onboarding*`、`applyment-poll`）本迭代不转调支付**：读切期间新旧必须同源 —— 支付自己产出的收款商户没有旧行，
    双写（S4）会按旧表把它的门店改绑回去，比对（S5）会把它报成 `NO_LEGACY`、下次启动关上读切门禁，读切路径取资料（`legacyRowOf`）也会扑空。
    转调要和「停双写、退役比对、读切路径改从新模型取资料」一起做，落在 I5 的商家端 `/biz/pay/onboardings` 与运营端进件接口上。本迭代交付的是支付侧进件能力本身与轮询。
15. **敏感资料只存掩码**：`ck_pay_onboarding_material_1` 要求 SENSITIVE 字段有密文 id，密钥模块搁置（执行方案 §4.6），结算账号只落 `SETTLE_ACCOUNT_MASKED`（TEXT），与旧进件一致；联系人手机按 TEXT 存（字段字典把它列为普通字段）。密钥模块就绪后改为 SENSITIVE + 密文。
16. **草稿的 `current_version = 0`**：表注释是「当前资料版本」、默认 1；没提交过的草稿没有版本行，记 0 让「首提 = 第 1 版」不用特判。
17. **反向依赖预算 MerchantQueryPort 35 → 38**：进件取门店快照用 S2 已登记的同一个 `payStoreById`，不是新的跨域方法；+3 是新类的 import / 字段 / 构造参数。
18. **I3 偏差 10 转来的 `PaymentPort#startPayment` 与独立的 `RouteSelectionPolicy` 本迭代没做，再转 I5**：今天交易下单冻结路由走 `MerchantQueryPort#paymentRouteOf`
    （商城 `OrderServiceImpl`、行业结账 `IndustryCheckoutServiceImpl`、积分目标），S6 已把它切到门店绑定 —— 读切后「选路由」事实上由 `PayMerchantQueryPort` 回答；
    缺的是「支付域自己选方式 → 选路由 → 冻结快照」这一个入口，它与开放 API 建单同形，随 I5 一起做。
