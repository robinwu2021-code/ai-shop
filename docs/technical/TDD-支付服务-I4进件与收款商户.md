# TDD-支付服务-I4进件与收款商户

状态：实施中
档位：2 · 依据：[开发计划 · 支付服务](./开发计划-支付服务.md) §5 I4 · [数据库现状对比与执行方案](./支付服务-数据库现状对比与执行方案.md) §3.2、§4.5（B1–B4、S5 比对、S6 读切）、§4.6、§5（Q2 / Q3 / Q4 / Q6）· [数据库表结构说明 v4](./TDD-支付服务-数据库表结构说明.md) §6.2、§8.1、§8.2 · [代码清单](./支付服务-代码清单-开放API刷卡POS内部调用.md) §2.4 · [ADR-041](./ADR/ADR-041-进件与收款商户归支付域.md) · [代码 Review](./支付服务-代码Review-2026-10-24.md) R01 / R02 / R04 / R06 / R23 / R24 / R26
创建：2026-10-26 · 最后更新：2026-10-26

把「收款商户」与「进件」从 `shop-merchant` 的一张 `mch_payment_merchant`（一行既是进件申请又是商户号，`store_no` 空串 = 主体级默认号）迁成支付域的模型：
进件单（多版本、资料一字段一行）成功产出**收款商户** `pay_merchant`；门店用哪个收款商户由**门店绑定** `pay_merchant_binding` 决定，**没有主体级默认、没有兜底**。
迁移按执行方案的四步走：**双写 → 回填 → 比对 → 读切**，每一步一个开关，出问题切回旧解析。**每片先写会变红的测试，修复后变绿，再撤修复做一次消融。**

## 切片

| 片 | 内容 | 提交 |
|---|---|---|
| S1 | 迁移 10（`db/pay` V10）：进件单 / 版本 / 资料，收款商户、门店绑定、开通产品、状态日志；7 个实体与 Mapper | — |
| S2 | `spi.pay.PayMerchantQueryPort` + `PayRoute` + `PayMerchantQueryPortImpl`（只按门店绑定解析、无兜底）；`PayMerchantBindingService`（同主体、同市场、ACTIVE） | — |
| S3 | 回填 B1–B4：平台级商户（配置里的服务商号）→ 收款商户与进件 v1 → 门店绑定（主体级号逐店展开）→ 开通产品；三份冲突报告（Q2 无默认门店、Q3 两来源不一致、方式映射不到） | — |
| S4 | 双写 `shop.pay.merchant.dual-write`：进件结果、门店绑定、资金路径在旧表落库的同时写新表 | — |
| S5 | 比对 C07：每个门店 × 渠道，旧解析与 `routeOf` 逐条比，差异出报告；差异为 0 是读切门禁 | — |
| S6 | 读切 `shop.pay.merchant.read-from=legacy|pay`：旧端口的支付解析方法改由新模型回答；切回即回退 | — |
| S7 | 进件迁入支付：`OnboardingService`（每版本外部单号唯一 R04、在途唯一 R06、驳回原因随版本 R23）+ 轮询；旧入口在 `pay` 读切后转调 | — |

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

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC2 / AC3 | `PayMerchantRoutingFlowTest`（5 条：`onlyStoreBindingResolves` 同主体没绑的店解析不到；`suspendedMerchantNotCollectableButRefundable`；`collectableRoutesByPriority`；`bindingRules` 别的主体 / 别的市场 / 非 ACTIVE / 渠道不符；`rebindKeepsOneActive`） | ✅ | ① 不看状态 + 不查同主体 → 两条红 ② 没绑定兜底取任一 ACTIVE（R01 的样子）+ 改绑不释放 → 两条红 ✅ |
| AC4 | `PayMerchantBackfillFlowTest`（3 条：`merchantsMigrated` 主体级号落默认门店、原号、资金路径取主体、默认额度落产品；`bindingsExpandedAndConflictsReported` 默认店 / 门店自己的号 / 主体级号展开 / Q3 不绑，Q2、METHOD 进报告；`idempotent` 再跑五张表行数不变） | ✅ 3/3 | ① 不展开 + Q3 自动取第一个 → `bindings…` 红 ② 进件与商户不按号幂等 → `idempotent` 红 ③ 映射不到时默默跳过 → `bindings…` 红 ✅ |
| AC1 | `MapperSmokeTest`（新 7 个 mapper）· `SchemaDriftTest` · `PayMigrationTest` · 本机 MySQL 9.7 临时库执行 V10 | ✅ | — |

## §6 对账二 · 设计 → 实现

（实现后填）

### 偏差说明

1. **V10 不建 `ck_pay_merchant_2`**，理由见 §2 S1。
2. **反向依赖预算 MerchantQueryPort 31 → 35**：绑定校验要知道门店的主体与市场，新增 `payStoreById`（真的跨域调用，pay-svc 独立时要补一个 HTTP 客户端方法），已登记 `known-pay-reverse-deps.txt`。
3. **回填报告不数开通产品**：设计稿的报告有产品数，实现只回 `merchants / bindings / conflicts` —— 产品是商户的附属，五张表的行数由比对（S5）与幂等用例看。
4. **微信直连（`direct`）旧行按 MERCHANT_OWN 迁、渠道商户号取旧子商户号**：旧模型直连时实际收款用平台商户号、子商户号只作标识；这样迁可保住（渠道, 渠道商户号, 子商户号）唯一，但「收款到底用谁的号」要在 S5 比对与 S6 读切时以旧解析为准核一遍。
5. **市场 → 币种在回填里写死**（CN / AE / SA）：`pay_channel_product_market` 是按产品配的，没有「市场的本币」一处真源；其他市场进 `CURRENCY` 冲突、不迁。
