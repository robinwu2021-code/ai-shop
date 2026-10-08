# TDD 商品归属重构：品牌库与门店 Offer

状态：草稿
关联需求：[PRD-品牌商品库与门店商品](../../requirements/PRD-品牌商品库与门店商品.md)
关联决策：[ADR-030](../adr/ADR-030-商品归属改为门店Offer与品牌库.md)（取代 ADR-011 的「商品挂主体」实现层）
创建：2026-10-08
档位：**2**（新聚合 + 新表族 + 跨端 + 不可逆决策）

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 | 期 |
|---|---|---|---|
| AC1 | 品牌在主体之上，一品牌多主体（加盟跨法人） | 新表 `mch_brand` + `mch_brand_entity`（多对多归属） | P1 |
| AC2 | 库条目含类目/标题/图/规格组(带 optionCode)，无价无存 | 新表 `prd_brand_spu`（仿 `prd_spu_std` 结构 + `brand_no`） | P1 |
| AC3 | 只有总部主体可写，加盟只读 | 权限码 `BizPerms.BRAND_SPU`；写端点校验「调用方 `entityNo == brand.hqEntityNo`」 | P1 |
| AC4 | 无品牌的独立单店不受影响 | `brandOf(entityNo)` 返回空 → 选品入口不下发（端上按能力位隐藏） | P1 |
| AC4b | 库维护界面在 B 端 app，运营端不提供 | `b-app` 新页 `brand-spu/*`；`/biz/brand-spu` 读写都在 /biz；运营端只留强制下架 | P1 |
| AC5 | 门店从库选品一键生成本店商品 | `POST /biz/goods/from-brand-spu`（批量）→ `GoodsOfferService.createFromBrandSpu` | P2 |
| AC6 | 门店可直接自建，不经库 | 现有建品链路不变，`brand_spu_no = NULL` | P2 |
| AC7 | 同库条目同门店只能一条在用 | 唯一索引 `uk(store_no, brand_spu_no)`（`deleted=0` 条件下）+ 服务端前置校验 | P2 |
| AC8 | 列表能分辨来源 | `GoodsVO.source = BRAND_SPU \| SELF`（由 `brand_spu_no` 是否为空派生，不新存字段） | P2 |
| AC9 | 门店下架/删除不影响库与其他门店 | Offer 是门店级行，删它天然只影响本店；库条目不反向联动 | P2 |
| AC10 | 总部改库 → 引用门店自动跟变 | **读时解引用**：`prd_goods` 不拷贝库字段，展示字段在查询时从 `prd_brand_spu` 取 | P2 |
| AC11 | 总部改库永不动门店价/存/上下架 | 价存上架只存在于 Offer/`prd_sku`；库表**物理上没有**这些列 | P1 |
| AC12 | 库条目归档 → 已引用照常在售、不能再被新店选中 | 归档只改 `prd_brand_spu.status`；选品列表过滤 `status=ACTIVE`；解引用不看 status | P2 |
| AC13 | 门店可局部改写，改写字段不再跟随库 | `prd_goods.overrides`(JSON 字段名集合)；解引用时逐字段「有改写取本店、否则取库」 | P2 |
| AC14 | 价/存/上下架都在门店这一层 | `prd_goods.on_sale` + `prd_sku(goods_no).price/stock` | P2 |
| AC15 | 一店卖掉不影响另一店 | Offer 各自有 `prd_sku` 行，扣减天然隔离 | P3 |
| AC16 | 平台强制下架仍有效，作用在门店商品层 | `prd_goods.platform_suspended`（从 `prd_store_goods` 搬过来的列） | P3 |
| AC17 | 买家按社区看在架商品，判据仍是 ReachRule | `GoodsVisibility` 简化为「Offer.store_no ∈ servingStores(communityNo)」 | P3 |
| AC18 | 同品牌多店送达同社区时不重复展示 | 保留现有 `providingStores` 口径（默认店优先、否则店号最小），按 `brand_spu_no \| goods_no` 去重 | P3 |
| AC19 | 买家不感知库 | C 端 VO 不出 `brandSpuNo`/`source` | P3 |
| AC20 | 同名同址聚落不再新增重复 | `CommunityServiceImpl` 建聚落处前置「名+位置」查重复用 | **P0** |
| AC21 | 买家选社区不出现同名同址两条 | `nearby()` 按「名+位置」折叠（治本后仍留折叠作兜底） | **P0** |
| AC22 | 嘉逸花园这一例修好 | 合并两条嘉逸花园，经营范围引用改指保留的那条 | **P0** |
| AC23 | 存量重复有可重复执行的合并处置，不丢引用 | 合并脚本/运营端动作：先改指 `mch_service_area.ref_code`，再归档被并聚落 | **P0** |

