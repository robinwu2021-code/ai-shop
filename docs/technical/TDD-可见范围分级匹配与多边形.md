# TDD-可见范围分级匹配与多边形

状态：**已实施**（2026-10-10）—— 三端与迁移 V396/V397 落地，验证见 §5、遗留见 §8
档位：2（改可见性核心判定 = 目录/详情/下单共用契约；加库表/列/枚举值；加端点参数；加 i18n；加 ErrorCode；加两个三方依赖）
关联：[ADR-034 可见范围模型](ADR/ADR-034-可见范围模型-显式不限与多边形.md) · [ADR-013 服务区域](ADR/ADR-013-服务区域.md) · [ADR-031 商品归属门店](ADR/ADR-031-商品归属门店.md) · [ADR-033 业务编码统一生成](ADR/ADR-033-业务编码统一生成.md)
创建：2026-10-10
背景来源：用户 2026-10-10「有的门店城市可见、有的省市可见、有的某一地图范围可见，要按消费者当前定位筛选不同可见范围的商品」；起因是虹选粮油框了嘉逸花园却全平台可见。

> **给执行者**：按 §2.14 逐任务执行（推荐 subagent-driven-development，或 executing-plans）。每个任务固定节拍：先写红测试 → 跑确认红 → 最小实现 → 跑绿 → **按显式路径** `git add` → 立刻 commit（共享工作树：永远带路径、只提交自己认得的文件、绝不 amend）。后端 `mvn -o`、`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；改 `.vue` 跑 `vue-tsc` 不是 `tsc`。

## §0 对账一 · 需求 → 设计

### 需求（用户原话归纳）

1. 消费者**选收货地址或用当前定位**，系统按这个地址筛首页/目录商品。地址可以是小区、建筑物，也可能只是一个定位点。
2. 门店可见范围可配：**具体小区/建筑**、**街道/区/市/省**（行政分级，含村/居委会第五级）、**地图上画的任意多边形**、**全平台不限**；排除项同样支持各级（如「全部，但排除新疆、西藏」）。
3. 范围配在**门店级**，商品继承（不做单品覆盖）。
4. 多边形**自助生效、不审核**。
5. 「不限区域」改成商家**显式**选择；存量自动迁移、行为零变化。
6. 匹配**不再依赖「开放小区」锚点**。
7. 一次性按行政分级匹配（含排除），性能可控；调研后选定方案（见 ADR-034）。

### 拍板（2026-10-10）

| 决定 | 结论 |
|---|---|
| 行政级 + 聚落 + 排除 | **祖先码集合成员判定**（`ref_code IN (祖先集)` / `IN (communityNo, parentNo)`），2 条索引点查，Java 做集合运算与路判定 |
| 多边形 | **S2 网格覆盖写进派生表**，命中同样走 `IN`；边界 cell 用 **JTS `covers()`** 精判；几何库纯 Java（JTS + S2，无 JNI） |
| 否决 | MySQL 空间类型、ES、一条聚合 SQL、展开池、Redis GEO/GEOSHAPE、Tile38、Geohash（理由见 ADR-034） |
| 不限 | 显式 `UNLIMITED`；Java 迁移复刻旧 `routes()` 兜底语义回填存量 |
| 审核 | **所有粒度自助生效**（2026-08-24 起区/市送审已拿掉，`replaceAreas` 一律写 ACTIVE；DDL 注释是陈的） |
| 排除无法判定 | **fail-closed**：带行政级排除而无区划码、带多边形排除而无坐标 → 不可见；带小区级排除而无聚落号 → 不排除（聚落解析是尽力而为，见 §2.7） |
| 边界 | 算在内（JTS `covers`） |
| 粗排除 vs 细纳入 | 排除永远赢（沿用现状） |
| 楼栋链 | 只向上一级 `parentNo`（沿用现状） |
| 缓存 | 门店元数据快照（Caffeine，写路径 `AfterCommit` 失效 + TTL 300s 兜底）；范围/网格命中不缓存、走索引 |

### AC

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 多边形范围：坐标在面内可见、面外不可见、边界算在内 | `S2Cover` 覆盖 + `GeoPolygon.covers` 精判 |
| AC2 | 省/市/区县/街道/村居任一级框了，对应区划内消费者可见；消费者码比范围粗则**不**命中 | `ConsumerProfile.ancestorsOf` + `ReachMatchMapper.areaHits` |
| AC3 | 小区/楼栋按聚落号精确匹配（含父聚落） | `areaHits` 的 COMMUNITY 分支 |
| AC4 | 纯定位、未落到小区的消费者也能被省市区/多边形命中 | 合成画像；匹配不读 `open` |
| AC5 | 「不限」显式；没框任何范围的店对谁都不可见 | 删隐式分支；`level=UNLIMITED` 进快照 |
| AC6 | 存量「实际全平台可见」的店迁移后仍全平台可见 | `V397__backfill_unlimited.java` 复刻旧 `routes()`；迁移前后集合相等 |
| AC7 | 所有粒度自助生效（省、多边形同理） | `replaceAreas` 写 ACTIVE 不变 |
| AC8 | 目录/推荐/详情送达/下单落店/结算路选择**同一判定** | 全部经 `ReachRule.decide/selectable` |
| AC9 | 每请求不再整表重载 | `ReachSnapshotCache` + 2 条索引点查 |
| AC10 | 商家端能画多边形、能开关「不限」、能看每条范围项 | b-app `store-scope` + 新页 `store-scope-polygon` |
| AC11 | 消费者端把所选地址/定位坐标带给目录/推荐/详情接口 | c-app 传 `latE6/lngE6` |
| AC12 | 非法多边形被拒且可读 | `ErrorCode.SERVICE_AREA_POLYGON_INVALID` |
| AC13 | 排除 fail-closed（见拍板） | `ReachRule.decide` 前置判 |
| AC14 | DB 命中查找与内存命中查找**等价**（两种机制一个语义） | `HitFinderParityTest` |
| AC15 | 网格派生表可由 geometry 重建 | `ServiceAreaCells.rebuildAll()` + 内部触发接口 |

**孤立项**：无。

## §1 现状与根因

### 现状链路

```
GET /mp/goods | /mp/goods/promoted ?communityNo=&regionCode=
 → GoodsVisibility.goodsNos → serving(communityNo, regionCode)
    → MerchantPortImpl.servingStores(communityNo) / servingStoresInRegion(regionCode)
       → StoreReachLoader.allServing()  // 每请求：ACTIVE 主体 → 门店 → 范围项 → 履约路 → 子集，5 张表整表加载，零缓存
       → 逐店 ReachRule.covers(StoreReach, CommunityRef)
 → sellingAt（商品归属门店 V384 + 货架行）→ list() SQL
