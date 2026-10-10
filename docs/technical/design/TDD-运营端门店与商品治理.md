# TDD-运营端门店与商品治理

状态：**已实现**（2026-08-13。后端 `OpsStoreGovernFlowTest` 5 条全绿；ops-web `npm run check` 45 文件 / 621 条全绿、`gen:api` 通过）
关联需求：[运营端门店与商品治理-需求](../../requirements/运营端门店与商品治理-需求.md)（已确认，Q1-Q4 按建议口径）
创建日期：2026-08-13
覆盖任务：①a 门店档案 · ①b 商品详情与强制下架 · ①c 门店强制下线

---

## 1. 需求摘要

运营端补三块：**门店档案**（按主体列门店、跨主体检索、门店详情/经营状况/商品投影/订单筛选）、
**商品治理动作**（商品详情 + goods 级强制下架，留痕 + 商家可见原因）、
**门店级强制下线**（归违规处置，平台解除制，C 端真的看不到）。
全部只读为主、治理动作最小化——平台边界是「裁、定、兜」，不代商家运营。

## 2. 当前架构分析（现扫结论，三条推翻直觉）

| # | 事实 | 对设计的影响 |
|---|---|---|
| 1 | 门店表叫 **`mch_store`**（不是需求引用的 `usr_merchant_store`，那是前端 mock 时代旧名）。`status` DDL 注释写 `ACTIVE/SUSPENDED/READONLY`，但 **Java 侧只有 `ACTIVE`/`READONLY` 两个常量**，商家自助停用写 `READONLY`；`SUSPENDED` 是「DDL 有、代码无」的空位 | 强制下线**收编 `SUSPENDED`**，零迁移就能与商家自助停用区分 |
| 2 | **门店 `status` 目前对 C 端零影响**：C 端可见性的真闸门是 `prd_goods.on_sale` × `prd_store_goods.on_sale` × `prd_community_pool`（`syncPool()`），门店主页 `StoreServiceImpl.home()` 也不看 status；主体级封禁同样只是「标记 + 前端自觉」 | 强制下线不能只改 status，**必须显式走商品下架链路**（撤店级在售 → 重算主体总闸 → 撤池），且要跨模块开 Port |
| 3 | 运营端数据域「**只存不用**」：`sys_ops_staff` 的 scope 能算出真实 `DataScopeSpec`，但**现存每一条 ops 查询都 `executeWithoutScope` 绕开它**（订单、商家、商品全是）；`mch_store` 也未注册进 `DataScopeRegistration` | 需求 Q3「经营数据受数据域约束」**在本批做不到只对门店生效**——见 §5 取舍 T1 |

可复用（全部已存在，缺的只是 ops 出口）：

| 服务 | 关键签名 | 备注 |
|---|---|---|
| `StoreAdminService` | `list(merchantNo)` → `StoreVO(storeNo,name,address,isDefault,status,payMerchantNo,payReady,staffCount)` | shop-merchant |
| `MerchantStoreService` | `deliveryRule(merchantNo, storeNo)`；门面字段在 `mch_store` 行上 | shop-merchant |
| `MerchantOrderService` | `stats(merchantNo, storeNos)`、`todo(merchantNo, storeNos, pickupNos)`；**`storeNos==null`=全部、空集合=fail-closed，照抄这个口径** | shop-core.trade |
| `MerchantGoodsService` | `listForOps(...)`、`detail(merchantNo,goodsNo)`、`storeSkus()` 的店级库存覆盖读法（`prd_store_stock` 按 `entity_no` 免 join） | shop-core.product |
| `MerchantGovernService.recordViolation` | 副作用是处置的一部分（BREACH 计数、SUSPEND 推主体状态）——门店下线照这个形状加第三个分支 | shop-merchant |
| `AuditLogPort.record(action,target,detail,critical)` | ops Controller 标准用法：service 返回 VO → record → return | shop-base spi |

**模块边界**：shop-merchant 与 shop-core 互为兄弟，只能走 `shop-base/spi` 的 Port 互通。
**通知现状**：ops→商家**没有任何推送链路**（`NotifyBizType` 只有 OTP/运营密码/TEST 四个值；
入驻审核、店招驳回、封禁全都不推）——商家侧唯一的「通知」是拉取式（原因落在数据行上，B 端打开就看到）。

## 3. 方案设计

### 3.1 方案选型（四个关键决策）

