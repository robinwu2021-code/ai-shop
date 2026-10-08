# TDD 下单按门店拆单与运费模板

状态：**已确认**（2026-10-09 用户拍板，实现中）
档位：**2**（跨三端 + 库表 + 端点 + 不可逆：子单粒度）
关联决策：[ADR-031 子单按门店拆与运费按商品门店计算](ADR/ADR-031-子单按门店拆与运费按商品门店计算.md) ·
[ADR-030 商品归属改为门店 Offer](ADR/ADR-030-商品归属改为门店Offer与品牌库.md)
关联设计：[TDD-商品归属重构](design/TDD-商品归属重构-品牌库与门店Offer.md)（本篇**先做它的 P2/P3 中「商品归门店」那一半**，品牌库 P1 与 `brand_spu_no`/`overrides` 留在原计划）·
[TDD-快递100商家寄件](TDD-快递100商家寄件.md) §8（AC16 被本篇扩展）
创建：2026-10-09

---

## 需求（用户原话，2026-10-09）

> 下单的商品，要根据商品查找门店，再找到对应的模板，同时商品也可以设置特定的模板。
> 这里没有缓存，都是实时计算。商品和主体、和商户都没有关系，这和商品本身，以及商品所属的门店相关。
> 商品是特定的，商品一定是关联在特定的门店。

拍板（同日）：多模板淘宝式合并 · 模板挂商品 · 只能从平台模板选 · 子单按门店拆 ·
存量多店商品**每店复制一件** · 以后多店同款**每店各建一件** · 顺序：跳过品牌库，先商品归门店再运费。

### AC

| AC | Given / When / Then |
|---|---|
| AC1 | 每件商品有且只有一家所属门店；B 端在哪家店下建的商品就属于哪家 |
| AC2 | 存量：只在一家店在架的商品归那家；没有门店行的归主体默认店；多店在架的归**主店**（默认店在架取默认店，否则最早建的在架店），其余店各**复制一件** |
| AC3 | 买家看到、加购、下单的门店 = 商品所属门店；端上不再传「逛的是哪家店」 |
| AC4 | 一单里不同门店的商品各成一张子单，同一主体的两家店也各一张 |
| AC5 | 运费模板逐行解析：商品指定 ＞ 门店快递通道 ＞ 平台默认；实时读、不缓存 |
| AC6 | 同一门店多模板按淘宝式合并（ADR-031 §2.5）；只有一个模板时与现行公式逐字相同 |
| AC7 | 商家在 B 端商品编辑页可以给商品指定运费模板（只能从平台模板选），也可以改回「跟随门店」 |
| AC8 | 商品指定的模板被运营归档 → 下单回落门店模板（不回落成 0 元） |
| AC9 | 主体级额度 / 单笔上限按主体**汇总**判；活动赠积分一个主体只发一次 |
| AC10 | 活动满减、商家券按门店组判门槛与分摊；各子单优惠之和 = 整单优惠 |
| AC11 | 盐（粮油）+ 柿子（鲜果）同一单：预览成功、两张子单、各自运费正确 |

---

## §0 对账一 · 需求 → 设计

| AC | 落点 | 期 |
|---|---|---|
| AC1 | `prd_goods.store_no`；`MerchantGoodsServiceImpl.newGoods` 取 `BizContext.currentStoreNo()`；编辑不改归属 | A |
| AC2 | 迁移回填（确定的部分）+ 复制走正常建品接口（进销存自动建货品，见 §1.3） | A |
| AC3 | 可见性 / 详情 / 加购 / 快照全部读 `goods.store_no`；删 `storesOfEntities` 与 `storeChoices` | B |
| AC4 | `split()` 分组键 `merchantNo → storeNo`；`Group` 带 `storeNo` | C |
| AC5 | `FreightResolver`：逐行 `goods.freight_template_no` → `expressTemplateNo(store)` → 默认 | D |
| AC6 | `FreightPort.quoteMerged(...)`，单模板走原公式 | D |
| AC7 | `prd_goods.freight_template_no` + `GET /biz/freight-templates` + 商品编辑页选择器 | D |
| AC8 | 解析时模板不存在 / 已归档 → 下一级 | D |
| AC9 | `requireOrderAllowed` 按主体汇总；`bonusPoints` 按主体去重 | C |
| AC10 | `CampaignPort` / `CouponPort` 分摊单位改门店组；`Discount.of(storeNo)` | C |
| AC11 | 端到端用例 + 线上真机 | C/D |

**孤立项**：无。

---

## §1 现状与影响面

### 1.1 下单查配置的真实链路（2026-10-09，代码为准）

