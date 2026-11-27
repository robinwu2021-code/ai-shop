# TDD-运营端套餐商城（commerce Task 5）

状态：2026-11-11 P01–P07 本地实现与专项验证完成，待部署及目标门店联调
关联需求：[商城套餐实施计划 Task 5](../superpowers/plans/2026-11-04-commerce-mall-purchase-implementation.md) ·
[台湾线下购买补充设计](../superpowers/specs/2026-11-04-twd-offline-commerce-amendment.md) ·
接口真源 [commerce-mall-http.md](../api/commerce-mall-http.md) + `commerce-mall-openapi.json`（task8-v1）
创建：2026-11-04 · 最后更新：2026-11-11

档位：1 —— 本轮增加收款档案管理读模型和订阅摘要字段，保留原支付与冻结订单合同；补齐运营发布、档案恢复、代购入口。

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 经营类型 / 业务端 / 三档 Plan / 商品 SKU 可起草，编辑权与发布权分开 | `app/mall/catalog-tab.tsx`、`products-tab.tsx`；页面按 `commerce:catalog:edit` / `:publish` 分别显隐 |
| AC2 | 业务端从 deployment-options 选，不手填 client/route/acceptance | `catalog-tab.tsx` EndsSection（选中部署后编码只读，VERIFIED 必须绑定） |
| AC3 | 发布 / 停售带乐观锁版本、operationNo、审计原因；已发布快照只读 | PublishCard / `publishMallSkuVersion` / `stopMall*Sale`；`newOpNo` 每次表单打开生成一次 |
| AC4 | 金额 `{currency, minor}`，minor 整数字符串，不做浮点 | `lib/commerce-money.ts`（字符串切分 + BigInt） |
| AC5 | 订单列表/详情独立显示 payment / fulfillment / refund 三维 | `orders-tab.tsx` + `order-drawer.tsx` `OrderAxes` |
| AC6 | 硬件发货、退款审核（review-quote → approve/reject） | `order-drawer.tsx` ShipmentsSection / RefundsSection |
| AC7 | 门店权益来源：零金额独立端赠送 / 撤销 | `sources-tab.tsx` |
| AC8 | 收款档案（按币种）、待审队列翻页、订单线下详情、凭证二进制上传 | `collection-tab.tsx`、`claims-tab.tsx`、`order-drawer.tsx` OfflinePanel / EvidenceBox；`http-client.post` 支持 Blob |
| AC9 | 拒绝申报、核款、对账作废（含 MALL_UNMATCHED 处置）、代客报价→下单→发指引、线下退款出款 | `order-drawer.tsx` ConfirmForm / VoidForm / PayoutForm；`assisted-tab.tsx` |
| AC10 | 「申报」「资金」「权益」三类状态分开；ACCEPTED ≠ 到账 | OfflinePanel 三栏状态 + `ActionResult`（ACCEPTED 挂「资金端未确认」） |
| AC11 | 台湾三家线上通道只显示「未接通」，不可启用 | `collection-tab.tsx` 常量列表 + 禁用开关（`TW_ONLINE_CHANNELS`） |
| AC12 | 接口没就绪时报错，不回落 mock 成功 | `lib/api/https/commerce.ts` 原样抛 ApiError；`lib/commerce-reason.ts` 翻译原因码 |
| AC13 | `/ops/biz-mall/**` 等「做了没入口」端点被页面调用而消掉 | `check-ops-orphan`：33 → 0 新增 |

**孤立项**：无没落点的 AC。挂不上 AC 的设计 —— `lib/commerce-reason.ts` 与 `providers.tsx` 的全局 toast 翻译，
理由：后端商城错误 msg 是机器原因码，不翻译等于 AC12 的报错对运营不可读。

| AC14 / P04 | 发布使用目录回读版本，冲突后刷新，跨会话无需手填 | PublishCard 消费 catalogVersion |
| AC15 / P05 | 停用档案仍可读当前版本并恢复，报价仍只认可用档案 | 管理读模型与原 terms 读口分离 |
| AC16 / P06 | 展示门店当前方案与币种，筛选经营类型匹配商品 | 订阅只读摘要；沿原 subscription:read 授权 |
| AC17 / P07 | 硬件代购填写收货地址，同一请求重试保持原地址与幂等键 | AssistedTab 沿现有 ShippingAddress 合同 |