**D1 强制下架的实现**

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 新方法 `forceOff(goodsNo, reason)`：撤销过审**（`auditStatus→REJECTED` + 原因、`on_sale→false`、店级行全下、`syncPool()` 撤池） | 语义自洽：商家改后走**既有**重新提审链路（P-3.2.2）；原因落在商品行上，B 端现有页面直接显示（拉取式通知零新建）；C 端消失是审核态+撤池双保险 | REJECTED 与「审核驳回」共用状态，B 端看不出「平台下架」与「首次驳回」的区别——原因文案里注明即可 | ✅ 采用 |
| B. 复用 `audit(goodsNo,false,reason)` | 少一个方法 | `audit` 是给 AUDITING 队列的，对 APPROVED 商品调用违反其状态机；混用后守卫测不出哪条路径漏了留痕 | ❌ |
| C. 复用 `toggle(merchantNo,goodsNo,false)` | 已有 | biz 语义（商家管自己的货）：无原因、无留痕、无触达；且商家可自行再上架，「强制」名存实亡 | ❌ |

**D2 门店强制下线的状态与留痕**

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 收编 `SUSPENDED` + `mch_violation` 加 `store_no` 列** | 零门店表迁移；「这家店被处置过几次」可查（V9 的设计意图就是留事实）；商家自助（READONLY）与平台强制（SUSPENDED）天然区分 | violation 表一次迁移 | ✅ 采用 |
| B. `action=STORE_OFFLINE` + storeNo 塞 detail 文本 | 零迁移 | 事后无法按门店检索，申诉时「这家店第几次」拿不出来——正是 V9 建表要防的 | ❌ |
| C. `mch_store` 加 offline_by/reason/at 三列 | 留痕在门店行上 | 与违规处置两套留痕，同一件事记两处，迟早只剩一处在维护 | ❌ |

**D3 下线后 C 端不可见怎么实现**

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 新 spi Port `StoreShelfPort.setStoreOnSale(storeNo, false)`（impl 在 shop-core）+ `StoreServiceImpl.home()` 补 status 检查** | 复用唯一真实的可见性闸门（店级在售→主体总闸→撤池，`toggle()` L377-387 已有全套逻辑）；门店主页给「已停业」而不是裸 404 | 跨模块加一个 Port | ✅ 采用 |
| B. 只改 `mch_store.status` | 一行代码 | **C 端零影响**（§2 事实 2）——下线了还在卖，比不做更糟：运营以为处置完了 | ❌ |
| C. 把商家状态/门店状态接进 C 端每条查询 | 一劳永逸 | 改动面横穿 C 端全部商品查询，与本需求不成比例；`canSell()` 零消费方的现状说明这条链路要专项做 | ❌ 另立事项 |

**D4 ops→商家通知**

| 方案 | 结论 |
|---|---|
| **拉取式**：原因落数据行（商品=审核原因字段；门店=violation 记录 + B 端门店列表显示 SUSPENDED 与原因） | ✅ 与店招驳回（`mch_store_audit.reason`「原样出现在商家 B 端」）同一形状，零新链路 |
| 新建 `MerchantNotifyPort` + `NotifyBizType` 扩值 + 站内信商家维度改造（`msg_message` 主键是 user_no，没有商家维度） | ❌ 改动面 = 一次消息域改造，另立事项；先拉取式上线，推送是增强不是前提 |

### 3.2 模块设计

**后端新增（三个 Controller + 一个 Port + 服务扩展）**

| 落点 | 内容 |
|---|---|
| `shop-merchant/.../merchant/api/ops/OpsStoreController.java`（新） | 门店档案读 + 下线/恢复。`@Profile("ops")`，注解顺序 `@XxxMapping` 在前 `@PreAuthorize` 在后（矩阵生成器的正则要求） |
| `shop-merchant/.../service/MerchantGovernService`（扩） | `listStores(merchantNo)`（转调 StoreAdminService）· `searchStores(status, businessMode, keyword, page, size)`（新查询，跨主体）· `storeDetail(storeNo)`（门面+配送规则+经营模式+收款状态聚合）· `offlineStore/restoreStore` |
| `shop-core/.../trade/api/ops/OpsStoreStatsController.java`（新） | 门店经营数据。在 shop-core 是因为 `MerchantOrderService` 在 trade 域，shop-merchant 够不着（兄弟模块） |
| `shop-core/.../product/api/ops/OpsGoodsController.java`（扩） | `GET /ops/goods/{goodsNo}` 详情 · `POST /ops/goods/{goodsNo}/force-off` |
| `shop-core/.../product/service/MerchantGoodsService`（扩） | `detailForOps(goodsNo)`（旁路归属校验，**显式新方法**，不给 detail 加 null 语义）· `forceOff(goodsNo, reason)` · `listForOps` 加可选 `storeNo`（店级库存/在售覆盖投影，走 `idx_entity` 免 join）|
| `shop-base/.../spi/product/StoreShelfPort.java`（新） | `void setStoreOnSale(String storeNo, boolean onSale)`——impl 在 shop-core（店级行批量改 + 重算主体总闸 + syncPool），shop-merchant 的下线流程调用 |
| `shop-base/.../spi/merchant/MerchantQueryPort`（扩） | `Optional<String> entityNoOfStore(String storeNo)`——stats 端点要从 storeNo 反查主体 |
| `MerchantOrderService.opsList`（扩签名） | 加 `String storeNo` 入参（`ord_sub_order.store_no` 已有） |

