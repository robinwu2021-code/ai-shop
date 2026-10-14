# TDD-区划反查 N+1

状态：**已实现**（2026-10-08）
档位：**0**（纯缺陷修复，契约一项不动：端点签名、库表、权限码、i18n、配置项、对外 JSON 全不变）
　　　—— 本来按「要加分页」声明成 1 档，根因查清后发现不需要动契约，已更正。
　　　0 档本不必写文档，这份是为了把根因留档（它预言过自己，见 §1）。
关联：无 PRD。来源是 2026-10-08 上线的慢日志当天抓到的第一条
创建：2026-10-08 · 最后更新：2026-10-08

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | `regionNames(codes)` 一次查完，不再逐个走祖先链 | `RegionService` 加批量取行方法；`MasterDataPortImpl#regionNames` 改用它 |
| AC2 | `regionRural(codes)` 同上，且与 AC1 共用那一次查询 | `MasterDataPortImpl#regionRural` |
| AC3 | 返回值与改前**逐字相同**：同样的名字、同样的 rural 判定、查不到的码同样不进 map | 两个方法的单元测试对拍 |
| AC4 | 判据是**查询次数**不是毫秒 —— N 个码只准查 1 次 | 见 §5 的说明 |

**孤立项**

- 没落点的 AC：无。
- 挂不上 AC 的设计：无。
- **本轮不做但同病**：`BizRegionController:61` 也在 stream 里逐个 `regionService.path(c)`。
  同一个毛病、不同的调用点，单独处理（见 §2「明确不做」）。

## §1 现状与根因

### 它是慢日志上线当天抓到的第一条

2026-10-08 慢日志上线，一小时内的生产实测：

| 接口 | 次数 | p50 | max |
|---|---|---|---|
| `GET /biz/communities` | 2 | 76502ms | **77394ms** |

店主点一下「设经营范围」，等 **77 秒**。

### 根因：一个伪装成批量的 N+1

`/biz/communities` → `communityService.all()` → `nearby(null, null)`。
`located == false` 时**没有任何过滤**，`communities` 就是全部 23657 条 OPEN 聚落，
然后：

```java
Map<String, String>  originNames = masterDataPort.regionNames(originCodes);   // 23657 个码
Map<String, Boolean> originRural = masterDataPort.regionRural(originCodes);   // 同样 23657 个
```

两个方法**签名是批量的，实现是逐个**：

```java
for (String code : regionCodes) {
    var path = regionService.path(code);          // ← 每个码一次
    out.put(code, path.get(path.size() - 1).name());
}
```

而 `path()` 自己又是个循环：`find(code)` 逐级向上，`find` 是裸 `selectOne`、**无缓存**。
深圳的区划码 9 位（街道级），走到省是 **4 层**。

```
23657 个码 × 2 个方法 × 4 层 ≈ 189,000 次 selectOne
× 约 0.4ms ≈ 76 秒          ← 实测 75.5 秒，对得上
```

### 还有一处纯浪费

两个方法走完整条祖先链、反转，然后只取 `path.get(size - 1)` ——
**那就是 code 自己那一行**。祖先全部查了又全部丢掉。
而 `name` 与 `rural` 都是 `sys_region` 上的普通列（`toVOs` 只是直接取），
所以一条 `IN` 查询就能拿全。

### 这段代码预言过今天

`nearby()` 里那段注释写着（2026-09-18）：

> 线上实测：这个端点要 8.8 秒…原来的顺序是「全读 → 富化 → 过滤」：龙华开城之后
> OPEN 聚落有 2783 条，下面那两句 `masterDataPort.regionNames/regionRural` 就拿着
> 2783 个 origin_code 去 62 万行的区划表里反查… **表再大一个量级时再谈下推**

当时的修法只对**带坐标**的路径有效（先按半径筛再富化）。
而 `all()` 走的是 `nearby(null, null)` —— 那条路**没有筛**，被原样留下了。
聚落从 2783 涨到 23657（正好一个量级），8.8 秒就成了 75 秒。