## §1 现状与影响面

- 相关现有模块：`ops-web` 分层（contracts / https / mocks / mock/db）、`lib/nav.ts`、`lib/perm-map.ts`、`lib/point-codes.ts`。
- 可直接复用：DataTable / Drawer / ConfigCard / useConfirm / ReadOnlyNotice / StatusBadge / TabHeader。
- 会被改到的：`http-client.post`（新增 Blob 分支，JSON 路径不变）；全局 mutation 错误 toast（只替换已登记的商城原因码）。
- 旧「增值包授予 / 档位定义」沿用原逻辑；本轮后端扩充管理查询和订阅摘要，不改报价、资金或订单冻结规则。

## §2 方案

### 2026-11-11 增量执行计划

1. 复核 P01–P03 已有目录、权益、SKU 和发布校验，复用有效验证。
2. P04 移除猜测/手填目录版本，展示服务器 catalogVersion；冲突刷新后由运营重新确认发布。
3. P05 管理查询返回当前不可用档案及 enabled；支付报价/发指引仍沿原启用约束，原冻结 Terms 不增加可变字段。
4. P06 订阅读口增加经营类型、当前方案版本、币种、服务器时间摘要，保留现有读权限；无读权限时提示，并仍由正式报价决定资格。
5. P07 增加硬件地址表单，首次下单冻结地址，重试复用原意图；复用既有履约合同。
6. 集中执行变更相关 OPS 页面/合同测试、类型检查与后端 MySQL 9 测试，更新清单和真实证据。

### 契约变更
- 端点：无新增。档案 GET 返回独立 `OfflineCollectionProfile`，包含 `enabled`；PUT 和报价仍沿原 `OfflineCollectionTerms`。订阅读口增加 `businessTypeCode`、`currency`、`currentPlanVersionNo`、`serverNow`，授权不变。
- 库表 / 迁移：`V628__commerce_mall_ops_menu.sql` —— 7 个 MENU 功能点（`OPS_COMMERCE*`）+ SUPER_ADMIN 授权，可重入。
- 权限码：无新码；`UI_PERM_MAP` 恒等登记 V625/V627 的 17 个 `commerce:*` 码。
- i18n：`app/mall/copy.ts`（中英齐平）、`lib/i18n/nav-labels.ts` 8 条导航译文。
- 配置项：无。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `ops-web/lib/types/commerce.ts` | OpenAPI schema 逐字镜像，`Mall` 前缀 |
| 新增 | `ops-web/lib/api/contracts/commerce.ts` · `https/commerce.ts` · `mocks/commerce.ts` | 三层同构 |
| 新增 | `ops-web/lib/mock/db/commerce.ts` | 种子 + 状态机，原因码与后端同构 |
| 新增 | `ops-web/lib/commerce-money.ts` · `commerce-reason.ts` | 金额与原因码 |
| 新增 | `ops-web/app/mall/*` | 7 页签 + 订单抽屉 |
| 修改 | `lib/nav.ts`（merchant section 加 `commerce` 模块与 7 叶子）、`perm-map.ts`、`point-codes.ts`、`nav-labels.ts`、`http-client.ts`、`providers.tsx`、三个组合根 | |
| 修改 | `lib/nav.test.ts`（ADDED_SINCE_MERGE 登记 7 条）、`lib/perm-map.test.ts`（后端码集合补读 V625/V627 的 `commerce:` 种子） | 守卫口径扩展，不放宽 |
| 修改 | `packages/shared/src/contract/enum-registry.ts` | 登记 14 个商城枚举 |
| 新增 | `backend/.../V628__commerce_mall_ops_menu.sql` | 菜单行 |
| 生成 | ui-catalog.json、运营端-功能清单.md、openapi-ops.yaml、权限配置落库数据清单、动态菜单×角色矩阵 | |

## §5 对账三 · 实现 → 需求