**数据库迁移（V98，落地时并行会话已占 V96/V97，本轮让号）**

```sql
ALTER TABLE mch_violation
    ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '门店级处置时的门店号，空=主体级处置',
    ADD KEY idx_violation_store (store_no, at);
-- 解除时只恢复「平台压下去的」，商家自己下架的不动
ALTER TABLE prd_store_goods
    ADD COLUMN platform_suspended TINYINT NOT NULL DEFAULT 0;
-- 驳回/强制下架原因：商家能看到的那半边（此前原因哪里都不落，商家只能猜要改什么）
ALTER TABLE prd_goods
    ADD COLUMN audit_reason VARCHAR(512) DEFAULT NULL;
```

`MchStore` 补常量 `SUSPENDED`（DDL 已有该值，只是 Java 没收编）；
`MchViolation` 补 action 常量 `STORE_OFFLINE`。

> **落地时新增的两列**（设计时没料到）：`prd_goods.audit_reason` 是写测试时发现的 ——
> 原方案说「原因落在商品行上，B 端现有页面直接显示」，而**那个字段根本不存在**：
> `audit()` 驳回时原因只进审计日志（只有运营看得到）。不补这一列，
> 「商家改后重新提审」这条路商家根本不知道要改什么。

### 3.3 核心接口

| 方法 | 路径 | 权限码 | 实现 |
|---|---|---|---|
| GET | `/ops/merchants/{merchantNo}/stores` | `merchant:merchant:read`（登记表 L57 的 GET /ops/merchants 兜底规则自动覆盖） | `StoreAdminService.list` |
| GET | `/ops/stores` | `merchant:merchant:read`（**新规则，必须插在 `/ops/stores/audits` 那条之后**——规则第一条命中生效） | `searchStores` 新查询 |
| GET | `/ops/stores/{storeNo}` | `merchant:merchant:read` | `storeDetail` 聚合 |
| GET | `/ops/stores/{storeNo}/stats` | `merchant:merchant:read` | `entityNoOfStore` → `stats(merchantNo, List.of(storeNo))`；todo 只取门店维度三项（toShip/toDeliver/toStock）——toVerify/toPick 是自提点维度且不限商家，摆进门店页会被读成「这家店的活」 |
| GET | `/ops/goods?storeNo=` | `product:sku:read`（既有） | `listForOps` 店级投影 |
| GET | `/ops/goods/{goodsNo}` | `product:sku:read` | `detailForOps` |
| POST | `/ops/goods/{goodsNo}/force-off` | `product:sku:audit` | `forceOff`：REJECTED+原因 → on_sale=false → 店级行全下 → syncPool；`auditLogPort.record("GOODS_FORCE_OFF", goodsNo, reason, **critical=true**)` |
| GET | `/ops/orders?storeNo=` | 既有订单码 | `opsList` 扩参 |
| POST | `/ops/merchants/{merchantNo}/violations`（扩） | `merchant:merchant:ban`（既有规则已覆盖） | `ViolationReq` 加可选 `storeNo`；`action=STORE_OFFLINE` 时第三个副作用分支：`mch_store.status→SUSPENDED` + `StoreShelfPort.setStoreOnSale(storeNo,false)` |
| POST | `/ops/stores/{storeNo}/restore` | `merchant:merchant:ban` | 平台解除：`SUSPENDED→ACTIVE` + `setStoreOnSale(storeNo,true)`；审计 critical |

**门店下线的商家侧行为**：
`StoreAdminServiceImpl.setStatus` 加一条守卫——当前是 `SUSPENDED` 时商家启停一律拒绝，
错误文案指向「平台处置中，请联系平台」；不加这条，商家点两下启用就把强制下线自救掉了。
`StoreServiceImpl.home()` 对非 `ACTIVE` 门店返回「已停业」态（不是裸 NOT_FOUND——
扫码进来的老客要知道是店关了，不是链接坏了）。**READONLY（商家自助停用）同样进「已停业」**，
这是补需求 B-11.12.4 一直没兑现的「停用的门店 C 端不可见」。