**两道修法之间的缝**：修的人看的是「附近」这条路径，没看另一条进同一个方法的路。

### 为什么任何测试都抓不到它

H2 里的测试数据只有十几条聚落。189,000 次查询的那个乘数在测试里是 **10 × 2 × 4 = 80 次**，
跑 5 毫秒。**代码一行没错、所有测试全绿、生产 75 秒。**
这正是「测试环境绿不等于生产快」的标本 —— 它只能被生产的慢日志抓到。

### 影响面

`regionNames` / `regionRural` 共 8 个调用点（`CommunityServiceImpl` 6 处、
`MerchantPortImpl` 2 处）。**都在 Port 背后，调用方一行不用改**，且都跟着变快。

## §2 方案

### 契约变更

**一项都没有。** 端点签名、库表、权限码、i18n、配置项、对外 JSON 全不变 ——
同样的入参给出同样的出参，只是不再查 189,000 次。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `RegionService#rowsOf(Collection<String>)` | 一条 `IN` 查询，返回 `code → RegionVO`。**一个方法供两处用**（先复用，不各写一份） |
| 修改 | `platform/impl/RegionServiceImpl` | 实现上面那个 |
| 修改 | `platform/port/MasterDataPortImpl#regionNames` | 改用 `rowsOf`，不再循环 `path()` |
| 修改 | `platform/port/MasterDataPortImpl#regionRural` | 同上 |
| 新增 | `platform/RegionBatchLookupTest` | 判据见 §5 |

**为什么新方法加在 `RegionService` 而不是让 Port 直接碰 mapper**：
`ArchitectureTest` 管着「Port 只在 spi」「不许越过 Service 碰 Mapper」。

### 明确不做

- **分页 / 改端点签名**：N+1 修完之后 23657 条的组装还剩多少，要**修完再量**。
  先修根因，不要在不知道剩余成本的情况下改契约（改了就要动两端调用方）。
- **把 regionCode 下推到 SQL**：`all(regionCode)` 现在是「先全量 `all()` 再内存筛」，
  确实也该下推。但实测带 regionCode 是 75.5s、不带是 79s —— **下推只省 4 秒**，
  因为成本在富化不在筛选。修完 N+1 再看它值不值得。
- **给 `find()` 加缓存**：62 万行的区划表不适合整表缓存，而批量查完全不需要缓存。
  缓存会引入失效问题去换一个本来就不存在的问题。
- **`BizRegionController:61` 的同病**：同一个毛病、不同调用点，本轮不碰。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `RegionBatchLookupTest#regionNamesQueriesOnce` | ✅ | 改回循环 `path()` → **精准红** ✅ |
| AC2 | `RegionBatchLookupTest#regionRuralQueriesOnce` | ✅ | 未单独消融（见偏差说明 3） |
| AC3 | `#missingCodesStayOut` · `#emptyInputDoesNotQuery` · `#blankCodesDoNotBreakOrAppear` | ✅ | 同 AC1 那次消融一并变红 ✅ |

```
RegionBatchLookupTest: Tests run: 5, Failures: 0   BUILD SUCCESS（MVN_EXIT=0）

消融（regionNames 改回逐个 path()）：Failures: 3
  regionNamesQueriesOnce / missingCodesStayOut / blankCodesDoNotBreakOrAppear
  —— regionRuralQueriesOnce 保持绿是对的：那条路没被消融
还原后 5/5 绿

全量（干净 HEAD 副本，只带我这 4 个文件）：
  Tests run: 2497, Failures: 0, Errors: 0   BUILD SUCCESS
```