| AC | 测试 |
|---|---|
| AC1/AC3 | `lib/mock/db/commerce.test.ts` 「新建经营类型必须停用…」「FNB：起草三档 → 行业发布…」「SKU：退款政策只认两种…」 |
| AC2 | 同文件「业务端：VERIFIED 必须从部署验收清单里选…」 |
| AC4 | `lib/commerce-money.test.ts` 6 条 |
| AC5/AC6 | 「硬件发货…」「退款审核…」 |
| AC7 | 「权益来源…」 |
| AC8/AC9/AC10 | 「档案每次修改生成新版本…」「待审队列…」「拒绝申报只改申报…」「核款…」「金额不符 → UNMATCHED…」「作废…」「线下退款出款…」「代客…」「凭证每个键只能传一次…」 |
| AC8（二进制） | `lib/api/https/commerce.test.ts`「凭证上传发原始字节…」 |
| AC12 | 同文件「后端拒绝：原因码原样抛出…」「接口没装配（404）时报错…」；`lib/commerce-reason.test.ts` |
| AC13 | `node scripts/check-ops-orphan.mjs --check` |

消融：把 `http-client.post` 的 Blob 分支关掉 → 「凭证上传发原始字节」变红；去掉 mock 的收款主体校验 → 「核款…」变红；还原后全绿。

## §6 偏差与缺口

1. **已修复（P04）**：服务器已有 `catalogVersion`，OPS 自动读取并只读展示，缺失时禁发。明确版本冲突刷新并关闭旧确认，下次确认使用最新版本；网络失败保留原确认供原样重试。
2. **契约缺口**：买家申报不带凭证资产键，也没有「列出某单凭证」接口；运营抽屉只能列本次上传的键 + 按键查看。
3. **已修复（P05）**：管理 GET 包含停用档案及当前版本/`enabled`，可按原版本恢复；报价和指引继续受原启用约束，冻结条款不增加可变状态。
4. MALL_UNMATCHED 实收在订单详情里的形态按「paymentAttempts 中 `errorCode=MALL_UNMATCHED`」处理（mock 同此）；真实后端若不同，VoidForm 仍可手工补支付号。需在 Task 7 联调确认。
5. 未挂 `gated`：商城控制器由 starter 的 `@Bean` 方法装配，`gated-endpoints.test.ts` 只认类注解，登记会被判陈旧。开关关着时页面显示 404 错误态。
6. **已修复（P06–P07）**：有订阅读权时显示门店经营类型、币种和服务器当前方案，筛选匹配 SKU；无读权提示并以正式报价结果为准。硬件代购填写地址后可下单，首次尝试固定地址，重试沿原幂等键。
7. 真实市场、价格、周期、平台收款主体及档案须部署配置；到账、渠道协议、送件、签约和实物履约仍按目标门店联调验收。

### 2026-11-11 实现与验证（AC14–AC17）

| 范围 | 结果与证据 |
|---|---|
| P01–P03 | 复用原目录与三档/SKU 实现；CatalogOverviewTest 1、SkuPublicationTest 14、CatalogPublicationFinalReviewTest 9、CatalogWritePermissionTest 7，共 31 项通过；系统阶段 IndustryPublicationTest 16 项证据继续复用 |
| P04 | 页面覆盖服务器版本、缺失禁发、冲突刷新；实际 useConfirm/Radix Dialog 验证旧确认关闭、重新确认提交新版本 |
| P05 | PlatformOfflineCollectionMysqlTest 9 项通过，含停用管理读、租户/币种隔离、原版本恢复与可用 terms 约束；HTTP 和页面准确保留 enabled/版本 |
| P06 | SubscriptionMysqlTest 10、SourceManagementMysqlTest 2、SubscriptionViewsTest 1 通过；覆盖服务器生效边界、未来/撤销/独立端排除和过滤查询摘要保留 |
| HTTP | SourceHttpContractTest 1、MallOpenApiResponseTest 1、OfflineHttpContractTest 5，共 7 项通过；9 份真实 MVC 响应树按当前 OpenAPI 3.1 校验通过 |
| P07 | 页面验证硬件必填与网络失败原意图重试；mock 验证联系信息缺失拒绝、相同请求回放、同键换地址拒绝；复用原后端地址与履约合同 |
| OPS | 页面 8、HTTP 6、mock 状态机 22、mock 管理投影 3，共 39 项通过；typecheck、变更文件 ESLint 通过；OpenAPI 已重新生成 |
| 路由与入口 | check-ops-contract、check-ops-orphan --check 通过，既有 8 个接口欠账与 13 个未分类入口未扩大 |