```

### `ReachRule.covers` 现有四分支

```
① EXCLUDE 命中 → 否
② 路为 SUBSET → 只认勾的 INCLUDE
③ includes 为空 → 快递/自送 且 c.open()        ← 隐式「不限」
④ includes 非空 → 任一 INCLUDE matches(c)
matches: COMMUNITY → 相等 或 (c.open 且 父相等)；其它 level → c.open 且 regionCode.startsWith(ref)
```

`StoreReachLoader.routes()` 兜底：门店一路未开（或未迁到 channel 模型）→ 按主体 `fulfillment_reach`：`SHIPPING`→快递(全部)、`PICKUP`/空→自提(全部)、其余→自送(全部)。**回填必须逐字复刻这段。**

### 三个根因

1. **隐式不限**（分支 ③）：INCLUDE 为空 + 快递/自送 = 全平台，且不报错。虹选粮油即此类。
2. **开放小区锚点**：③ 与 `matches` 都要求 `c.open()`；区县查询先找区里的开放小区。
3. **无几何、无索引**：没有多边形；每请求整表重载逐店判。

### 已有、可复用

- 消费者地址 `usr_address.lat_e6/lng_e6`（只有省/市/区**名字**，无区划码、无聚落号）；定位解析 `CommunityService.resolve(latE6,lngE6,coarse)` 给到区县码（6 位）与最内层聚落。
- `cmt_community.lat_e6/lng_e6`、`regionCode`（建议挂街道 9 位）、`parentNo`（楼栋→小区）。`CommunityQueryPortImpl.refs()` 刻意只 select 4 列（两万多行），加坐标两列可接受、仍不读围栏。
- `sys_region` 五级国标码：省 2 / 市 4 / 区县 6 / 街道 9 / 村居 12，嵌套。
- `mch_service_area`（门店级 V381；`mode`、`status`、`source`；唯一键 `(entity_no, store_no, level, ref_code)`；索引 `idx_service_area_ref(level, ref_code)`）；`mch_fulfillment_channel(store_no, channel, enabled, scope_mode ALL|SUBSET, ops_locked)`；`mch_channel_area(store_no, channel, area_no)`。
- `replaceAreas` 全量删重插、按 `level|refCode` 沿用 `area_no`、删项时 `channelAreaMapper.purgeAreas`。
- Flyway **Java 迁移已有先例**（`db/migration/V181__seed_villages.java`）；`AfterCommit.run(label, Runnable)`；Caffeine 已在依赖树；`CacheConfig` 常量式注册缓存名。
- b-app `pages/store-scope/index.vue` + `components/biz/biz-region-picker.vue`；`packages/shared` 的 `AREA_LEVEL` 常量派生 `AreaLevel` 类型、`ServiceArea` 接口；c-app `stores/location.ts` 持有坐标。

## §2 方案

### 契约变更

| 面 | 变更 |
|---|---|
| **依赖** | 根 POM `dependencyManagement` 钉 `org.locationtech.jts:jts-core:1.20.0`、`com.google.geometry:s2-geometry:2.0.0`；`shop-base` 引用。`mvn -o` 前要先灌进本机与服务器 `~/.m2`（T0） |
| **库表** | V396：`mch_service_area` 加 `geometry TEXT NULL`、索引 `(store_no, mode)`；新表 `mch_service_area_cell`；`level` 取值加 `PROVINCE`/`POLYGON`/`UNLIMITED`。V397（Java）：UNLIMITED 回填 |
| **端点参数** | `GET /mp/goods`、`/mp/goods/promoted`、`/mp/goods/{goodsNo}` 加可选 `latE6`、`lngE6`；`POST /biz/store/profile` 的 `serviceAreas[].geometry` |
| **DTO** | `AreaCommand(level, refCode, mode, geometry)`；`StoreProfileVO.ServiceAreaVO` 加 `geometry`；`CommunityRef` 加 `latE6/lngE6`；`GoodsQuery` 加 `latE6/lngE6`；新 `ConsumerProfile` |
| **端口** | `MerchantQueryPort` 加 `servingStores(ConsumerProfile)`、`serves(merchantNo, storeNo, ConsumerProfile)`；旧 String 版保留为委托 |
| **枚举值** | `level` 三个新值 → 「带枚举的表八处」登记（§2.11） |
| **ErrorCode** | `SERVICE_AREA_POLYGON_INVALID` → 四处登记 |
| **i18n** | b-app `store.*` 新词条三语；c-app 详情「销售区域」两条三语；后端 `err.service_area.polygon_invalid` 三语 |
| **配置** | `shop.reach.*`（`ReachGeoProps`，`@ConfigurationProperties`，全部有字段默认值） |
| 权限码 | 无 |

### 2.1 总体结构

```
消费者请求(communityNo? regionCode? latE6? lngE6?)
  → ConsumerProfileResolver.resolve → ConsumerProfile{ancestors, communityNo, parentNo, cellTokens, coords}
  → ReachMatcher.match(profile):
        hits     = DbHitFinder.find(profile)            // 2 条索引点查：范围项命中 + 网格命中（含纳入/排除、边界标记）
        snapshot = ReachSnapshotCache.get()             // 门店元数据：履约路/子集/是否不限/排除维度
        candidates = hits.keys ∪ snapshot.unlimitedStores
        for s in candidates: ReachRule.decide(meta(s), hits(s), profile, polygonCache) → 可见门店
  → GoodsVisibility 沿用 sellingAt / list()
反向展开（B 端「覆盖哪儿」/预览/运营供给分布）：
  InMemoryHitFinder.hitsFor(该店的 areas+cells, 候选小区画像) → 同一个 ReachRule.decide
```

### 2.2 数据模型

**V396__service_area_geometry_cells.sql**（H2/MySQL 共用；**不写 uca1400 排序规则**，不用 `MODIFY COLUMN` 改注释）

```sql
ALTER TABLE mch_service_area
    ADD COLUMN geometry TEXT NULL COMMENT 'level=POLYGON 时的顶点 JSON [[lngE6,latE6],...]（规范化、首尾不重复）；其余为 NULL';
CREATE INDEX idx_service_area_store_mode ON mch_service_area (store_no, mode);

