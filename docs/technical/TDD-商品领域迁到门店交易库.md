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

### 2.0 候选商品链路验收

按既有平台与交易库 DDL 收窄门店归属查询，对齐门店经营类目及排序字段，将 SKU 的门店库存读写对齐交易库。商品保存、发布、门店上架、改价和库存回读须使用同一商品号。验证账号禁止访问旧库，并以候选接口结果和 n_trade 的行记录对账。

对齐候选库的唯一索引前，将软删行计入两个物理键的查重范围，另查空键和通用经营货架的门店/平台类目重复。无冲突时在每张表的一条在线 ALTER 中替换普通索引，再查索引列序与唯一标志。保存已有商品和门店上架在读取商品状态、分配修订号或新增上架行前，先在商品事务内用 FOR UPDATE 锁住所属主体的商品主行；编辑和上架共享同一锁。待两张表均确认后，以无旧库权限的应用连接并发保存、上架，按商品号、SKU、门店与市场核对行数及价格库存。

兼容旧版清除门店价后软删的 SKU 行。重新定价或初始化门店库存时，在唯一键命中旧记录的情况下仅恢复同主体、同门店、同 SKU、同市场、无库存和无锁量的软删行。沿用原行编号，清除旧价格或应用本次价格，不恢复已取消的售价。

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
- 规格范围的搬法：类目绑定写 `CATEGORY` 维度级行（`value_no=''`），类目取值子集写同范围取值级行；商家覆盖写 `MCH_CATEGORY`（`scope_ref` = 商家号/类目号，`value_no` 空串照搬，`enabled=0` → `DISABLED`）；商家常用维度与取值写 `MERCHANT`（`favorite=1`）。
- 规格模板的搬法：平台模板按名字对到规格库的平台维度、选项按 code 对到取值，写 `CATEGORY_TYPE` 推荐（对不上的维度或取值列进搬迁报告，人工定）；`category_no` 非空的类目级模板不搬；商家自存模板转为他的自建维度 + `MERCHANT` 常用。
- 草稿的搬法：每行草稿在提交历史里落一行（`EDITING` → `EDITING`、`SUBMITTED` → `SUBMITTED`，`base_version` → `base_goods_version`），编号取该商品最大编号 +1；若同一商品已有未发布（`DRAFT`）且内容相同的那一行，直接改它的状态而不另开。
- 门店目录的搬法：旧库每个目录版本一行写进 `prd_store_catalog`（`revision_index` → `version_no`、`frozen_payload` → `frozen_content` 原文照搬、`stage = PUBLISHED`，目录号沿用旧编号）；门店商品写 `prd_catalog_item`（`catalog_version_no = ''`、`on_sale` → `enabled`）；类目库存开关写 `prd_store_category` 主体级行（`category_no` 按同一摘要算）。
- 合表的搬法：`prd_store_price` 与 `prd_store_stock` 按 门店 + SKU 合成一行（库存行没有市场列，取本市场）；类目付款方式只搬 `allowed=0` 的行进 `disallowed_pay_modes`；SKU 定价策略、卡类权益绑定按 SKU 写进本市场那一行（权益绑定的 `status` 非 ACTIVE 或已删的，SKU 置 `DISABLED`）。
- 历史库存单独用 [migrate-product-stock.sql](../../backend/scripts/migrate-product-stock.sql) 搬迁。停止两端库存写入，从同一个旧库快照导出 `prd_sku`、`prd_store_stock` 到演练机上的 `product_stock_snapshot`，先装好目标商品、SKU、价格、原始订单锁。用独立迁移账号经 mysql 批处理客户端连接目标库并执行脚本（遇错退出），应用账号无需读快照。主体总量写本市场 `store_no=''` 的唯一行，各店总量按实际门店号写入，保留价格与已锁量。跨市场库存不一致、目标库存已变化、SKU 归属不一致或回读对不上都会中止。保存脚本输出并按锁单号逐笔核对迁入的在途订单锁后再放开写入。脚本可在冻结窗口内重跑。本市场默认 TW，其他环境执行前在同一连接设 `@home_market`。
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
| ~~收藏的归属不一致：拆库清单写并入平台基础库 `mbr_favorite`，实际建在 n_member~~ | 已定（用户 2026-11-28）：收藏放会员营销库，按实库改正拆库清单 |
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

