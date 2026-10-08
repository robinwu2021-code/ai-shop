# TDD-经营范围排除地区

状态：已实现
关联需求：`docs/requirements/PRD-位置与经营范围.md`（§边界「只有 EXCLUDE」、A12 已按本文更正）
前置：`TDD-经营范围改门店级.md`（V381，范围已是门店级）
创建：2026-10-08

## §0 对账一 · 需求 → 设计

需求原文（店主，2026-10-08）：「针对经营范围可以排除，比如排除新疆、西藏等地区。」

典型店：全国发快递、一个地区都没框（= 不限地区），只想不卖到几个偏远省。

| AC | 一句话 | 落点 |
|---|---|---|
| AC1 | 不限地区的门店能在 B 端选择器里直接排除省/市/区 | `biz-region-picker` 的 `canExclude` 放开「不限」态 |
| AC2 | 被排除地区的买家看不到这家店的货 | 已有：`ReachRule` 排除最先生效、`unlimited` 后照样减 —— 只补用例 |
| AC3 | 收货地址在被排除**省**的快递/自送单，下单即拒 | `OrderServiceImpl#requireNotRegionRestricted` 加门店排除省 |
| AC4 | 买家详情页「销售区域」说实话：「不限地区（新疆、西藏除外）」 | `SaleScope` 加 `excludedNames` + c-app 文案 |

**孤立项**：无。

## §1 现状

「不卖到哪」此前散在三处，没有一处完整：

| 机制 | 粒度 | 管什么 | 缺口 |
|---|---|---|---|
| 经营范围 EXCLUDE（`mch_service_area.mode`） | 门店 | 可见性 | 只给「已被上级覆盖」的行排除 —— 不限态点不出来；下单只看买家所在小区、不看收货地址 |
| 运费模板「不配送」 | 门店快递模板 | 快递拒单 | 不影响可见性：结算时才被拒 |
| 商品 `restricted_regions`（V376） | 单件商品 | 下单按收货省拒 | 要逐件设 |

后两者保留：运费模板管运费、商品限购管单品。本 TDD 只把门店级排除补完整。

审核：所有粒度范围自 2026-08-24 起保存即 ACTIVE，排除项无需另改。

## §2 方案

### 契约变更
- 对外 JSON：`SaleScope`（`/mp/goods/{no}` 的 `saleScope`）加 `excludedNames: string[]`，仅 `unlimited=true` 时可能非空。
- i18n：c-app 新词条 `goods.scopeUnlimitedExcept`。
- 端点 / 库表 / 权限码 / 配置：无。
- SPI：`MerchantQueryPort` 加 `excludedProvinces(merchantNo, storeNo)`。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 改 | `b-app/.../biz-region-picker.vue` | 新 prop `bareExclude`：为真时省/市/区/街道/小区行都给「排除」 |
| 改 | `b-app/.../store-scope/index.vue` | 传 `bareExclude = 没有纳入项 && (自送 || 快递)` |
| 加 | `MerchantPortImpl#excludedProvinces` | 该店 `level=PROVINCE, mode=EXCLUDE` 的两位码 |
| 改 | `OrderServiceImpl#requireNotRegionRestricted` | 收货省 ∈ 门店排除省 → `OUT_OF_DELIVERY_RANGE`（与商品限购同一道闸） |
| 改 | `MerchantPortImpl#saleScope` | 不限时带出排除地名 |
| 改 | `GoodsVO.SaleScopeVO` / `MerchantQueryPort.SaleScope` / `GoodsServiceImpl` / `packages/shared` 类型 / c-app 详情 | 透传与显示 |

### §3 决定
- **下单只拦到省**：收货地址只存省市**名字**、无区划码；与商品限购同口径用 `Provinces.provinceCodeOf(address)`。
  市/区级排除只管可见性（买家所在位置），下单不拦 —— 按名字猜市区会误杀（多个「朝阳区」）。
- **买家页排除地名 = 所有「不限」门店都排除了、且没有任何限定门店框进去的地区级排除项**：
  主体口径是各店并集，一家店不送新疆、另一家送，就不能对买家说「新疆除外」。
  小区/楼栋级排除不上买家页（「除 3 幢」对外地买家是噪音）。
- 框了范围的店（非不限）不显示排除：已列出的地名本身就是范围。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC2 | `GoodsSaleScopeFlowTest#unlimitedMinusExcludedProvinceIsInvisibleThere` | ✅ | —（判定早已存在，本条补用例） |
| AC3 | `FreightOrderFlowTest#storeExcludedProvinceBlocksCreate` | ✅ | 去掉门店排除省那句判断 → 红「门店排除了新疆，应拒」✅ |
| AC4 | `GoodsSaleScopeFlowTest#unlimitedListsExcludedRegions` | ✅ | — |
| AC4 并集 | `GoodsSaleScopeFlowTest#excludedNotClaimedWhenAnotherStoreCoversIt` | ✅ | 去掉「限定门店框进去就不说除外」的过滤 → 红 ✅ |
| AC1 | vue-tsc 两端 0；b-app-mock H5 注入「不限 + 快递」实点：省行同时有「排除」与勾选框，点排除写入 `{PROVINCE, 31, EXCLUDE}`、行变「取消排除」且勾选框收起；卡片显示「整个 · 已排除」+「不限范围」 | ✅ | — |

既有相关类（ServiceArea*、StoreScopedVisibility、StoreFulfillment、Freight、GoodsSaleScope）88 条全绿。

## §6 对账二 · 设计 → 实现

与 §2 相比多动了：

| 文件 | 为什么 |
|---|---|
| `MerchantPortImpl#saleScope` 判序 | **V381 遗留缺陷**：先列框选、后判不限。门店级之后「默认店全国发、分店框了乌鲁木齐」时买家页只写「乌鲁木齐」，可见性却是全国。改成先判任一门店不限。由 `excludedNotClaimedWhenAnotherStoreCoversIt` 撞出 |
| `store-scope/index.vue#areaPending` | 页面仍按 08-24 前的规则把未保存的省/市/区判「待审」，排除项也被算进「整区、整市需运营审核」。对齐选择器：只读 `status === PENDING` |
| `GoodsSaleScopeFlowTest#cleanup` | 原先不删本组造的门店，补上 |

未做：b-app mock 的范围预览不模拟「不限」，注入态下显示「覆盖 0 个聚落」；真后端以全部开放聚落为候选再减排除，不受影响。