后端本轮共 60 项通过（31 目录 + 21 MySQL 资金/订阅 + 7 MVC + 1 边界），无跳过。原生数据库为 MySQL 9.7.4:3308，各测试仅创建/删除自己的随机库。构建使用本次源文件临时快照，避免共享 target 被并发会话清理。日志位于本机会话 `/tmp/ai-shop-plan-*.log`，临时路径不作为长期交付物。

代码 review 发现的“冲突后弹窗保留旧闭包”已修复并增加真实弹窗交互验证。未提交、未部署；上述专项通过不代表 C/O/A/B 同店全流程或真实渠道验收已完成。


### 2026-11-11 UME166 代购原键重试修订

线上 B01 实测：首次创建订单成功，原幂等键重放却返回 ORDER_CLAIM_INCOMPLETE。事件认领按门店买方与运营发起人摘要隔离，JDBC 回查误把摘要当作 buyer_user_no 查询。修复为从冻结 Quote 传入实际门店买方，并同时匹配原 initiatedBy；ACCOUNT 查询、幂等事件认领、事务锁序与落单字段不变，无数据库迁移。

验证：原生 MySQL9 新增 STORE 原键重放/另一运营隔离，连同原同键并发与事件重复回放专项3项通过；CommerceQuoteTest 原专项通过。线上原订单 e0e0b04fa70c4b549b2e5c297f7c73e3 保留，已在后端cf872964部署后使用原报价/原键成功回查原单，未新建替代订单。当前仅测试报价、待付款单与作废；不伪造银行实收。

边界：原有 mall_order 唯一键仍是 tenant_no/buyer_user_no/idempotency_key。不同运营对同店使用相同键的双创建不属于本次修复，也未宣称支持；本次不改唯一键、历史订单或资金事实。专项合计25项（22项报价回归＋3项原生MySQL并发/回放）通过。

线上结果：原单付款指引回读一致；明确未发生银行转账，经RECONCILIATION测试资料正式作废，订单和支付轴CLOSED，付款尝试FAILED且paidAt=null，原FREE版本未变。API/OPS均UP，部署锁已释放；独立PAY/inventory未重启。完整新店同店购买与真实实收仍未完成。

### v2 商品、SKU 与行业套餐发布（迁移中）

商城配置启用后，商品、SKU、套餐草稿、发布与停售共用商城目录仓储和商城事务管理器；货币策略沿用宿主注入。商品、SKU 及组成保留原业务键、乐观锁和发布前校验。v2 的端权益组成存为 ENTITLEMENT。

套餐每版一行。新草稿从同一套餐继承额度、试用和排序字段；发布更新选中版本的生效区间、销售状态和行版本，替换旧当前版时结束原区间。总览与编辑按同一套字段投影。

验收入口为 MallCatalogPublicationMysqlTest.publishesEditedSkuAndPlansAndReadsTheDictionaryVersionInOverview。在独立测试库中编辑商品、SKU，发布后经 Facade 总览核对套餐与在售 SKU，并直接核对基础能力库 attrs.plan_catalog_version。商城事务保存发布命令、选中版本和结果。基础能力库以旧版本为条件更新 attrs.plan_catalog_version，并记下 attrs.plan_catalog_publication_no。写字典失败后，同一命令重试或读取总览会先补完待同步版本。上述用例覆盖字典更新失败及恢复后的总览。


### v2 门店交易与订阅切库边界

订单在报价时预分配订单号，确认后从 QUOTE 进入 ORDERED；财务历史只查询 ORDERED。线下核款的申请与处置按记录类型区分。退款复核按原请求的冻结证据计算。门店端开通使用商城履约任务登记排期，商城事务提交后更新平台授权段，失败任务由商城恢复作业继续处理。