### 3.4 前端（ops-web）

| 改动 | 文件 | 守卫 |
|---|---|---|
| `/merchants` 加 `stores` tab（跨主体检索 + 按商家筛 + 详情抽屉：档案/经营/商品/订单四段） | `TAB_KEYS` 加 `"stores"` · 新建 `app/merchants/stores-tab.tsx` · `copy.ts` zh/en | nav.test 的 tab 反查、page-copy 无裸中文 |
| nav 叶子 `/merchants?tab=stores`，perm `merchant:merchant:read`（恒等映射已存在，**不动 perm-map**），matrix `P-11.2` | `lib/nav.ts` · `lib/i18n/nav-labels.ts` · nav.test 的 `MATRIX` 白名单加 `"P-11.2"` | group 相邻、perm 前缀=merchant |
| 契约四件套：`listStores/getStore/getStoreStats` + `getGoodsDetail/forceOffGoods` + `listOrders` 加 storeNo + `StoreQ` | `contracts/{store,merchant,product}.ts` · `https/*` · `mocks/*` · `mock/db/store.ts`（新 `stores` 种子，**storeNo 与 merchant.ts 的 ST001/ST002 对齐**）· `query.ts` | contract parity、mock 真落库、ops-reason-required（force-off 的 reason 必须真发出去） |
| 商品池抽屉加 goods 级「强制下架」（原因必填）与详情；SKU 级 5 条**保持 mock 现状不动**（任务 #6） | `app/products/page.tsx` 抽屉段 · `copy.ts` | — |
| 违规封禁 tab 的处置表单加「门店级」选项（选门店 → action=STORE_OFFLINE） | `app/merchants/credit-tab.tsx`（BanTab） | — |
| 后端矩阵基线 | `node scripts/gen-perm-endpoint-matrix.mjs` 重生成 fixtures；`perm-endpoint-map.mjs` 加 `/ops/stores` 读写两条规则 | ops-perm-matrix 四断言 |
| 菜单种子 | `sys_function_point`/`sys_role_point` 迁移（`gen-perm-seed.mjs` 生成），门店档案叶子授 BD | 无种子则菜单灰显 |

### 3.5 配置项

无新增运行时常量。门店额度沿用 `shop.store.max-per-entity`（本需求不碰）。

## 4. 测试策略

**后端（scenario + arch）**

| 场景 | 断言 |
|---|---|
| 门店档案流 | BD 角色列门店/详情/stats 正常；`GOODS_OPS` 调 `/ops/stores` 403（不持有 merchant:merchant:read）；storeNos 空集合 fail-closed 口径不被破坏 |
| 强制下架流（真链路） | APPROVED+在售商品 force-off → C 端社区池查不到（`prd_community_pool` 无行）→ B 端详情显示 REJECTED+原因 → 商家改后重新提审回 AUDITING；**无 reason 400**；审计日志有 critical 记录 |
| 门店下线流（真链路） | violation(STORE_OFFLINE) → `mch_store=SUSPENDED` + 该店 `prd_store_goods` 全下 + 撤池 → C 端门店主页「已停业」→ **商家 setStatus 自救被拒** → 平台 restore 后全部恢复 |
| 违规留痕 | `mch_violation.store_no` 落值；按门店检索处置历史 |
| 权限矩阵 | 重生成基线后 `ops-perm-matrix` 四断言全绿（新端点全部登记、无死规则） |

**前端**：`npm run check`（contract parity / nav 11 条 / perm-map / page-copy / mock 落盘）+ `npm run gen:api` 通过。
**共享守卫**：`ops-endpoint-exists`（后端同批落地，无需登记 KNOWN_GAPS）、`ops-reason-required`、`ops-pagination`。

## 5. 风险与取舍

