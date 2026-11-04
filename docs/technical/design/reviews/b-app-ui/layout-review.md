> 本地提交范围已按用户要求拆分；以下为完整工作区实施记录，独立提交范围及验证限制见 [commit-scope.md](./commit-scope.md)。

# B端组件布局约束与页面复核

> 状态：源码约束、页面修正与代表页浏览器检查已完成；未提交、未发布，未做真机全状态验收。2026-10-26。

[组件约束](../../B端文案与Layout规范.md#56-组件组合的强制约束) · [完整处置数据](./layout-review.json)

## 范围与结果

96页、42组件全量结构审查，本轮修正19页及1个业务组件。按最终规则回扫改前源码的13处间距风险位置和14处标题分隔候选已逐项处理；它们是结构审查位置数，不等于13次真实用户故障。

新增 sh-stack（20rpx纵向间距）和 sh-actions（16rpx动作间距、等宽换行）。只对显式使用组合类的容器生效。已验证修改文件的script内容和事件/v-model绑定与本轮改前相同。

## 约束落地与验证

- `check:ui` 已拦截未知组件参数、sh-kv内的文本编辑控件、可能相邻或重复的控件无间距、间距所有者叠加。已接入B端npm test。
- 29项扫描/策略fixture通过，其中19项专门验证布局。覆盖透明模板、循环、互斥分支、正确gap方向、逻辑与行内边距、选择器优先级、原生/自定义控件及动作槽。
- 独立复核发现3项检查器问题，均先复现失败、再修复通过：模板/循环边界漏检、错误方向gap误通过、行内简写误报。未延期处理评审发现。
- 中文11页、36组浏览器几何记录覆盖320/375/430px：营销储值查询、期初对齐、发票、收款账户、积分流水空态、预约排期、会员录入、资质录入、商品编辑、店铺设置、评价回复/申诉。记录中的页面横向溢出、stack间隔和等宽动作组检查均通过。
- 商品编辑滚到底后，最后卡片底边667.08px，操作条顶边747.41px，可见内容未被挡住。按钮缩成窄药丸的问题已用真实uni-button复现并验证修复（375px下宽度由56px变为327px）。
- B端282项、C端599项测试通过，B/C类型检查及H5/小程序/App构建通过。全量共享检查539通过/27失败，失败名称与原因完整保存在数据JSON；未修改阈值或按整页豁免。

浏览器使用本地Mock，没有提交保存、发布、退款或库存命令。打印数据态、积分账单数据态在演示环境不可用，仅做源码及已有组件测试；全部96页的全数据/权限/错误/忙碌状态和原生软键盘、安全区仍需专项验收。静态规则检查声明，不能替代真实几何。

[几何记录](../../../../../output/b-app-ui/layout-review/geometry.json) · [储值表单截图](../../../../../output/b-app-ui/layout-review/marketing-balance-375.png) · [底部动作对齐截图](../../../../../output/b-app-ui/layout-review/stock-align-375.png) · [评价表单截图](../../../../../output/b-app-ui/layout-review/review-reply-375.png)

## 页面清单

| 页面 | 本轮处置 | 卡片/输入/动作静态位置数 |
|---|---|---|
| `account` | 保留现有结构 | 0 / 0 / 0 |
| `activities` | 保留现有结构 | 1 / 0 / 1 |
| `activity-create` | 保留现有结构 | 5 / 0 / 3 |
| `activity-edit` | 保留现有结构 | 1 / 19 / 9 |
| `after-sale` | 保留现有结构 | 1 / 2 / 6 |
| `apply` | 保留现有结构 | 8 / 7 / 2 |
| `coupon` | 保留现有结构 | 1 / 0 / 3 |
| `coupon-edit` | 保留现有结构 | 0 / 10 / 4 |
| `coupon-issues` | 保留现有结构 | 1 / 0 / 1 |
| `coupon-send` | 保留现有结构 | 0 / 0 / 2 |
| `coupons` | 保留现有结构 | 1 / 0 / 1 |
| `cross-store` | 保留现有结构 | 3 / 0 / 2 |
| `customers` | 保留现有结构 | 1 / 1 / 1 |
| `delivery` | 保留现有结构 | 2 / 4 / 2 |
| `deposit` | 修正分组/标题 | 3 / 0 / 0 |
| `entities` | 保留现有结构 | 1 / 0 / 1 |
| `entity-detail` | 保留现有结构 | 6 / 0 / 0 |
| `goods-edit` | 修正分组/标题 | 9 / 27 / 25 |
| `goods-list` | 保留现有结构 | 1 / 1 / 6 |
| `goods-publish` | 修正分组/标题 | 4 / 0 / 2 |
| `goods-revision` | 保留现有结构 | 4 / 0 / 1 |
| `goods-revisions` | 保留现有结构 | 4 / 0 / 0 |
| `group` | 保留现有结构 | 0 / 0 / 2 |
| `group-open` | 保留现有结构 | 0 / 0 / 2 |
| `groups` | 保留现有结构 | 1 / 0 / 1 |
| `home` | 保留现有结构 | 5 / 0 / 1 |
| `income` | 保留现有结构 | 7 / 0 / 0 |
| `invoice` | 修正分组/标题 | 4 / 2 / 1 |
| `locations` | 保留现有结构 | 2 / 0 / 0 |
| `login` | 保留现有结构 | 1 / 3 / 3 |
| `marketing` | 保留现有结构 | 2 / 0 / 1 |
| `marketing-assets` | 修正分组/标题 | 10 / 1 / 8 |
| `me` | 保留现有结构 | 2 / 0 / 0 |
| `member-add` | 保留现有结构 | 1 / 2 / 1 |
| `member-detail` | 修正分组/标题 | 5 / 0 / 2 |
| `member-reach` | 保留现有结构 | 6 / 2 / 1 |
| `member-segment` | 保留现有结构 | 0 / 0 / 2 |
| `member-segments` | 保留现有结构 | 1 / 0 / 0 |
| `member-settings` | 保留现有结构 | 3 / 0 / 0 |
| `member-tag` | 保留现有结构 | 0 / 0 / 2 |
| `member-tags` | 保留现有结构 | 2 / 0 / 0 |
| `messages` | 保留现有结构 | 2 / 0 / 0 |
| `my-specs` | 保留现有结构 | 0 / 3 / 6 |
| `notify-settings` | 保留现有结构 | 4 / 0 / 0 |
| `offline-sale` | 保留现有结构 | 3 / 0 / 1 |
| `order` | 修正分组/标题 | 15 / 4 / 11 |
| `orders` | 保留现有结构 | 2 / 0 / 0 |
| `payment` | 保留现有结构 | 3 / 3 / 3 |
| `payout-account` | 修正分组/标题 | 3 / 4 / 1 |
| `period` | 保留现有结构 | 1 / 0 / 5 |
| `periods` | 保留现有结构 | 1 / 0 / 0 |
| `picking` | 保留现有结构 | 2 / 0 / 1 |
| `plan` | 保留现有结构 | 3 / 0 / 3 |
| `platform-activities` | 保留现有结构 | 1 / 0 / 0 |
| `platform-activity-apply` | 保留现有结构 | 0 / 1 / 3 |
| `points` | 修正分组/标题 | 4 / 0 / 3 |
| `points-records` | 修正分组/标题 | 2 / 0 / 1 |
| `print-center` | 修正分组/标题 | 12 / 1 / 16 |
| `print-devices` | 修正分组/标题 | 7 / 5 / 14 |
| `print-templates` | 修正分组/标题 | 7 / 2 / 6 |
| `purchase-edit` | 保留现有结构 | 3 / 0 / 2 |
| `qualifications` | 保留现有结构 | 4 / 3 / 3 |
| `quotes` | 保留现有结构 | 1 / 3 / 3 |
| `reach-task` | 保留现有结构 | 2 / 0 / 2 |
| `reach-tasks` | 保留现有结构 | 1 / 0 / 0 |
| `recharge-activity-edit` | 保留现有结构 | 6 / 4 / 3 |
| `reviews` | 回复/申诉独立字段组 | 1 / 2 / 4 |
| `role-detail` | 保留现有结构 | 3 / 1 / 2 |
| `schedule` | 修正分组/标题 | 2 / 4 / 1 |
| `settle` | 修正分组/标题 | 8 / 0 / 0 |
| `ship-settings` | 保留现有结构 | 3 / 4 / 0 |
| `sku-identity` | 保留现有结构 | 4 / 1 / 2 |
| `staff` | 保留现有结构 | 4 / 3 / 4 |
| `staff-detail` | 保留现有结构 | 4 / 0 / 1 |
| `statement` | 修正分组/标题 | 3 / 0 / 1 |
| `stats` | 保留现有结构 | 5 / 0 / 0 |
| `stock` | 保留现有结构 | 4 / 0 / 2 |
| `stock-align` | 修正分组/标题 | 2 / 0 / 4 |
| `stock-check` | 保留现有结构 | 6 / 2 / 2 |
| `stock-cross` | 保留现有结构 | 1 / 0 / 0 |
| `stock-detail` | 保留现有结构 | 1 / 0 / 1 |
| `stock-docs` | 保留现有结构 | 2 / 0 / 0 |
| `stock-out` | 保留现有结构 | 4 / 0 / 1 |
| `stock-report` | 保留现有结构 | 1 / 0 / 0 |
| `stock-settings` | 修正分组/标题 | 3 / 0 / 0 |
| `stock-sync-records` | 保留现有结构 | 1 / 0 / 0 |
| `store` | 保留现有结构 | 4 / 2 / 0 |
| `store-categories` | 保留现有结构 | 1 / 0 / 1 |
| `store-notice` | 保留现有结构 | 3 / 1 / 1 |
| `store-pick` | 保留现有结构 | 1 / 0 / 1 |
| `store-scope` | 保留现有结构 | 3 / 3 / 4 |
| `store-scope-polygon` | 保留现有结构 | 0 / 0 / 0 |
| `stores` | 保留现有结构 | 3 / 2 / 3 |
| `suppliers` | 保留现有结构 | 1 / 0 / 0 |
| `transfer` | 保留现有结构 | 7 / 1 / 4 |
| `verify` | 保留现有结构 | 5 / 3 / 11 |

清单中的保留表示本轮源码结构规则未发现新错误，不代表96页全状态视觉或真机验收。查看数据JSON可追溯每页与每组件。