2026-11-28 SKU 库存验证：`DishOfferQueryPortTest` 5 项通过，覆盖店价、主体库存与门店库存的锁定、释放、确认、退回和数量下限。在本机 MySQL 9.7.4 临时库加载正式表结构，冻结快照两次执行迁移后主体余额总数 11、锁 2，门店总数 7、锁 3、价 8100 不变。模拟回滚、旧市场数量不一致、目标余额已改变均验证通过。生产数据搬迁与在途订单锁对账待切换窗口执行。

### 候选环境实测（2026-11-28）

候选库将规格维度适用范围存在 `prd_spec_usage`，将取值的启停、排序及覆盖名称存在 `prd_spec_value_usage`。在该结构上启用 `shop.product.split-spec-template-reads=true`，用于商品编辑器读取模板及发布时重新解析规格。采用合并表 DDL 的部署沿用默认配置。规格配置后台的写入与本次商品验收分开验证。

商品模块绑定实际 MySQL 库 `n_trade`；平台登录、商户与公共服务使用候选实例的隔离测试数据。商户账号登录后选定 `M-OLD / S-OLD`，经营类目为 `CAT210`。商品连接账号访问旧库时，MySQL 返回 1142（拒绝 SELECT）。

| 操作 | 接口及对应记录 |
|---|---|
| 保存 | `/biz/goods/save` 返回商品 `G26112800Q4WB24S9`、SKU `SK261128JSAW1H2A0E`。按号查到 `prd_goods` 为 DRAFT，`prd_store_sku` 期初库存为 9。 |
| 审核与单店上架 | 商户提交、运营审核通过，带 `S-OLD` 门店上下文上架。单店模式下 `prd_goods.on_sale=1`，商户列表显示 ON_SALE。 |
| 修订发布 | 保留同一商品号和 SKU，保存标题尾缀 R2，预览、发布、审核后 `prd_goods_revision` 第 2 版为 ONLINE，商品主行标题一致。修订请求传入库存 4，发布后门店库存仍为 9。 |
| 门店改价与库存回读 | 门店接口设置价格 1777、库存 13。按商品号和主体关联 `prd_sku`、`prd_store_sku`，门店仅一条有效余额，价格 1777、数量 13、锁量 0。另发详情请求读回相同的价格和库存。 |
| 最终构建重跑 | 在启用分表模板读取的候选进程重新登录，`CAT210` 的模板接口返回 0 个可用模板，与该主体的实际配置一致。用同一商品和 SKU 再次保存 R3、预览、发布并运营审核，修订 2 变为 SUPERSEDED，修订 3 为 ONLINE。草稿传入库存 4，原库存 13 得以保留。 |
| 多门店上架 | 隔离平台夹具增加第二家营业门店 `S-QA-0F9D9A87`。下架 `S-OLD` 后，`prd_catalog_item.enabled` 两店分别为 0、1；重新上架后分别为 1、1。按门店查询下架和在售列表都命中原商品，`storeOnSale` 随请求变化。 |
| 最终核对 | `S-OLD` 改价 1888、调库存到 17 后，独立详情 GET 返回 ON_SALE、1888、17。用相同商品、SKU、主体和门店联查交易库得到一行，价格 1888、库存 17、锁量 0。 |

本轮 Maven 定向回归共 218 项（shop-core 7，shop-app 211），失败、错误和跳过均为 0。另按候选分表 DDL 在隔离 MySQL 9 验证带模板编号商品的规格重新解析。早期候选公共服务夹具缺少发件箱表，两次事件写入失败；补齐后最终构建重跑，候选日志无 ERROR。[接口请求号、逐步查库结果和环境清理记录](../handoff/v2-retail-closure-2026-11-27/product-n-trade-candidate-20261128.json)