**为什么要在干净副本里跑全量**：工作区当时混着并行会话大量在建改动
（`BizRegionController` / `ScopeTextResolver` / 他们在 `GoodsVisionPort` 上叠的新东西）。
直接在工作区跑，`ArchitectureTest#noFullyQualifiedReferences` 红 1 条，
点名的是**他们的** `BizRegionController`。只带我的 4 个文件进干净副本，2497 跑 0 失败。

**AC4 的判据是查询次数，不是毫秒** —— 这一条要单独说。

用毫秒当断言在这个仓库是行不通的：同一个测试类一小时内跑 7 次，
耗时在 13.27–20.88 秒之间（1.57 倍），因为这是个十个并行会话共用的目录。
而**查询次数是确定值**：N 个码就是 1 次，跑一百遍都一样，与机器、与数据量都无关。

而且次数恰好是这个缺陷的**本体** —— 它慢不是因为某行代码慢，是因为多查了 189,000 次。
量次数就是量根因，量毫秒只是量症状。

## §6 对账二 · 设计 → 实现（实现完再填）

```
 .../ai/neargo/shop/platform/RegionService.java     |  23 ++++
 .../shop/platform/impl/RegionServiceImpl.java      |  40 +++++++
 .../shop/platform/port/MasterDataPortImpl.java     |  34 +++---
 .../shop/platform/RegionBatchLookupTest.java       | 120 +++++++++++++++++++++
 4 files changed, 199 insertions(+), 18 deletions(-)
```

与 §2 模块设计逐行对得上（新方法 + 实现 + 两处改用 + 一个测试类）。

### 偏差说明

**1. 新方法返回窄记录 `RegionBrief`，不返回 `RegionVO`。**

§2 原写的是「返回 `code → RegionVO`」。落地时发现 `RegionVO` 有 13 个字段，
其中 `hasChild` 要靠一次「哪些码还有下级」的查询才能填 —— 而名字与城乡标记用不上它。
为了一个用不到的字段多查一次，正是本 TDD 要修的那类浪费。
改成 `record RegionBrief(String name, boolean rural)`。
这也与 `pathNames` 的既有取舍一致（它同样刻意绕开 `toVOs`）。

**2. 发现仓库里已经有过同一次修复，但没修到共用的 Port。**

`RegionService#pathNames` 的 javadoc 写着：「逐个调就是 `path()` 的逐级查库 × 两万多
≈ 十万次往返（实测让 `/ops/coverage/distribution` 卡死 30 秒以上）。这里按层级分批 `IN`」。

**同一个病、同一套解法，当时只落在那一个调用点上**，`MasterDataPortImpl` 里这两段
原封不动留着。于是 `/ops/coverage/distribution` 快了，`/biz/communities` 没有 ——
而它们富化用的是同一份数据。这和 §1 里「只修了带坐标那条路」是同一道缝的两次出现。

**3. `regionRural` 没有单独消融。**

AC1 的那次消融（只把 `regionNames` 改回循环）让 3 条变红，而
`regionRuralQueriesOnce` 保持绿 —— 这是对的，它那条路没被动。
要单独证明 AC2，得再做一次只改 `regionRural` 的消融。没做，记在这里。
两段实现逐字同形，风险可接受。

**4. 一条测试原本钉错了对象。**

`blankCodesAreSkipped` 原本断言「Port 调 `byCodes` 之前先把空白滤掉」。
但空白过滤**从 Port 挪进了 `byCodes`**，于是那条断言钉的是实现细节、
而且在 mock 下直接 NPE。改成验契约结果（空白不出现在返回里、也不让调用炸），
重构时才不会被它挡住。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-08 | 草稿，待确认 |
| 2026-10-08 | 用户确认，开始实现 |
| 2026-10-08 | 已实现。`RegionBatchLookupTest` 5/5 绿、消融精准红；干净副本全量 2497 跑 0 失败。<br>闸门：`mvn -pl shop-app -am test`（扫 shop-app + 其依赖的全部测试源码）。<br>**线上效果待部署后用慢日志复量** —— 这正是本修复的验收方式。 |