| 步 | 做什么 | 依据 | 位置 |
|---|---|---|---|
| ① | 取行（结算页从购物车取） | 购物车行**不记门店** | `selectedCartItems` |
| ② | 按**主体**分组 | `prd_goods.entity_no` | `split()` |
| ③ | 每个主体落**一家**店 | 自提点 → 端上 `storeChoices[主体]` → 默认/服务社区/其余营业店中第一家在架有货 | `storesOfEntities` |
| ④ | 找模板 | `mch_fulfillment_channel(主体, 店, EXPRESS).config.templateNo`，没配 → 默认 | `expressTemplateNo` |
| ⑤ | 算运费 | 地区拒 → 满额免 → 首续重 + 地区加收；整组重量与商品额合计 | `FreightPortImpl.quote` |
| ⑥ | 写子单 | 一个主体一张 | `create()` |

### 1.2 会被改到（调研结论）

- **商品归属**：`prd_store_goods/price/stock` 读写 20 个主文件（`GoodsVisibility`、`GoodsServiceImpl`、`GoodsQueryPortImpl`、`StockPortImpl`、`StoreStockReader`、`StockSyncServiceImpl`、`MerchantGoodsServiceImpl` 投影整套、invbridge 四个类、`StoreShelfPortImpl`）。
- **子单按门店**：`OrderServiceImpl` 约 15 处按 `merchantNo` 键的映射。**现状下同主体两店会撞 `uk_sub_order_no` 整单失败**（`subOrderNoOf` 按主体键）。营销端口 `CampaignPort.pick/Discount.of`、`CouponPort.Allocation.discountOf` 按主体汇总，两组会各拿一份整额 → 优惠翻倍。
- **契约**：`OrderVO` 子单无 `storeNo/storeName`；`CheckoutCapabilityVO.MerchantCapability`、`MerchantOffers` 按主体；C 端 `segmentByMerchant`。

### 1.3 进销存（线上 DUAL 双写）

进销存是**独立库**，键为 `sku_no → inv_item_ref → item`、`store_no → inv_location`。
迁移里克隆 `prd_sku` 会让克隆件在进销存里投影出一个**空货品**（卖 0 或分叉）。
→ **复制不走迁移，走正常建品接口**：新商品、新 SKU 由正常链路投影进进销存，库存用正常的设库存接口写入。
线上需要复制的只有 4 件（同一测试主体「虹选」），全部可在发布后用自动化票据完成。

### 1.4 明确不受影响

结算 / 分账（已是每子单一张账单、带 `store_no`）· 售后（按子单）· B 端订单列表（已按 `store_no IN`）· 日报（已按 `(entity, store)`）· 快递寄件（已取子单门店）。

---

## §2 方案

### 2.1 契约变更

**库表**（号以实现时 `ls` 为准）

| 迁移 | 动作 | 期 |
|---|---|---|
| Vα | `prd_goods` 加 `store_no varchar(32) NULL` + `idx_goods_store(store_no, on_sale)`；回填确定部分（AC2 前三种） | A |
| Vβ | `prd_goods.store_no` 置 NOT NULL（4 件复制完成、线上回读为 0 空行之后） | B 收尾 |
| Vγ | `prd_goods` 加 `freight_template_no varchar(32) NULL` | D |

**端点**

| 端点 | 变化 | 期 |
|---|---|---|
| `GET /mp/cart` | `CartItem` 加 `storeNo/storeName` | B |
| `POST /mp/order/preview` `capability` `create` | `storeChoices` 不再读（仍收，忽略）；`activityChoices`/`addressChoices` 增加 `storeNo` 键（只有 `merchantNo` 的旧条目应用到该主体全部门店）；子单、能力、优惠按门店出，带 `storeNo/storeName` | C |
| `GET /biz/freight-templates` | **新**：平台在用模板列表（非归档），登录即可读 | D |
| `POST /biz/goods/save` / `GET /biz/goods/{no}` | 加 `freightTemplateNo`（不传 = 不改，空串 = 跟随门店） | D |

**i18n**：B 端「运费模板 / 跟随门店」两条；C 端无新词条（段头改显示门店名）。
**配置**：无。**权限码**：无（新端点归 `biz:goods` 读档）。

### 2.2 模块设计

