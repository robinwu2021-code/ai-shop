# TDD-商城商品分类（商户商城多级分类）

状态：已实现（2026-11-06 用户确认：增加分类表、支持多级；分支 feat/mall-category）
关联：[TDD-运营端套餐商城](./TDD-运营端套餐商城.md) · 接口真源 [commerce-mall-http.md](../api/commerce-mall-http.md) + `commerce-mall-openapi.json`
创建：2026-11-06

档位：1 —— 新增一张表（commerce 迁移 V11）、运营端新增分类接口、B 端商城商品接口增加字段、运营端页面。不新建领域、不跨服务。

## §0 对账一 · 需求 → 设计

| 需求（2026-11-06 用户） | 设计落点 |
|---|---|
| 商户商城要有分类表，不再是商品上的自由文本 | 新表 `mall_category`；保存商品时 `categoryCode` 必须是已启用分类 |
| 分类支持多级 | `parent_code` 自引用；最多 3 级（`MAX_DEPTH`），防环 |
| 运营可维护 | `/ops/biz-mall/categories` 列表 / 新建 / 修改（含启停）；运营端「分类」标签页；商品表单改为下拉选分类 |
| B 端按分类展示 | 商城商品接口返回 `categories`：本店能看到的商品用到的启用分类及其全部上级 |
| 名称多语言 | `names` 为 `{zh-CN, zh-TW, en}` 子集，至少一个；前端按语言取，缺了回落 |

非目标：分类图标上传（只存 URL）、分类下的排序覆盖到商品级（商品仍用自身 `sortOrder`）、删除分类（只停用）。

## §1 现状与影响面

- `mall_product.category_code VARCHAR(32)`：运营手填，服务端只校验格式（`CatalogSupport.code`）。运营端表单输入还会被转大写，而 166 台湾种子用的是小写 `subscriptions/hardware/bundles` —— 两边已不一致。
- 目录写入是「整份快照」模式：`CatalogSupport.write` 在独占锁下读 `CatalogState`，内存修改后 `JdbcCatalogRepository.save` 只按版本号落差异。分类沿用同一模式，天然与商品校验同一事务、同一把锁。
- 运营目录概览 `CatalogOverview` 一次返回全部目录对象；B 端 `StorefrontView` 返回本店可见商品。

影响面：commerce-api（新记录、facade 方法、两个视图加字段）、commerce-core（`CategoryService`、商品校验、概览与店面组装）、commerce-persistence（V11、仓储读写）、starter HTTP（运营端三个端点）、OpenAPI、ops-web（类型、接口、页面）、b-app 契约类型。

## §2 方案

### 契约变更

**表** `mall_category`（迁移 `V11__commerce_mall_category.sql`）

| 列 | 类型 | 说明 |
|---|---|---|
| `category_code` | VARCHAR(32) | 编码，同租户唯一，建后不可改；规则同现有 `code()` |
| `parent_code` | VARCHAR(32) NULL | 上级编码；NULL = 一级 |
| `level` | INT | 1..3，由服务端按上级计算 |
| `names` | JSON | `{"zh-CN","zh-TW","en"}` 子集，至少一个，每个 ≤64 字 |
| `icon` | VARCHAR(512) NULL | 图标地址 |
| `sort_order` | INT | 同级排序，小在前 |
| `status` | VARCHAR(16) | `ENABLED` / `DISABLED` |
| 公共列 | | `id, tenant_no, created_*, updated_*, version, deleted`，同其他 commerce 表 |

唯一键 `(tenant_no, category_code)`，索引 `(tenant_no, parent_code)`。迁移内按 `mall_product` 现有 `DISTINCT category_code` 补一级分类（`names` 三种语言都先填编码本身，`ENABLED`），保证存量商品立即有效；运营之后改名。

**规则**
1. 新建：`expectedVersion=0`；上级必须存在且 `ENABLED`；`level = 上级.level + 1`，超过 3 拒绝（`CATEGORY_TOO_DEEP`）。
2. 修改：可改上级（不得是自己或自己的下级 → `CATEGORY_CYCLE`；移动后整棵子树深度仍 ≤3）、名称、图标、排序、状态。
3. 停用：仍有 `ENABLED` 下级 → `CATEGORY_HAS_CHILDREN`；仍有在售商品（`saleStatus=ON_SALE`）引用 → `CATEGORY_IN_USE`。
4. 商品保存：`categoryCode` 必须是存在且 `ENABLED` 的分类（任意一级），否则 `CATEGORY_UNAVAILABLE`。已发布的商品、SKU 不回溯校验。
5. 权限：读 = 目录读；写 = 目录编辑（`CatalogSupport.authorize(a,"edit")`），与行业、商品同一套。审计事件 `CATEGORY_SAVED`。

