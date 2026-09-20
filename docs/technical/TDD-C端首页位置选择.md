# TDD-C端首页位置选择

状态：已实现（2026-09-20）
档位：2（无新端点 · 无库表 · 新增 i18n 词条 · 已有页面加一个模式）
原型：https://claude.ai/artifact/8fR5US62QopHk6cxULpNFL（`prototypes/c-locate.html` l01–l06）
依据：用户 2026-09-20「按照美团的方案走」
创建日期：2026-09-20

## 1. 要解决的是什么

首页顶栏那一行位置，点下去进的是**收货地址页**。那一页回答的是「下单寄到哪」：
要换一个地方看货，得先建一条收货地址（地址簿上限 20 条），
而「我现在想看看龙华体育馆那一带有什么」并不需要一条收货地址。

美团外卖把这两件事分开：顶栏点开的是「现在按哪儿看货」，一页四段 ——
搜索、当前定位、我的收货地址、附近；底下才是「新增收货地址」。

微信驳回了静默精确定位（`wx.getLocation`），所以「当前定位」只到区、误差约 5 公里
（见 `stores/location.ts` 的 `label`）。美团那一格在我们这儿必须多一颗
**「在地图上选」**（`wx.chooseLocation`，不受那条限制）—— 它是这个端上唯一拿得到准坐标的路。

## 2. 方案选型

| 方案 | 说明 | 结论 |
|---|---|---|
| A 新开 `pages/locate/index` | 与 `address-pick` 并列 | ❌ 搜索、附近、地图选点三段要再写一遍。两页的搜索迟早给出两种结果，而界面上看不出来 |
| B `pages/address-pick` 加一个浏览模式（推荐） | `?mode=browse`：多一段「我的收货地址」，选中即切浏览位置、不建地址 | ✅ 搜索与附近只有一份实现；两个模式的差别集中在「选完干什么」 |

采用 B。

## 3. 契约

**不新增端点、不动库表。** 用到的都是已有的：

- `GET /mp/place/search`（搜索）· `GET /mp/community/nearby`（附近）· `POST /mp/location/resolve`（解析地名）
- `GET /mp/address`（我的收货地址）· `POST /mp/address/:id/active`（切换生效地址）

## 4. 模块

- `c-app/src/pages/address-pick/index.vue`：新增 `mode=browse`
  - 标题 `addressPick.browseTitle`「选择位置」
  - 新增「我的收货地址」段（`location.list`，当前生效那条打勾）：点一条 → `location.switchTo(addressId)` → `navigateBack`
  - 「当前定位」「附近小区」「搜索结果」任一条选中 → `location.useTransient(坐标)` → `navigateBack`（**不写地址簿**，它是浏览上下文不是资料）
  - 底部动作条「新增收货地址」→ 现有的新建流程（`next=edit`）
  - 没有坐标时「附近」整段不渲染；没有地址时「我的收货地址」整段不渲染（原型 l05 / l06）
- `c-app/src/pages/home/index.vue`：`gotoPlace` 改为跳 `ROUTES.addressPick + "?mode=browse"`
- i18n 三语：`addressPick.browseTitle` · `myAddresses` · `useThisSpot` · `switched`

## 5. 测试

`c-app/tests/locate-browse.test.ts`：

1. `mode=browse` 时渲染「我的收货地址」段，非浏览模式不渲染。
2. 点一条地址 → 调 `switchTo(该 addressId)`，且**不**调建地址的接口。
3. 点「附近小区」的一条 → 调 `useTransient(那条的坐标)`，且不写地址簿。
4. 没有坐标时不渲染「附近」段；没有地址时不渲染「我的收货地址」段。
5. 首页 `gotoPlace` 跳的是带 `mode=browse` 的选点页。

消融：把「选完 `switchTo`」换回「返回一条 place」→ 第 2 条必红。

## 6. 风险

- **顶栏要分得出两种状态**：按「当前位置」逛与按某条收货地址逛，顶栏文案必须不一样
  （`label` 已经这么做了：前者显示 `transientName`）。这一条是既有行为，本次不改，但测试要钉住。
- 地图选点在 H5 上没有 JS key（`canChooseLocation()` 为假），那一颗按钮在 H5 上不显示 —— 既有逻辑，沿用。

## 实现 → 需求（测试真实输出）

- `c-app/tests/locate-browse.test.ts`：8 跑 / 0 红。
  消融：把 `choose()` 浏览分支里的 `useTransient` 换成别的调用 → 1 红（「浏览模式走 useTransient」那条）。
- c-app 全量：245 跑 / 0 红；`vue-tsc` 干净。
- H5 mock 实跑：`?mode=browse` 进去标题是「选择位置」，「我的收货地址」段列出了地址，
  底部是「新增收货地址」；mock 没有坐标，所以「附近小区」整段没渲染（原型 l05 的那一格）。

## 偏差说明

- **没有新开页面**，按 §2 方案 B 在 `pages/address-pick` 上加 `mode=browse`（原型把它画成了一页新的「选择位置」，实际是同一页的第二个模式）。
- 顶部的「搜索范围 / 切换城市」那一行在浏览模式里保留了。原型没画它，但去别的城市看货是真实的用法（出差、给父母买），而它已经在那儿。
- 原型 l04「在地图上选」用的是页面底部既有的那颗「地图选点」，没有在「当前定位」卡里再放一颗 —— 同一件事不给两个入口。
