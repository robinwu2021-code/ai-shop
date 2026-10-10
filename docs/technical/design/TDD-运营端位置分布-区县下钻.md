# TDD-运营端位置分布：区县概览 + 聚落下钻

状态：已实现（2026-10-08）
关联需求：[PRD-位置与经营范围](../../requirements/PRD-位置与经营范围.md) O11 / O12 / O13、§8.3
创建：2026-10-08

> 档位：1 —— 改运营端 `/ops/coverage/distribution` 的**返回结构**、新增一个区县下钻端点、
> 更新 PRD §8.3 一条过时口径（商品数不再来自已删的社区池）。不建表、不碰域边界、可回退。

## §0 为什么改（需求对账的结论）

O11/O12/O13 要的是**聚合分析**：「哪些聚落有人、哪些有商家、供需在哪儿对不上」，§4.2 对运营明确是「聚合口径」。
而现接口 `/ops/coverage/distribution` 把**每个开放聚落逐行全量**返回（线上 23656 行 / 5.3MB），
再被「一个开了全国快递的商家」放大成「几乎每个聚落都 1 商家 / 0 买家」——99.95% 是噪声行。
这偏离了需求，也是那屏端到端 7s 的根因（服务端算只 ~1.3s，其余全是传 5.3MB）。

**改回需求要的形态：区县概览（默认） → 点开某区县才拉它的聚落明细。** 两万多聚落不可能平铺，
概览只能按区县汇总；而招商要落到具体小区，所以保留聚落粒度在下钻里。一个下钻串起 O11/O12（概览）与 O13（缺口 + 招商清单）。

## §1 对账一 · 需求 → 设计

| 需求 | 落点 |
|---|---|
| O10 坐标健康度（算不了的四格） | `DistributionVO.unattributable`，**不变** |
| O11 买家位置分布（哪些聚落有人、多少） | 区县行 `RegionRow.buyerCount` / `buyerCommunityCount` |
| O12 商家覆盖分布（哪些聚落有商家、几家） | 区县行 `RegionRow.merchantCommunityCount` |
| O13 供需缺口（有人没商家 / 有商家没人 / 空） | 区县行各桶计数 + 全局 `totals` 四桶 + 全局 `supplyGaps`（招商清单，可行动到小区） |
| O13 下钻到具体聚落 | 新端点 `GET /ops/coverage/distribution/communities?regionCode=`（限一个区县，行数被区县框住） |
| §8.3 商品数口径 | **改**：社区池已删，改为现算（`GoodsVisibility` / `SupplyStatsPort`）；PRD §8.3 同步更新 |

孤立项：无。

## §2 方案

### 新契约

```
GET /ops/coverage/distribution            （权限 community:read，不变）
DistributionVO {
  regions: RegionRow[]          // 有开放聚落的区县，一区县一行（今天就深圳几个区 + 运城，个位数）
  supplyGaps: DistributionRow[] // 招商清单：有买家、无商家覆盖的聚落（全局、小集合；可行动）
  totals: Totals                // 全局四桶计数 + 买家总数
  unattributable: Unattributable// O10，不变
}
RegionRow(regionCode, regionName, communityCount,
          buyerCount, buyerCommunityCount, merchantCommunityCount,
          supplyGapCount, demandGapCount, emptyCount)
Totals(communities, buyers, okCount, supplyGapCount, demandGapCount, emptyCount)

GET /ops/coverage/distribution/communities?regionCode=xxx&page=1&size=200   （权限 community:read）
CommunityPage {                 // 字段名与 ops-web Page<T> 对齐，端上直接喂 PagedTable
  records: DistributionRow[]     // 当前页的聚落明细（复用现有 DistributionRow），按买家数降序
  total:   long                  // 该区县开放聚落总数（不是总页数）
  page, size: int
}
```

