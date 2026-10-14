# TDD-新品播店级行

状态：已实现（随删池重构 7621ee956 落地）
关联需求：无独立 PRD —— 来自 2026-10-07 线上实测的缺陷（店主报「切到虹选粮油，柿子还在」）。
验收标准写在 §0，由本文件锚定。
创建：2026-10-07 · 最后更新：2026-10-07（删池后落点由 syncPool 改为 onSaleSideEffects）

## §0 对账一 · 需求 → 设计

缺陷原文（店主）：「在 b 端 app 上，切换到虹选粮油，柿子还存在。」

线上实证（2026-10-07，主体 `M202609161449440002055`，四家门店）：

- 该主体 16 件货，**只有柿子（10-05 建）在 `prd_store_goods` 里一行都没有**，其余 15 件四店都有行；
- 以粮油店身份调 `/biz/goods` 回三条：柿子 + 两件金龙鱼，柿子的 `storeOnSale` 是 `null`；
- 柿子的社区池在**三家店**各 23656 行（共 70968）——而粮油店的经营类目只有 `CAT710`，
  也就是说**买家在粮油店的服务范围里真的买得到柿子**。

| AC | 一句话 | 落点 |
|---|---|---|
| AC1 | 多门店主体新建的货上架后，`prd_store_goods` 里必须有行，不能是零行 | `MerchantGoodsServiceImpl#onSaleSideEffects` → 新增 `seedStoreRowsIfMissing` |
| AC2 | 播种的在架态按**门店经营类目**判，不经营这一类的店播 `on_sale=0` | 复用既有的 `sellsHere(storeNo, categoryNo)` |
| AC3 | 单店主体（或没有门店上下文）行为逐字不变——零行仍是「跟随主体」 | `seedStoreRowsIfMissing` 的 `storeNos().size() <= 1` 早退 |
| AC4 | 买家侧：不经营该类目的门店，其社区池里不该出现这件货 | `storesSelling` 不再走「零行 → 全部 ACTIVE 门店」那一支 |

**孤立项**：无。四条 AC 都有落点；没有挂不上 AC 的设计条目。

## §1 现状与影响面

### 零行为什么会产生

店级行只有一个写入点：`setStoreOnSale`，而它**只被 `toggle` 调用**。
让一件货变成在架的另外三条路一条都不播行：

| 路径 | 位置 | 做了什么 |
|---|---|---|
| 免审直通 | `save()` 的 `!stayDraft && !auditRequired()` 分支 | `setOnSale(true)` + `onSaleSideEffects` |
| 换版收尾 | `save()` 的 `PUBLISHING != null` 分支 | 保持原在售态 + `onSaleSideEffects` |
| 过审兑现 | `audit()` 的 `pendingOnSale` 分支 | `setOnSale(true)` + `onSaleSideEffects` |

所以**多门店改造之后新建的每一件货都是零行**，直到有人手动点一次上下架。
9-30 那次修的是「播种时别播成在架」，没修「一开始根本不播」。

### 零行被三个读者当成「跟随主体」

| 读者 | 位置 | 后果 |
|---|---|---|
| `excludeOffSaleHere` | B 端「全部」页签 | 零行的货不在 `managed` 集合里 → 每家店都列它 |
| `applyStoreScopedSale` | B 端「在售/已下架」页签 | 同上，走 `notIn(managed)` 那一支 |
| `storesSelling` | 社区池 → C 端可见性 | 返回**全部 ACTIVE 门店** → 池行铺到不经营该类目的店 |

前两个是显示问题，第三个是买家真能买到。

### 可直接复用

- `sellsHere(storeNo, categoryNo)` —— 门店经营类目判据，与上架闸 `requireInStore` 同一条；
- `setStoreOnSale` 里那段播种逻辑（逐店排除已有行、撞唯一键当「别人刚播过」）——
  形状照搬，不另写一套。

### 会被改到的

- 多门店主体的建品 / 过审 / 免审直通：之后会多出 N 条 `prd_store_goods` 行（N = 门店数）。
- `storesSelling` 对这些货不再回落「全部 ACTIVE 门店」，因为它们不再是零行。

### 明确不受影响的

- **单店主体**：`storeNos().size() <= 1` 早退，一行都不播，三个读者的行为逐字不变。
- **存量货**：线上当前「多门店主体 + 零店级行」的商品数是 **0**（10-07 查过，柿子那条已在
  排查过程中被一次真实下架补上了行）。所以这次改动对现有数据没有任何即时影响，它防的是下一件新货。
