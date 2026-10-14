# TDD-C端朋友圈分享修补

状态：已实现（2026-09-29）
档位：1（新增 i18n 词条；不动端点 / 库表 / 权限 / 配置）
关联需求：[TDD-C端裂变与商家招募](design/TDD-C端裂变与商家招募.md) §3.2 B2b（朋友圈）/ B3（海报）/ §7.3；
用户 2026-09-29「生成海报部分没有分享到朋友圈，重新梳理分享到朋友圈的形态以及内容」→ 梳理后确认先修三处漏洞
创建：2026-09-29

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 海报能一键发到朋友圈，不再只有「保存到相册」 | `biz-poster.vue`：小程序里主按钮调 `wx.showShareImageMenu`（微信原生菜单：发送给朋友 / 分享到朋友圈 / 收藏 / 保存）；老版本微信没有这个接口时退回保存 |
| AC2 | 朋友圈卡片有配图，不是默认图 | 门户 / 商品详情 / 资质页的 `onShareTimeline` 传 `imageUrl`（商品主图、门店第一件在售商品图、主体标） |
| AC3 | 从朋友圈卡片进来（单页模式）时，加购、下单、跳页不再「点了没反应」，而是明说「点底部『前往小程序』」 | `@shared/ports/share` 的 `inTimelineSinglePage()`（场景值 1154）；`App.vue` 挂页面跳转拦截器；购物车 `add` 前置拦截；三个落地页顶部一条提示 |

**孤立项**：无。

## §1 现状与影响面

- 单页模式（场景值 1154）下微信**禁止登录、支付与页面跳转**，底部固定一条「前往小程序」。
  门户、商品详情、资质页三处开着 `onShareTimeline`，从朋友圈进来的人点加购会被带去登录页 ——
  跳转被微信拦掉，**什么都不发生、也不报错**。
- 海报面板写着「发朋友圈 · 存相册」，实现只有 `saveImageToPhotosAlbum`。
- 不受影响：转发给好友（`onShareAppMessage`）、H5（没有单页模式，也没有 `showShareImageMenu`）、B 端。

## §2 方案

### 契约变更
- i18n（三语）：`share.singlePageTip`（页顶提示）、`share.singlePageBlocked`（被拦时的 toast）、`poster.toMoments`（海报主按钮）
- 端点 / 库表 / 权限码 / 配置：无

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `packages/shared/src/ports/share.ts` | 加 `inTimelineSinglePage()`、`shareImageUrl()`、`showShareImage()`；`buildShareTimeline` 已支持 `imageUrl`，不改 |
| 新增 | `c-app/src/shared/single-page.ts` | `guardSinglePageNavigation()`：单页模式下拦 `navigateTo / redirectTo / switchTab / reLaunch` 并 toast；同时保存提示文案给非组件代码用 |
| 修改 | `c-app/src/App.vue` | 启动时调 `guardSinglePageNavigation` |
| 修改 | `c-app/src/stores/cart.ts` | `add` 在单页模式下直接抛出带提示的错误（调用方原本就把 message toast 出来） |
| 修改 | `packages/ui/src/styles/base.css` | 小程序块间缝名单登记 `biz-single-page-tip` |
| 新增 | `c-app/src/components/biz/biz-single-page-tip.vue` | 单页模式下的页顶提示条 |
| 修改 | `c-app/src/pages/store/index.vue` · `goods/index.vue` · `merchant/index.vue` | 挂提示条；朋友圈卡片带 `imageUrl` |
| 修改 | `c-app/src/components/biz/biz-poster.vue` | 小程序主按钮「分享到朋友圈」→ `showShareImageMenu`，次按钮保存；失败退回保存 |
| 修改 | `c-app/src/i18n/locale/{zh-CN,en,ar}.ts` | 上述词条 |
| 新增 | `c-app/tests/timeline-share.test.ts` | 判据见 §5 |
| 修改 | `c-app/tests/goods-detail-layout.test.ts` | 分享模块的替身改为「保留原实现、只覆盖 canNativeShare」 |

## §5 对账三 · 实现 → 需求

| AC | 测试（`c-app/tests/timeline-share.test.ts`） | 结果 |
|---|---|---|
| AC1 | ★★★ 有原生图片菜单就弹它，不去存相册 · ★★ 点取消不替他存图 · ★★★ 老版本微信退回保存 | ✅ |
| AC2 | ★★★ 配图只放行 http(s)，按顺序取第一个能用的 | ✅ |
| AC3 | ★★★ 场景值 1154 才算单页模式 · ★★★ 四个跳转接口挂拦截并取消 · ★★ 非单页模式一个都不挂 · ★★★ 加购在请求之前就拦 · ★★ 页顶提示只在单页模式出现 | ✅ |

```
c-app vitest：63 个文件 / 429 条全绿；vue-tsc 0 错；设计守卫、RTL、词条对账全过
消融：去掉购物车的单页拦截 → 「加购在请求之前就拦」变红；去掉海报的退回保存 → 「老版本微信退回保存」变红；还原后全绿
```

## 偏差说明

- 单页模式与 `showShareImageMenu` 都只在微信真机里存在，H5 与单测验不了真实效果 ——
  单测验的是判定与分支；**真机要在体验版上看一遍**：朋友圈卡片的配图、从朋友圈进门户点加购的提示、海报「分享到朋友圈」弹出的菜单。
- 模块设计里原本写「拦截器直接写在 App.vue」，实现时抽到 `c-app/src/shared/single-page.ts`：为了能单测，
  也让购物车拿得到提示文案（store 里引 i18n 会让部分页面测试的 vue-i18n 替身失效）。