取证后，使用门店接口下架两家测试店的同一商品。`prd_goods` 保留 APPROVED、`on_sale=0`，两条 `prd_catalog_item.enabled=0`，第 3 版内容和 `S-OLD` 的价格、库存记录保留供按号追溯。

首轮验收时，候选实库的 `prd_store_sku.uk_store_sku_market` 和 `prd_store_category.uk_food_1` 均为普通索引，仓库目标 DDL 将两者定义为唯一索引。本次按同一商品和门店串行验收，回读仅一行。后续索引对齐与并发验收见下节。

最终发布、门店改价、库存调整、详情回读的请求号依次为 `332bbe83543f45f98406f63092f1159c`、`89ff2997bfcf4d2a94d177d168e8164c`、`839c282cbefb45c890bc8e08cfc9063a`、`b520a2c392f24494b15147cd3782d288`。


### 候选交易库唯一索引与并发验收（2026-11-28）

变更前用 MySQL 9.7.4 在 `n_trade` 按下表的列组合分组，计入全部软删行。另查通用经营货架的 `(store_no, platform_category_no)`，重复组数为 0。无须合并或删除历史数据。

| 表 | 查重键 | 变更前总行数 | 重复键组数 | 含 NULL 的键 | 原索引 → 新索引 |
|---|---|---:|---:|---:|---|
| prd_store_sku | store_no, sku_no, market | 2 | 0 | 0 | uk_store_sku_market，NON_UNIQUE 1 → 0 |
| prd_store_category | category_no | 1 | 0 | 0 | uk_food_1，NON_UNIQUE 1 → 0 |

两条语句分别在线执行，每张表在一条 ALTER 内替换索引。执行后通过 `information_schema.statistics` 核实唯一标志和列序。业务键的可空性沿用目标 DDL，MySQL 唯一索引允许多个包含 NULL 的键。

```sql
ALTER TABLE n_trade.prd_store_sku
    DROP INDEX uk_store_sku_market,
    ADD UNIQUE INDEX uk_store_sku_market (store_no, sku_no, market),
    ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE n_trade.prd_store_category
    DROP INDEX uk_food_1,
    ADD UNIQUE INDEX uk_food_1 (category_no),
    ALGORITHM=INPLACE, LOCK=NONE;
```

首轮重放的两个并发首次写入均只留下一个业务键，但接口各有一个请求失败。两次为同一新店设置相同经营类目时，失败请求命中唯一键；同时首次设置同一门店 SKU 的价格和库存时，一个请求遇到死锁。修复后，同一商品的保存、上架、门店定价和调库存以商品主行串行化；重复选择同一经营类目时，服务按主体、门店、平台类目和货架编号读取已提交行，核对请求内容后返回。

候选库的 `locked_qty` 可为 NULL。中间构建在“先创建门店价，再设置门店库存”的顺序下写入了价格和数量，但锁量仍为 NULL，后续库存锁定运算会失效。最终构建在插入价格行时初始化锁量，将价格行转为库存行或设置库存时以 0 补空，保留已有正数锁量。

最终构建使用两个独立商户登录会话，每组并发两个请求。首设经营类目、首次并发写门店价与库存、并发保存同一在售商品、同店首次上架、保存与另一门店上架、原门店改价与调库存，共 6 组 12 个首发请求均返回业务码 0。受限应用连接访问旧库商品表返回 MySQL 1142。

商品 `G26112800Q4WB24S9`、SKU `SK261128JSAW1H2A0E` 的并发保存仅分配一份第 5 版草稿，随后正式发布。验收时商品主行与市场 SKU 各一条，四家测试门店各有一条经营类目、上架关系和 SKU 余额。按修订号、上架业务键、门店库存键、经营类目键查重均为 0。各店独立详情请求和 SQL 值如下，锁定量均为 0。

