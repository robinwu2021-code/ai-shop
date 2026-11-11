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
