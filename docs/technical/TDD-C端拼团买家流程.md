# TDD-C端拼团买家流程

状态：已实现（2026-09-19）；p02 的一处待拍板（见偏差说明）
档位：1（新端点 · 既有 VO 加字段 · i18n · 新页面）
原型：https://claude.ai/artifact/UyG6Rj21TqqFZWxvyo7pN1（`prototypes/c-goods-group.html` p01–p12）
依据：用户 2026-09-19「按照原型执行」
创建日期：2026-09-19

## 1. 需求 → 落点

| 原型 | 说的是什么 | 落点 | 测试 |
|---|---|---|---|
| p04 | 付完团单直接落在自己的团页：倒计时、人头、「付款成功，已开团 / 已参团」、主按钮「邀请邻居来拼」 | 支付页：订单带 `groupNo` 时付款成功 `redirectTo` 团页 `?paid=1` | c-app `group-flow`「付完团单落团页」 |
| p05 | 团详情：一行商品（图 + 名 + 团价 + 单买价），整行点进商品详情 | 团页把「商品 / 成团价」两行文字换成商品行 | c-app `group-flow`「团详情有商品行」 |
| p06 | 已成团：状态轴（已付款 → 已成团 → 备货 → 送达），按钮「查看订单」 | `GroupBuyVO.myOrderNo`（我参团的那一单）；有它才出按钮 | 后端 `GroupOrderFlowTest` · c-app |
| p07 | 没凑齐：「已全额退款，原路退回 ¥X」+「单独买 / 再开一个团」 | 团页 FAILED 分支 | c-app |
| p08 | 订单详情里的拼团进度卡：还差几人、倒计时、「邀请邻居来拼」 | 订单有 `groupNo` 时取团详情画进度卡 | c-app |
| p12 | 我的拼团：拼团中 / 已成团 / 没凑齐三栏；拼团中的卡直接给「邀请」；底部挂「我发起的邻里自提团」 | 新端点 `GET /mp/group-buy/mine`；新页 `pages/my-groups/index`；「我的」入口替换「我发起的团」 | 后端 `GroupOrderFlowTest` · c-app |

## 2. 契约

- `GET /mp/group-buy/mine`（要登录）→ `List<GroupBuyVO>`：我参加过的团（开团也算参加），按我参团的时间倒序。
- `GroupBuyVO` 末尾加 `String myOrderNo`：当前买家在这个团里的那一单（`mkt_group_member.sub_order_no`）；没参团 / 未登录为 null。
- `OrderVO` 末尾加 `String groupNo`（`ord_sub_order.group_no`），只在 C 端订单视角（`orderView`）上填；支付视角的子单跟着带。

## 3. 模块

- 后端：`GroupService.myJoinedGroups` · `GroupServiceImpl`（`toGroupBuyVO` 补 `myOrderNo`）· `GroupVOs.GroupBuyVO` · `MpGroupController` · `MpEndpointAuthTest`
- 端上：`types/marketing.ts` · `endpoints / contract / http / mocks` · `pages/pay` · `pages/group` · `pages/order` · 新页 `pages/my-groups` · `pages/me` · `pages.json` · 三语词条

## 4. 测试

后端 `GroupOrderFlowTest`（新增两条）：开团付款后 `mine` 里有它、`myOrderNo` 指向那一单；别人看同一个团 `myOrderNo` 为空。
端上 `group-flow.test.ts`。消融：`mine` 不按用户过滤 → 别人的团混进来，必红。

## 偏差说明

- **加了一处 §2 没写的契约：`OrderVO.groupNo`**。端上 `Order` 类型早就声明了 `groupNo`，
  但后端从没下发过 —— p04（付完落团页）与 p08（订单里的进度卡）都读它，设计时照着类型写、没核回包。
  现在由 `orderView` 统一补（原方法改名 `orderViewBase`），支付视角的子单跟着带上。
- **p02「＋ 我要发起一个团」没有移走**（待用户拍板）：原型说开团入口在商品详情、这里不需要第二个。
  但这个按钮背后的表单是**邻里自提团**（「送到我家」+ 地址 + 取货时段，C-GB-05/06）的唯一入口，
  商品详情的「开团」走下单、不带这两项。移走它等于删掉邻里自提这条线（连带「我发起的团」的签收核销），
  超出了原型要改的范围。p02 的团卡本来就有缩略图、团价、进度、倒计时，其余已符合原型。
- p06 的状态轴只画「已付款 → 已成团 → 商家备货中 → 送达」四步的静态样子，不接订单履约进度
  （那要按订单状态逐步点亮，下一轮再接）。
- p08 的「取消（退款）」按钮没有加：团单的取消沿用订单页已有的取消入口。
- 「我的」页里「我发起的团」一行换成「我的拼团」，前者收进我的拼团页的页底（原型 p12 注）。

## 实现 → 需求（测试真实输出）

- 后端 `GroupOrderFlowTest`：12 跑 / 0 红（新增 `myJoinedGroupsListsOnlyMine`、`myOrderNoOnlyForMembers`，后者同时钉订单详情的 `groupNo`）。
  消融：去掉「按人过滤」→ 红（第一版对照没付款、不进成员表，消融不红；已改成对照也付款）。
- 端上 `group-flow.test.ts`：6 跑 / 0 红；团页、订单页换回旧版本 → 5 红。