| 门店 | 价格（最小货币单位） | 库存 |
|---|---:|---:|
| S-OLD | 2666 | 46 |
| S-QA-078728F0 | 2333 | 33 |
| S-QA-0F9D9A87 | 2111 | 22 |
| S-QB-078728F0 | 2555 | 45 |

定向 Maven 回归通过 102 项（shop-app 96，shop-core 6），另在本机 MySQL 9 验证经营类目的双事务首次设置、历史行恢复和跨主体冲突。

验收后通过商户接口下架四家测试门店的同一商品，保留第 5 版和价格库存用于追溯。复查全表仍无空键和重复键，测试账号、进程和本地夹具已清理。[并发请求、最终 SQL 和清理证据](../handoff/v2-retail-closure-2026-11-27/product-n-trade-concurrency-20261128.json)

PR #59 补充验证：`StorePriceFlowTest#legacyCancelledPriceRowCanBeRepricedAndThenUsedForStock` 在修复前命中门店 SKU 唯一键并返回 10500。修复后，旧行可以重新定价或初始化库存，保留原行编号，库存初始化清除历史价格与划线价。使用目标 DDL 在本机 MySQL 9.7.4 验证同一事务在重复键报错后可恢复该行，并确认跨主体或已有库存的行不会被恢复。

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

### 第 3 步 · 批次 1（纯改名）

- `PrdSpuStd` → `prd_standard_goods`、`PrdSellRule` → `prd_sellable_rule`：列完全一致，只改 `@TableName`；基线 89 → 87。
- 后续（不在商品范围）：媒体模块 `CoreMediaRefs` 按表名清理无主图片，仍登记旧表 `prd_spu_std`（其覆盖测试扫的是旧迁移）；媒体随它自己的迁移改成按句柄查新库。
- 其余改名表都牵涉合表，放批次 2：`prd_store_price` → `prd_store_sku`（并入门店库存）、`prd_category_spec` → `prd_spec_usage`（类目改为范围）、`prd_category_spec_value` 在零售定稿里并入 `prd_spec_usage`。

### 第 3 步 · 批次 2（合表）

