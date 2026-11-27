# TDD-商品领域迁到门店交易库

> 状态：草稿（2026-11-27）· 档位：1（动了库表与配置；依据 ADR-066 模块按配置选数据源、ADR-068 门店交易库句柄）
> 需求：用户 2026-11-27「业务不再访问旧库」「开始迁移商品领域，先整理方案，如何确保不会再访问 ai_shop_ume 的表」。
> 现状与功能清单：[商品领域迁移情况](design/商品领域-迁移情况.md)；全局：[旧库调用清单](design/旧库调用清单.md)。

## §0 对账一 · 需求 → 设计

需求文档里没有这条，用户口述，按「附：临时补 AC」写：

| AC | 内容 | 落点 |
|---|---|---|
| AC1 | 商品领域的代码在运行时不访问 `ai_shop_ume`（含主库、复用主库地址的连接池）的任何表 | §2.2 四层保障 |
| AC2 | 商品领域的表在门店交易库 `n_trade`；评价、专题、收藏在会员营销库 `n_member` | §2.3 表改造 |
| AC3 | 22 项功能（F01–F22）迁移后行为不变 | §2.3、§5 |
| AC4 | 正式库里的商品数据完整搬到新库，可对账 | §2.5 |
| AC5 | 别的领域只经端口用商品，不直接查商品的表或 Mapper | §2.4 |

## §1 现状与影响面

- 商品领域 36 个 Mapper 全部在旧库：25 个带交易库句柄（`TradeTableMapper`）但句柄没绑新库、落回主库；11 个没有句柄（评价 3、专题 2、收藏 1、库存同步与恢复 4、权益绑定指令 1）。
- 商品包的 SQL 只联查自己的表（另有 9 处读 `information_schema` 做表结构自检）。别的领域的 SQL 里没有直接查 `prd_*` 的，除了 `shop-gateway` 库存对接里 3 个类用退役的库存同步表。
- 别的领域直接注入商品 Mapper 或实体的：`shop-gateway` 库存对接（`invbridge`）16 个类，以及开发用的 `DevSeeder`。
- **事务**：下单（`OrderPersister`、`OrderStockAllocator`、订单恢复）与退款在交易句柄的事务（`TRADE_TX_MANAGER`）里调商品的 `StockPort` 写库存锁（`prd_stock_lock`）、门店库存。商品与订单现在共用这一个事务。
- 目标表：商品要用的 `n_trade` 新表都已建好；`n_member` 里评价三张已建，`mbr_favorite` 已建；**专题 `prd_topic`、`prd_topic_goods` 在 `n_member` 的实库和设计 DDL 里都没有**。
- 数据量：商品 4 行、SKU 5 行、规格值 9 行、门店商品 9 行，其余多为 0。

## §2 方案

### 2.1 契约变更