CREATE TABLE IF NOT EXISTS mch_service_area_cell
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    area_no    VARCHAR(64) NOT NULL COMMENT '所属多边形范围项 mch_service_area.area_no',
    entity_no  VARCHAR(64) NOT NULL,
    store_no   VARCHAR(64) NOT NULL,
    mode       VARCHAR(16) NOT NULL COMMENT '随多边形：INCLUDE / EXCLUDE',
    cell_id    VARCHAR(32) NOT NULL COMMENT 'S2 cell token',
    s2_level   TINYINT     NOT NULL,
    boundary   TINYINT     NOT NULL DEFAULT 0 COMMENT '1=边界 cell，命中后还要用多边形精判；0=内部 cell，命中即在内',
    tenant_no  VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME    NOT NULL,
    created_by VARCHAR(64)          DEFAULT NULL,
    updated_at DATETIME    NOT NULL,
    updated_by VARCHAR(64)          DEFAULT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '恒为 0 —— 派生表走物理删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sac_area_cell (area_no, cell_id),
    KEY idx_sac_cell (cell_id),
    KEY idx_sac_store (store_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='多边形范围的 S2 网格派生表（可由 mch_service_area.geometry 重建）';
```

- `POLYGON` 行：`ref_code` = 几何指纹（规范化顶点 JSON 的 SHA-256 前 32 位十六进制）——几何不变则 `level|refCode` 不变，`replaceAreas` 现有「沿用 area_no」机制自动生效；几何改了就是新项、重算网格。`geometry` 必填。
- `UNLIMITED` 行：`ref_code='*'`、只许 INCLUDE；唯一键天然保证一店一条。
- `PROVINCE` 行：`ref_code` = 2 位省码。
- 网格表派生：由多边形行的 `geometry` 可全量重建（AC15）。

**V397__backfill_unlimited.java**（复刻旧语义，幂等）：对每家 ACTIVE 主体下的 ACTIVE 门店：
`legacyUnlimited = 无(ACTIVE INCLUDE 范围项) ∧ routes 含 ALL 的 EXPRESS 或 MERCHANT_DELIVERY`，其中 `routes` = 该店 `enabled=1 ∧ ops_locked≠1` 的履约路（SUBSET 不算）；若一条都没有 → 按主体 `fulfillment_reach`：`SHIPPING`→EXPRESS、`PICKUP`/NULL→STORE_PICKUP（不算）、其余→MERCHANT_DELIVERY。命中且尚无 UNLIMITED 行 → 插入 `(area_no=BizKey.next(SERVICE_AREA), level='UNLIMITED', ref_code='*', mode='INCLUDE', status='ACTIVE', source='SELF', created_by='V397_UNLIMITED_BACKFILL')`。日志打印回填门店数。判据：上线前在生产库跑 `scripts/reach/unlimited-backfill-preview.sql` 得 N，迁移后 `SELECT COUNT(*) FROM mch_service_area WHERE created_by='V397_UNLIMITED_BACKFILL'` = N。

### 2.3 几何与网格（shop-base `ai.neargo.shop.geo`）

- `GeoPolygon.parse(json)`：顶点 ≥3 且 ≤`maxVertices`、经纬度在 [-180e6,180e6]/[-90e6,90e6]；去掉与首点重复的尾点；JTS 建环 → `Polygon`，`isValid()` 不成立则 `buffer(0)` 归一，仍不是单个有效 `Polygon` → `IllegalArgumentException`。`covers(latE6,lngE6)` = 外接矩形预筛 + `PreparedGeometry.covers`（含边界）。`fingerprint()` = SHA-256(规范化 JSON) 前 32 位。
- `S2Cover.cover(polygon, minLevel, maxLevel, maxCells)`：外环 → `S2Loop`（`normalize()` 保证逆时针）→ `S2Polygon`；`S2RegionCoverer` 取 `getCovering`（超集）与 `getInteriorCovering`（子集）；边界 cell = covering 里不被 interior 包含的。`S2Cover.tokens(latE6,lngE6,minLevel,maxLevel)` = 点所在 cell 在 `[min,max]` 各级的 `parent(l).toToken()`。
- 参数（`ReachGeoProps`）：`s2MinLevel=12`（边长约 2 km）、`s2MaxLevel=16`（约 150 m）、`s2MaxCells=256`、`polygonMaxVertices=200`、`snapshotTtlSeconds=300`。

### 2.4 消费者画像（shop-base `ai.neargo.shop.spi.reach.ConsumerProfile`）

```java
public record ConsumerProfile(List<String> ancestors, String communityNo, String parentNo,
                              List<String> cellTokens, Integer latE6, Integer lngE6) {
    public boolean hasRegion()    { return !ancestors.isEmpty(); }
    public boolean hasCommunity() { return communityNo != null && !communityNo.isBlank(); }
    public boolean hasCoords()    { return latE6 != null && lngE6 != null; }
    /** 2/4/6/9/12 位截取，只取 ≤ 自身长度的 —— 消费者码比范围项粗就不会命中 */
    public static List<String> ancestorsOf(String regionCode) { ... }
}
```
`ConsumerProfileResolver`（shop-core）输入 `(communityNo, regionCode, latE6, lngE6)`：有 `communityNo` → 读聚落（regionCode、parentNo、坐标兜底）；无 `regionCode` 且有坐标 → `communityService.resolve(lat,lng,coarse=true).regionCode()`；`cellTokens` 由坐标算。**确知即记，不臆造。**

### 2.5 命中查找（shop-merchant `ai.neargo.shop.merchant.reach`）

```java
record StoreHits(Set<String> includeAreaNos, Set<String> excludeAreaNos,
                 Set<String> boundaryIncludeAreaNos, Set<String> boundaryExcludeAreaNos)
interface HitFinder { Map<String, StoreHits> find(ConsumerProfile p, String storeNoOrNull); }
```
- `DbHitFinder`：`ReachMatchMapper.areaHits(ancestors, communityNo, parentNo, storeNo)` + `cellHits(tokens, storeNo)`（SQL 见 T5；纳入要 `status='ACTIVE'`，排除不看 status；`UNLIMITED` 不在此查，走快照）。
- `InMemoryHitFinder.hitsFor(StoreItems items, ConsumerProfile p)`：同一语义的 `Set.contains`，供反向展开/预览用。
- `HitFinderParityTest`：同一份种子数据、同一批画像，两种查找结果逐店相等（AC14）。

### 2.6 快照

`StoreMeta(entityNo, storeNo, List<Route> routes, boolean unlimited, boolean hasAdminExclude, boolean hasCommunityExclude, boolean hasPolygonExclude)`；`ReachSnapshot(Map<String,StoreMeta> stores, Set<String> unlimitedStores)`。`ReachSnapshotLoader.load()` 复用现有 `routes()` 逻辑（从 `StoreReachLoader` 迁出）；`ReachSnapshotCache`（Caffeine 单键、`expireAfterWrite(ttl)`、`evict()`）。失效点：`MerchantStoreServiceImpl.replaceAreas/syncCommunities`、`StoreFulfillmentServiceImpl.save`、`StoreAdminServiceImpl.create/setStatus`、`MerchantGovernServiceImpl` 门店/范围写操作 → `AfterCommit.run("reach-snapshot-evict", cache::evict)`。商家自看自己范围（`profile()`）不经快照。

### 2.7 唯一规则 `ReachRule.decide`

```
decide(StoreMeta s, StoreHits h, ConsumerProfile p, PolygonCache polys):
  if s.hasAdminExclude   && !p.hasRegion()  → false        // fail-closed
  if s.hasPolygonExclude && !p.hasCoords()  → false        // fail-closed
  if h.excludeAreaNos 非空 → false                           // 排除先算、永远赢
  for a in h.boundaryExcludeAreaNos: if polys.covers(a, p) → false
  inc = h.includeAreaNos ∪ { a ∈ h.boundaryIncludeAreaNos : polys.covers(a, p) }
  for r in s.routes:
     r.subset ? (inc ∩ r.subsetAreaNos ≠ ∅ → true)
              : (inc ≠ ∅ → true;  s.unlimited ∧ r.channel ∈ {EXPRESS, MERCHANT_DELIVERY} → true)
  → false
selectable(s, h, p, r, polys)：同上只看一条路；自提 ALL 路在排除判定通过后直接 true（沿用）
```
小区级排除在无聚落号时不排除：聚落解析尽力而为，若也 fail-closed，所有纯定位用户会看不到任何「排除了某一栋楼」的店，不成比例。

### 2.8 门面与调用方迁移

`ReachMatcher`（`@Service`）：`match(profile)`、`covers(storeNo, profile)`、`selectable(storeNo, channel, profile)`、`reachableCommunities(storeNo)`（候选沿用「开放小区 ∪ 点名小区」，用 `InMemoryHitFinder`）、`preview(entityNo, storeNo, areaCommands)`（临时 `StoreItems`，多边形即时算网格）。
`MerchantPortImpl`：`servingStores(ConsumerProfile)`/`servingStoresInRegion(regionCode)`→画像只带祖先码/`serves(...)`/`reachableCommunities`/`previewReachable`/`storeCoverage` 全部改调门面；旧 `servingStores(String communityNo)` 保留，内部由 `CommunityRef` 造画像。`StoreReachLoader.allServing()`、`ReachRule.covers/matches/unlimitedWhenUnframed` **删除**。`GoodsVisibility.serving(...)` 改收坐标。

### 2.9 保存路径

`replaceAreas`：
- `POLYGON`：`GeoPolygon.parse(a.geometry())`（失败 → `SERVICE_AREA_POLYGON_INVALID`）；`refCode` 服务端覆写为 `fingerprint()`；行写 `geometry=normalizedJson()`；插行后 `ServiceAreaCells.rebuild(row, polygon)`（先删该 `area_no` 的 cell，再批量插）。
- `UNLIMITED`：`refCode` 覆写 `'*'`；`mode=EXCLUDE` → `BAD_REQUEST`。
- 删掉的范围项：现有 `channelAreaMapper.purgeAreas(gone)` 之后加 `cellMapper.purgeByAreaNos(gone)`。
- 方法末尾 `AfterCommit.run("reach-snapshot-evict", snapshotCache::evict)`。
- `StoreProfileVO.ServiceAreaVO` 回显 `geometry`。
- `ServiceAreaCells.rebuildAll()`：遍历所有 POLYGON 行重算（参数变更/修复用），挂内部触发接口 `POST /internal/reach/rebuild-cells`（沿用现有 internal 接口鉴权与登记方式）。

### 2.10 查询接口与端

- 后端：`GoodsQuery` 加 `latE6, lngE6`（保留两个旧构造）；`MpCatalogController.goodsList/promotedGoods/goodsDetail` 透传；`GoodsService.deliverableTo(goodsNo, communityNo, latE6, lngE6)` 重载。
- c-app：`GoodsQuery` TS 类型加 `latE6?/lngE6?`；首页、分类、商品详情从 `location.browsePointE6` 取坐标传给
  `goodsList`/`promoted`/`goodsDetail`。**搜索页、店铺页、同类推荐刻意不传** —— 它们本来就不按位置筛
  （搜索页注释写着「那是主动找特定商家，用户自己清楚在找什么」），加上去是改它们的行为。

  > **与原设想不同的一处取舍（2026-10-10 实施时定）**：原计划给只画了多边形的店在「销售区域」
  > 那一行显示「配送范围内」，为此要给后端 `SaleScope` 加一个「有没有多边形」的标记。**没做。**
  > 那句话对买家几乎没有信息量（他不知道那片在哪），而他真正要问的「送不送到我这儿」
  > 由已有的 `deliverable` 字段回答 —— 详情页的 `outOfScope` 读的就是它，拦购买并给理由。
  > 本次给详情接口接上坐标之后，**多边形店的送达判断才真的判得出来**，那比多一句模糊的描述有用。
  > `unlimited=true` 那一行的文案不用改：后端 `ReachRule.unlimited` 的判据已换成显式 UNLIMITED 项，
  > 端上照旧读这个布尔。
- b-app：`AREA_LEVEL` 加三值（类型自动跟上）；`ServiceArea` 加 `geometry?: string`；`store-scope` 列表渲染 POLYGON 行（顶点数）、UNLIMITED 开关行；`biz-region-picker` 放开 PROVINCE；新页 `pages/store-scope-polygon/index.vue`（独立页承载原生 `<map>`，点加顶点、长按删末点、完成/清空、≥3 点才能完成；`polygons` 属性实时填充）；三语词条。

### 2.11 登记面（漏一处 pre-push 就红）

- **新表** `mch_service_area_cell`：`DataScopeRegistration.register(...)`（带 `entity_no/store_no` 归属列，否则 `data-scope-coverage` 守卫红）、`TableHasProducerTest`（实体+Mapper）、`SchemaParityTest`、`backend/scripts/gen-test-schema.py`（重生成 H2 schema）、`gen-table-inventory`、`gen-erd` + `schema-lineage.test.ts`（`area_no` 跨表键登记）。
- **枚举值**（`level` +PROVINCE/POLYGON/UNLIMITED）：`MchServiceArea.java` 常量、`StoreProfileVO.java`、`ReachRule.java`、`MerchantGovernServiceImpl.java`、`CommunityQueryPort.java` 注释、b-app 三语 locale、c-app `api/mocks/community.ts`、`packages/shared` `mock/db.ts` + `utils/constants/index.ts(AREA_LEVEL)` + `utils/region.ts`、`gen-glossary` 产物。
- **ErrorCode 四处**：`ErrorCode.java`、`i18n/messages{,_en,_ar}.properties`、`ErrorCodeUniqueTest`（自动）、`gen-glossary`。
- **OpenAPI**：`ServiceAreaVO.geometry`、`AreaCommand.geometry`、`/mp/goods*` 新参数 → b-app/c-app `scripts/gen-openapi.mjs`，`RESPONSE_TYPES` 登记。
- **UI 清单**：`pages.json` 新页 → `python3 scripts/gen-ui-catalog.py`。
- **生成物**：`node scripts/check-generated-docs.mjs` 全绿；pre-push 整套。

### 2.12 错误处理

| 情形 | 处理 |
|---|---|
| 多边形顶点 <3 / >200 / 坐标越界 / JSON 坏 / buffer(0) 后仍无效 | `SERVICE_AREA_POLYGON_INVALID`，文案指明原因 |
| 库里 POLYGON 行 geometry 解析失败 | `PolygonCache` 返回「不覆盖」+ WARN（含 area_no）；网格表仍在 → 内部 cell 照常命中、边界 cell 按不在内 |
| UNLIMITED 重复 / mode=EXCLUDE | 唯一键拦重复；EXCLUDE → `BAD_REQUEST` |
| 开快递/自送但没框范围 | 允许保存；店不可见；范围页顶部提示「尚未设置可见范围，消费者看不到本店」 |
| 请求坐标无效（越界） | 视为无坐标 |
| 消费者缺维度而店有对应排除 | fail-closed（§2.7） |
| 多实例缓存不一致 | ≤ 300 s；单实例生产下写路径即时失效 |

### 2.13 性能预算

- 每请求：快照内存读 + 2 条索引点查（`IN` ≤ 5+2 与 ≤ 5 个值）+ 边界 cell 数次 JTS `covers`。目标 `/mp/goods` 本机 P95 < 200 ms（`api-latency-budget`）。
- 保存多边形：S2 覆盖 ≤ 256 cell，批量插入一次。
- 快照构建：5 张小表 + `mch_service_area` 两列扫描（判排除维度/不限），300 s 一次或写后立即。

### 2.14 实施计划

> 文件路径均相对仓库根。每任务结尾按路径 `git add` + commit；提交信息末行 `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`。

#### T0 依赖与离线仓库

**Files**：Modify `backend/pom.xml`（`<dependencyManagement>`，第 100 行起）；Modify `backend/shop-base/pom.xml`。
**Steps**
1. 根 POM `dependencyManagement/dependencies` 加
   ```xml
   <dependency><groupId>org.locationtech.jts</groupId><artifactId>jts-core</artifactId><version>1.20.0</version></dependency>
   <dependency><groupId>com.google.geometry</groupId><artifactId>s2-geometry</artifactId><version>2.0.0</version></dependency>
   ```
   `shop-base/pom.xml` `dependencies` 加两条（不写版本，由根 BOM 钉；模块里钉版本不生效）。
2. 联网一次灌本机仓库：`cd backend && mvn -U dependency:resolve -pl shop-base`（若 `jts-core:1.20.0` 不存在则改 `1.19.0` 并同步 POM）。
3. 服务器同样灌一次（部署走服务器构建）：`ssh <prod> 'cd /data/ai-shop/src/backend && mvn -U dependency:resolve -pl shop-base'`，再 `mvn -o -q compile -pl shop-base` 确认离线可编。
4. 验证：`mvn -o -q package -pl shop-app -am -DskipTests && unzip -l shop-app/target/*.jar | grep -E 'jts-core|s2-geometry'` 两个 jar 都在。
**Commit**：`build(reach): 引入 jts-core 1.20.0 与 s2-geometry 2.0.0（纯 Java 几何与网格）`

#### T1 V396 DDL + 网格表实体/Mapper + 登记

**Files**：Create `backend/shop-app/src/main/resources/db/migration/V396__service_area_geometry_cells.sql`（§2.2 全文）；Create `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/entity/MchServiceAreaCell.java`；Modify `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/entity/MchServiceArea.java`（加 `geometry` 字段 + 常量 `LEVEL_PROVINCE/LEVEL_POLYGON/LEVEL_UNLIMITED/UNLIMITED_REF="*"`）；Modify `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java`（加 `ServiceAreaCellMapper extends BaseMapper<MchServiceAreaCell>`，方法 `@Delete("DELETE FROM mch_service_area_cell WHERE area_no IN (...)") int purgeByAreaNos(@Param("areaNos") Collection<String>)`）；Modify `backend/shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java`（照 203 行 `mch_store` 写法登记 `mch_service_area_cell`，归属列 `entity_no`/`store_no`）；Regenerate `backend/shop-app/src/test/resources/schema-test.sql`（`python3 backend/scripts/gen-test-schema.py`）。
**Test**：`backend/shop-app/src/test/java/ai/neargo/shop/arch/TableHasProducerTest`、`SchemaParityTest`、`MapperSmokeTest` 全绿（它们会自动覆盖新表）；`packages/shared` 的 `data-scope-coverage.test.ts` 不新增红账。
**Run**：`mvn -o -q test -pl shop-app -am -Dtest='TableHasProducerTest+SchemaParityTest+MapperSmokeTest' -Dsurefire.failIfNoSpecifiedTests=false`；`cd packages/shared && npx vitest run tests/data-scope-coverage.test.ts`。
**Commit**：`feat(reach): V396 范围项加 geometry、新增 S2 网格派生表 mch_service_area_cell`

#### T2 `GeoPolygon`（JTS）

**Files**：Create `backend/shop-base/src/main/java/ai/neargo/shop/geo/GeoPolygon.java`；Test `backend/shop-base/src/test/java/ai/neargo/shop/geo/GeoPolygonTest.java`。
**Interfaces · Produces**：`static GeoPolygon parse(String json, int maxVertices)`、`boolean covers(int latE6, int lngE6)`、`int vertexCount()`、`String normalizedJson()`、`String fingerprint()`、`Polygon jts()`、`Envelope bbox()`；非法抛 `IllegalArgumentException(原因)`。
**Test（先写、先红）**
```java
@Test void 面内在内_面外不在_边界算在内() {
    String sq = "[[114000000,22500000],[114010000,22500000],[114010000,22510000],[114000000,22510000]]";
    GeoPolygon g = GeoPolygon.parse(sq, 200);
    assertThat(g.covers(22505000, 114005000)).isTrue();
    assertThat(g.covers(22520000, 114005000)).isFalse();
    assertThat(g.covers(22500000, 114005000)).as("边界").isTrue();
    assertThat(g.vertexCount()).isEqualTo(4);
}
@Test void 凹多边形_凹口外的点不算在内() { /* U 形：凹口中心点 covers=false，臂上点 true */ }
@Test void 尾点与首点重复被去掉_指纹稳定() {
    GeoPolygon a = GeoPolygon.parse("[[0,0],[1000,0],[1000,1000],[0,1000],[0,0]]", 200);
    GeoPolygon b = GeoPolygon.parse("[[0,0],[1000,0],[1000,1000],[0,1000]]", 200);
    assertThat(a.vertexCount()).isEqualTo(4);
    assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).hasSize(32);
}
@Test void 顶点不足三个_越界_超上限_坏JSON_都抛() {
    for (String bad : List.of("[[0,0],[1,1]]", "[[200000000,0],[1,1],[2,2]]", "not json"))
        assertThatThrownBy(() -> GeoPolygon.parse(bad, 200)).isInstanceOf(IllegalArgumentException.class);
    String many = IntStream.range(0, 201).mapToObj(i -> "[" + i + "," + (i*7%100) + "]").collect(joining(",", "[", "]"));
    assertThatThrownBy(() -> GeoPolygon.parse(many, 200)).isInstanceOf(IllegalArgumentException.class);
}
```
**Impl 要点**：Jackson 读 `int[][]`；去尾重复点；`GeometryFactory` 建 `LinearRing`（x=lng/1e6, y=lat/1e6）→ `Polygon`；`!isValid()` 时 `buffer(0)`，结果不是 `Polygon`（或空）→ 抛；`PreparedGeometryFactory.prepare(polygon)`；`covers` 先 `bbox.contains(x,y)` 再 `prepared.covers(point)`；`normalizedJson` 用去重后顶点重序列化；指纹 `MessageDigest SHA-256` → hex 前 32。
**Run**：`mvn -o -q test -pl shop-base -Dtest=GeoPolygonTest`。**Commit**：`feat(geo): GeoPolygon——JTS 解析/校验/含边界精判/指纹`

#### T3 `S2Cover`

**Files**：Create `backend/shop-base/src/main/java/ai/neargo/shop/geo/S2Cover.java`；Test `backend/shop-base/src/test/java/ai/neargo/shop/geo/S2CoverTest.java`。
**Interfaces · Produces**：`record Cell(String token, int level, boolean boundary)`；`static List<Cell> cover(GeoPolygon g, int minLevel, int maxLevel, int maxCells)`；`static List<String> tokens(int latE6, int lngE6, int minLevel, int maxLevel)`。
**Test**
```java
@Test void 内部cell命中的点必在面内_覆盖外的点必在面外_属性测试() {
    GeoPolygon g = GeoPolygon.parse(SHENZHEN_IRREGULAR_6PTS, 200);
    List<Cell> cells = S2Cover.cover(g, 12, 16, 256);
    Set<String> interior = cells.stream().filter(c -> !c.boundary()).map(Cell::token).collect(toSet());
    Set<String> all      = cells.stream().map(Cell::token).collect(toSet());
    Random rnd = new Random(42);
    for (int i = 0; i < 2000; i++) {
        int lat = 22450000 + rnd.nextInt(120000), lng = 113950000 + rnd.nextInt(120000);
        List<String> t = S2Cover.tokens(lat, lng, 12, 16);
        boolean inInterior = t.stream().anyMatch(interior::contains);
        boolean inAny      = t.stream().anyMatch(all::contains);
        if (inInterior) assertThat(g.covers(lat, lng)).as("内部 cell ⇒ 在面内").isTrue();
        if (!inAny)     assertThat(g.covers(lat, lng)).as("覆盖外 ⇒ 不在面内").isFalse();
    }
    assertThat(cells.size()).isBetween(1, 256);
}
@Test void tokens按级别逐级给出_数量等于级别数() { assertThat(S2Cover.tokens(22500000, 114000000, 12, 16)).hasSize(5); }
```
**Impl 要点**：外环顶点 → `S2LatLng.fromE6(lat,lng).toPoint()` → `new S2Loop(points)`，`loop.normalize()`；`new S2Polygon(loop)`；`S2RegionCoverer.builder().setMinLevel(min).setMaxLevel(max).setMaxCells(maxCells).build()`；`covering=getCovering(poly)`、`interior=getInteriorCovering(poly)`；`boundary = !interior.contains(cellId)`；`tokens`：`S2CellId.fromLatLng(...)` 后 `parent(l).toToken()`。
**Run**：`mvn -o -q test -pl shop-base -Dtest=S2CoverTest`。**Commit**：`feat(geo): S2Cover——多边形网格覆盖（内部/边界）与点的各级 token`

#### T4 `ConsumerProfile` + `ReachGeoProps` + `CommunityRef` 坐标

**Files**：Create `backend/shop-base/src/main/java/ai/neargo/shop/spi/reach/ConsumerProfile.java`（§2.4）；Create `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/reach/ReachGeoProps.java`（`@ConfigurationProperties(prefix="shop.reach")`，字段默认值 12/16/256/200/300；`@Component`）；Modify `backend/shop-base/src/main/java/ai/neargo/shop/spi/user/CommunityQueryPort.java:66`（`CommunityRef` 加 `Integer latE6, Integer lngE6`）；Modify `backend/shop-core/src/main/java/ai/neargo/shop/community/port/CommunityQueryPortImpl.java:143-150`（`select` 加 `getLatE6,getLngE6`，构造加两参）；Modify `backend/shop-merchant/.../MerchantPortImpl.java` 与 `ReachRule.java` 里所有 `new CommunityRef(` 调用补两个 `null`（编译通过即可，T7 会重写）；Test `backend/shop-base/src/test/java/ai/neargo/shop/spi/reach/ConsumerProfileTest.java`。
**Test**
```java
@Test void 祖先码按2_4_6_9_12截取_只取不超过自身长度的() {
    assertThat(ConsumerProfile.ancestorsOf("440304001003")).containsExactly("44","4403","440304","440304001","440304001003");
    assertThat(ConsumerProfile.ancestorsOf("440304")).containsExactly("44","4403","440304");
    assertThat(ConsumerProfile.ancestorsOf(null)).isEmpty();
    assertThat(ConsumerProfile.ancestorsOf("44030")).as("非标长度：只取能截的").containsExactly("44","4403");
}
```
**Run**：`mvn -o -q test -pl shop-base -Dtest=ConsumerProfileTest` + `mvn -o -q compile -pl shop-app -am`。**Commit**：`feat(reach): ConsumerProfile 消费者画像 + CommunityRef 带坐标 + shop.reach 配置`

#### T5 `ReachMatchMapper` + `DbHitFinder` + `InMemoryHitFinder` + 等价测试

**Files**：Create `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/ReachMatchMapper.java`；Create `.../merchant/reach/StoreHits.java`、`HitFinder.java`、`DbHitFinder.java`、`InMemoryHitFinder.java`、`StoreItems.java`（`record StoreItems(List<MchServiceArea> areas, List<MchServiceAreaCell> cells)`）；Test `backend/shop-app/src/test/java/ai/neargo/shop/scenario/HitFinderParityTest.java`（需 Spring 上下文与 H2）。
**Mapper SQL**
```java
@Select("""
<script>
SELECT store_no AS storeNo, area_no AS areaNo, mode AS mode
FROM mch_service_area
WHERE deleted = 0 AND store_no IS NOT NULL
  AND ((mode = 'INCLUDE' AND status = 'ACTIVE') OR mode = 'EXCLUDE')
  <if test="storeNo != null"> AND store_no = #{storeNo}</if>
  AND (
    <choose>
      <when test="ancestors != null and ancestors.size() > 0">
        (level IN ('PROVINCE','CITY','DISTRICT','STREET','VILLAGE') AND ref_code IN
          <foreach collection="ancestors" item="a" open="(" separator="," close=")">#{a}</foreach>)
      </when>
      <otherwise>1 = 0</otherwise>
    </choose>
    <if test="communityNo != null"> OR (level = 'COMMUNITY' AND ref_code = #{communityNo})</if>
    <if test="parentNo != null">    OR (level = 'COMMUNITY' AND ref_code = #{parentNo})</if>
  )
</script>""")
List<AreaHitRow> areaHits(@Param("ancestors") List<String> ancestors, @Param("communityNo") String communityNo,
                          @Param("parentNo") String parentNo, @Param("storeNo") String storeNo);

@Select("""
<script>
SELECT store_no AS storeNo, area_no AS areaNo, mode AS mode, boundary AS boundary
FROM mch_service_area_cell
WHERE <choose><when test="tokens != null and tokens.size() > 0">cell_id IN
        <foreach collection="tokens" item="t" open="(" separator="," close=")">#{t}</foreach></when>
      <otherwise>1 = 0</otherwise></choose>
  <if test="storeNo != null"> AND store_no = #{storeNo}</if>
</script>""")
List<CellHitRow> cellHits(@Param("tokens") List<String> tokens, @Param("storeNo") String storeNo);
```
`AreaHitRow(storeNo, areaNo, mode)`、`CellHitRow(storeNo, areaNo, mode, boundary)` 为 record。`DbHitFinder.find` 把两组行按店聚成 `StoreHits`（cell 行：`boundary=0` 进 include/exclude，`=1` 进 boundaryInclude/boundaryExclude）。`InMemoryHitFinder.hitsFor(items, p)`：areas 按 level 做同样成员判断；cells 按 `cellTokens.contains(cell.cellId)`。
**Test（等价）**：种子：3 家店——A 框 `DISTRICT 440304`、B 点名 `CM001` + 排除 `CM001-B2`（楼栋）、C 一个多边形（经 `ServiceAreaCells.rebuild` 落网格，T9 之前先直接 insert 行）；画像 6 个（区内/区外/小区/楼栋/面内/面外）。断言 `DbHitFinder.find(p, null)` 与「对每店 `InMemoryHitFinder.hitsFor(itemsOf(store), p)`」逐店相等。
**Run**：`mvn -o -q test -pl shop-app -am -Dtest=HitFinderParityTest -Dsurefire.failIfNoSpecifiedTests=false`。**Commit**：`feat(reach): 命中查找——索引点查(DB) 与内存查找同语义，附等价测试`

#### T6 快照

**Files**：Create `.../merchant/reach/StoreMeta.java`、`ReachSnapshot.java`、`ReachSnapshotLoader.java`（从 `StoreReachLoader.routes()` 迁出路合成逻辑，签名不变）、`ReachSnapshotCache.java`；Modify `backend/shop-app/src/main/java/ai/neargo/shop/config/CacheConfig.java`（加常量 `REACH_SNAPSHOT = "reachSnapshot"` 并按现有方式注册）；Test `backend/shop-app/src/test/java/ai/neargo/shop/scenario/ReachSnapshotCacheTest.java`。
**Loader 语义**：ACTIVE 主体×ACTIVE 门店；`routes` 同现有；`unlimited` = 该店有 `level='UNLIMITED' AND mode='INCLUDE' AND status='ACTIVE'` 行；`hasAdminExclude` = 有 `mode='EXCLUDE' AND level IN (五级)`；`hasCommunityExclude` = `level='COMMUNITY'`；`hasPolygonExclude` = `level='POLYGON'`。
**Test**：两次 `cache.get()` 只触发一次 loader（用 `@SpyBean`/计数桩）；`cache.evict()` 后再 `get()` 重新加载；`replaceAreas` 保存后（T9 接线）下一次 `get()` 看到新门店元数据。
**Commit**：`feat(reach): 门店元数据快照（Caffeine）与失效`

#### T7 `ReachRule.decide/selectable` 重写 + `PolygonCache`

**Files**：Modify `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/reach/ReachRule.java`（删 `covers/matches/matchesAny/unlimitedWhenUnframed/reachable/namedCommunities`，保留 `Route`，新增 `decide`、`selectable`、`unlimited(StoreMeta)`）；Create `.../reach/PolygonCache.java`（Caffeine `areaNo → GeoPolygon`，miss 时读 `mch_service_area.geometry`；解析失败记 WARN 并缓存「不覆盖」）；Modify `backend/shop-merchant/src/test/java/ai/neargo/shop/merchant/reach/ReachRuleTest.java`（重写）。
**Test（纯单元，不起 Spring）**
```java
@Test void 排除先算_任一命中整店不可见() { ... excludeAreaNos={X} → decide=false 即便 include 命中 }
@Test void 没框范围的店对谁都不可见_不再隐式全平台() { meta(unlimited=false, routes=[EXPRESS all]); hits=empty → false }
@Test void 不限只对快递自送为真_自提为假() { meta(unlimited=true, routes=[STORE_PICKUP all]) → false; routes=[EXPRESS all] → true }
@Test void 子集路只认勾选项_不限不参与子集() { routes=[subset{a1}]; hits.include={a2} → false; ={a1} → true; unlimited=true 仍按子集 }
@Test void 边界cell要精判_内部cell直接算() { boundaryInclude={P}; polys.covers(P,p)=false → false; =true → true }
@Test void failClosed_有行政排除而无区划码_不可见() { meta(hasAdminExclude=true); p.ancestors=[] → false }
@Test void failClosed_有多边形排除而无坐标_不可见() { meta(hasPolygonExclude=true); p.coords=null → false }
@Test void 小区级排除无聚落号不排除() { meta(hasCommunityExclude=true, routes=[EXPRESS all]); hits.include={a} → true }
@Test void selectable_自提全部路通过排除后直接可选() { ... }
```
**Commit**：`feat(reach): ReachRule 改为唯一组合规则 decide/selectable——排除先算、fail-closed、显式不限、边界精判`

#### T8 `ReachMatcher` 门面 + 调用方迁移 + 删旧加载

**Files**：Create `.../merchant/reach/ReachMatcher.java`；Modify `backend/shop-base/src/main/java/ai/neargo/shop/spi/user/MerchantQueryPort.java`（加 `servingStores(ConsumerProfile)`、`serves(String,String,ConsumerProfile)`）；Modify `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantPortImpl.java:155-335`（六个方法改调门面；`servingStoresInRegion` 造只带祖先码的画像；旧 `servingStores(String)` 由 `communityRef` 造画像）；Modify `backend/shop-merchant/.../reach/StoreReachLoader.java`（删 `allServing()`、`build()` 的 includes/excludes 组装；`load/loadEach` 保留给 B 端回显）；Modify `backend/shop-core/src/main/java/ai/neargo/shop/product/service/impl/GoodsVisibility.java:157`（`serving(communityNo, regionCode, latE6, lngE6)` → `ConsumerProfileResolver` → `merchantPort.servingStores(profile)`；`providingStores`、`deliverable` 同步）；Create `backend/shop-core/src/main/java/ai/neargo/shop/community/service/ConsumerProfileResolver.java`。
**Test**：`StoreScopedVisibilityFlowTest` 新增：`纯定位无小区命中市级店`、`框区的店对区外不可见`、`框小区的店对别的小区不可见即便开了快递`（虹选粮油用例）、`全部但排除新疆_乌鲁木齐不可见_深圳可见`；现有可见性/下单/落店测试全绿。
**Run**：`mvn -o -q test -pl shop-app -am -Dtest='StoreScopedVisibilityFlowTest+StoreStockFlowTest+StoreFulfillmentFlowTest+MultiStoreOrderScopeTest' -Dsurefire.failIfNoSpecifiedTests=false`。
**Commit**：`feat(reach): ReachMatcher 门面；可见性/送达/落店/展开全部改走新判定；删 allServing 整表加载`

#### T9 保存路径：多边形、不限、网格、失效、ErrorCode

**Files**：Modify `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/MerchantStoreService.java:159`（`AreaCommand(level, refCode, mode, geometry)` + 保留两个旧构造）；Modify `.../service/impl/MerchantStoreServiceImpl.java:369-440`（`replaceAreas`：POLYGON/UNLIMITED 分支、`cellMapper.purgeByAreaNos(gone)`、`AfterCommit.run("reach-snapshot-evict", snapshotCache::evict)`）；Create `.../reach/ServiceAreaCells.java`（`rebuild(MchServiceArea row, GeoPolygon g)`、`rebuildAll()`）；Modify `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/StoreProfileVO.java:55`（`ServiceAreaVO` 加 `geometry`）；Modify `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java`（`SERVICE_AREA_POLYGON_INVALID(10473, "err.service_area.polygon_invalid")`——104xx 段已用到 10472，`ErrorCodeUniqueTest` 兜底）；Modify `backend/shop-app/src/main/resources/i18n/messages.properties`、`messages_en.properties`、`messages_ar.properties`（各加一行）；Modify `StoreFulfillmentServiceImpl.save`、`StoreAdminServiceImpl.create/setStatus`、`MerchantGovernServiceImpl` 门店/范围写方法末尾加同一句 `AfterCommit.run(...)`；Create 内部接口 `POST /internal/reach/rebuild-cells`（沿用现有 internal 控制器与鉴权登记方式）。
**replaceAreas 分支**
```java
if (MchServiceArea.LEVEL_POLYGON.equals(a.level())) {
    GeoPolygon g;
    try { g = GeoPolygon.parse(a.geometry(), props.getPolygonMaxVertices()); }
    catch (IllegalArgumentException e) { throw BizException.of(ErrorCode.SERVICE_AREA_POLYGON_INVALID, e.getMessage()); }
    refCode = g.fingerprint(); row.setGeometry(g.normalizedJson()); polygonToIndex.put(row, g);
} else if (MchServiceArea.LEVEL_UNLIMITED.equals(a.level())) {
    if (MchServiceArea.MODE_EXCLUDE.equals(a.mode())) throw BizException.of(ErrorCode.BAD_REQUEST);
    refCode = MchServiceArea.UNLIMITED_REF;
}
// ... insert 后：polygonToIndex.forEach(cells::rebuild);  gone 非空：cellMapper.purgeByAreaNos(gone)
```
**Test**：`ServiceAreaFlowTest` 新增：`保存多边形后有网格行且边界标记两类都有`、`同一多边形重复保存 area_no 不变`、`改几何后重算网格`、`删多边形网格一起删`、`非法多边形被拒并返回可读码`、`UNLIMITED 排除被拒`、`保存后快照失效`。
**Commit**：`feat(reach): 保存多边形/不限范围项——几何校验、S2 网格派生、级联清理、快照失效`

#### T10 V397 Java 回填 + 预检 SQL

**Files**：Create `backend/shop-app/src/main/java/db/migration/V397__backfill_unlimited.java`（照 `V181` 范式）；Create `scripts/reach/unlimited-backfill-preview.sql`（同一判据的只读 SQL，上线前对生产库算 N）；Test `backend/shop-app/src/test/java/ai/neargo/shop/scenario/UnlimitedBackfillTest.java`。
**迁移核心**
```java
@Override public void migrate(Context ctx) throws Exception {
    Connection c = ctx.getConnection(); int added = 0;
    try (ResultSet rs = c.createStatement().executeQuery(
        "SELECT s.store_no, s.entity_no, e.fulfillment_reach FROM mch_store s JOIN mch_entity e ON e.entity_no = s.entity_no " +
        "WHERE s.status = 'ACTIVE' AND e.status = 'ACTIVE' AND s.deleted = 0 AND e.deleted = 0")) {
        while (rs.next()) {
            String store = rs.getString(1), entity = rs.getString(2), reach = rs.getString(3);
            if (hasActiveInclude(c, store) || hasUnlimited(c, store) || !legacyUnlimited(c, store, reach)) continue;
            insertUnlimited(c, entity, store); added++;
        }
    }
    LOG.info("[V397] UNLIMITED 回填 {} 家门店", added);
}
/** 逐字复刻 StoreReachLoader.routes()：enabled=1 且 ops_locked<>1 的路；一条都没有 → 按主体 fulfillment_reach 兜底 */
private boolean legacyUnlimited(Connection c, String store, String reach) {
    List<String[]> routes = query(c, "SELECT channel, scope_mode FROM mch_fulfillment_channel WHERE store_no=? AND enabled=1 AND (ops_locked IS NULL OR ops_locked=0)", store);
    if (!routes.isEmpty()) return routes.stream().anyMatch(r -> "ALL".equals(r[1]) && ("EXPRESS".equals(r[0]) || "MERCHANT_DELIVERY".equals(r[0])));
    if (reach == null || "PICKUP".equals(reach)) return false;   // 自提：没框 = 谁也看不到
    return true;                                                 // SHIPPING→快递、其余→自送：没框 = 不限
}
```
`insertUnlimited` 用 `BizKey.next(BizKey.SERVICE_AREA)`，`created_by='V397_UNLIMITED_BACKFILL'`，`source='SELF'`。
**Test**：H2 里造四家店（①无范围+快递 ALL；②无范围+快递 SUBSET；③无范围+无路+主体 SHIPPING；④无范围+无路+主体 PICKUP），手动调 `migrate`，断言只有 ①③ 得到 UNLIMITED 行且再跑一次不重复。
**Commit**：`feat(reach): V397 回填显式 UNLIMITED——逐字复刻旧 routes() 兜底，幂等，附预检 SQL`

#### T11 查询接口带坐标

**Files**：Modify `backend/shop-core/src/main/java/ai/neargo/shop/product/service/GoodsService.java:90-98`（`GoodsQuery` 末尾加 `Integer latE6, Integer lngE6`，两个旧构造补 `null,null`；`deliverableTo(goodsNo, communityNo, latE6, lngE6)` 重载）；Modify `backend/shop-app/src/main/java/ai/neargo/shop/portal/mp/MpCatalogController.java:228-252,284-289`（三个端点加 `@RequestParam(required=false) Integer latE6, Integer lngE6`）；Modify `GoodsServiceImpl.list/promoted/detailForBuyer` 透传。
**Test**：`MpCatalogController` 场景测试：`只带坐标不带小区_命中多边形店`、`坐标越界视为无坐标`。
**Commit**：`feat(mp): 目录/推荐/详情接口接收 latE6/lngE6——纯定位也能按范围筛`

#### T12 旧测试迁到显式不限（消融）

**Files**：全量跑后把依赖「没框 + 开快递 = 全平台」的用例逐条改为显式加 `UNLIMITED` 行或框范围（预期集中在 `StoreScopedVisibilityFlowTest`、`StoreFulfillmentFlowTest`、`ServiceAreaFlowTest`、`GoodsSaleScopeFlowTest`、`MerchantSelfOperatedFlowTest`）；**不许改判定迁就测试**。
**Run**：在干净 HEAD 副本 `mvn -o -q test`，以 surefire 报告为准（`-q` 吞计数）。目标：全量 0 失败。
**Commit**：`test(reach): 依赖隐式不限的用例改为显式 UNLIMITED/框范围`

#### T13 B 端类型、词条、列表与省级

**Files**：Modify `packages/shared/src/utils/constants/index.ts:314`（`AREA_LEVEL` 加 `PROVINCE:"PROVINCE"`, `POLYGON:"POLYGON"`, `UNLIMITED:"UNLIMITED"`）；Modify `packages/shared/src/types/region.ts:276`（`ServiceArea` 加 `geometry?: string`）；Modify `packages/shared/src/utils/region.ts`、`packages/shared/src/mock/db.ts`、`c-app/src/api/mocks/community.ts`（新值跟上）；Modify `b-app/src/i18n/locale/zh-CN.ts`、`en.ts`、`ar.ts`（`store.polygonArea`「配送范围（{n} 个顶点）」、`store.drawArea`「画配送范围」、`store.unlimited`「全平台不限」、`store.unlimitedHint`「全平台消费者都能看到本店」、`store.noScopeHint`「尚未设置可见范围，消费者看不到本店」、`store.polygonInvalid`「范围无效：{reason}」、`store.level.PROVINCE`「省」）；Modify `b-app/src/pages/store-scope/index.vue:60-110,520-545`（列表项按 level 渲染：POLYGON 显示顶点数、UNLIMITED 显示开关；新增「画配送范围」入口跳 T14 新页；「不限」开关增删 `{level:'UNLIMITED', refCode:'*', mode:'INCLUDE'}`；顶部无范围提示）；Modify `b-app/src/components/biz/biz-region-picker.vue`（放开 PROVINCE 为可选叶）。
**Run**：`cd b-app && npx vue-tsc --noEmit`；`cd packages/shared && npx vitest run tests/i18n-parity.test.ts`。
**Commit**：`feat(b-app): 经营范围页支持多边形项/全平台不限/省级，三语词条`

#### T14 B 端画图页

**Files**：Create `b-app/src/pages/store-scope-polygon/index.vue`；Modify `b-app/src/pages.json:397`（在 `store-scope` 之后加 `{"path":"pages/store-scope-polygon/index","style":{"navigationBarTitleText":"画配送范围","app-plus":{"navigationStyle":"custom"}}}`）；Regenerate `docs/technical/design/ui-catalog.json`（`python3 scripts/gen-ui-catalog.py`）。
**页面要点**：独立页承载 `<map>`（App 端原生组件，不得放 fixed 弹层）；`:polygons="[{points, fillColor:'#2F80ED33', strokeColor:'#2F80ED', strokeWidth:2}]"`；`@tap` 取 `e.detail` 经纬度追加顶点（uni `<map>` 的 tap 不带坐标时用 `map.getCenterLocation` + 十字准星方案，以真机为准）；长按删末点；顶点 <3 时「完成」禁用；完成后 `uni.$emit('store-scope:polygon', geometryJson)` 回上一页并 `navigateBack`；上一页监听后追加 `{level:'POLYGON', refCode:'', mode:'INCLUDE', geometry}` 并调现有 `save()`。
**Run**：`npx vue-tsc --noEmit`；`python3 scripts/gen-ui-catalog.py --check`；真机验画图（记忆：模拟器不是真机）。
**Commit**：`feat(b-app): 画配送范围页（独立页承载原生地图）`

#### T15 C 端带坐标与文案

**Files**：Modify `c-app/src/api/http.ts:164` 与对应 `GoodsListQuery` 类型（加 `latE6?: number; lngE6?: number`）；Modify `c-app/src/pages/home/index.vue`、`category/index.vue`、`search/index.vue`、`groups/index.vue`、商品详情页（调用处从 `useLocationStore()` 取 `latE6/lngE6` 一并传）；Modify c-app 三语 locale（`goods.saleScope.unlimited`「不限地区」、`goods.saleScope.polygon`「配送范围内」）。
**Run**：`cd c-app && npx vue-tsc --noEmit`；H5 注入状态验（记忆：没后端验 uni H5）+ 真机。
**Commit**：`feat(c-app): 目录/推荐/详情带当前地址坐标；销售区域文案`

#### T16 登记面与生成物

**Files**：按 §2.11 清单逐项：`schema-lineage.test.ts` 登记 `mch_service_area_cell.area_no → mch_service_area.area_no`；`b-app/scripts/gen-openapi.mjs`、`c-app/scripts/gen-openapi.mjs` 重跑 + `RESPONSE_TYPES`；`node scripts/gen-glossary.mjs`、`node scripts/gen-table-inventory.mjs`、`node scripts/gen-erd.mjs`、`python3 backend/scripts/gen-test-schema.py`、`python3 scripts/gen-ui-catalog.py`、`node scripts/gen-doc-index.mjs`。
**Run**：`node scripts/check-generated-docs.mjs`（期望「✓ … 都是最新的」）；`cd packages/shared && npx vitest run`（不新增红账）；在干净 HEAD 副本跑 pre-push 整套的各闸门。
**Commit**：`docs(gen): 可见范围改造的登记与生成物`

#### T17 文档收尾

**Files**：本 TDD §6/§7 填实；ADR-034 状态改「已实施」；`docs/technical/README.md` 由生成器更新。
**Commit**：`docs(reach): TDD/ADR 收尾`

### 明确不做

- 不做数据库空间类型/空间索引；不引 ES/Redis GEO/Tile38（ADR-034）。
- 不做单品级范围、半径圆、多边形自交校验、多边形审核。
- 不删 `cmt_community.open` 及运营用法；只是匹配不再读它。
- 反向展开（B 端「覆盖哪儿」）候选仍为「开放小区 ∪ 点名小区」，只影响展示。
- 不做双引擎开关（两份规则并存就是分叉）；回滚 = 换回旧 jar（迁移全是加列/加表/加行，旧代码对未知 level 不命中）。

### 风险与对策

| 风险 | 对策 |
|---|---|
| 新依赖离线拉不到（本机/服务器 `mvn -o`） | T0 先灌 `~/.m2` 并验 fat jar；版本不存在立刻改 POM |
| 回填把不该全平台的店补成 UNLIMITED | 判据逐字复刻 `routes()`；上线前预检 SQL 得 N、迁移后计数 = N；`created_by` 可批量撤 |
| 旧测试依赖隐式不限 | 它们变红是 AC5 的消融证据；只改测试不改判定 |
| S2 覆盖参数不合适（cell 太多/太粗） | 参数化；`rebuildAll()` 内部接口可全量重算 |
| 原生 `<map>` 画图交互 | 独立页；真机验 |
| 虹选粮油当前故障 | 本方案消除这一类；它当前的成因仍需查生产三张表定位是哪一种，单独修数据 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 消融（撤掉实现必须红） |
|---|---|---|
| AC1 | `GeoPolygonTest`（5）、`S2CoverTest`（3）、`ReachDecideTest#boundaryCellsNeedExactCheck` | 去掉精判 → 红 |
| AC2 | `ConsumerProfileTest#ancestors`、`HitFinderParityTest#dbAndInMemoryAgree`、`StoreScopedVisibilityFlowTest#framedDistrictIsInvisibleOutsideIt` | **已消融验证**：`ancestorsOf` 改成不截（只给自身一级）→ 2 红 |
| AC3 | `HitFinderParityTest`（小区/楼栋画像） | — |
| AC4 | `StoreScopedVisibilityFlowTest#cityLevelStoreIsVisibleWithoutAnyCommunity` | **已消融验证**：在 `decide` 开头加回「消费者必须落在聚落里」→ 2 红 |
| AC5 | `ReachDecideTest#unframedStoreReachesNobody`、`#unlimitedOnlyForExpressAndDelivery` | 恢复隐式分支 → 红 |
| AC6 | `UnlimitedBackfillTest`（四家店只补两家、幂等）+ 生产预检 N 对账 | 删回填 → 红 |
| AC7 | `ServiceAreaPolygonFlowTest#savingPolygonDerivesCells`（同条断言「自助生效，不送审」） | — |
| AC8 | 现有下单/落店/送达/结算路选择测试全绿 | — |
| AC9 | `ReachSnapshotCacheTest#cachesAndEvicts`、`#unlimitedStoresReflectExplicitItem`、`#excludeFlagsPerDimension` | 去掉 evict → 红 |
| AC10/11 | `vue-tsc`、i18n-parity、真机 | — |
| AC12 | `ServiceAreaPolygonFlowTest#invalidPolygonIsRejected` | — |
| AC13 | `ReachDecideTest#failClosedWhenDimensionMissing`、`#communityExcludeDoesNotFailClosed` | 去掉前置判 → 红 |
| AC14 | `HitFinderParityTest#dbAndInMemoryAgree`（六种画像逐店逐项比对） | 任一查找改语义 → 红 |
| AC15 | `ServiceAreaPolygonFlowTest#sameGeometryKeepsAreaNo`、`#changingGeometryRebuildsAndPurgesOld`、`#removingPolygonPurgesCells` | 不清旧网格 → 红 |
| 性能 | 本机 1000 店×50 多边形 `/mp/goods` P95 < 200 ms | — |


> **这张表写错过一次，值得记下来**：第一版里 AC1/AC2/AC4/AC5/AC7/AC12/AC13/AC15 写的是
> `ReachRuleTest` / `ServiceAreaFlowTest` 这些**代码里并不存在**的类名与中文方法名 ——
> 实现时类名定成了 `ReachDecideTest` / `ServiceAreaPolygonFlowTest`，而表没跟着改。
> 更要紧的是 **AC2/AC4 那两条当时根本没有对应的用例**，只是表里写着像有。
> 对账三的全部价值在于「说的是真话」，一份写着不存在的用例名的表比没有表更坏：
> 读的人会以为验过了。两条用例已补齐（见上），并各做了一次消融确认它们真的在保护那件事。

## §6 对账二 · 设计 → 实现

| 设计（§2） | 落到哪儿 | 备注 |
|---|---|---|
| 显式 `UNLIMITED` 项取代隐式分支 | `MchServiceArea.UNLIMITED_REF`、`ReachRule.unlimited/unlimitedChannel` | 判据从「没有 INCLUDE + 快递/自送」换成「有一条显式 UNLIMITED」 |
| 存量自动迁移 | `V397__backfill_unlimited_service_area.java` | `area_no` 用 `SVAMIG` + SHA-256(storeNo) 前 16 位：**同一个库跑第二遍不撞唯一键**（第一版用自增计数器，测试当场抓到） |
| 祖先码 `IN` 取代前缀匹配 | `ConsumerProfile.ancestorsOf`（2/4/6/9/12 位）、`ReachMatchMapper.areaHits` | 走得上既有的 `idx_service_area_ref(level, ref_code)` |
| 多边形存顶点、派生 S2 网格 | `mch_service_area.geometry`、`mch_service_area_cell`、`S2Cover`、`ServiceAreaCells` | 网格是**派生**的，`geometry` 在就能重建 |
| 边界精判 | `GeoPolygon.covers`（bbox 预筛 + `PreparedGeometry`） | 内部 cell 直接算命中，边界 cell 才下到 JTS |
| 自相交不拒、规范化 | `GeoPolygon` 用 `GeometryFixer` | **不能用 `buffer(0)`**：它按绕向只留领结的一半，商家画的半片被静默丢掉而界面显示「已保存」 |
| 快照 + 失效 | `ReachSnapshot(Loader\|Cache)`、`ReachMatchMapper.snapshotVersion` | 失效靠 `COUNT(*) + SUM(version)` 探针，**不是**在 ~20 个事务方法里手写 evict —— 那种漏一处就是静默错 |
| 区域画像（模糊定位只到区县） | `ConsumerProfile.ofArea`、`MerchantPortImpl.servingStoresInRegion`、`GoodsVisibility.serving` 分支 | T11 时我自己引入过这个缺陷：T8 让 `serving` 直接走点画像，于是「只框了嘉逸花园」的商家在「福田区」下不可见 |
| 两套查找等价 | `HitFinder` / `DbHitFinder` / `InMemoryHitFinder` + `HitFinderParityTest` | 六种画像逐店逐项比对 |
| B 端范围页 / 画图页 | `b-app/src/pages/store-scope`、`store-scope-polygon` | 画图独立成页（原生 `<map>` 在 fixed 弹层里整棵子树不渲染）、准星取中心点（`@tap` 两端不一致） |
| C 端传坐标 | `c-app` 首页/分类/详情 + `requests.ts` 三个 query 类型 | 契约那一步我漏过一次：`satisfies` 对展开不做多余属性检查，字段发得出去而 spec 里没有 |

**没做的**（都在上面各处写了理由）：多边形店的「配送范围内」文案（买家要的是 `deliverable`，已接上）；
`GoodsDetailQuery` 之外的其余端点 query 登记（不在本次范围）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-10 | 需求澄清（多边形/门店级/显式不限/不审/去锚点）；A vs B 比较；用户要求按 ES/SQL/中间件调研比较；调研后定「祖先码 IN + S2 网格 + JTS 边界精判 + 快照」，否决 MySQL 空间/ES/聚合 SQL；用户确认结论，写本 TDD §2.14 实施计划 |
| 2026-10-10 | 用户指出「测不到不是决策依据」——撤回「H2 无 JTS 测不到」这条否决理由（`scripts/test-on-mysql.sh` 跑真 MySQL 9.7），改按真实代价重新论证 |
| 2026-10-10 | 后端 T0–T12 落地：2639 个测试，本次改动 0 失败。遗留两条 `ArchitectureTest`（`message→trade`、`link` 包未登记）属 ship-notify 那条线，挡所有人，**不由我修也不绕过** |
| 2026-10-10 | B 端 T13/T14、C 端 T15 落地；生成物 T16：24 个生成器全部最新、界面清单 277 屏。顺手补了 `track: "TrackView"`（别人漏的登记让三份 spec 一份都生不出来） |
| 2026-10-10 | T17：把 §5 里写错的用例名改成真的，**补齐 AC2/AC4 两条此前只在表里存在的用例**，各做一次消融（加回聚落前置条件 / 祖先码不截）确认变红后还原 |

## §8 还没做完的

| 项 | 说明 |
|---|---|
| 真机验画图页 | `store-scope-polygon` 用原生 `<map>`，本仓库的规矩是「模拟器不是真机」 |
| 推送 | 被 ship-notify 那条线的两条 `ArchitectureTest` 挡着；不用 `--no-verify` |
| 虹选粮油**当前**那一例 | 本方案消除这一类，但它现在是四种成因里的哪一种，仍要查生产 `mch_store`/`mch_service_area`/`mch_fulfillment_channel` 才知道，单独修数据 |
| 性能那一行 | §5 里「本机 1000 店×50 多边形 P95 < 200 ms」**还没实测**，不要当成已验 |
| 「只给坐标」那一腿没有测试覆盖 | 坐标反解成区划码走 `MasterDataPort.resolveRegion`，依赖 sys_region 带坐标或外部地图 —— 测试库两样都没有，反解恒为空。只能在生产上按真路径量（2026-10-10 量过：修复前纯坐标 1 件、带区划码 11 件；修复后两者一致）。要补覆盖得先给测试库种一份带坐标的区划 |

## §9 上线与生产实测（2026-10-10）

V396/V397 随 16:36 那次发布已应用。**回填对账成立**：`created_by='V397_UNLIMITED_BACKFILL'` 的行 1 条 = 上线前对照量 1 条。

虹选粮油那一例的成因查清了，是四种里的**第四种**：粮油自己框了嘉逸花园，而同主体的
「虹选鲜果」一条纳入项都没有 + 开着快递 —— 旧规则下它就是「全平台」，买家在别处看到的是它的那条路。
V397 已把这条隐式规则落成显式 UNLIMITED 行（范围页上看得见、改得动）。

上线后按端上真实路径实测，抓出三处只有在生产上才看得见的缺陷，都已修并复测：

| # | 缺陷 | 症状 | 修在哪 |
|---|---|---|---|
| 1 | `visibleGoodsNos` 收下坐标又少传两个参数 | 整条坐标链路是死的；只给坐标 = 不筛 = 全平台 | `GoodsServiceImpl` |
| 2 | `saleScope` 按主体并集算，而商品只属于一家店 | 面粉只在嘉逸花园可见，详情页却写「不限地区」 | `saleScope(merchantNo, storeNo)` |
| 3 | `serving()` 按「有没有坐标」分流 | 端上真实请求（区划码+坐标）掉进单点分支，区域展开被顶掉，首页 11 件→1 件 | 分流改为只看聚落号；坐标并进区域画像 |

第 3 条是**我今天上线之后才引入的回退**，上线前端上根本不传坐标。三条各有一条从出错那条路进的用例，并各做过消融。

最终生产实测（`38a4a1a33`）：

| 请求 | total | 五得利（只框嘉逸花园） |
|---|---|---|
| 只有区划码 440309 | 11 | 4 |
| 区划码 + 坐标（端上真实请求） | 11 | 4 |
| 区划码 + 区内另一点 | 11 | 4 |
| 聚落号 | 11 | 4 |
| 广州：区划码 + 坐标 | 1 | 0 |
| 详情页销售范围（五得利） | — | `unlimited=false, areaNames=[嘉逸花园], deliverable=true` |
| 详情页销售范围（柿子，归属显式不限的店） | — | `unlimited=true, 除新疆/西藏` |