- **2-a 类目付款方式**：`prd_category_pay_mode` → `prd_category.disallowed_pay_modes`（JSON 数组，只记禁止的，空为 NULL 即全部放行；语义与原表「没有行即放行」一致）。读写集中在 `CategoryPayModePolicy`；`OpsCategoryPolicyController`、`PayModeServiceImpl` 改读类目表；删 `PrdCategoryPayMode` 与其 Mapper。运营端接口出参不变（`configured` 恒等于 `!offlineAllowed`）。基线 −1。
- **2-b SKU 定价策略**：`prd_sku_pricing_policy` → `prd_sku.tax_class` / `promotion_eligible`，写在本市场那一行。原表的 `store_no` 维度不丢：服务本来就要求商品归属这家门店（`goods.store_no`），一个 SKU 只属一家店。策略编号改用 `sku_no`；版本复用该行乐观锁 `version`，未配时对外为 0。行为差异：同一 SKU 的其他修改（改价、条码）也会让在途的策略编辑冲突重来。`taxClasses` 的门店过滤改为经 `prd_goods.store_no`。删 `PrdSkuPricingPolicy` 与其 Mapper、数据范围登记；`PrivateOwnerDataScopeTest` 改为校验 `prd_sku` 同样拒绝仅位置授权。`BizKey.SKU_PRICING_POLICY` 暂留（术语表由它生成），再无写入方。
- **2-b 卡类权益绑定**：`prd_entitlement_binding` → `prd_sku.entitlement_version_no` / `entitlement_rule_snapshot` / `entitlement_units`；币种从冻结的规则快照里取（写入时已校验与市场币种一致）。原表的 `status`（代码只写 ACTIVE）与「停用绑定」改为停用 SKU（`prd_sku.status = DISABLED`），同样不能借此退回旧的无权益卖法。专用防重表 `prd_ent_binding_command`（不在新库设计）改走公共的 `RequestIdempotencyPort`。基线 −4。
- **2-c 门店价 + 店级库存**：`prd_store_price`、`prd_store_stock` → `prd_store_sku`（一店一 SKU 一市场一行），实体 `PrdStoreSku`、`StoreSkuMapper` 取代原来两套。列映射：`stock` → `sellable_qty`、`locked_stock` → `locked_qty`，口径不变（总量含已锁，可卖 = `sellable_qty - locked_qty`），扣减 SQL 逐条照搬。合表带来的语义要点：
  - 两半各自可空、回退方向相反（门店价空回退主体价；店级库存空 = 这家店没有，按 SKU 判是否按店管理）。**一律看列不看行**：`hasStoreStock` / `managedByStore` / 可售量读取都加 `sellable_qty IS NOT NULL`，扣减类 SQL 也带这个条件，避免只设过价的行「影响 1 行」冒充成功。
  - 写入：建店级库存走 `StoreSkuMapper.startStoreStock`（有行补列、无行才插，市场取本市场）；取消门店价只清价格两列，行上有店级库存时不删行；改价显式 set（划线价传空能清掉）。
  - 价格来源的 `storePrice` 版本沿用原约定：行在就记它的版本（价格回退时也记，缺版本直接报错）。合表后店级库存的变动也会让这个版本变 —— 它只作留痕、不做比对，`prd_sku` 的版本本来就随锁库存变。
  - 数据域：旧库门店价登记了 MERCHANT、店级库存没登记；合表后按库存那半不登记（扣减路径多处直接调 Mapper，登记后漏一处豁免就是「有货却下单失败」）。
  - 本批不动 `prd_sku.stock`（主体级库存，过渡列）：去掉它意味着单店商家也要按店记库存，属于业务口径变化，另列一步。
  - 基线 −2，另有 2 条 R5（invbridge 回填）随类名改写，仍属第 4 步。
- **2-d 规格的使用范围**：`prd_category_spec`、`prd_category_spec_value`、`prd_merchant_spec_override`、`prd_merchant_spec`、`prd_merchant_spec_value` → `prd_spec_usage`，实体 `PrdSpecUsage`、`SpecUsageMapper` 取代五套。按 `scope_type` 分：
  - `CATEGORY`：`scope_ref` = 类目号；维度级行即原类目绑定，取值级行即原类目取值子集（含类目内叫法）。
  - `MCH_CATEGORY`（列宽 16，不用全称）：`scope_ref` = 商家号 + `/` + 类目号，`entity_no` = 商家号；原商家覆盖。原 `enabled=false` → `status = DISABLED`，其余为 `ACTIVE`；取值级行的 `value_no` 照旧存取值的 code。
  - `MERCHANT`：`scope_ref` = 商家号；原商家常用维度与取值。代码里没有读写方，只随数据搬迁。
  - 维度级行的 `value_no` 存空串而不是 NULL：唯一键 `(scope_type, scope_ref, dim_no, value_no)` 里 NULL 互不相等，存 NULL 就挡不住重复。
  - 整份替换仍真删（`purgeScope`，唯一键不含 `deleted`）；查询一律从 `SpecUsageMapper.categoryDims / categoryValues / overrides` 起手，避免漏掉范围条件读到别的范围的行。
  - 测试种子仍在旧表，`schema-test-extra.sql` 末尾复制进新表。基线 −5。
