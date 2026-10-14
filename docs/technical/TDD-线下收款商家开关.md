# TDD-线下收款商家开关

状态：已实现
关联需求：[PRD-支付方式与预约预定](../requirements/PRD-支付方式与预约预定.md) §3.1 AC-1 · §7.1
上游设计：[TDD-实现清单-线下支付与积分](TDD-实现清单-线下支付与积分.md)（四层判定、下单、确认收款）
创建：2026-09-27 · 最后更新：2026-09-27

## §0 对账一 · 需求 → 设计

线下支付的「下单 → 待收款 → 商家确认收款」整条链早已上线，四层判定（`PayModeServiceImpl`）
也在跑。**缺的是四层里商家自己那两层的写入口**：`mch_store.offline_pay_enabled` 与
`prd_goods.pay_modes` 两列在 V244 加上之后，全仓库没有一处 setter 调用 ——
于是任何一家店都开不出线下收款，而所有闸门全绿。上游 TDD §5.2 的 B 端清单里就漏了这两行。

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| PRD AC-1 | 商家可在**商品维度**开启「支持线下支付」，默认关闭 | `PUT /biz/goods/{goodsNo}/pay-mode` → `MerchantGoodsService#setPayModes` |
| 上游 TDD ③ | 门店层开关，**默认关**，商家自己开（`StorePayPort` 注释） | `PUT /biz/store/{storeNo}/pay-setting` → `StorePaySettingService#save` |
| 上游 TDD ② | 主体资质不足不能线下收款，且原因要与「店没开」区分 | 开门店开关时校验 `QualificationPort`，不足抛 `OFFLINE_PAY_NOT_QUALIFIED`（80012，已有码与三语文案） |
| StorePayPort 注释 | 货到付款是单独一格，不跟着线下收款一起开 | `codEnabled` 独立字段；关线下收款时一并关掉（没有线下收款就没有货到付款） |

**孤立项**：无。平台层（类目 × 支付方式，`/ops/category-pay-modes`）早已有写入口，不在本次。

## §1 现状与影响面

- 判定入口：`shop-core/.../product/service/impl/PayModeServiceImpl` —— 只读两列，本次**不改**
- 门店开关读：`shop-merchant/.../port/MerchantPayPortImpl#offlinePayEnabled / codEnabled` —— 不改
- 下单、确认收款、结算：不改
- **明确不受影响**：商品保存 `/biz/goods/save` 与 `GoodsVO`（支付方式单独一个端点，不进保存指令，
  理由见 §2）；门店资料 `/biz/store`（它的保存会跑公告机审与覆盖范围校验，开关不该搭车）

## §2 方案

### 契约变更
- 端点（4 条新）：
  - `GET  /biz/store/{storeNo}/pay-setting` —— `biz:store`
  - `PUT  /biz/store/{storeNo}/pay-setting` —— `biz:store:admin`（收不收现金是店主级经营决定，与库存同步开关同档）
  - `GET  /biz/goods/{goodsNo}/pay-mode` —— `biz:goods`
  - `PUT  /biz/goods/{goodsNo}/pay-mode` —— `biz:goods`
- 库表：无（两列 V244 已有）
- 权限码：无新增
- i18n：后端无新增（复用 80011 / 80012）；b-app 新增开关文案
- 配置项：无

### 为什么商品支付方式单独一个端点，不进 `/biz/goods/save`
保存指令对**在售商品**走「存草稿 → 发布」双版本并要重审（防「过审后换货」）。
支付方式不是审核对象，改它不该触发重审、也不该让商品掉出在售 ——
与上下架 `/toggle` 同一类「即时生效的经营开关」。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-merchant/.../service/StorePaySettingService.java` | 接口 + `PaySettingVO` |
| 新增 | `shop-merchant/.../service/impl/StorePaySettingServiceImpl.java` | 读写两列；开线下校验资质；关线下连带关货到付款 |
| 新增 | `shop-merchant/.../api/biz/BizStorePayController.java` | 两条门店端点 |
| 修改 | `shop-core/.../product/service/MerchantGoodsService.java` + `impl/MerchantGoodsServiceImpl.java` | `payModes` / `setPayModes`：取值域 `PayModes.ALL`，恒含 ONLINE |
| 修改 | `shop-core/.../product/api/biz/BizGoodsController.java` | 两条商品端点 |
| 新增 | `shop-app/src/test/.../scenario/OfflinePaySwitchFlowTest.java` | 验收用例 |
| 修改 | b-app 契约四处 + 门店设置页 + 商品编辑页 | 两个开关 |
| 登记 | `BizEndpointPermTest` 判权表 · `gen-openapi.mjs` · 生成产物 | 见记忆「新增 /biz 端点要登记七处」 |

### 关键接口
```java
record PaySettingVO(String storeNo, boolean offlinePayEnabled, boolean codEnabled, boolean qualified)
PaySettingVO StorePaySettingService#get(String entityNo, String storeNo)
PaySettingVO StorePaySettingService#save(String entityNo, String storeNo, Boolean offline, Boolean cod)  // null = 不改
List<String> MerchantGoodsService#setPayModes(String merchantNo, String goodsNo, List<String> payModes)
```

## §5 对账三 · 实现 → 需求

| AC | 测试方法 | 结果 |
|---|---|---|
| AC-1 商品开线下 → 判定出 OFFLINE | `OfflinePaySwitchFlowTest#storeAndGoodsSwitchesOpenOfflinePay` | ✅ |
| 门店默认关、商品开了也不给 | 同上前半段 | ✅ |
| 资质不足开门店开关被拒 80012 | `#enablingWithoutLicenseIsRejected` | ✅ |
| 关线下连带关货到付款 | `#turningOfflineOffAlsoTurnsCodOff` | ✅ |
| 非法取值 400、恒含 ONLINE | `#payModesAreValidatedAndAlwaysKeepOnline` | ✅ |
| 别家的店 / 商品 404 | `#otherEntitysStoreIsNotFound` | ✅ |

```
Tests run: 5, Failures: 0, Errors: 0 -- OfflinePaySwitchFlowTest
Tests run: 4, Failures: 0, Errors: 0 -- BizEndpointPermTest
Tests run: 16, Failures: 0, Errors: 0 -- ArchitectureTest
全量 2549 跑（17 个模块）/ 0 红（基线 0 条）   ← check-head-compiles.sh @ db3f18c5
```

**消融**（改回去必须变红）：
- 去掉开关时的资质校验 → `enablingWithoutLicenseIsRejected` 红
- 商品写入不绕数据域 → 两条红，报「影响 0 行」—— 证明用例真的在店主域下跑，
  也证明 `rows != 1` 那道自检是必要的（不然接口返回成功、库里没变）

**线上**：2026-09-27 db3f18c5 上线；虹选粮油（ST202609271616020003110）经新端点打开门店开关、
4 件商品加 OFFLINE，回读库四层（门店 1 / 商品含 OFFLINE / 执照有效 / 类目无禁止行）齐备。

## 偏差说明

- 设计初稿想把门店开关挂在 `/biz/store` 门店资料上（少一组端点登记）。改为独立端点：
  门店资料的保存会顺带跑公告机审与覆盖范围校验，开一个收款开关不该触发这些副作用。