**孤立项**：无 AC 没落点；无落点挂不上 AC。

> **P0 独立于本次重构**（AC20–AC23 只碰社区域），可单独先发，不等商品模型改造。

---

## §1 现状与影响面

### 1.1 真实链路（以代码与迁移为准）

```
平台标准品库 prd_spu_std(311) ──复制+溯源(std_no, 只收敛 categoryNo 与 optionCode)──▶
主体商品 prd_goods(entity_no, 无 store_no)(21) + prd_sku(entity_no, 价/存总量)(33)
   ├─(查询时现算)▶ prd_store_goods(store_no,goods_no,on_sale)(62) × servingStores(ReachRule)
   ├─▶ prd_store_price(store_no,sku_no)(1)   无行→回退主体价
   └─▶ prd_store_stock(store_no,sku_no)(2)   无行→视为 0
```

### 1.2 会被改到

| 面 | 量 | 说明 |
|---|---|---|
| `StoreGoods` 引用 | 12 文件 | 读写全部改为 Offer 自身字段 |
| product 域 `entity_no` 引用 | 21 文件 | 多数保留（结算键），查询键改 `store_no` |
| 商品相关端点 | 20 个 | `/mp` `/biz` `/ops` 三端 |
| `goods_no` 出现 | **259 文件** | **一律不改名**（ADR-030 §3.3） |

### 1.3 明确不受影响

- 结算/分账/积分：仍按 `entity_no`（Offer 上冗余保留）。
- `ReachRule` / `StoreReachLoader` / 经营范围：**零改动**，只是调用方从「主体商品×货架」变成「Offer.store_no」。
- 平台标准品库 `prd_spu_std` 与 `std_no` 复制式取用：保留，另一层。
- 订单历史：自带快照且已双写 `store_no`。

---

## §2 方案

### 2.1 契约变更

**库表**（迁移号以实现时 `ls` 为准，当前本地最大 V383、线上已应用 382，故从 **V384** 起）

| 迁移 | 动作 |
|---|---|
| V384 | 建 `mch_brand`(brand_no, name, **hq_entity_no**, status, …) + `mch_brand_entity`(brand_no, entity_no)；`hq_entity_no` 定总部主体（ADR-030 §3.5） |
| V385 | 建 `prd_brand_spu`(brand_spu_no, brand_no, category_no, title, title_i18n, subtitle, cover, images, spec_groups, keywords, barcode, status) |
| V386 | `prd_goods` 加 `store_no`(先可空)、`brand_spu_no`、`overrides`、`platform_suspended` |
| V387 | **回填**：按 `prd_store_goods` 把每条主体商品拆到门店；原货号留给默认店；门店价/存并入对应 `prd_sku`；回填后 `store_no` 置 NOT NULL |
| V388 | 删 `prd_store_goods` / `prd_store_price` / `prd_store_stock`（**在 P3 读写切完、观察期过后**） |

> **迁移冻结**：V384 起为新增，不碰任何已应用迁移（改一个字符 checksum 就对不上，线上起不来）。
> V387 的回填是**先补数据再改约束**，顺序反了多门店主体会丢行。

**端点**

| 端点 | 用途 | 权限 | 登记 |
|---|---|---|---|
| `GET /biz/brand-spu` | 浏览本品牌库（总部维护列表 + 门店选品列表共用） | `biz:goods`（同品牌皆可读） | /biz 七处 + `RESPONSE_TYPES` |
| `POST /biz/brand-spu` | 总部新建库条目 | `biz:brand:spu` + `entityNo==hqEntityNo` | 同上 |
| `PUT /biz/brand-spu/{no}` | 总部编辑/归档库条目 | 同上 | 同上 |
| `POST /biz/goods/from-brand-spu` | 批量选品生成门店商品（AC5） | `biz:goods` | 同上 |
| `GET/POST /ops/brand` | 运营维护品牌与主体归属（含 `hq_entity_no`） | `OpsPerms` 新码 | /ops 五处 |