- **2-e 规格模板**：`prd_spec_template` 退场，不新建平行的维度（旧模板与规格库内容重复：22 条测试种子的选项码只有 `VCASE` 一个在规格库里找不到）。读写集中在 `SpecTemplateBook`：
  - 平台模板 → 品类推荐 `prd_spec_usage.scope_type = CATEGORY_TYPE`，`scope_ref` = 品类（`*` = 不限品类）。维度级行指向规格库的维度，取值级行是推荐的几档，叫法不同记 `label_override`。运营按名字建：规格库有同名平台维度就用它，没有就按规格库规则建一个（禁用词、重名同样拦）；选项按 code 找取值，没有就建成平台取值（量纲维度要归一量，此路建不出新档位）。
  - 运营端模板号改为「品类-维度号」（同一维度可推荐给多个品类、各自归档）；下发给商家的 `templateNo` 改为维度号，与规格库下发一致，商品保存时据它反查取值编号。接口形状不变，ops-web、b-app 不用改。
  - 商家「存为常用规格」→ 走「我的规格」建自建维度（同名平台维度则直接用平台的），再记 `scope_type = MERCHANT`、`favorite = 1`；下发时两种情况都带上，同一维度只给一次。
  - 类目级的 16 条旧模板不搬：类目选定后只用规格库的类目绑定，它们本来就读不到。
  - 测试种子：品类推荐 6 组直接按新表写进 `schema-test-extra.sql`（不从旧表复制），普通品类原叫「规格」的那组即件数维度 `SD_COUNT`。
  - 基线 −1；新增用例 `OpsProductGovernFlowTest#categoryTypeRecommendationPointsAtLibraryDims`。
- **2-f 商品草稿**：`prd_goods_draft` → `prd_goods_revision`，草稿就是提交历史里的一行。不改表结构，状态加三个值（列宽 10 内）：`EDITING` 编辑中、`SUBMITTED` 待审、`DISCARDED` 已放弃；原 `DRAFT` 仍表示「还没上架时直接保存的那一版」，不是草稿。
  - 草稿 = 状态为 `EDITING` / `SUBMITTED` 的那一行，一件商品至多一行（锁商品行保证）；「有未发布修改」就看它在不在。原 `base_version` 改存 `base_goods_version`。读写集中在 `GoodsRevisionService` 的草稿方法。
  - 去向：存在售商品的修改 = 有草稿改它（内容变了才改、退回编辑中），没有新开一行；免审发布 = 草稿行改在售、原在售改已替换；送审 = 改待审；撤回 = 改回编辑中；驳回 = 这一行记驳回与原因，再复制一行编辑中的新草稿（编号 +1）；放弃 = 改已放弃。草稿保存不再另记一笔历史（草稿行本身就是那一笔）。
  - 换版时先把草稿行改成在售再回放（原来是先删草稿行），返回的 `hasDraft` 才是 false；回放失败整个事务回滚。
  - 审核队列、待审看板改数提交历史里的 `SUBMITTED`；数据域登记从 `prd_goods_draft` 换到 `prd_goods_revision`（同为 MERCHANT / `entity_no`，服务层访问都已显式豁免）；门店换绑的「有没有商品数据」检查同样换表。
  - B 端：`GoodsRevisionStatus` 加三个值，历史页加中英阿三语文案，编辑中/待审用提醒色；编辑页横幅「草稿 vN · 线上 vM」改认编辑中/待审。接口字段不变。
  - 测试：`GoodsIntegrityFlowTest` 驳回后断言历史留有驳回原因；`M9bBizGoodsFlowTest` 放弃后断言历史留有已放弃那一版；`b-app/tests/goods-revisions.test.ts` 加新状态。基线 −1。