| # | 取舍 | 说明 |
|---|---|---|
| **T1** | **数据域本批不接**（与需求 Q3 的确认相左，需重新拍板） | 现状是运营端数据域全线「只存不用」，每条 ops 查询都 `executeWithoutScope`。只给门店端点接数据域会造出「订单能看全量、门店只能看片区」的割裂，且 `mch_store` 未注册进 `DataScopeRegistration`（注册是 fail-closed，风险大）。建议：本批沿用现状，「ops 查询统一接数据域」另立专项 |
| T2 | REJECTED 复用审核驳回态 | 平台下架与首次驳回在 B 端显示为同一状态，靠原因文案区分（「平台强制下架：…」前缀）。不为此新增状态值——四态模型（AUDITING/APPROVED/REJECTED × on_sale）已被三端消费，扩枚举穿三端 |
| T3 | 门店下线不校验未完成订单 | 与商家自助停用同口径（已有单照常履约核销）——强制下线拦的是新增售卖，不是正在进行的交付 |
| T4 | `restore` 恢复到 ACTIVE 而不是「下线前的状态」 | 下线前若是 READONLY，恢复成 ACTIVE 等于平台替商家做了启用。但记「下线前状态」要加列，且该场景（对已停用的店再强制下线）本身罕见——处置动作对 SUSPENDED/READONLY 门店直接拒绝，从源头消掉这个分支 |
| T5 | stats 端点在 shop-core、档案端点在 shop-merchant，前端合并展示 | 跨模块聚合要开 trade Port，为省一次前端并行请求不值得 |
| **T6** | `MerchantGovernServiceImpl` 用 `ObjectProvider<StoreShelfPort>` 而不是直接注入（落地时新增） | 直接注入形成构造期的环：`MerchantPortImpl → GovernService → StoreShelfPort → MerchantGoodsService → GoodsService → MerchantPortImpl`，整个上下文起不来。**没有改成「Controller 编排」来绕开** —— 那样撤货架就从「处置的一部分」变成「调用方记得调就调」，漏一次的症状是「处置完了还在卖」，与压根没做一样且不报错 |

## 6. 实现任务

- [x] V98 迁移：`mch_violation.store_no` + 索引、`prd_store_goods.platform_suspended`、`prd_goods.audit_reason`；`MchStore.SUSPENDED` / `MchViolation.STORE_OFFLINE` 常量（**V96/V97 被并行会话占用，本轮让号**）
- [x] `StoreShelfPort`（spi）+ shop-core impl（店级行批量 + 主体总闸重算 + syncPool，复用 toggle 现有逻辑）
- [x] 用 `entityOfStores` 反查主体（既有方法，不必新增 `entityNoOfStore`）
- [x] `MerchantGovernService`：searchStores / storeDetail / violation 门店分支 / restore；`StoreAdminServiceImpl.setStatus` 的 SUSPENDED 守卫（70021）
- [x] `OpsStoreController` + `OpsStoreStatsController` + `OpsGoodsController` 扩展；`opsList` 加 storeNo；`listForOps` 加门店投影
- [x] `StoreServiceImpl.home()` 非 ACTIVE → `closed=true`
- [x] `perm-endpoint-map.mjs` 三条规则 + 角色×端点基线重生成（183 端点，守卫全绿）
- [x] ops-web：`/merchants?tab=stores` 门店档案页 / 商品抽屉 goods 级强制下架 / 违规处置加门店级 / 契约四件套 / nav+copy / mock 种子（ST001–ST004）
- [x] 后端 scenario 测试 `OpsStoreGovernFlowTest` 5 条（与 StoreSettle/StoreStock 同跑 17/17 绿）
- [x] 前端 `npm run check`（621 条）+ `npm run gen:api` + function_point 种子迁移 V99
- [ ] 更新平台端功能清单与需求矩阵状态（约定「状态取自代码」，由生成器/下次盘点带走）

**未做的一处（有意）**：`/orders` 只在契约层支持 `storeNo`，没加筛选框 ——
mock 的 `Order` 种子里没有门店维度，加一个筛不动任何东西的控件比没有更坏。
要接需先给订单 mock 补门店维度，属独立一步。

### 6.1 落地时踩到并已守住的两个坑

1. **测试手机号段与 `StoreSettleFlowTest` 逐个重合**：单独跑 5/5 绿，全量跑时复用了同一个账号，
   于是门店列表拿到的是**别人商家的门店**——而「筛选失效」与「筛选正确」在只数行数的断言下
   长得一模一样。已换号段，并补一条「每行 merchantNo 都等于目标主体」的断言把它守住。
2. **`prd_goods.audit_reason` 原本不存在**：方案说「原因落在商品行上，B 端直接显示」，
   而 `audit()` 驳回时原因只进审计日志（只有运营看得到）。不补这一列，
   「商家改后重新提审」这条路商家根本不知道要改什么。

---
确认记录：2026-08-13 用户确认（含 §5 T1：数据域本批沿用现状，「ops 查询统一接数据域」另立专项）