**权限码**：B 端新增 `BizPerms.BRAND_SPU = "biz:brand:spu"`，默认授予 `OWNER` / `MANAGER`
（`ROLE_PERMS` + `LABELS` 两处都要加，否则角色页显示空白）。
运营端新增品牌归属码。新 ErrorCode 四处登记。

**i18n**：选品入口、来源标识（品牌库/本店自建）、归档提示、改写脱钩提示 —— 三语。
动态键要带至少两段前缀，否则整片不受闸门管。

**配置**：无新增。

**带枚举的表八处**：`prd_brand_spu.status`、`mch_brand.status` 各走一遍登记。

### 2.2 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-merchant/.../brand/entity/MchBrand.java` `MchBrandEntity.java` | 品牌聚合 |
| 新增 | `shop-merchant/.../brand/service/BrandService.java` + impl | `brandOf(entityNo)`、`entitiesOf(brandNo)` |
| 新增 | `shop-base/.../spi/merchant/BrandQueryPort.java` | 商品域要问「这个主体属于哪个品牌」，走 spi 不跨域直连 |
| 新增 | `shop-core/.../product/entity/PrdBrandSpu.java` | 库条目 |
| 新增 | `shop-core/.../product/service/BrandSpuService.java` + impl | 库 CRUD + 归档 + 引用数统计 |
| 新增 | `shop-core/.../product/api/biz/BizBrandSpuController.java` | 库读写**都在 B 端**（AC4b）。写操作校验 `entityNo == brand.hqEntityNo` |
| 修改 | `shop-base-auth/.../auth/BizPerms.java` | +`BRAND_SPU`；`ROLE_PERMS` 与 `LABELS` 两处同步 |
| 新增 | `shop-core/.../product/service/impl/GoodsOfferService.java` | `createFromBrandSpu`（AC5/AC7）、`resolveRefs`（AC10/AC13 解引用） |
| 修改 | `shop-core/.../product/entity/PrdGoods.java` | +`storeNo` +`brandSpuNo` +`overrides` +`platformSuspended`；类注释改写归属语义 |
| 修改 | `shop-core/.../product/service/impl/GoodsVisibility.java` | 三方现算 → 「Offer.store_no ∈ servingStores」；删 `sellingAt` 的货架分支 |
| 修改 | `shop-core/.../product/service/impl/GoodsServiceImpl.java` | 列表/详情主查改带 `store_no`；去掉 `withStoreScope` 的价存换算 |
| 修改 | `shop-core/.../product/service/impl/MerchantGoodsServiceImpl.java` | 删 `applyStoreScopedSale`/`excludeOffSaleHere`/`loadStoreProjection`/`seedStoreRowsIfMissing`/`writeStoreOnSale`（投影整套作废） |
| 修改 | `shop-core/.../product/port/GoodsQueryPortImpl.java` | 下单取价/存改读 Offer 的 `prd_sku`，删 `storePrices()` |
| 修改 | `shop-merchant/.../port/StoreShelfPort` 实现 | 平台强制下架改写 Offer 的 `platform_suspended` |
| 删除 | `PrdStoreGoods` `PrdStorePrice` `PrdStoreStock` 实体与 mapper | P4，V388 之后 |
| 修改 | `b-app` 商品列表/建品页 | 选品入口（AC5）+ 来源标识（AC8）+ 按门店分组 |
| **新增** | `b-app/src/pages/brand-spu/`（列表 + 编辑）+ `pages.json` 登记 | 总部维护库（AC4b）。**改完必须重跑 `gen-ui-catalog.py` 并提交 JSON**，否则 pre-push 挡所有人 |
| 修改 | `b-app` 「我的」菜单 | 仅 `hq_entity_no` 主体且持 `biz:brand:spu` 时出现入口 |
| 新增 | `ops-web` 品牌归属页（品牌↔主体、指定总部主体） | **不含**库维护（AC4b）。加菜单要改 `ops-web/lib/nav.ts` 且菜单在库里（要跑生成器落迁移） |
| 修改 | 契约四处（`contract/http/endpoints/requests`）两端 | 新端点 |
| **P0 独立** | `shop-core/.../community/service/impl/CommunityServiceImpl.java` | 建聚落查重复用（AC20）+ `nearby` 折叠同名同址（AC21） |
| **P0 独立** | 合并处置（运营端动作或一次性脚本） | 改指 `mch_service_area.ref_code` 后归档被并聚落（AC22/AC23） |

### 2.3 关键接口