商城库通过 shop.modules.mall.datasource 指定，基础能力库通过 shop.modules.foundation.datasource 指定。平台授权使用平台模块的数据源。空库建表顺序见 [商城库初始化](../../backend/commerce/commerce-persistence/src/main/resources/db/mall/README.md)。绑定数据源前须完成历史数据迁移与对账。

### v2 商城剩余任务（2026-11-27）

门店购买链路以[商城库设计](design/数据库设计/库/04-平台商城库.md#从商品到门店授权)为准。先把在售主体套餐接入商品、SKU 和门店订单。收款及订阅幂等、跨库恢复分别验收，最后演练历史切换。

| 顺序 | 任务 | 完成条件 |
|---|---|---|
| 1 | 主体套餐归属（已确认） | 商户域维护主体档位、报价、首店前的免费档、跨店额度与首次试用。商城承接订单、收付款和退款；历史交易映射与写入切换列入第 4 项。 |
| 2 | 确定收款和订阅操作的幂等凭据 | 先核实支付生产接入方如何提供事件号，以及主动查单和通知如何关联同一笔收款。分别确定支付事实和订阅请求的持久化位置与唯一键。验收覆盖同键重试、同键不同内容、跨门店复用请求号和事务回滚。是否增加业务表待定。 |
| 3 | 确定独立库之间的事务边界 | 分别明确创建门店与授予免费档、转移归属与更新订阅、业务写入与端权限复核如何保持一致。验收覆盖一侧失败、并发转移或撤权、连接中断，核对授权与提交结果。 |
| 4 | 实现并验证上述写入路径 | 按确认的归属、凭据及事务方案接入实际调用入口，在独立数据库中验证开店、购买、退款和撤权；对失败后重试核对订单、资金和权益。 |
| 5 | 历史迁移对账与切库演练 | 在演练库回填历史数据，按租户比对目录版本、订单与退款金额、订阅周期、额度余额和已开通授权段。验证发布后总览读回新版本、支付后履约、退款后撤销和切换回退。 |

#### 主体套餐归属与交易交接

商户域沿用 sys_merchant_plan_def、mch_plan_offer 和 mch_entity_plan。以主体号持有免费档、付费档与试用资格；续费保留已用试用标记及主体专属额度覆盖。购买时选定的门店用于校验买家归属和确定交易上下文。

**订单迁移顺序**

1. 梳理在售商户报价的价格、币种、期限、权益和版本，将每组销售条件映射到实际发布的商城商品及 SKU 版本。单卖 plan 的 SKU 使用 PLAN 组成项。旧报价号和版本用于迁移对账及旧请求重放。主体档位的跨店额度、首次试用和覆盖值仍由商户域解释。现有 plan 关联表面向端权益。主体档位接入时需先列出其权益代码及商户域的执行动作，再发布对应 SKU。
2. 将门店自购和运营代购接入同一报价、下单入口。由已授权的购买门店确定 `store_no` 和 `entity_no`，订单行引用所购 SKU 及版本。迁移既有购买时保留原购买单号、支付尝试号和收款指引；无法确定历史商品版本的单据列入对账差异。
3. 按支付结果、订单行组成和购买时的权益代码驱动履约。门店端权益交给平台基础库，主体额度交给商户域。一个套餐包含多项交付时逐项记录结果，恢复只推进尚未完成的交付。
4. 端到端验收覆盖两店购买同一 SKU 后分别授权、plan 发布新版后旧单仍按原权益履约，以及“硬件＋plan”的发货和授权分别完成。模拟授权系统中断后重放原任务，核对权益有效期与次数；退款后核对仅撤回原单对应的来源。

**当前实现进度**

商城交易切换时应保留主体、门店、买家、代购人、请求号，以及购买时冻结的报价版本、金额、币种、期限和付款前套餐。实收匹配后由商户域复核所有权和原套餐，再决定生效或转人工复核。迟付、金额不匹配沿用原处置；资金已收和权益已生效分开记录。配置独立商城库后，实收匹配凭据写入 mall_payment_ref，以租户和支付尝试号定位，绑定原购买单、核款动作及其冻结参数。商户域将购买单标记为 PAID_PENDING（已收款，权益待生效）。定时恢复按原单重放，商户权益、核款动作和购买单终态在同一事务提交。商城凭据提交后若商户域尚未记下待生效状态，恢复从原 CONFIRMING 动作读取商城凭据。商户域在主体锁内冻结购买单和报价。签发付款指引前，商城先按原购买单号和支付尝试号在 mall_payment_ref 登记 CREATED，保存冻结的收款方、金额和付款参数。商城写入失败时购买单停在 PREPARED，定时恢复与重复购买共用同一签发逻辑。匹配实收更新该行；关单记录支付域确认的关闭凭据。旧版仅记录实收的商城行仍可恢复。订单主表与订单行、付款申报、退款和历史交易映射仍待切换。

#### 门店收款确认入口核对

仓库内，支付对账作业遇到 PLATFORM_MALL 支付流水时，把支付流水号交给 ShopMallPaymentRecovery。它用流水号找到支付域的租户、订单号和支付尝试号，查到 SUCCESS 后，门店商城分支调用 PaymentFacade.refresh。商城按冻结的支付尝试重新查单，查到成功后以支付操作号生成稳定的内部事件号。支付域收到的原始通知号未作为这条商城路径的幂等键。商城处理抛错时，支付交接保留待处理状态，供后续恢复。

代码依据见 [支付对账分流](../../backend/shop-gateway/src/main/java/ai/neargo/shop/paybridge/OrderPaidReconciler.java)、[支付交接与恢复](../../backend/shop-gateway/src/main/java/ai/neargo/shop/commercebridge/port/ShopMallPaymentRecovery.java)、[支付尝试查询](../../backend/shop-gateway/src/main/java/ai/neargo/shop/commercebridge/ShopMallPlatformPaymentAdapter.java) 和 [商城收款确认](../../backend/commerce/commerce-core/src/main/java/ai/neargo/commerce/core/fulfillment/CommerceFulfillmentService.java)。

FulfillmentFacade.confirmPayment 仍接受带事件号的已核实事实。当前仓库的生产源码中只找到接口声明与实现。确定收款凭据模型前，还需核实仓库外是否有调用方依赖该契约。订阅请求的跨门店查重另行确认。

#### 门店商城历史对账

在同一停写时点取得旧库、商城库、基础能力库和平台基础库的快照。各库可在不同实例。每个连接文件在 MySQL 的 `[client]` 段指定只读用户、连接参数和对应的 `database`，文件保存在仓库外。仓库根目录执行：

```bash
python3 scripts/reconcile-mall-v2.py --tenant MAIN \
  --legacy /path/legacy.cnf --mall /path/mall.cnf \
  --foundation /path/foundation.cnf --platform /path/platform.cnf
```

脚本按租户和业务键逐行比对，输出各项两侧行数、差异数、缺失或多出的业务键，以及值有变化的字段名。退出码 0 表示所列检查一致，1 表示存在差异，2 表示检查未完成或该租户在各项检查中均无记录。`--limit` 控制明细行数，汇总始终统计全部差异。

核对范围包括行业目录版本、已落单的订单及其行和组成金额、原生退款与退款行、线下退款快照、订阅账户和周期、额度账户与批次和流水、平台已开通端的排期及客户端绑定。已生成订单号的报价按 QUOTE 排除。同一原生退款附有线下出款时，原生退款金额和出款快照分别核对。授权段按门店、周期、权益、排期版本和段序比对，旧库 NORMAL 对应新库 SCHEDULED、ACTIVE 或 ENDED。传入 `--merchant /path/merchant.cnf` 可对比切换前旧库与切换后商户域的档位定义、报价版本和主体套餐实例，覆盖首店前无门店的主体、额度覆盖及首次试用标记。同一参数也开启主体购买单与商城付款尝试对账，按订单号比对支付尝试号、收款方、金额、币种及冻结参数，兼容旧版仅记录实收的商城行。切库验收前先恢复 PREPARED 中断单。主体交易历史回填、历史作废授权段、线上支付渠道实收及切换回退另行验收。
