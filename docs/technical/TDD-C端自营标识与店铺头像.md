# TDD-C端自营标识与店铺头像

状态：已实现（2026-09-19）
原型：https://claude.ai/artifact/JiGyKHUMfdZkqSB5g1zh5J（首页 / 店铺列表 / 店铺详情）
创建日期：2026-09-19

## 1. 问题

**电商法 §37**：平台自营业务必须以显著方式与第三方业务区分标记。

虹选鲜果在库里就是自营（`mch_entity.self_operated = 1`，两家门店 `business_mode = SELF_OPERATED`），
`biz-merchant-bar` 也早有 `v-if="merchant.selfOperated"` 的自营标 —— 但**商品接口给端上的商家简介里没有这个字段**：

- `MerchantQueryPort.MerchantBrief` 没有 `selfOperated`；
- 于是 `GoodsVO.MerchantBriefVO`（商品详情 / 列表）与 `GroupVOs.MerchantBriefVO`（团购 / 报价）也没有；
- 端上 `merchant.selfOperated` 恒为 undefined，**商品页、首页、团购卡上的自营标一次都没显示过**。

~~店铺列表 / 详情走的是 `MerchantVO`，那一份本来就有 `selfOperated`。~~ **这句是错的**（只看了前端类型、没看接口回包）：`MerchantVO` 与 `VisitedMerchantVO` 都没有，店铺页三档与详情页一样从没显示过自营标。2026-09-19 真机验证时发现，两份一起补上。

## 2. 改动

后端（加字段，只加不改）：

| 位置 | 改动 |
|---|---|
| `MerchantQueryPort.MerchantBrief` | 末位加 `boolean selfOperated`，`MerchantPortImpl` 的 `find` / `findAll` 从 `mch_entity.self_operated` 读 |
| `GoodsVO.MerchantBriefVO` | 末位加 `boolean selfOperated`，`GoodsServiceImpl` 两处构造透传；找不到商家的兜底为 `false` |
| `GroupVOs.MerchantBriefVO` | 同上，`GroupServiceImpl` 两处构造透传 |
| `MerchantVO`（店铺列表 / 推荐 / 详情） | 末位加 `boolean selfOperated`，同样读 `mch_entity.self_operated`（真机发现后补） |
| `VisitedMerchantVO`（`/mp/merchant/visited`） | 末位加 `boolean selfOperated`。**第一版漏了这一份**：店铺页「我买过的」那一档在真机上把虹选鲜果显示成一个「虹」字（2026-09-19 补） |

**判据只认 `mch_entity.self_operated`，不从门店的 `business_mode` 推**：那一列管的是结算（谁是销售主体），
标识管的是对买家的法定告知。两列今天一致，但合成一个会让「改结算口径」顺带改掉对外告知，反之亦然。

端上：

- `biz-merchant-bar` / 商品卡 / 店铺页统一显示「自营」标，放在店名**之前**（它回答「谁在卖」）。
- **店铺头像**：自营店用品牌面（`c-app/src/static/brand/store-self.png`，由 `brand/build.py` 的
  `build_app_static()` 生成，与 C 端 App 图标同一套参数，勿手改）；第三方店用上传的 logo，
  没传时用店名首字的文字头像，不再用 🏪 占位表情。

## 3. 测试

- 后端：`MerchantSelfOperatedFlowTest` —— 自营商家的商品详情 `merchant.selfOperated = true`，
  第三方为 `false`；团购列表同样透传。把 `MerchantPortImpl` 里的读取写死成 `false`，自营那条当场变红。
- 端上：商家条 / 商品卡在 `selfOperated` 为真时出「自营」、为假时不出；自营无 logo 时用品牌头像、
  第三方无 logo 时是首字头像。