```java
// 解引用：展示字段逐字段「有改写取本店、否则取库」（AC10/AC13）
// 不拷贝 —— 这是引用式与现有 std_no 复制式的唯一本质差别
record OfferView(String goodsNo, String storeNo, String title, String cover,
                 String specGroups, Source source) {}
enum Source { BRAND_SPU, SELF }

List<OfferView> resolveRefs(List<PrdGoods> offers);   // 批量，一次取库，禁 N+1

// 选品（AC5/AC7）：同店同库条目已存在则跳过并回报，不报错整批失败
record PickResult(List<String> createdGoodsNos, List<String> skippedBrandSpuNos) {}
PickResult createFromBrandSpu(String storeNo, List<String> brandSpuNos);
```

```java
// 可见性简化后（AC17/AC18）
// 旧：主体商品 × servingStores × prd_store_goods 三方现算
// 新：Offer 自己带 store_no
Set<String> visibleGoodsNos(String communityNo) {
    Set<String> stores = merchantQueryPort.servingStores(communityNo); // ReachRule，零改动
    return offerMapper.goodsNosOf(stores, /* onSale */ true, /* notSuspended */ true);
}
```

**性能**：`servingStores` 已有；Offer 查询需索引 `idx(store_no, on_sale)` 与 `idx(brand_spu_no)`。
解引用一律批量取库（接口预算 <1s，一般 ~200ms）。

### 2.4 分期（每期可独立发布）

| 期 | 内容 | 可独立发布 |
|---|---|---|
| **P0** | 重复聚落：建聚落查重 + `nearby` 折叠 + 嘉逸花园合并（AC20–23） | ✅ 与商品模型无关 |
| P1 | 建品牌聚合 + 品牌库表 + 总部维护端（V384/V385） | ✅ 纯新增，零行为变化 |
| P2 | `prd_goods` 加列 + 回填 + 选品/自建两路 + 解引用（V386/V387） | ✅ 双读期，读仍可走旧投影 |
| P3 | 读写切到门店 Offer（可见性/下单/B 端/运营端） | ❌ 这一步才是真的改造 |
| P4 | 删三张被吸收的表（V388） | ✅ 观察期后 |

---

## §3 选型（档位 2）

| 决策 | 选 | 弃 | 理由 |
|---|---|---|---|
| 门店 Offer 怎么建 | **`prd_goods` 加 `store_no`** | 新建 `prd_store_listing`（V2 纸面方案） | `goods_no` 在 259 个文件、订单也引用；新表等于全域改名，收益为零 |
| 库→门店 | **引用式（读时解引用）** | 复制式（像现有 `std_no`） | 复制式改一次要刷 N 份、且不回流 —— 正是连锁总部要解决的痛点 |
| 库挂哪一级 | **品牌/连锁级**（跨法人） | 主体级 / 平台级 | 加盟店是不同法人；挂主体则同品牌各一份 |
| 门店价/库存 | **就是 Offer 的 `prd_sku`** | 保留覆盖表 | 覆盖表线上 1 条/2 条等于没人用；且两张表缺省方向相反（价回退、存归零）是已记录的陷阱 |
| `entity_no` | **留在 Offer 上做结算键** | 摘掉 | 通道按主体发号（ADR-011 硬约束），摘掉要动四个域 |
| 局部改写 | **`overrides` 存字段名集合** | 每个字段加 `xxx_overridden` 布尔 | 字段会长，布尔会爆；集合可演进 |
| 库维护端 | **B 端 app** | 运营端代维 / 新建品牌端 | 总部自己就是平台上的经营主体，已有账号与录入能力可复用；运营代维等于把商家的日常工作搬给运营 |
| 总部身份怎么表达 | **`mch_brand.hq_entity_no` + 权限码** | 给 `mch_account` 加 `brand_no` | 账号绑单一主体是现有模型的基石，加一级身份会穿透鉴权全链；总部本就是其中一个主体 |
| 重复聚落 | **治本（建处查重）+ 兜底（读处折叠）** | 只折叠显示 | 只折叠的话库里继续长重复，经营范围还会指错 |

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| V387 回填把多门店主体的货拆错 | 商家商品错乱 | 回填前后对账：每店 Offer 数 == 该店原货架在架行数；单店主体 Offer 数 == 原商品数。先补数据再改 NOT NULL |
| 原货号归属选错导致历史断链 | 订单追溯 | 原货号固定留给**默认店**；订单本就带快照与 `store_no` |
| 解引用引入 N+1 | 列表变慢（预算 <1s） | `resolveRefs` 强制批量；加 `idx(brand_spu_no)`；用例断言查询次数 |
| 投影代码删不干净，留两套判定 | 「看得见、下单说没货」 | P3 一次切完并删实体与 mapper；棘轮守卫断言 `StoreGoods` 零引用 |
| 并行会话抢迁移号 | 推不上去 | 实现时现 `ls` 取号；本文件写的 V384+ 是计划值 |
| 合并聚落丢经营范围引用 | 商家范围静默变空 | 先改指再归档；合并后断言「指向被并聚落的行数 == 0」 |
| 库归档联动下架（违反 AC12） | 商家莫名断售 | 解引用**不看** `status`；用例覆盖「归档后仍可售」 |