- **2-g 门店目录三张表**（用户 2026-11-28 确认，其中两处改了新库 DDL，改在 `scripts/gen-retail-ddl.py`）：
  - 目录版本 `prd_store_catalog_version` → `prd_store_catalog`，**一个版本一行**。DDL：去掉「一店一行」`uk_prd_catalog_scope` 与 `uk_prd_catalog_no`，改为 `(tenant_no, entity_no, catalog_no, version_no)`、`(tenant_no, catalog_version_no)` 两条唯一；`frozen_content` 从 `json` 改为 `mediumtext`（MySQL 会规范化 JSON，读回的文本与写入不同，快照摘要即对不上）。发布 = 锁住最新一版（`lockLatest ... FOR UPDATE`）、核对 expectedRevision、插下一版；结账锁同一行。读与结账取「已生效里最新的那一版」，定时生效照旧。首发的目录号用门店号，两人同时首发撞唯一键只留一份。`StoreSalesCatalogIT` 改为用定稿 DDL 建表（删掉旧迁移 V596 的升级用例），本机 MySQL 9.7 上 7 条全过。
  - 门店商品 `prd_store_goods` → `prd_catalog_item` 的「门店级行」：`catalog_version_no` 存空串、`enabled` 即原 `on_sale`。DDL 加唯一 `(tenant_no, store_no, goods_no, catalog_version_no)`；餐饮的 `uk_food_1 (category_no, goods_no)` 保留（门店级行类目为空，不受它约束）。查询一律从 `StoreGoodsMapper.storeLevel()` 起手，免得把菜单条目当成门店上下架。
  - 主体类目库存开关 `prd_entity_category_inv` → `prd_store_category` 的「主体级行」：门店号、目录号存空串，`platform_category_no` 为平台类目，`inv_managed` 为开关；行编号 `category_no` 按「主体 + 平台类目」摘要算出（`INV` + 29 位），同一开关永远落同一行，并发首设撞键后改那一行。不改 DDL。
  - 新表 `tenant_no` 无默认值，两个实体构造时填 `MAIN`。门店换绑检查从 `prd_store_goods` 换到 `prd_catalog_item`；数据域去掉 `prd_store_catalog_version` 的登记。基线 −3。
- 公共防重表 `sys_idempotent` 目前在主库、不与商品事务同库（定价策略原本就是如此）；它属于公共能力，随防重服务化处理，不进门店交易库。

### 第 4 步 · 别的领域直连商品（2026-11-28，库存同步先保留）

- 新 SPI `InventoryProductPort`（shop-base）＋实现 `InventoryProductPortImpl`（商品领域）：库存对接要的商品、SKU 收成只含字段的事实记录（`GoodsFact`、`SkuFact`、`GoodsStats`），记库存方式的切换、事件与发货守卫要的锁号查询也从这里走；判据仍在 `InvManagedService` 一处。记库存方式与审核状态的取值在接口上给常量。
- 改走接口的 10 个类：`GoodsChainServiceImpl`、`MerchantChainServiceImpl`、`InvManagedAppService`、`InvManagedBackfillRunner`、`InventoryHealthServiceImpl`、`InventoryOrphanSweepServiceImpl`、`InventoryOverviewQuery`、`InventoryWritebackService`、`InventoryDeliveryGuard`，以及 `DevSeeder`。口径变化：商品链路与在架 SKU 改按本市场那一行计（与 `InvManagedService.skusOf` 同口径），多市场时销量只算本市场。
- `DevSeeder` 不走建品接口（演示数据要固定编号 G0001/SK0001，测试按编号断言），改为调商品领域的 `ProductDemoSeeder`（同样只在 `shop.seed.enabled=true` 时存在），商品表只由商品领域写。
- `StoreSalesCatalogServiceImpl` 的数据源与事务改点名商品句柄（R4）；商品与交易绑同一个数据源名时是同一个连接池，结账事务里的判据照旧。`StoreSalesCatalogIT` 补上未绑定时的句柄别名，本机 MySQL 上 7 条全过。
- **保留未动**（库存同步一组，待定是否下线交库存服务）：`StockSyncAppService`、`StockAlignmentService`、`StockReconcileService`、`StockSyncSweepJob`、`OnlineQuotaPortImpl`、`InventoryBackfillServiceImpl`（商品库存搬进进销存的回填）、`ExecutionStockMirrorEvidenceServiceImpl` 与库存模块的 `ExecutionStockRetentionSchemaVerifier`（执行占用，表待退役）。基线 R5 剩 20 条、R4 剩 1 条，都在这一组。

### 偏差说明

- 测试库没有按原方案「去掉旧 `prd_*`、两个句柄绑同一个 H2」，而是同一个 H2 库里追加一份按新设计生成的建表脚本：改动更小，漂移测试不受影响，且能逐批迁移。

