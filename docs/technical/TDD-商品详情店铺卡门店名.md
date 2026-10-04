# TDD-商品详情店铺卡门店名

状态：已实现（2026-10-04）
档位：1（改详情返回 `GoodsVO.store` 的填充逻辑 + 前端组件加一个 prop；不新建端点/库表/权限码）
关联需求：[TDD-C端门店化与门店门户](TDD-C端门店化与门店门户.md) 偏差#2、[TDD-C端商品详情页v3](TDD-C端商品详情页v3.md) 第二步；
用户 2026-10-04「商品详情页门店名称还是虹选科技有限公司」「每个商品都会关联门店，根据商品查询门店，不论从哪里进来」
创建：2026-10-04

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 从门店门户进详情（带 storeNo）：店铺卡显示那家门店名 | 后端 `detailForBuyer` 对传入 storeNo 走 `withStoreScope` 填 `store` |
| AC2 | 不带 storeNo（首页推荐/搜索/购物车进）：也按**商品关联的门店**显示门店名，不显示主体名 | 后端解析商品归属门店：唯一在售店 → 主体默认营业店 |
| AC3 | 商品一家店都没在售、且主体无默认店：回落主体名（诚实默认，不编一个门店） | 解析不到门店号时 `store` 为空，前端回落 `merchant.name` |
| AC4 | 前端店铺卡优先显示门店名，没有才显主体名 | `biz-merchant-bar` 加 `storeName` prop |

**孤立项**：无。

## §1 现状与根因

- 端点 `/mp/goods/{goodsNo}` 收 `storeNo`，转给 `GoodsServiceImpl.detailForBuyer(goodsNo, storeNo)`。
- **根因**：`detailForBuyer` 收到 storeNo 只用来**替换库存 skus**，手写了一遍，**从不调 `withStoreScope`、从不填 `GoodsVO.store`**。
  于是 `store` 恒为 null，前端 `biz-merchant-bar` 只能显示 `merchant.name`（主体名「虹选科技有限公司」）。
- 对照：**列表路径 `list` 做对了** —— 它 `withStoreScope(v, storeOfGoods.get(no), names)` 填了 store。详情漏了这一步。
- 类型层已就绪：`Goods.store`（`GoodsStoreBrief{storeNo, storeName}`）、`GoodsVO.StoreBriefVO` 都在，无需改。

## §2 方案

### 契约变更
- 对外 JSON：`/mp/goods/{no}` 的 `store` 字段**现在会被填**（之前恒 null）。字段本身已存在，不是新增。
- 端点 / 库表 / 权限码 / i18n / 配置：无。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core/.../product/service/impl/GoodsServiceImpl.java` `detailForBuyer` | 解析门店号（storeNo 参数 > `soleSellingStoreOf([goodsNo])` > `merchantPort.defaultStoreNo(merchantNo)`），用 `withStoreScope` 统一填 `store`+库存，删手写的库存替换段 |
| 修改 | `c-app/src/components/biz/biz-merchant-bar.vue` | 加 `storeName?: string` prop；头像与标题优先显示门店名、回落 `merchant.name` |
| 修改 | `c-app/src/pages/goods/index.vue` L1170 | 传 `:store-name="goods.store?.storeName"` |
| 新增 | `backend/.../scenario/GoodsDetailStoreNameTest.java` | 判据见 §5 |
| 修改 | `c-app/tests/goods-detail-layout.test.ts` | 加门店名显示断言 |

**解析门店号的口径**（与 list、与下单落店同序）：
1. 调用方传了 storeNo（从门户进）→ 用它；
2. 否则商品只有一家店在售 → 那家；
3. 否则取主体默认营业店；
4. 都没有 → 不填 store（前端回落主体名）。

## §5 对账三 · 实现 → 需求

| AC | 测试 | 结果 |
|---|---|---|
| AC1 | `GoodsDetailStoreNameTest` ★★★ 带 storeNo → store.storeName 是那家、≠主体名 | ✅ |
| AC2 | ★★★ 不带 storeNo、唯一在售店 → store.storeName 是那家 | ✅ |
| AC4 | `goods-detail-layout` ★★★ 有 store 显门店名、无 store 回落主体名 | ✅ |

```
后端 GoodsDetailStoreNameTest 2 条绿；前端 441 条全绿、vue-tsc 0 错
消融：去掉 detailForBuyer 最后填 store 的 withStore → 两条「store 被填上了」变红，还原后绿
```

## 偏差说明

- **填 store 必须放在所有 `withXxx` 重建之后**：`withSaleScope` 手写 `new GoodsVO(...)` 时把 `store` 置 null，
  先填就被它清掉了（店铺卡于是恒显主体名）。list 路径没这个坑，因为它的 `withStoreScope` 是整条链最后一步。
- **库存替换仍放最前**：`directBuyable` / 促销要读 skus。所以「换库存」与「填 store」拆成一前一后两段，
  不再合用 `withStoreScope`（那个把两件事绑在前面）。
- AC2 多店在售 → 主体默认店、AC3 无店回落主体名：口径在代码里（soleSellingStoreOf 只在唯一在售时返回、
  否则 defaultStoreNo、再否则不填），场景测试只覆盖了主干（带/不带 storeNo 的唯一在售店），未单独构造多店/无店。