- 审核态（`PENDING` / `REJECTED` / `DRAFT`）：播种只发生在 `onSaleSideEffects(g, true)`，
  未上架的货不播。

## §2 方案

### 契约变更

- 端点：无
- 库表 / 字段 / 迁移号：**无**（只往既有的 `prd_store_goods` 写行，不改结构）
- 权限码：无
- i18n 词条：无
- 配置项：无

> 本来还打算给 `prd_community_pool` 加 `idx(goods_no, store_no)`，**实测后撤回**：
> 真库副本（536168 行）上暖缓存稳态只快 0.02 秒（0.158 → 0.141），而优化器 cost
> 说该快 9 倍。整张表在 buffer pool 里，全表扫就是一次顺序读；多一个二级索引反而
> 让每次上下架的两万多行写入各多维护一次。详见本次会话的量测记录。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/shop-core/.../MerchantGoodsServiceImpl.java` | `onSaleSideEffects(g, onSale)` 的 `if (onSale)` 分支调 `seedStoreRowsIfMissing(g)` |
| 新增 | 同上 | `private void seedStoreRowsIfMissing(PrdGoods g)` |
| 修改 | `backend/shop-app/src/test/.../StoreScopedVisibilityFlowTest.java` | 新增 AC1/AC2/AC4 的用例 |

### 关键接口

```java
/**
 * 多门店主体的货，第一次上架时把店级行播齐 —— 零行只应属于「主体级时代」的存量商家。
 * 判据与上架闸 requireInStore 同一条（sellsHere）：不经营这一类的店播 on_sale=0。
 */
private void seedStoreRowsIfMissing(PrdGoods g);
```

挂在 `onSaleSideEffects` 而不是三个入口上：它是十处上下架的唯一汇聚点（那段注释本来就这么写的），
逐个入口去加必漏一个，而漏掉的那个会静默地产生一件串店的货。

## §5 对账三 · 实现 → 需求（测试）

两条用例，各守一个方向（另一会话在干净 HEAD 副本 + 删池改动上跑过，后端全量 2432 条 0 红）：

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 + AC2 + AC4 | `StoreScopedVisibilityFlowTest#newGoodsOfMultiStoreMerchantGetsStoreRows` | ✅ 绿 | 清空 `seedStoreRowsIfMissing` 方法体 → 红 ✅ |
| AC3 | `StoreScopedVisibilityFlowTest#singleStoreMerchantStillHasNoStoreRows` | ✅ 绿 | 去掉方法开头 `stores.size() <= 1 return` 那一行 → 红 ✅ |

**消融那一栏按「各守一边」校准过**（2026-10-07，另一会话实测）：
- 清空方法体只会让 AC1 那条红；AC3 仍绿 —— 因为单店在方法开头就 `return`，清不清方法体都走不到下面。
- AC3 守的是**反方向**：去掉 `stores.size() <= 1 return`，单店商家会被误播店级行，这条才红。
- 两条合起来：AC1「多店必须播」、AC3「单店必须不播」，各挡一边，都有效。

三条用例(含 AC1/AC2/AC4 合并的断言)写法与实现模型无关——只查 `prd_store_goods` 与 `/biz/goods` 列表，不碰已删的社区池，所以删池之后照样成立。

## §6 对账二 · 设计 → 实现

本功能（`seedStoreRowsIfMissing` + 两条用例）随删池重构 `7621ee956` 一同提交
（提交说明里写明了「含另一会话的店级播种」）。与 §2 模块设计的差异：

| 差异 | 说明 |
|---|---|
| 落点 `syncPool` → `onSaleSideEffects` | 删池把 `syncPool`（还要重写社区池）换成只发两条事件的 `onSaleSideEffects`，它是删池后十处上下架新的唯一汇聚点。`seedStoreRowsIfMissing` 的逻辑一行未变，只是挂载点改名、调用条件由另一会话补成 `if (onSale)`。 |
| 社区池相关的 AC4 断言 | 原写「买家侧不该出现这件货」走 `/mp/goods`；删池后买家侧改由 `GoodsVisibility` 查询时现算，断言走的是同一个 `/mp/goods` 对外行为，不受实现换法影响，照常成立。 |