| 项 | 变更 |
|---|---|
| 库表 | 商品领域 36 张旧表按拆库清单改造：保留 8、改名 5、并入 13、改归会员营销库 5、退役 5（逐张见[迁移情况 §2](design/商品领域-迁移情况.md#2-表的去向36-张旧表)） |
| 配置 | 新增模块句柄 `product`；评价、专题、收藏用已有的会员营销句柄 `memberMarketing`。部署时 `shop.modules.product.datasource=trade`、`shop.modules.trade.datasource=trade`（同名，见 §2.3）、`shop.modules.member-marketing.datasource=member`；`shop.datasources.trade`、`.member` 指向 `n_trade`、`n_member`；新增 `shop.legacy-databases`（旧库名单，有默认值） |
| 端点 | 不变（114 个，路径与出入参不动） |
| 权限码、i18n | 不变 |

### 2.2 如何确保不再访问 ai_shop_ume（四层）

任何一层单独都可能漏，四层叠起来，漏任何一处都会在测试、推送或启动时被拦下。

**L1 配置：旧库连不上**

- 旧库配置已从运行配置删除（v2 `03a071f73`）。商品句柄必须绑新库，不绑就启动失败（主库已不存在，不再有「没绑就落回主库」）。
- 新增启动校验 `LegacyDatabaseGuard`：所有 `shop.datasources.*.url` 的库名不得是旧库名单里的（`ai_shop`、`ai_shop_ume`、`ai_shop_ume_inv`、`ai_shop_ume_rehearsal`，名单在配置 `shop.legacy-databases` 里），命中就拒绝启动。防的是部署时把新库连接误填成旧库地址。

**L2 数据库权限：就算代码想访问，库也不让**

- 给新库建专用账号：`shop_trade` 只授 `n_trade.*`，`shop_member` 只授 `n_member.*`，**两个账号对 `ai_shop_ume` 没有任何权限**。
- 这一层最硬：代码里即便写了 `ai_shop_ume.prd_goods` 这种带库名的 SQL，或连接被误配到旧库，MySQL 都会直接拒绝（1142/1044），不会悄悄读到旧数据。
- 账号由 root 在 .61 上建（应用账号没有建用户的权限）；口令进环境文件，不进仓库。

**L3 代码守卫：写错了推不上去**

新增架构测试 `ProductDatabaseIsolationTest`，挂进推送闸门的后端全量：

1. `ai.neargo.shop.product` 下每个 Mapper 必须继承 `ProductTableMapper`（评价、专题、收藏继承 `MemberMarketingTableMapper`）。不允许直接继承 `BaseMapper`、不允许无句柄的 `@Mapper`。
2. 每个 Mapper 用到的表（实体上的 `@TableName` 与注解 SQL 里的 FROM、JOIN、INTO、UPDATE），必须在目标库的设计 DDL 里有：商品对 `docs/technical/design/schema/ddl/05-n_trade.sql`，评价等对 `07-n_member.sql`。`information_schema` 另列白名单。
3. 商品包里不允许带库名的表引用（`\w+\.(prd|rvw|mbr)_`），不允许出现 `ai_shop`。
4. 商品包里不允许注入未点名的 `DataSource`、`JdbcTemplate`、`JdbcClient`，也不允许点名主库（`dataSource`、`jdbcTemplate`）。
5. 商品包以外的生产代码不允许 import 商品的 Mapper 和实体，只能经 `ai.neargo.shop.spi.product.*` 端口。现有的 16 个库存对接类和 `DevSeeder` 在 §2.4 里改掉。

**L4 验收：清单归零、联调实测**

- [旧库调用清单](design/旧库调用清单.md)里 `shop.product` 的「旧库 Mapper」为 0，作为迁移完成的判据（清单由脚本生成，进生成物闸门）。
- 联调时在 .61 用 `performance_schema.events_statements_summary_by_digest` 按账号、按库统计，应用账号对 `ai_shop_ume` 的访问为 0。

### 2.3 模块设计

**句柄**：新增 `product` 模块句柄（目标 `n_trade`）与标记接口 `ProductTableMapper`；评价、专题、收藏的表在会员营销库，复用已有的 `memberMarketing` 句柄（储值、权益已在用，目标 `n_member`），新增标记接口 `MemberMarketingTableMapper`。`ModuleMapperScanConfig` 按标记把商品包的 Mapper 收到这两个句柄上，全局扫描排除这两个标记。

为什么不直接用已有的交易句柄：交易句柄上还有订单等 118 个 Mapper，它们的表还没对齐新库。商品要能先改造、先在测试里跑通，就得有自己的绑定。

**事务边界（关键决定）**：下单、退款与锁库存必须仍在同一个事务里。做法是把 `product` 与 `trade` 两个句柄**绑到同一个数据源名 `trade`**：绑到同一个名字的模块共用连接池与事务管理器（ADR-066），所以锁库存与写订单仍是一个事务。由此得出：

- 商品可以先改造、先在测试里验证；
- **上线必须与交易同批**：在交易对齐新库之前，交易句柄绑不了 `n_trade`，商品单独上线会让下单锁库存跨库、失去原子性。（v2 已删旧库配置，交易迁完之前本来也不会上线。）
- 测试里两个句柄仍落在同一个 H2 库（shop）：`backend/scripts/gen-product-test-schema.py` 从 `05-n_trade.sql` 的全部 `prd_*` 与 `07-n_member.sql` 的评价、收藏表生成 `schema-product-test.sql`，h2db profile 在 `schema-test.sql` **之前**执行，同名的旧建表语句因此跳过（不 DROP：内存库跨测试上下文存活，DROP 会删掉别的上下文造的演示数据）。新设计去掉但代码还在用的列作「过渡列」临时补上并在文件头列出；只在旧库有的商品表暂不动，代码不再用时再去掉。别的领域的 SQL 不查 `prd_*`（§1），所以新旧两套表在一个测试库里不冲突；漂移测试只比 `schema-test.sql` 与迁移，不受影响。
- `StockPortImpl` 等商品服务上的 `TRADE_TX_MANAGER` 改为 `PRODUCT_TX_MANAGER`；绑到同一数据源时它们是同一个 bean。

**跨库的 4 个类**：评价（`n_member`）读商品（`n_trade`）的地方，改为经端口按商品号读，不在一个事务里写两个库。

**表改造**（实体、Mapper、服务的改动；按依赖顺序）：

| 批次 | 内容 | 涉及功能 |
|---|---|---|
| 1 保留与改名 | prd_category、prd_goods、prd_goods_revision、prd_sku、prd_spec_dim、prd_spec_value、prd_stock_lock、prd_store_catalog 对齐新字段；prd_spu_std → prd_standard_goods、prd_sell_rule → prd_sellable_rule、prd_category_spec → prd_spec_usage、prd_category_spec_value → prd_spec_value_usage、prd_store_price → prd_store_sku 改 `@TableName` 与字段 | F01–F16、F22 |
| 2 并入 | 门店库存并入 prd_store_sku；门店商品 ⇒ prd_catalog_item；草稿 ⇒ prd_goods_revision；商家规格三张 ⇒ prd_spec_usage、prd_spec_value_usage；SKU 定价策略、权益绑定 ⇒ prd_sku 的列；类目付款方式 ⇒ prd_category 的列；规格模板 ⇒ prd_spec_dim；目录版本 ⇒ prd_store_catalog；托管类目 ⇒ prd_store_category | F01–F08、F11–F12、F20–F21 |
| 3 改归会员营销库 | rvw_review、rvw_review_like、rvw_appeal、mbr_favorite 挂 `member` 句柄；专题待补设计（见 §4） | F17–F19 |
| 4 退役 | prd_execution_retention、prd_stock_restore、prd_stock_sync_log、prd_store_stock_sync、prd_ent_binding_command 及其 Mapper、服务删除；对应功能交给库存服务（见 §4） | F20、F21 |

### 2.4 别的领域直连商品的改造

- `shop-gateway` 库存对接的 16 个类：读商品、SKU 改走 `GoodsQueryPort` 等已有端口；写库存锁改走 `StockPort`；用退役表的 3 个类（`ExecutionRetentionRouteMapper`、`StockAlignmentService`、`StockReconcileService`）随库存同步一起下线。
- `DevSeeder`：改用商品服务的写接口造演示数据，不直接用 Mapper。

### 2.5 数据搬迁

- 一次性脚本 `scripts/migrate-product-to-n-trade.py`：从 `ai_shop_ume` 读、按 §2.3 的并入与改名规则转换、写 `n_trade` / `n_member`。可重复执行（按业务号覆盖），先在演练库 `ai_shop_ume_rehearsal` 上跑。
- 市场：旧库 SKU、门店价的 `market` 为 `CN` 的改为 `TW`（正式库 5 个 SKU 里 4 个是 `CN`，来源是代码原先写死的「本市场」；新库默认值与代码的本市场都按部署配置为 `TW`，见 [列默认值补回方案](design/门店交易库-列默认值补回方案.md) §3）。
- 对账：逐表比行数，商品、SKU、门店价与库存逐行比关键字段；对账报告存进上线记录。
- 搬迁在切换窗口里与交易一起做，搬完旧表只读、不删。

### 2.6 实施顺序

1. 句柄与守卫：加 `product`、`member` 句柄和标记接口，加 `ProductDatabaseIsolationTest`（先列出现状违规，作为改造清单）与 `LegacyDatabaseGuard`。
2. 测试库：生成测试用的商品新表建表脚本，`product` 与 `trade` 绑同一个 H2。
3. 按 §2.3 的四个批次改实体、Mapper、服务，每批跑商品相关测试。
4. 改别的领域的直连（§2.4）。
5. 守卫全绿、旧库调用清单里商品归零。
6. 建新库专用账号（L2，需 root），写搬迁脚本并在演练库验证。
7. 与交易同批上线：搬数据、对账、切换。

## §4 风险

| 风险 | 处理 |
|---|---|
| 商品先上线会让下单锁库存跨库 | 上线与交易同批（§2.3）；守卫测试保证 `product` 与 `trade` 在配置里绑同一个数据源名 |
| 专题两张表在 n_member 没有设计 | 待定：补进会员营销库设计，或随运营位改到别处（见下方待确认） |
| 收藏的归属不一致：拆库清单写并入平台基础库 `mbr_favorite`，实际建在 n_member | 以实库为准挂 `member` 句柄，同时改正拆库清单 |
| 库存同步类功能（F20）随表退役 | 需确认这些功能由库存服务接手、商品侧直接下线 |
| 设计 DDL 还会变（拆库会话在统一规范名） | 守卫按 DDL 文件校验，DDL 改了守卫会立刻指出要跟着改的 Mapper |

**待确认**

1. ~~切换批次~~ 已定（用户 2026-11-27「按推荐来」）：商品改造先行、上线与交易同批。
2. 专题（`prd_topic`、`prd_topic_goods`）放哪个库、表结构怎么定。
3. 库存同步、对齐、原始库存恢复这几项功能直接下线、交给库存服务，可否。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 |
|---|---|
| AC1 | `ProductDatabaseIsolationTest`（L3 五条）、`LegacyDatabaseGuardTest`（旧库名拒绝启动） |
| AC2 | `ProductDatabaseIsolationTest#tablesExistInTargetDdl` |
| AC3 | 现有商品相关测试在新表上全部通过：`CategoryTest`、`CategoryTreeFlowTest`、`SpecLibrary*`、`BizGoodsParseTextTest`、`BizGoodsZipPlanTest`、`GoodsCatalogQueryPortTest`、`CatalogPriceSourcePortTest`、`BizDashboardAndReviewFlowTest` 等（实施第 1 步列全清单） |
| AC4 | 搬迁脚本的对账报告（演练库） |
| AC5 | `ProductDatabaseIsolationTest#onlyPortsOutsideProduct` |

## §6 对账二 · 设计 → 实现

### 第 1 步（句柄与守卫）已做

- `ModuleDataSources`：新增 `PRODUCT` 句柄与三个 bean 名、旧库名单 `LEGACY_DATABASES`。
- `ProductTableMapper`、`MemberMarketingTableMapper`：新标记接口；`ModuleMapperScanConfig` 按标记收取，`MybatisPlusConfig` 全局扫描排除。
- 商品包 25 个 Mapper 由 `TradeTableMapper` 改为 `ProductTableMapper`，84 处 `TRADE_*` 句柄引用改为 `PRODUCT_*`（未绑新库时两者都指向主库那组 bean，行为不变）。
- `ModuleDataSourceRegistrar.rejectLegacy`：具名数据源指向旧库即拒绝启动（L1），`LegacyDatabaseGuardTest` 覆盖。
- `ProductDatabaseIsolationTest`（L3 五条）与基线 `product-db-isolation-baseline.txt`：现状 89 条违规（R1 11、R2 28、R4 2、R5 48），即第 3、4 步的工作清单，只许减少。
- `ModuleTxManagerConventionTest`：商品包改为点名 `PRODUCT_TX_MANAGER`。

### 第 2 步（测试库）

- `backend/scripts/gen-product-test-schema.py` → `schema-product-test.sql`（25 张表：n_trade 的 21 张 `prd_*` + 评价 3 张 + 收藏），挂进 `check-generated-docs`；`application-h2db.yml` 的 `schema-locations` 把它排在 `schema-test.sql` 之前。过渡列：`prd_sku.stock`、`prd_sku.locked_stock`、`prd_store_catalog.current_version_no`。

### 偏差说明

- 测试库没有按原方案「去掉旧 `prd_*`、两个句柄绑同一个 H2」，而是同一个 H2 库里追加一份按新设计生成的建表脚本：改动更小，漂移测试不受影响，且能逐批迁移。

- 原方案新建 `member` 句柄；实现时发现已有 `memberMarketing` 句柄目标就是会员营销库，改为复用，只加标记接口。

## §7 确认与完成

- [x] 切换批次已定
- [ ] 专题去处、库存同步类功能下线两项待确认
- [ ] 方案确认
