# TDD-C端商品详情·销售范围标识

状态：已确认（口头，2026-09-18）
关联需求：口头 —— 「首页根据门店范围展示商品清单即可，不要写社区在买以及位置信息，
但是可以在商品详情页标识商品的销售范围，列表页面暂时可以不标识」
创建日期：2026-09-18

## 1. 需求摘要

首页已经不再说「你在哪儿」（见上一条提交）。位置这件事从首页撤掉之后，
**买家失去了唯一一处能看出「这件货卖不卖到我这儿」的地方** —— 列表本来就按可见性筛过，
筛掉的那些他看不见，剩下的他也不知道为什么剩下。

把这句话挪到**商品详情页**：买家点进来才问「这个送到我这儿吗」，
那一刻给一行范围，比在列表里每张卡上重复一遍更省地方也更贴题。

验收：
- `/mp/goods/{goodsNo}` 返回一个可读的销售范围；`/mp/goods` 列表**不返回**（恒 null）。
- 商家没配过地理范围、且不是只做自提 → 显示「不限地区」而不是空白。
- 商家只做自提且一个范围都没配 → **什么都不显示**（那是「谁也看不到」的配置，
  不能翻成「不限」，见 `packages/shared/src/types/store.ts` 里 `serviceAreas` 的那段注释）。

## 2. 当前架构分析

- `mch_service_area`（shop-merchant）：一家主体的地理覆盖项，INCLUDE / EXCLUDE 两向，
  level ∈ COMMUNITY / VILLAGE / STREET / DISTRICT / CITY。
- 名字怎么取，`MerchantGovernServiceImpl#areaNameOf` 已经有一份：
  COMMUNITY 走 `CommunityQueryPort#communityName`，其余走 `MasterDataPort#regionPathName`。
- 跨域只能走 spi Port（ArchUnit 拦着），product 域不能直接读 `mch_service_area`。
- `MerchantPortImpl` 已经注入了 `serviceAreaMapper` / `communityQueryPort` / `masterDataPort`，
  这次不新增依赖。

## 3. 方案设计

### 方案选型

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A（采用）在 `MerchantQueryPort` 上加 `saleScope(merchantNo)`，detail 里填 | 复用已有的展开与取名；列表零成本 | 详情页多一次查询 | ✅ |
| B 端上拿 `reachableCommunities` 自己拼 | 后端不用改 | 那是展开后的聚落列表，几十上百条，且是**可见性内部口径**，印给买家看等于把实现细节当文案 | ❌ |
| C 在 `toVO` 里统一填 | 一处改完 | 列表一屏几十行 → N+1，且列表本来就不要 | ❌ |

### 接口

`MerchantQueryPort`（shop-base/spi/user）新增：

```java
SaleScope saleScope(String merchantNo);

record SaleScope(boolean unlimited, java.util.List<String> areaNames, int areaCount) {}
```

- `unlimited=true` → 端上显示「不限地区」，`areaNames` 此时为空。
- `unlimited=false` 且 `areaNames` 为空 → **端上整行不渲染**（只做自提却没配点的那种）。
- `areaNames` 最多 `SALE_SCOPE_SAMPLE`(6) 条，`areaCount` 是总数 ——
  只给截断后的列表会让「6 个」和「60 个」长得一样。
- 区划取**叶子名**不取整条路径：运营看「浙江省 / 杭州市 / 西湖区」是为了不看错，
  买家看到自己家那三个字就够，路径只会把这一行挤成两行。

`GoodsVO` 新增末位字段 `SaleScopeVO saleScope`（形状同上）。
**只有 `GoodsService#detail` 填**，其余构造点传 null。

### 端上

`c-app` 商品详情页在商家信息条下面加一行：
「销售范围：龙华区、观澜街道 等 8 个地区」／「销售范围：不限地区」。
`saleScope` 为 null 或 `!unlimited && areaNames` 空 → 整行不渲染。

## 4. 测试策略

- 后端：`MpGoodsSaleScopeTest`（MockMvc，走 `/mp/goods/{no}` 与 `/mp/goods`）
  1. 配了两条 INCLUDE → detail 里有两个名字、`areaCount=2`、`unlimited=false`
  2. 一条都没配、启用了配送 → `unlimited=true`
  3. 一条都没配、只启用自提 → `unlimited=false` 且 `areaNames` 空
  4. **列表端点恒 null** —— 这一条是本次唯一的性能约束，没有它没人会发现列表退化成 N+1
- 端上：源码守卫，钉「列表卡片模板里不出现 saleScope」+ 详情页有那一行。

## 5. 风险

- EXCLUDE 项不进这一行：买家看得到这件商品，就说明他没被排除掉；
  把排除项印出来只会让他读成「这些地方也卖」。
- PENDING 状态的范围项不算数 —— 还没生效的范围不该出现在买家页上。

## 6. 实现任务

- [ ] `MerchantQueryPort#saleScope` + `MerchantPortImpl` 实现
- [ ] `GoodsVO.SaleScopeVO` + `GoodsServiceImpl#detail` 填充
- [ ] `packages/shared` 契约字段
- [ ] c-app 详情页渲染 + i18n 三语
- [ ] 后端场景测试 4 条 + 端上守卫