| 期 | 动作 | 路径 |
|---|---|---|
| A | 新增迁移 Vα | `shop-app/.../db/migration/` + `schema-test.sql` |
| A | 修改 | `PrdGoods`（+`storeNo`）· `MerchantGoodsServiceImpl.newGoods/save`（建品取当前店）· `GoodsVO`（带 `storeNo`） |
| B | 修改 | `GoodsVisibility` · `GoodsServiceImpl` · `GoodsQueryPortImpl`（快照带 `storeNo`，删 `storePrices`/`firstStoreThatCanFulfil`）· `StockPortImpl`/`StoreStockReader`（只走 `prd_sku`）· `CartServiceImpl`/`CartItemVO` · `MerchantGoodsServiceImpl` 删投影整套 · invbridge 按商品门店过滤 · `StoreShelfPortImpl` |
| B | 删 | `storesOfEntities`；c-app `store-choice.ts` |
| C | 修改 | `OrderServiceImpl`（分组键、映射重键、主体汇总）· `CampaignPort`/`CouponPort` 及实现（分摊单位）· `OrderVO`/`CheckoutCapabilityVO`（带店）· `MpTradeController` 请求 DTO · c-app `cart.ts`、`order-confirm`、`cart` 页按店分段 |
| D | 新增 | `FreightPort.quoteMerged` + `FreightPortImpl`（淘宝式）· `packages/shared/src/utils/freight.ts` 同口径 + 测试 · `BizExpressController` 模板列表 · b-app 商品编辑页选择器 + 契约四处 |

### 2.3 关键接口

```java
// 期 D：一个门店组的运费。lines 已带各自解析好的模板号
record FreightLine(String templateNo, int weighedGram, int unweighedUnits, long goodsAmountMinor) {}
Optional<Quote> quoteMerged(List<FreightLine> lines, String receiverAddress);
// 规则：按模板分组 → 地区拒任一即拒 → 各组满额免 → 非免组里首费最高者计首重+其余续重，
//       其他非免组全部重量按各自续重 → 加收取参与组最高一笔。单模板 ≡ quote()
```

### 2.4 分期与发布

| 期 | 内容 | 可独立发布 |
|---|---|---|
| A | 加列 + 回填 + 建品写归属（只写不读） | ✅ 行为不变 |
| — | 线上复制 4 件（自动化票据，走正常建品） | 运营动作 |
| B | 读写切到商品门店；删投影；删 `storeChoices`；NOT NULL | ✅ |
| C | 子单按门店；营销分摊按门店；C 端按店分段 | ✅ |
| D | 商品级模板 + 淘宝式合并 + B 端选择器 | ✅ |

---

## §3 选型

见 ADR-031 §3。补一条：**复制存量多店商品走接口而不走迁移** —— 进销存是独立库且按 `sku_no` 建货品，迁移克隆的 SKU 在进销存里没有货品。

## §4 风险

| 风险 | 缓解 |
|---|---|
| 回填归错店 | 回填后对账：每件商品 `store_no` 非空且该店有它的在架行（或无行归默认店）；4 件多店的打印出来人工看 |
| 期 B 删投影留残 | 守卫：主代码 `PrdStoreGoods/PrdStorePrice/PrdStoreStock` 引用归零（期 B 之后；表本身 P4 再删） |
| 期 C 优惠翻倍 | 用例断言「各子单优惠之和 = 整单优惠」，同主体两店 |
| 并行会话改 `OrderServiceImpl` | 每期提交前 `git diff HEAD` 只认自己的行 |

---

## §5 对账三 · 实现 → 需求（实现时填）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `GoodsStoreOwnershipTest#newGoodsBelongsToCurrentStore` `#editKeepsOwner` | ✅ 2/2（期 A） | 撤 `newGoods` 里 `setStoreNo` → 2/2 红 |
| AC2 | 迁移 SQL 在线上库只读预演（2026-10-09）：17 件在用商品全部归到其在架那家店，4 件多店的归主店（粮油 ×3、鲜果 ×1） | ✅ | — |
| AC3 | `StoreScopedVisibilityFlowTest`（改造） | | |
| AC4 | `StoreSplitOrderFlowTest#同主体两店两张子单` | | |
| AC5/AC8 | `FreightResolveTest#商品模板优先` `#归档回落门店` | | |
| AC6 | `FreightMergeTest`（后端）+ `freight.test.ts`（端上同组用例） | | |
| AC7 | `FreightTemplateGoodsFlowTest#指定与清空` | | |
| AC9 | `StoreSplitOrderFlowTest#额度按主体汇总` `#赠积分只发一次` | | |
| AC10 | `StoreSplitOrderFlowTest#子单优惠之和等于整单` | | |
| AC11 | 线上真机：盐 + 柿子同单 | | |

## §6 对账二 · 设计 → 实现（每期贴 `git show --stat`）

**期 A**：`V384__goods_store_no.sql`（新）· `PrdGoods`（+`storeNo`）· `MerchantGoodsServiceImpl.newGoods`（建品取当前店）·
`schema-test.sql`（+列）· `GoodsStoreOwnershipTest`（新）。与 §2.2 期 A 一致；`GoodsVO` 带 `storeNo` 挪到期 B（期 A 只写不读）。

## §7 偏差说明

- **期 A 回填有 4 行留空**：已删除的 4 件面粉（`deleted=1`），所属主体已没有门店。期 B 置 NOT NULL 前要先处理（填其主体曾用的店或物理清理），不能直接加约束。