> **下钻为什么也要分页**（2026-10-08 补）：原设计假设区县「几百级」。线上实测不成立 ——
> 深圳的区县是估价扫出来的，宝安区一个区 **6367** 个聚落、且几乎全空（0 买家 0 商家）。
> 一次全量返回就是把这次要消灭的噪声缩到一个区县里重演（1.5MB / ~2s）。改成分页（接口
> 默认每页 200、上限 500；**端上用库件约定的 50**——`PAGE_SIZES=[10,20,50]`，>50 一屏也扫不完），
> 一页只建行、只查区县名、只序列化一页，恒 < 1s。买家数不在库里（靠坐标现算），
> 要「有动静的排前面」就得先对全区县算一遍买家数再切页 —— 都是内存 map 查，便宜。

- `DistributionRow` / `Unattributable`：字段不变，复用。
- **区县归并**：聚落的 `region_code` 取国标**区县前缀**（6 位）归组；区县名用 `MasterDataPort.regionPathNames` 批量取（已有，亚秒）。码短于 6 位或查不到名的，用码本身兜底。
- **供需分类**（与现 ops-web `classify` 同口径，挪到服务端）：
  买家>0 ∧ 商家=0 → 缺供给(supply)；商家>0 ∧ 买家=0 → 缺需求(demand)；皆 0 → 空(empty)；皆>0 → ok。
- **商家数口径**：`merchantCommunityCount` = 该区县里「有商家覆盖」的聚落数；聚落级「有没有商家」来自 `SupplyStatsPort.byCommunity()`（现算，`merchantCount>0`）。**不再读社区池**。
- 行数据来源一份（`SupplyStatsPort.byCommunity()` + 买家归属 `innermostNos`），区县汇总与下钻共用，避免两处口径分叉（§8.3 的警告）。

### 为什么默认不返回 demand 明细

今天 `supplyGap`（有人没商家）≈ 0（一个全国快递商家覆盖所有聚落），`demand` ≈ 23645（饱和噪声）。
默认页把 demand 收成区县行里的一个计数；要看具体哪些，点区县走下钻（被区县框住，几百行级）。
`supplyGaps`（将来商家不再全国覆盖时才有内容）直接给全局小清单——那才是招商要的。

### 模块设计

| 动作 | 路径 |
|---|---|
| 改 | `CommunityAdminService.DistributionVO`（加 regions/supplyGaps/totals；DistributionRow/Unattributable 不变） |
| 改 | `CommunityAdminServiceImpl#distribution`：聚落级事实表（买家/商家/货）→ 按区县汇总 + 抽 supplyGaps + totals |
| 新增 | `CommunityAdminService#communitiesInRegion(regionCode)` + impl（限区县前缀的聚落明细） |
| 新增 | `OpsCoverageController` 下钻端点 `GET /ops/coverage/distribution/communities`（登记：ops 五处） |
| 改 | ops-web `distribution-tab.tsx`（区县表 + 展开下钻 + 招商清单 + 卡片）、`community.ts`（+下钻方法）、`copy.ts` |
| 改 | PRD §8.3「聚落的商品数 = 社区池行数」→「= 现算（买家真搜得到）」 |
| 生成物 | ops openapi / 契约 / API 详情 / 角色×端点矩阵 等重跑 |

## §4 测试策略

- `CoverageDistributionTest`（改写）：
  - 种几个带坐标、分属两个区县的聚落 + 买家地址 + 商家范围；
  - 断言 `regions` 按区县归并、每区县的 buyerCount/各桶计数正确；
  - `totals` 四桶 = 各区县之和 = 逐聚落分类之和（**对账量**：换个方向数一遍）；
  - `supplyGaps` 恰好是「有买家无商家」那些聚落；
  - 下钻 `communitiesInRegion(区县码, page, size)` 只回该区县的聚落，别的区县不漏进来。
  - 下钻分页（`drillPaginates`）：total = 区县开放聚落数、每页满、翻页不重不漏、越末页返回空。