---

## §5 对账三 · 实现 → 需求（测试，实现时填）

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC1/AC3 | `BrandScopeTest#一品牌多主体` `#加盟主体不得写库` `#总部主体可写` | | 去掉 `entityNo==hqEntityNo` 校验 → 红 |
| AC4b | `BrandSpuTest#库写端点在biz不在ops`；`BizPerms` 断言 OWNER/MANAGER 含 `biz:brand:spu` | | 从 `ROLE_PERMS` 摘掉该码 → 红 |
| AC2/AC11 | `BrandSpuTest#库条目无价无存列` | | 给库表加 price 列 → 红 |
| AC4 | `BrandSpuTest#无品牌主体选品入口为空` | | |
| AC5/AC7 | `GoodsOfferTest#选品生成门店商品` `#同店同条目不重复` | | 去掉唯一索引与前置校验 → 红 |
| AC6 | `GoodsOfferTest#自建商品brandSpuNo为空` | | |
| AC8 | `GoodsOfferTest#来源由brandSpuNo派生` | | |
| AC9 | `GoodsOfferTest#删本店Offer不影响库与他店` | | |
| AC10 | `GoodsOfferTest#改库条目门店展示跟变` | | 改成建品时拷贝 → 红（这条守的就是「引用式」） |
| AC12 | `GoodsOfferTest#库归档后已引用仍可售且不再可选` | | 解引用加 status 过滤 → 红 |
| AC13 | `GoodsOfferTest#改写字段不再跟随库` | | |
| AC14/AC15 | `StoreIndependenceTest#两店价存互不影响` `#一店扣减不影响另一店` | | 价存回退到主体 → 红 |
| AC16 | `PlatformSuspendTest#强制下架作用在Offer层` | | |
| AC17/AC18 | `StoreScopedVisibilityFlowTest`（改造现有）`#按社区可见` `#多店不重复` | | 去掉 servingStores 交集 → 红 |
| AC19 | `MpGoodsVOTest#C端不出brandSpuNo` | | |
| AC20 | `CommunityDedupTest#同名同址复用不新增` | | 去掉查重 → 红 |
| AC21 | `CommunityDedupTest#nearby折叠同名同址` | | |
| AC22/AC23 | `CommunityMergeTest#合并后无行指向被并聚落` + 线上验「选嘉逸花园看得到商品」 | | 跳过改指直接归档 → 红 |
| 回填 | `V387BackfillTest#每店Offer数等于原在架货架行数` `#单店主体无感` | | |

**消融每条必做**：撤实现 → 对应测试变红。没变红说明根本没测到。

**闸门**：13 道 pre-push（UI 清单要重跑 `gen-ui-catalog.py`、契约、i18n、vue-tsc、后端编译）、
`ArchitectureTest`（新 Port 只能在 spi、Controller 不碰 Mapper）、`BackendI18nParityTest`、
`known-*.txt` 不得变长。说「检查过了」时要同时说清扫了哪些目录。

---

## §6 对账二 · 设计 → 实现

（实现完贴 `git diff --stat`，与 §2.2 逐行比）

---

## §7 偏差说明

（实现中与本设计不一致处写这里，**先改文档再改代码**）

---

## §8 待补

- [x] 库维护端 —— **定在 B 端 app**（2026-10-08），见 ADR-030 §3.5、AC4b
- [ ] 更新 `数据库-ER图.md` 与 `docs/technical/diagrams/db-prd.svg` / `db-mch.svg`（P1 落表时一并重出）

模型关系图：[商品归属-品牌库与门店Offer.svg](../diagrams/商品归属-品牌库与门店Offer.svg)
