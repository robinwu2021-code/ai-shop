# TDD-门店背景图与门户精修

状态：已实现（2026-09-29）
档位：1（新增库表列 `mch_store.banner_url` + 对外 JSON 字段 + i18n；不新建域、不动权限码与端点）
关联需求：用户 2026-09-29「1，去掉我常买部分 2，顶部的背景色太丑了，可以通过配置开启或者关闭，3，整体布局不够精细」；
追问确认「如果设置背景，就是第二个（照片），如果没有设置，就是第一个（主色浅底）」「每家店自己开」
关联设计：[TDD-C端门店化与门店门户](TDD-C端门店化与门店门户.md) AC11（门户结构）
创建：2026-09-29

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 店主能在 B 端给自己的店设一张顶部背景图 | 新列 `mch_store.banner_url`；`POST /biz/store` 带 `bannerUrl`（不新增端点）；B 端店铺装修卡里一格 `sh-uploader` |
| AC2 | 设了背景图，买家打开门店顶部就是这张照片 | `StoreHomeVO.StoreFront.bannerUrl`；**两条进门户的路都要发**（按主体号 → `MerchantPortImpl.storeFront`，按门店号 → `StoreDirectoryPortImpl.front`） |
| AC3 | 没设（或清掉）就是主色浅底 —— 不是那片深绿渐变，也不拿商品图凑 | C 端 `bannerUrl` 为空时 `.band` 只有 `--sh-primary-tint`；空串 = 清掉，不传 = 不改 |
| AC4 | 乱传的值不能进库（本地临时路径、半截地址） | `saveBanner` 只收 `http(s)` 且 ≤512 字符，否则 `BAD_REQUEST` |
| AC5 | 去掉「我常买」一栏 | `pages/store/index.vue` 删除该块与 `frequentItems` 调用；连带 11 条词条删除 |
| AC6 | 布局精细一些 | 信息卡（店名 → 营业信息一行 → 公告 → 券）压在顶部那条底上；页签改「文字 + 短线」（`sh-tabs` 新增 `line`），与下面的分类 chip 分成两种样子；商品单列行距与分隔线统一 |

**孤立项**：无。

## §1 现状与影响面

- 门店**没有**背景图字段。`mch_store_audit.kind` 里留了 `BANNER`（店招图），但后端从没有任何一处写它，
  `MchStore` 也没有对应列 —— 那是一条从未接通的设计。本次**不复用它**：图片走平台上传通道（与商品图、资质图同一条），
  机审是敏感词表，对图片无从判起。
- 上一版（同日早些时候）用「本店销量最高那件商品的主图放大虚化 + 品牌色」当背景 —— 用户评「太丑」，本次删除。
- 不受影响：公告的机审与人审（`NOTICE`）、营业时间/地址、C 端其它页面。

## §2 方案

### 契约变更
- 库表：`mch_store` 加 `banner_url VARCHAR(512) NULL`（V368）
- 对外 JSON：`StoreProfileVO.bannerUrl`（B 端读写）、`StoreHomeVO.StoreFront.bannerUrl`（C 端只读）、
  `BizMerchantController.StoreReq.bannerUrl`（入参）