- 消融：把区县归并键改成「整条 region_code」（不截 6 位）→ 同区县不同街道被拆成多行，区县数暴涨、断言红。
- 性能判据（接口预算）：线上实测默认接口返回体从 5.3MB 降到几 KB、端到端 < 1s（上线后核）。

## §5 对账三 · 实现 → 需求

每条 AC 指名一个测试方法，跑过贴真实输出（`CoverageDistributionTest`，8/8 绿）：

| AC（PRD） | 测试方法 | 结论 |
|---|---|---|
| O11/O12 区县概览按区县归并、各计数对 | `regionRollupAndSupplyGaps` | ✅ |
| O13 四桶自洽：ok+supply+demand+empty == communities == 各区县之和 | `totalsReconcile` | ✅ |
| O13 下钻只回该区县的聚落，别的区县不漏进来 | `drillStaysInRegion` | ✅ |
| 下钻分页：total 对、每页满、翻页不重不漏、越末页空 | `drillPaginates` | ✅ |
| O10 有坐标不落围栏 → 单列「落在围栏外」，不混进缺需求 | `addressesOutsideEveryFenceAreCountedSeparately` | ✅ |
| O10 没坐标 → 进「算不了的」，不进买家总数 | `addressesWithoutCoordsAreNotSilentlyDropped` | ✅ |
| C11/C12 归属走层级优先于距离（楼里的人算给楼） | `buyersInsideABuildingCountForTheBuilding` | ✅ |
| 关停聚落不进概览/下钻，但「算不了的」里报条数 | `closedCommunitiesAreReportedNotHidden` | ✅ |

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in ai.neargo.shop.scenario.CoverageDistributionTest
[INFO] BUILD SUCCESS
```

**消融（必须红）**：把 `districtOf` 改成「返回整条 region_code、不截 6 位」，
在**干净的 HEAD 副本**（`a33994102` + 仅覆盖我这 4 个文件，排除共享树里同伴的在建改动）里跑：

```
[ERROR] Tests run: 7, Failures: 1 -- regionRollupAndSupplyGaps
java.lang.AssertionError: [D1 区县行要在概览里]   (CoverageDistributionTest.java:127)
```

同一区县的不同街道（9 位码不同、6 位前缀相同）被拆成多行，D1 的区县行（`995010`）在概览里找不到 → 红。
归并键这一行就是整个「区县概览」的开关，消融证明它真的被测到。

> 为什么要在副本里跑：共享工作树此刻有同伴的模块合并在进行（分支 `refactor/module-consolidation`），
> 主树 `mvn test` 一度因**无关**测试类编译不过而 BUILD FAILURE。在 HEAD 副本里只放我的 4 个文件，
> 绿/红都干净可信 —— 见记忆 `parallel-session-blocks-verification` / `head-copy-must-be-current-head`。

## §6 偏差说明

- **设计 → 实现对账**：`git diff --stat` 的 10 个文件与 §3 模块设计逐行对得上，无多余/缺失文件
  （4 后端 + 6 ops-web；文档/生成物另计）。
- **招商清单的实现细节**：`supplyGaps` 行在 `distribution()` 的主循环里用**已算好的 `pool` map** 直接拼，
  不另调 `supplyStatsPort.byCommunity()` —— 否则每行一次就成了 N+1（返工时踩过一次，已纠）。
- **`merchantCommunityCount` 口径**：区县里「有商家覆盖」的聚落数，来自现算 `SupplyStatsPort`（`merchantCount>0`），
  与 §8.3 改后的口径一致，不读已删的 `prd_community_pool`。
- **ops-web mock 的区县名**：解到 6 位区县码那一级（省/市/区），与后端 `regionPathNames(区县码)` 同口径；
  自查时发现 mock 原来借某聚落的街道级 `regionPath`，会在「区县」列多出一截街道，已改（见 `community.ts` 注释）。
- 无其他与设计不符之处。

---
状态更新：**已实现**（2026-10-08）。