**接口**
- `GET /ops/biz-mall/categories` → `CategoryView[]`（扁平，带 `level/parentCode/version`），按 `(level, sortOrder, code)`。
- `POST /ops/biz-mall/categories` body `CategoryDraft{categoryCode, parentCode, names, icon, sortOrder, enabled, expectedVersion, reason}` → `DraftSaveResult`。
- `PUT /ops/biz-mall/categories/{code}` 同上（路径编码为准，body 不带编码）。
- `GET /ops/biz-mall/catalog`（概览）加 `categories`。
- `GET /biz/stores/{storeNo}/mall/products`（`StorefrontView`）加 `categories: StorefrontCategory[]{code, parentCode, level, names, icon, sortOrder}`：本店可见商品用到的启用分类 + 全部上级，按 `(level, sortOrder, code)`。

### 模块设计

- api：`CategoryDraft`、`CategoryView`；`CatalogFacade.saveCategory / categories`；`CatalogOverview.categories`；`StorefrontView.categories`（保留旧构造器，避免改动所有调用方）。
- core：`CategoryService`（上面 1–3、5）；`SkuPublicationService.saveProduct` 加规则 4；`CatalogState.categories` 并进 `copy()`；`CatalogQueryService.overview` 与 `DefaultStorefrontFacade` 组装输出。
- persistence：`JdbcCatalogRepository` 读写 `mall_category`（与商品同样按版本谓词只写差异）。
- starter：运营端控制器三个端点；`commerce-mall-openapi.json` 加路径与 schema。错误码进 `CatalogErrorCode`（宿主信封默认 400）。
- 宿主：`V631__commerce_mall_category_menu.sql` 菜单点 `OPS_COMMERCE__TAB_CATEGORIES`（`/mall?tab=categories`，权限沿用 `commerce:catalog:read`，只授 SUPER_ADMIN；V628 的 83~88 仍为默认值时顺移一位）。
- ops-web：类型、接口、mock 同构规则、「商城商品分类」页签（树形列表 + 新建 / 新建下级 / 编辑抽屉 / 启停）、商品表单分类改下拉（显示「上级 › 下级」路径，去掉转大写）、原因码人话、导航叶子。
- b-app：本分支 b-app 还没有商城店面页（由 b端app 会话在新原型里做），只在接口文档里说明 `categories` 字段，不改 b-app 代码。
- 种子：`scripts/seed/seed-mall-catalog-tw.py` 先建 `subscriptions / hardware / bundles` 三个一级分类（带三语名称）再建商品；存量环境由 V11 补的分类会被改名。

### 计划（按 TDD 顺序）

1. core 单测：深度、成环、停用条件、商品引用校验 —— 先红后绿。
2. V11 + 仓储；MySQL 测试覆盖迁移补数与读写往返。
3. 概览 / 店面输出；HTTP 契约测试与 OpenAPI 响应校验。
4. ops-web 页面与测试；生成物与闸门。
5. 合入发布分支，部署 166 前另行确认。

## §5 对账三 · 实现 → 需求

| 需求 | 实现 | 证据 |
|---|---|---|
| 有分类表 | commerce V11 `mall_category`；存量商品编码补成一级分类 | `CategoryMysqlTest.v11SeedsLevelOneCategoriesFromExistingProducts`；`MallFulfillmentMysqlTest` 旧库升级路径 |
| 多级（≤3）、防环、移动重算层级 | `CategoryService`（深度含整棵子树、`CATEGORY_CYCLE`、子孙层级随写入重算并加版本） | `CategoryTest` 9 条；`CategoryMysqlTest.treeRoundTripsWithLevelsNamesAndVersions` |
| 停用条件 | `CATEGORY_HAS_CHILDREN` / `CATEGORY_IN_USE` | `CategoryTest.disableNeedsNoEnabledChildrenAndNoOnSaleProducts`、`onSaleProductBlocksDisable` |
| 商品必须挂启用分类 | `SkuPublicationService.saveProduct` → `CATEGORY_UNAVAILABLE` | `CategoryTest.productNeedsAnEnabledCategoryOfAnyLevel` |
| 运营可维护 | 三个端点 + 页签 + 商品下拉 | `MallOpenApiResponseTest`（含 PUT）+ OpenAPI 3.1 校验；ops-web mock 测试 3 条；本机 mock 页面实测新建三级、停用被拒提示 |
| B 端按分类展示 | `StorefrontView.categories`（用到的启用分类 + 全部上级） | `StorefrontCategoriesTest`；`MallStorefrontMysqlTest`（含线下 readiness 路径保留） |
| 多语言名称 | `names` ⊂ {zh-CN, zh-TW, en}，≥1，≤64 | `CategoryTest.namesAndCodeAreValidated`；OpenAPI `propertyNames` |

未覆盖：宿主 MySQL 上 V631 只做了 H2 投影（schema-test.sql）与生成物校验，没在真库上重放；部署 166 前在预发布库验证。