- i18n：b-app `store.banner`（三语）；c-app 删除 `store.frequent/reorder/reorderAdded/reorderDropped/reorderPriceUp/noHistory/times/invalid/distanceTo/favAct/favOn`、`common.added`
- 端点 / 权限码 / 配置：无（沿用 `POST /biz/store`、`GET /mp/store/{no}`）

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/.../db/migration/V368__store_banner.sql` | 加列。**写成一行** —— `MediaRefCoverageTest` 的 ADD COLUMN 扫描只认单行 |
| 修改 | `merchant/entity/MchStore.java` | `bannerUrl` 字段 |
| 修改 | `merchant/service/MerchantStoreService.java` + `impl` | `saveBanner()`：校验 + 写空串清掉（`updateById` 跳 null，写 null 等于没清） |
| 修改 | `merchant/dto/StoreProfileVO.java` | B 端回读 |
| 修改 | `portal/biz/BizMerchantController.java` | `StoreReq.bannerUrl`；非 null 才调 `saveBanner`（不传 = 不改） |
| 修改 | `spi/user/MerchantQueryPort.StoreFront` | 端口记录加字段 |
| 修改 | `merchant/port/MerchantPortImpl.java` · `StoreDirectoryPortImpl.java` | **两条读路径都要填** |
| 修改 | `product/dto/StoreHomeVO.java` · `product/service/impl/StoreServiceImpl.java` | C 端出参 |
| 修改 | `shop-merchant/.../media/MerchantMediaRefs.java` | 登记 `mch_store.banner_url`，否则图会被当孤儿回收 |
| 修改 | `packages/shared/src/types/store.ts` | `StoreProfile.bannerUrl`（B 端）、`StoreFront.bannerUrl`（C 端） |
| 修改 | `packages/shared/src/mock/db.ts` | 种子 `bannerUrl: ""`（默认没设） |
| 修改 | `packages/ui/src/components/sh-tabs.vue` | 新增 `line` 变体：文字 + 短线 |
| 修改 | `b-app/src/pages/store/index.vue` + 三语 | 店铺装修卡里一格上传；改了走原有保存条 |
| 修改 | `c-app/src/pages/store/index.vue` | 去掉我常买；顶部那条底 + 信息卡；页签用 `line`；分类吸顶 |
| 修改 | `c-app/src/components/biz/biz-share-act.vue` | `lightBox`：浅底上是白圆 + 正文色图标 |
| 新增 | `backend/.../scenario/StoreBannerFlowTest.java` · `c-app/tests/tabs-line.test.ts` | 判据见 §5 |

## §5 对账三 · 实现 → 需求

| AC | 测试 | 结果 |
|---|---|---|
| AC1/AC2 | `StoreBannerFlowTest` ★★★ 设了背景图：B 端回读是它、C 端门户也是它 · ★★★ 按门店号进门户也读得到（另一个端口实现） | ✅ |
| AC3 | `StoreBannerFlowTest` ★★★ 空串 = 清掉、不传 = 不改 · ★★ 从没设过读到空串不是 null；`store-portal.test` ★★★ 设了是照片、没设是浅底、不拿商品图凑 | ✅ |
| AC4 | `StoreBannerFlowTest` ★★ 不是 http(s) 的值拒收（10400） | ✅ |
| AC5 | `store-portal.test` ★★★ 没有「我常买」一栏，且不去取 | ✅ |
| AC6 | `tabs-line.test` ★★★ line 是文字 + 短线不画 chip · ★★ 不传 line 仍是 chip · ★★ 点一项发 change | ✅ |

```
后端：StoreBannerFlowTest 5 条全绿；MediaRefCoverageTest / SchemaDriftTest / ArchitectureTest /
      StorePortalFlowTest / MyStoreFlowTest / BizEndpointPermTest 一并绿（在 HEAD 干净副本里跑的）
c-app：vue-tsc 0 错；store-portal 6 条 + tabs-line 3 条全绿
消融：
  · 去掉 StoreDirectoryPortImpl 里的 bannerUrl → 「按门店号进门户也读得到」变红
  · 去掉 MerchantPortImpl 里的 bannerUrl     → 另两条变红
  · 去掉 MerchantMediaRefs 的登记            → MediaRefCoverageTest 报 mch_store.banner_url
  · C 端背景图改回「取商品主图」             → 「不拿商品图凑」变红
  全部还原后复跑，绿
```

## 偏差说明

- **不复用 `mch_store_audit.kind=BANNER`**：那条设计是「店招图走人审」，而本次图片走平台上传通道，
  与商品图、资质图同一条路。真要审图是另一件事（要接图片审核服务），不在这一份里。
- **背景图不经机审**：公告那套是敏感词表，对图片无从判起。
- **没有单独的 `/biz/store/banner` 端点**：它随整张门面表单一起存（`POST /biz/store`），
  与营业时间、地址同一次保存 —— 多一个端点就要多走一遍 `/biz` 七处登记，而它没有独立的使用场景。
- **`MediaRefCoverageTest` 的扫描盲点**：它的 `ADD COLUMN` 正则只认单行写法，仓库里 V212/V214/V215 那种多行写法它看不见。
  V368 因此**刻意写成一行** —— 不是风格偏好，是为了让守卫真的能看住这一列。
