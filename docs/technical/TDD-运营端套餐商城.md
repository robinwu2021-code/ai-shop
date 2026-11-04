# TDD-运营端套餐商城（commerce Task 5）

状态：已实现（mock 验证；真实后端联调属于 Task 7）
关联需求：[商城套餐实施计划 Task 5](../superpowers/plans/2026-11-04-commerce-mall-purchase-implementation.md) ·
[台湾线下购买补充设计](../superpowers/specs/2026-11-04-twd-offline-commerce-amendment.md) ·
接口真源 [commerce-mall-http.md](../api/commerce-mall-http.md) + `commerce-mall-openapi.json`（task8-v1）
创建：2026-11-04 · 最后更新：2026-11-04

档位：1 —— 不改后端接口；动了运营端菜单与权限映射、页面文案、菜单种子迁移（V628）。

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

## §1 现状与影响面

- 相关现有模块：`ops-web` 分层（contracts / https / mocks / mock/db）、`lib/nav.ts`、`lib/perm-map.ts`、`lib/point-codes.ts`。
- 可直接复用：DataTable / Drawer / ConfigCard / useConfirm / ReadOnlyNotice / StatusBadge / TabHeader。
- 会被改到的：`http-client.post`（新增 Blob 分支，JSON 路径不变）；全局 mutation 错误 toast（只替换已登记的商城原因码）。
- 明确不受影响的：旧「增值包授予 / 档位定义」（`/merchants?tab=plans|plan-defs`）原样保留；后端代码零改动。

## §2 方案

### 契约变更
- 端点：无新增，消费 task8-v1 的 41 个 `/ops` 操作。
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

1. **契约缺口**：发布要 `expectedCatalogVersion`，运营目录接口不回读行业目录版本。首次发布固定 0；再次发布由运营填（本会话发布过会预填），填错会被 VERSION_CONFLICT 拒绝而不会误发布。需后端在 overview 的 BusinessTypeView 补 `catalogVersion`。
2. **契约缺口**：买家申报不带凭证资产键，也没有「列出某单凭证」接口；运营抽屉只能列本次上传的键 + 按键查看。
3. **契约缺口**：收款档案 GET 不回读 `enabled`。
4. MALL_UNMATCHED 实收在订单详情里的形态按「paymentAttempts 中 `errorCode=MALL_UNMATCHED`」处理（mock 同此）；真实后端若不同，VoidForm 仍可手工补支付号。需在 Task 7 联调确认。
5. 未挂 `gated`：商城控制器由 starter 的 `@Bean` 方法装配，`gated-endpoints.test.ts` 只认类注解，登记会被判陈旧。开关关着时页面显示 404 错误态。
6. 代客下单暂不支持含硬件（需要收货地址表单），页面明确提示。