- 原方案新建 `member` 句柄；实现时发现已有 `memberMarketing` 句柄目标就是会员营销库，改为复用，只加标记接口。

- 批次 2-b 定价策略的版本：原表有独立版本，并进 SKU 后复用行版本（设计 DDL 无专用列；加列属于改表结构，需另行确认）。

## §7 确认与完成

- [x] 切换批次已定
- [ ] 专题去处、库存同步类功能下线两项待确认
- [ ] 方案确认

### 门店经营类目的存量对账

先冻结门店经营类目写入，再对同一实例的旧库与候选交易库运行[货架对账工具](../../deploy/ume/product-cutover/reconcile_store_categories.py)。依赖 PyMySQL，连接使用受控的 MySQL 客户端配置文件。

```sh
python3 deploy/ume/product-cutover/reconcile_store_categories.py check --config "$MYSQL_CNF" --source "$SOURCE_DB" --trade "$TRADE_DB"
python3 deploy/ume/product-cutover/reconcile_store_categories.py apply --config "$MYSQL_CNF" --source "$SOURCE_DB" --trade "$TRADE_DB"
python3 deploy/ume/product-cutover/reconcile_store_categories.py verify --config "$MYSQL_CNF" --source "$SOURCE_DB" --trade "$TRADE_DB"
```

旧 `mch_store_category.category_no` 写入 `prd_store_category.platform_category_no`，`display_name`、`sort` 分别写入 `name`、`sort_no`。交易库的 `category_no` 按应用同一规则生成，由门店号、一个零字节和平台类目号的 UTF-8 编码生成无连字符的 Java 名称 UUID。通用货架不挂目录，保留主体、启用状态、软删、版本和审计字段。

`check` 默认只读，报告源记录数、已匹配数与待补数。相同编号被其他分类占用、同一货架已挂不同编号、目标字段与源不符均中止。冻结窗口内的 `apply` 仅插入缺行，`verify` 要求每条源记录逐字段一致。其他门店分类、目录专用分类与主体库存开关保留原样。

2026-11-28 本机 MySQL 9.7.4 按源表结构与目标 DDL 建立一次性双库。两家门店使用同一平台类目，其中一行为已删除记录；首次导入 2 行，再次导入 0 行，`verify` 匹配 2 行。目标原有的目录分类与主体库存行均保留，修改目标显示名后再导入被拒。应用层 `StoreCategoryFlowTest` 共 9 项通过，覆盖移出加回、目录与自定义行隔离、授权闸及商品归属。

### 分表规格库的建品模板读取

已采用合表 DDL 的部署沿用现有配置。物理结构为维度使用表 `prd_spec_usage` 与取值使用表 `prd_spec_value_usage` 的候选交易库启用 `shop.product.split-spec-template-reads=true`。建品侧按类目取已启用的维度，从取值表还原平台子集、显示名、顺序与商家覆盖；同一取值上的类目内商家设置优先于该商家的全局设置。维度名取 `prd_spec_dim.name`，平台和自有取值按所属主体筛选。商品保存、审核和发布对带 `templateNo` 的选项沿用同一个值编号解析与快照生成过程。

类目未选时，分表中 `CATEGORY_TYPE` 记录能确定推荐维度，其字段不能指定该品类的推荐值子集；读取返回记录中有依据的维度。维度没有商家改名字段时保留主数据名。规格维护的写路径仍按合表设计，分表模式验收范围为模板读取、建品归一和发布。

本地原生测试 `SplitSpecTemplateReadsMysqlTest` 从版本化的[候选四表 DDL](../../backend/shop-app/src/test/resources/db/product-split-spec/verified-spec-schema.sql)建立一次性 MySQL 9 库。设置 `C06_REVIEW_MYSQL_URL=jdbc:mysql://127.0.0.1:3308/information_schema`，访问本机 3308 使用 `C06_REVIEW_MYSQL_USER` 与 `C06_REVIEW_MYSQL_PASSWORD`。
