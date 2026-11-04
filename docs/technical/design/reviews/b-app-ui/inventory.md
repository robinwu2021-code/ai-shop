# B 端 UI 全量源码清单

> 状态：当前工作区的源码盘点；实施与验收结果见 review.md。由 `node scripts/audit-b-app-ui.mjs` 生成，勿手改。

## 范围与口径

当前工作区 B 端全部 Vue/TS、共享 UI Vue、三语词库及路由；静态盘点，未做运行时视觉验收。

- 动态 key 不推断为缺失或未使用；无静态引用不等于孤儿。

- 直写中文覆盖 Vue 模板/表达式/脚本字符串；script-string 也可能是日志、默认值或内部数据，须人工判断。服务端、外部共享工具、原生插件与英文直写不保证穷尽。

- 重复中文不等于业务同义；布局候选不等于已确认缺陷。

- 组件用量为模板静态引用；不含运行时动态组件与循环实际渲染次数。

共享 UI 的引用也参与词条分析；C 端页面不参与统计。行号对应生成时工作区，源码改动后请重新运行。

完整三语文案及静态引用见 [copy-catalog.json](./copy-catalog.json)；候选项、组件 props/依赖/使用位置与每页 CSS 规模见 [audit.json](./audit.json)。

| 项目 | 数量 |
| --- | --- |
| pages | 93 |
| registeredRoutes | 93 |
| components | 42 |
| localeKeys | 3098 |
| catalogEntries | 3098 |
| references | 2826 |
| dynamicReferences | 264 |
| duplicateGroups | 265 |
| duplicateKeys | 693 |
| literalCandidates | 1 |
| longCopyCandidates | 15 |
| layoutCandidates | 19 |

## 页面清单

| 源码页面 | 已注册 | 直接使用的组件（模板次数） | 静态文案引用 | 直写中文候选 | 布局候选 |
| --- | --- | --- | --- | --- | --- |
| account/index.vue | 是 | sh-scaffold ×1、sh-icon ×2 | 13 | 0 | 0 |
| activities/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1、sh-actionbar ×1 | 29 | 0 | 0 |
| activity-edit/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-icon ×10、sh-actionbar ×4、sh-sheet ×4、biz-audience-picker ×1、sh-empty ×1 | 125 | 0 | 0 |
| after-sale/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-stepper ×1、sh-option ×2 | 43 | 0 | 0 |
| apply/index.vue | 是 | sh-scaffold ×1、biz-rule-help ×1、sh-empty ×1、sh-section ×3、sh-icon ×4、sh-option ×1、sh-switch ×1、sh-check ×1、sh-uploader ×1、sh-add ×2 | 75 | 0 | 0 |
| coupon/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-icon ×1、sh-actionbar ×1 | 16 | 0 | 0 |
| coupon-edit/index.vue | 是 | sh-scaffold ×1、sh-icon ×2、sh-actionbar ×2、sh-sheet ×1 | 45 | 0 | 0 |
| coupon-issues/index.vue | 是 | sh-scaffold ×1、sh-actionbar ×1、sh-empty ×1 | 10 | 0 | 0 |
| coupon-send/index.vue | 是 | sh-scaffold ×1、sh-icon ×1、sh-actionbar ×1、biz-audience-picker ×1 | 15 | 0 | 0 |
| coupons/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1、sh-actionbar ×1 | 9 | 0 | 0 |
| cross-store/index.vue | 是 | sh-scaffold ×1、sh-rating ×1、sh-empty ×1 | 32 | 0 | 0 |
| customers/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-empty ×1、sh-icon ×1、biz-batch-tag-sheet ×1 | 33 | 0 | 0 |
| delivery/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、sh-empty ×1 | 16 | 0 | 0 |
| deposit/index.vue | 是 | sh-scaffold ×1、sh-kv ×5、sh-empty ×1 | 17 | 0 | 0 |
| entities/index.vue | 是 | sh-scaffold ×1、sh-icon ×1 | 15 | 0 | 0 |
| entity-detail/index.vue | 是 | sh-scaffold ×1、sh-icon ×3、sh-go ×1 | 24 | 0 | 0 |
| goods-edit/index.vue | 是 | sh-scaffold ×1、sh-uploader ×1、sh-icon-btn ×3、sh-cover ×1、sh-icon ×10、sh-switch ×1、sh-kv ×4、sh-sheet ×7、sh-section ×5、sh-add ×2、sh-go ×4、sh-option ×1、sh-actionbar ×1 | 248 | 0 | 0 |
| goods-list/index.vue | 是 | sh-scaffold ×1、sh-icon-btn ×1、sh-tabs ×1、sh-empty ×1、sh-cover ×1、sh-icon ×1、sh-fab ×1、sh-sheet ×1 | 42 | 0 | 0 |
| goods-publish/index.vue | 是 | sh-scaffold ×1、sh-icon ×2 | 15 | 0 | 0 |
| goods-revision/index.vue | 是 | sh-scaffold ×1、sh-icon ×2、sh-actionbar ×1 | 14 | 0 | 0 |
| goods-revisions/index.vue | 是 | sh-scaffold ×1 | 10 | 0 | 0 |
| group/index.vue | 是 | sh-scaffold ×1、sh-icon ×1、sh-actionbar ×1 | 16 | 0 | 0 |
| group-open/index.vue | 是 | sh-scaffold ×1、sh-icon ×7、sh-empty ×1、sh-actionbar ×1、sh-sheet ×3 | 20 | 0 | 0 |
| groups/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1、sh-actionbar ×1 | 15 | 0 | 0 |
| home/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-go ×1、sh-stat ×2、sh-icon ×2 | 38 | 0 | 0 |
| income/index.vue | 是 | sh-scaffold ×1、sh-icon ×1 | 30 | 0 | 0 |
| invoice/index.vue | 是 | sh-scaffold ×1、sh-kv ×3、sh-section ×1、sh-go ×1、sh-empty ×1 | 26 | 0 | 0 |
| locations/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-icon ×1、sh-add ×1、sh-section ×1 | 13 | 0 | 0 |
| login/index.vue | 是 | sh-scaffold ×1、sh-check ×1 | 23 | 0 | 0 |
| marketing/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-icon ×8 | 19 | 0 | 0 |
| me/index.vue | 是 | sh-scaffold ×1、sh-icon ×12、sh-theme-sheet ×1 | 25 | 0 | 0 |
| member-add/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 10 | 0 | 0 |
| member-detail/index.vue | 是 | sh-scaffold ×1、sh-kv ×3、sh-icon ×3、sh-sheet ×1、sh-empty ×1 | 22 | 0 | 0 |
| member-reach/index.vue | 是 | sh-scaffold ×1、sh-icon ×1、sh-empty ×1、sh-stat ×1、sh-go ×1、biz-audience-picker ×1 | 21 | 0 | 0 |
| member-segment/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-icon ×1、sh-empty ×1、sh-actionbar ×1 | 27 | 0 | 0 |
| member-segments/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、biz-rule-help ×1 | 15 | 0 | 0 |
| member-settings/index.vue | 是 | sh-scaffold ×1、sh-option ×2、sh-switch ×1、sh-kv ×4 | 21 | 0 | 0 |
| member-tag/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-icon ×4、sh-empty ×1、sh-actionbar ×1 | 26 | 0 | 0 |
| member-tags/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 24 | 0 | 0 |
| messages/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-go ×2、sh-empty ×1 | 6 | 0 | 0 |
| my-specs/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-section ×1、sh-add ×1、sh-icon ×3、sh-icon-btn ×2、sh-empty ×1、sh-sheet ×1 | 28 | 0 | 1 |
| notify-settings/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、sh-icon ×3 | 30 | 0 | 0 |
| offline-sale/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、sh-add ×1、sh-icon ×1、sh-section ×1、sh-empty ×1、sh-actionbar ×1、biz-item-picker ×1 | 21 | 0 | 0 |
| order/index.vue | 是 | sh-scaffold ×1、sh-go ×4、sh-cover ×1、sh-dialog ×1、sh-sheet ×1、sh-trace ×1 | 81 | 0 | 0 |
| orders/index.vue | 是 | sh-scaffold ×1、sh-go ×1、sh-tabs ×1、sh-empty ×1、sh-cover ×1 | 11 | 0 | 0 |
| payment/index.vue | 是 | sh-scaffold ×1、sh-kv ×1、sh-uploader ×1 | 35 | 0 | 0 |
| payout-account/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 17 | 0 | 0 |
| period/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-actionbar ×1 | 25 | 0 | 0 |
| periods/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1 | 12 | 0 | 0 |
| picking/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1、sh-cover ×1、sh-actionbar ×1 | 18 | 0 | 0 |
| plan/index.vue | 是 | sh-scaffold ×1、sh-icon ×1 | 27 | 0 | 0 |
| platform-activities/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1 | 14 | 0 | 0 |
| platform-activity-apply/index.vue | 是 | sh-scaffold ×1、sh-icon ×2、sh-actionbar ×1、sh-sheet ×1、sh-empty ×1 | 37 | 0 | 0 |
| points/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、StorePointStatements ×1 | 25 | 0 | 0 |
| points-records/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 4 | 0 | 0 |
| print-center/index.vue | 是 | sh-scaffold ×1、sh-stat ×2 | 43 | 0 | 0 |
| print-devices/index.vue | 是 | sh-scaffold ×1 | 37 | 0 | 0 |
| print-templates/index.vue | 是 | sh-scaffold ×1、sh-check ×2 | 19 | 0 | 0 |
| purchase-edit/index.vue | 是 | sh-scaffold ×1、sh-kv ×2、sh-add ×1、sh-icon ×1、sh-actionbar ×1、biz-item-picker ×1、biz-supplier-picker ×1 | 19 | 0 | 0 |
| qualifications/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-uploader ×1 | 23 | 0 | 0 |
| quotes/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 22 | 0 | 0 |
| reach-task/index.vue | 是 | sh-scaffold ×1、sh-section ×1、sh-empty ×2、sh-actionbar ×1、biz-batch-tag-sheet ×1 | 19 | 0 | 0 |
| reach-tasks/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1 | 15 | 0 | 0 |
| reviews/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-rating ×1 | 22 | 0 | 0 |
| role-detail/index.vue | 是 | sh-scaffold ×1、sh-icon ×1 | 17 | 0 | 0 |
| schedule/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 19 | 0 | 0 |
| settle/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 37 | 0 | 0 |
| ship-settings/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、sh-icon ×1、sh-savebar ×1 | 26 | 0 | 0 |
| sku-identity/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-kv ×4、sh-actionbar ×1 | 31 | 0 | 0 |
| staff/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×2、sh-icon ×2 | 28 | 0 | 0 |
| staff-detail/index.vue | 是 | sh-scaffold ×1 | 16 | 0 | 0 |
| statement/index.vue | 是 | sh-scaffold ×1、sh-section ×1、sh-go ×1、sh-kv ×6、sh-empty ×1 | 32 | 0 | 0 |
| stats/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、sh-rating ×1、sh-icon ×1 | 12 | 0 | 0 |
| stock/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-tabs ×1、sh-empty ×1、sh-actionbar ×1、sh-go ×1、sh-icon ×4 | 20 | 0 | 1 |
| stock-align/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-actionbar ×1 | 17 | 0 | 0 |
| stock-check/index.vue | 是 | sh-scaffold ×1、sh-empty ×2、sh-check ×1、sh-actionbar ×2 | 37 | 0 | 0 |
| stock-cross/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-go ×1 | 6 | 0 | 0 |
| stock-detail/index.vue | 是 | sh-scaffold ×1、sh-stat ×1、sh-section ×3、sh-kv ×8、sh-empty ×1 | 38 | 0 | 0 |
| stock-docs/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-empty ×1、sh-icon ×1 | 16 | 0 | 0 |
| stock-out/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、sh-kv ×4、sh-add ×1、sh-icon ×1、sh-actionbar ×1、biz-item-picker ×1 | 22 | 0 | 0 |
| stock-report/index.vue | 是 | sh-scaffold ×1、sh-section ×4、biz-rule-help ×1、sh-kv ×9、sh-empty ×1、sh-icon ×2 | 22 | 0 | 0 |
| stock-settings/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-switch ×2、sh-section ×2、sh-go ×4 | 19 | 0 | 0 |
| stock-sync-records/index.vue | 是 | sh-scaffold ×1、sh-empty ×1 | 6 | 0 | 0 |
| store/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、biz-time-range ×1、sh-icon ×2、sh-uploader ×1、sh-savebar ×1 | 31 | 0 | 0 |
| store-categories/index.vue | 是 | sh-scaffold ×1、sh-switch ×1、sh-empty ×1、sh-actionbar ×1、biz-category-sheet ×1 | 10 | 0 | 0 |
| store-notice/index.vue | 是 | sh-scaffold ×1、sh-actionbar ×1、sh-icon-btn ×1 | 18 | 0 | 0 |
| store-pick/index.vue | 是 | sh-scaffold ×1、sh-check ×1、biz-switch-curtain ×1 | 10 | 0 | 0 |
| store-scope/index.vue | 是 | sh-scaffold ×1、biz-store-tag ×1、sh-icon ×3、sh-icon-btn ×1、sh-switch ×3、sh-go ×4、sh-check ×1、biz-pickup-sheet ×1、biz-region-picker ×1、biz-scope-text ×1、sh-savebar ×1 | 85 | 0 | 0 |
| store-scope-polygon/index.vue | 是 | sh-scaffold ×1 | 9 | 0 | 0 |
| stores/index.vue | 是 | sh-scaffold ×1、sh-icon ×1、sh-option ×1、biz-switch-curtain ×1 | 50 | 0 | 0 |
| suppliers/index.vue | 是 | sh-scaffold ×1、sh-empty ×1、sh-add ×1 | 17 | 0 | 0 |
| transfer/index.vue | 是 | sh-scaffold ×1、sh-icon ×4、sh-empty ×1、sh-kv ×1、sh-actionbar ×3、sh-go ×2、sh-add ×1、biz-item-picker ×1、sh-sheet ×1 | 36 | 0 | 0 |
| verify/index.vue | 是 | sh-scaffold ×1、sh-tabs ×1、sh-icon ×2、sh-empty ×1 | 42 | 0 | 0 |

## 组件清单

页数/次数均指 B 端页面直接使用；间接依赖另列，以免把外壳内部组件误报为未使用。props 以 audit.json 中从源码提取的声明为准；用途说明引用现有 ui-lib.json，空白项需人工补充。

| 组件 | 用途 | 源码 | 直接页面数/次数 | 内部组件依赖 |
| --- | --- | --- | --- | --- |
| app-overlay | 应用常驻层 | b-app/src/components/app-overlay.vue | 0/0 |  |
| biz-audience-picker | 选人面板（原型 m12 / m13） | b-app/src/components/biz/biz-audience-picker.vue | 3/3 | sh-sheet、sh-icon |
| biz-batch-tag-sheet | 批量打 / 去一个标签（原型 m06；名单「对这批人」与效果页「下单的人打标签」两处用） | b-app/src/components/biz/biz-batch-tag-sheet.vue | 2/2 | sh-sheet、sh-icon、sh-empty |
| biz-category-sheet | 调整经营类目 —— 经营类目页与建商品页共用的底部面板（TDD-门店经营类目 §4.2） | b-app/src/components/biz/biz-category-sheet.vue | 1/1 | sh-sheet、sh-option |
| biz-item-picker | 挑货 —— 开单时选货那一步，进货 / 报损 / 调拨三处共用 | b-app/src/components/biz/biz-item-picker.vue | 4/4 | sh-sheet、sh-empty |
| biz-pickup-sheet | 自提点选择弹层 | b-app/src/components/biz/biz-pickup-sheet.vue | 1/1 | sh-sheet、sh-check、biz-time-range、sh-icon |
| biz-region-picker | 经营范围选择器 | b-app/src/components/biz/biz-region-picker.vue | 1/1 | sh-sheet、sh-tabs、sh-icon、sh-icon-btn、sh-check |
| biz-rule-help | 按需打开的规则说明（复用 sh-sheet） | b-app/src/components/biz/biz-rule-help.vue | 3/3 | sh-sheet |
| biz-scope-text | 经营范围文字录入（TDD-经营范围文字录入 AC5/AC6）：写一句话 → 识别 → 确认 → 填进清单 | b-app/src/components/biz/biz-scope-text.vue | 1/1 | sh-sheet、sh-check、sh-switch |
| biz-store-tag | 当前门店胶囊 | b-app/src/components/biz/biz-store-tag.vue | 8/8 | sh-icon、sh-go |
| biz-supplier-picker | 挑供应商 —— 开进货单时选「这批货从哪来」 | b-app/src/components/biz/biz-supplier-picker.vue | 1/1 | sh-sheet、sh-empty |
| biz-switch-curtain | 切店幕布：整屏主色、大字店名 | b-app/src/components/biz/biz-switch-curtain.vue | 2/2 | sh-icon |
| biz-time-range | 时段输入 | b-app/src/components/biz/biz-time-range.vue | 1/1 |  |
| StorePointStatements | 门店积分账单：财务人员查看当前及历史修订，确认账单或提出异议 | b-app/src/components/points/StorePointStatements.vue | 1/1 |  |
| sh-actionbar | 悬浮内缩的贴底操作条 —— 第三种贴底形态，此前库里没有 | packages/ui/src/components/sh-actionbar.vue | 28/35 |  |
| sh-add | ＋ 加一项按钮 | packages/ui/src/components/sh-add.vue | 9/11 | sh-icon |
| sh-check | 勾选框 / 单选点 | packages/ui/src/components/sh-check.vue | 6/7 | sh-icon |
| sh-confirm | 确认弹层的壳 | packages/ui/src/components/sh-confirm.vue | 0/0 | sh-dialog |
| sh-cover | 商品封面 | packages/ui/src/components/sh-cover.vue | 5/5 |  |
| sh-dialog | 居中对话框的外壳：遮罩 + 居中面板 + 可选标题 + 内容插槽 + 可选动作条 | packages/ui/src/components/sh-dialog.vue | 1/1 |  |
| sh-empty | 空态 | packages/ui/src/components/sh-empty.vue | 56/59 |  |
| sh-fab | 悬浮新建按钮 | packages/ui/src/components/sh-fab.vue | 1/1 |  |
| sh-go | 带箭头的行内链接：「去填地址 ›」「切换门店 ›」「查看详情 ›」这一类 | packages/ui/src/components/sh-go.vue | 14/28 | sh-icon |
| sh-icon-btn | 图标按钮 | packages/ui/src/components/sh-icon-btn.vue | 5/8 | sh-icon |
| sh-icon | 单色图标 | packages/ui/src/components/sh-icon.vue | 43/117 |  |
| sh-kv | 键值行：左边一个名目，右边它的值 | packages/ui/src/components/sh-kv.vue | 13/54 |  |
| sh-option | 选项卡片 | packages/ui/src/components/sh-option.vue | 5/7 |  |
| sh-pick | 「选一项」弹层的壳 | packages/ui/src/components/sh-pick.vue | 0/0 | sh-sheet、sh-icon |
| sh-prompt | 输入弹层的壳 | packages/ui/src/components/sh-prompt.vue | 0/0 |  |
| sh-rating | 评分 | packages/ui/src/components/sh-rating.vue | 3/3 |  |
| sh-savebar | 底部未保存条 | packages/ui/src/components/sh-savebar.vue | 3/3 |  |
| sh-scaffold | 页面外壳 | packages/ui/src/components/sh-scaffold.vue | 93/93 | sh-icon、sh-empty、sh-tabbar、sh-prompt、sh-confirm、sh-pick、app-overlay |
| sh-section | 卡内标题行 | packages/ui/src/components/sh-section.vue | 11/23 |  |
| sh-sheet | 底部弹层 | packages/ui/src/components/sh-sheet.vue | 10/21 | sh-icon-btn |
| sh-stat | 数字格 | packages/ui/src/components/sh-stat.vue | 13/15 |  |
| sh-stepper | 数量步进器：减 · 数 · 加，装在一枚弱色药丸里 | packages/ui/src/components/sh-stepper.vue | 1/1 | sh-icon |
| sh-switch | 开关 | packages/ui/src/components/sh-switch.vue | 6/9 |  |
| sh-tabbar | 自定义底部菜单 | packages/ui/src/components/sh-tabbar.vue | 0/0 | sh-icon |
| sh-tabs | 筛选条 | packages/ui/src/components/sh-tabs.vue | 15/15 |  |
| sh-theme-sheet | 外观面板 | packages/ui/src/components/sh-theme-sheet.vue | 1/1 | sh-icon |
| sh-trace | 物流轨迹（TDD-物流轨迹多渠道；原型 prototypes/logistics-trace.html s01–s03、 | packages/ui/src/components/sh-trace.vue | 1/1 |  |
| sh-uploader | 图片格 | packages/ui/src/components/sh-uploader.vue | 5/5 | sh-cover、sh-icon |

## 原生组件

| 组件 | 直接页面数 | 模板次数 |
| --- | --- | --- |
| view | 93 | 1488 |
| text | 93 | 1804 |
| button | 3 | 36 |
| picker | 7 | 15 |
| input | 31 | 128 |
| textarea | 7 | 10 |
| scroll-view | 1 | 1 |
| image | 2 | 3 |
| switch | 3 | 3 |
| map | 1 | 1 |
| cover-view | 1 | 3 |

## 全局公共类

仅统计 base.css 的声明及页面静态 class 属性；动态 :class 和 UnoCSS 工具类不计入次数。B 端 .field 的间距覆盖在 App.vue。

| 公共类 | 直接页面数 | 静态使用次数 |
| --- | --- | --- |
| field | 15 | 58 |
| field__area | 6 | 9 |
| field__head | 1 | 5 |
| field__input | 25 | 73 |
| field__label | 18 | 67 |
| sh-actions | 3 | 5 |
| sh-badge-count | 1 | 1 |
| sh-block | 4 | 10 |
| sh-block__body | 1 | 1 |
| sh-block__head | 0 | 0 |
| sh-block__head--tabs | 0 | 0 |
| sh-btn | 65 | 221 |
| sh-btn--danger | 8 | 8 |
| sh-btn--danger-solid | 1 | 1 |
| sh-btn--md | 1 | 2 |
| sh-btn--muted | 24 | 46 |
| sh-btn--sm | 15 | 83 |
| sh-btn--soft | 19 | 61 |
| sh-card | 83 | 266 |
| sh-card__head | 21 | 41 |
| sh-cell | 21 | 160 |
| sh-cells | 17 | 52 |
| sh-center | 9 | 13 |
| sh-chip | 58 | 152 |
| sh-chip--danger | 5 | 6 |
| sh-chip--dashed | 2 | 7 |
| sh-chip--dashed-quiet | 1 | 1 |
| sh-chip--icon | 6 | 7 |
| sh-chip--primary | 16 | 25 |
| sh-chip--solid | 1 | 1 |
| sh-chip--success | 1 | 1 |
| sh-chip--warning | 7 | 10 |
| sh-dialog__act | 1 | 2 |
| sh-dialog__acts | 0 | 0 |
| sh-fill | 58 | 163 |
| sh-grip | 0 | 0 |
| sh-hint | 39 | 106 |
| sh-hit | 4 | 33 |
| sh-link | 25 | 67 |
| sh-link--quiet | 6 | 15 |
| sh-link--warn | 2 | 4 |
| sh-mask | 0 | 0 |
| sh-mb-md | 1 | 3 |
| sh-mb-sm | 18 | 30 |
| sh-mt-md | 9 | 11 |
| sh-mt-sm | 31 | 115 |
| sh-mt-xs | 10 | 24 |
| sh-muted | 83 | 512 |
| sh-notice | 13 | 27 |
| sh-notice--danger | 4 | 6 |
| sh-notice--muted | 0 | 0 |
| sh-notice--success | 2 | 2 |
| sh-notice--warning | 8 | 14 |
| sh-num | 67 | 332 |
| sh-panel | 0 | 0 |
| sh-ph | 0 | 0 |
| sh-root | 0 | 0 |
| sh-row | 90 | 562 |
| sh-row--baseline | 21 | 32 |
| sh-row--between | 76 | 323 |
| sh-row--divided | 9 | 25 |
| sh-row--top | 1 | 1 |
| sh-scaffold | 0 | 0 |
| sh-scrollx | 0 | 0 |
| sh-searchbox | 2 | 2 |
| sh-seg | 3 | 7 |
| sh-seg--fill | 1 | 2 |
| sh-seg--off | 0 | 0 |
| sh-seg--on | 0 | 0 |
| sh-stack | 3 | 24 |
| sh-void | 2 | 3 |
| sh-was | 0 | 0 |
| sh-wrap | 25 | 49 |
| txt-body | 48 | 396 |
| txt-bold | 7 | 12 |
| txt-caption | 80 | 393 |
| txt-display | 25 | 35 |
| txt-faint | 0 | 0 |
| txt-hero | 3 | 3 |
| txt-ink | 3 | 5 |
| txt-mega | 6 | 6 |
| txt-price | 10 | 16 |
| txt-primary | 13 | 16 |
| txt-quiet | 12 | 38 |
| txt-strong | 61 | 144 |
| txt-sub | 29 | 101 |
| txt-title | 41 | 120 |

## 重复中文分组（待语义审查）

共用 common key 的候选优先排列；三语相同仍需核对动作、对象与上下文，禁止批量按字符串合并。

| 中文 | keys | 已有通用 key | 三语值均相同 |
| --- | --- | --- | --- |
| 已驳回 | apply.statusREJECTED、goods.statusREJECTED、goods.revStatusREJECTED、payment.status.REJECTED、store.pickup.stREJECTED、afterSale.status.REJECTED、afterSale.rejected、invoice.statusREJECTED、payoutAccount.status.REJECTED、me.statusREJECTED | — | 否 |
| 商品 | tab.goods、goods.title、order.items、reviews.dimGoods、platformApply.goods、group.goods、groupOpen.goods、activityEdit.comboType.GOODS、activityEdit.goods、couponEdit.scope | — | 否 |
| 审核中 | apply.statusAPPLYING、goods.statusPENDING、payment.status.APPLYING、store.noticeAuditing、store.pickup.stPENDING、entities.stReviewing、me.statusAPPLYING | — | 否 |
| 进行中 | groups.tab.OPEN、groups.status.OPEN、activities.tab.live、activities.status.live、activityEdit.status.RUNNING、stockCheck.counting、stockDocs.status.COUNTING | — | 否 |
| 已结束 | platformActs.tab.ENDED、platformActs.ended、activities.tab.ended、activityEdit.status.ENDED、coupons.tab.ENDED、coupons.status.ENDED、reachTasks.couponEnded | — | 否 |
| 已停用 | stores.disabled、staff.disabled、store.pickup.stSUSPENDED、payoutAccount.status.DISABLED、crossStore.disabled、memberTags.disabled、suppliers.archived | — | 否 |
| 营业执照 | apply.qualBUSINESS_LICENSE、qual.typeLicense、payment.missing.licenses、payment.licenses、entities.title、entityDetail.license | — | 否 |
| 活动 | platformApply.activity、group.activity、groupOpen.activity、marketing.activities、activities.title、message.tab.MARKETING | — | 否 |
| 清空 | skuIdentity.ruleDashV、goods.clear、goods.clearOk、store.timeRange.clear、store.polygonClear、stockCheck.clearAll | — | 否 |
| 盘点 | stock.entry.check、stock.reason.CHECK、stockCheck.title、stockDocs.label.COUNT、stockDocs.kindCount、stockDocs.kind.COUNT | — | 否 |
| 收起 | trace.fold、common.collapse、goods.moreFold、goods.specFold、store.picker.collapse、store.collapse | common.collapse | 否 |
| 不限 | goods.limitNone、group.anyPickup、groupOpen.anyPickup、activityEdit.unlimited、couponSend.unlimited | — | 否 |
| 待核销 | home.cell.toVerify、order.statusARRIVED、order.tabToVerify、verify.ovPending、verify.waiting | — | 否 |
| {n} 笔 | income.batchBills、income.dailyBills、income.inFlightHint、statement.billCountValue、invoice.billCountValue | — | 否 |
| 已完成 | order.statusCOMPLETED、order.tabDone、stockSync.operationStatus.COMPLETED、stockAlign.lineStatus.COMPLETED、stockAlign.lineStatus.DONE | — | 否 |
| 订单 | printCenter.order、tab.orders、home.orders、order.title、stats.orders | — | 否 |
| 已取消 | printCenter.status.CANCELLED、order.statusCANCELLED、order.expressStatus.CANCELLED、periods.tab.CANCELLED、periods.status.CANCELLED | — | 否 |
| 待处理 | printCenter.status.PENDING、printCenter.status.OPEN、periods.tab.SHORT、message.tab.pending、stockAlign.lineStatus.PENDING | — | 否 |
| 报损 | stock.entry.out、stock.reason.SCRAP、stockDocs.label.SCRAP、stockOut.title、stockOut.purpose.SCRAP | — | 否 |
| 调拨 | stock.entry.transfer、stockDocs.label.TRANSFER、stockDocs.kindTransfer、stockDocs.kind.TRANSFER、transfer.title | — | 否 |
| 已作废 | stockCheck.voided、stockDocs.status.VOIDED、stockDocs.voided、transfer.voided、transfer.status.VOIDED | — | 否 |
| 待审核 | store.picker.pendingTag、store.areaPending、platformActs.status.SUBMITTED、groups.status.PENDING、payoutAccount.status.PENDING | — | 否 |
| {n} 人 | stores.staffValue、activityEdit.coverN、couponIssues.people、memberSegments.count、memberTags.count | — | 否 |
| 人群 | activityEdit.audience、couponSend.audience、audience.activityTitle、memberSegments.title | — | 否 |
| 删除 | common.delete、apply.qualRemove、goods.removeParam、memberSegments.remove | common.delete | 否 |
| 商家配送 | goods.fulfillmentType.MERCHANT_DELIVERY、delivery.title、channel.MERCHANT_DELIVERY、fulfillmentLabel.MERCHANT_DELIVERY | — | 是 |
| 待发货 | home.cell.toShip、order.statusPAID、order.tabToShip、crossStore.toShip | — | 是 |
| 店铺公告 | home.noticeEntry、store.announcement、store.noticeTitle、reach.scene.NOTICE | — | 否 |
| 可用 {n} | offlineSale.availableN、stockOut.availableN、transfer.availableN、transfer.qtyHint | — | 否 |
| 已暂停 | printCenter.status.PAUSED、activities.tab.paused、activities.status.paused、activityEdit.status.PAUSED | — | 否 |
| 待核实 | printCenter.unknownLabel、points.statementStateUnknown、stockSync.operationStatus.UNKNOWN、stockAlign.lineStatus.UNKNOWN | — | 否 |
| 添加商品 | purchase.addItem、offlineSale.addItem、stockOut.addItem、transfer.addItem | — | 否 |
| 长期 | store.ttl.forever、activities.always、activityEdit.scheduleType.ALWAYS_ON、couponText.validNone | — | 否 |
| 有效期 | verify.couponValid、store.noticeTtl、coupon.validity、couponEdit.groupValidity | — | 否 |
| 所有人 | activityEdit.audienceOpt.ALL、audience.everyone、audience.coverAll | — | 是 |
| 元 | activityEdit.comboPh.CUT、couponEdit.facePh、couponEdit.capPh | — | 是 |
| 完成 | common.done、goods.restrictedDone、couponIssues.done | common.done | 是 |
| 编辑 | common.edit、goods.edit、store.edit | common.edit | 是 |
| 选填 | common.optional、goods.detailPh、goods.optional | common.optional | 是 |
| 手机号 | common.phone、apply.phone、notifySetting.phonePlaceholder | common.phone | 否 |
| 发放 | coupon.send、couponEdit.groupIssue、couponSend.title | — | 否 |
| {n} 张 | couponSend.leftN、couponText.total、entityDetail.qualsCount | — | 否 |
| 全部会员 | couponSend.preset.ALL、audience.allMembers、memberSegments.allMembers | — | 是 |
| 能收到 | couponSend.reachable、reach.reachable、memberSegment.reachable | — | 否 |
| 快递配送 | goods.fulfillmentType.EXPRESS、channel.EXPRESS、fulfillmentLabel.EXPRESS | — | 是 |
| 社区自提 | goods.fulfillmentType.NEIGHBOR_PICKUP、channel.NEIGHBOR_PICKUP、fulfillmentLabel.NEIGHBOR_PICKUP | — | 是 |
| 门店自提 | goods.fulfillmentType.STORE_PICKUP、channel.STORE_PICKUP、fulfillmentLabel.STORE_PICKUP | — | 是 |
| 价格 | goods.parsePrice、goods.price、goods.skuMatrix | — | 是 |
| 草稿 | goods.statusDRAFT、stockDocs.status.DRAFT、transfer.status.DRAFT | — | 是 |
| 库存 | goods.stock、goods.secStock、stock.title | — | 是 |
| 开团 | groups.open、groupOpen.title、groupOpen.submit | — | 是 |
| 缺货 | home.inv.shortage、goods.statusOUT_OF_STOCK、stock.statShortage | — | 否 |
| 滞销 | home.inv.stale、stock.statStale、stockReport.slow | — | 否 |
| 自带客流 | home.ownedTraffic、order.trafficMERCHANT_OWNED、customers.ownedTag | — | 否 |
| 请输入 11 位手机号 | login.phonePh、login.phoneInvalid、memberAdd.phonePh | — | 否 |
| 标签 | memberDetail.tags、memberAdd.tags、memberTags.title | — | 是 |
| 金额 | order.amount、activityEdit.comboType.AMOUNT、period.amount | — | 否 |
| 顾客 | order.buyer、verify.couponCustomer、customers.title | — | 否 |
| 已发货 | order.statusSHIPPED、order.tabShipped、order.shipped | — | 是 |
| 待付款 | order.statusWAIT_PAY、points.statementStateConfirmed、statement.st_CONFIRMED | — | 否 |
| 同步中 | order.syncPending、order.inventorySyncStatusValue.PENDING、stockSync.state.SYNCING | — | 否 |
| 未开始 | payment.status.NONE、activities.tab.pending、activities.status.pending | — | 否 |
| 提交中… | payment.submitting、invoice.submitting、payoutAccount.submitting | — | 是 |
| 使用中 | payoutAccount.current、payoutAccount.status.ACTIVE、memberTag.active | — | 否 |
| 已选 {n} 件 | platformApply.goodsN、activityEdit.goodsN、stockPick.picked | — | 否 |
| 份 | platformApply.quotaPh、period.qty、stock.uom.PORTION | — | 否 |
| 规则 | platformApply.rule、activityEdit.groupRule、couponEdit.groupRule | — | 否 |
| 待确认 | points.statementStateDraft、store.vPending、stockAlign.lineStatus.PREVIEW | — | 否 |
| 填一个大于 0 的整数 | purchase.qtyBad、offlineSale.qtyBad、stockOut.qtyBad | — | 是 |
| 合计 | purchase.total、offlineSale.totalQty、transfer.totalQty | — | 是 |
| 已过期 | qual.expired、plan.statusEXPIRED、stockAlign.lineStatus.EXPIRED | — | 否 |
| 收单中 | settle.batchStatusDRAFT、periods.tab.OPEN、periods.status.OPEN | — | 否 |
| 门店 | settle.store、memberSegment.store、locations.kind.STORE | — | 否 |
| 供应商 | stock.entry.suppliers、purchase.supplier、suppliers.title | — | 否 |
| 退供应商 | stock.reason.RETURN_SUPPLIER、stockDocs.label.RETURN_SUPPLIER、stockOut.purpose.RETURN_SUPPLIER | — | 否 |
| 在途 | stock.statTransit、stockDocs.status.SHIPPED、transfer.inTransit | — | 否 |
| 实存 {n} | stockAlign.onHand、stock.onHandN、purchase.onHandN | — | 否 |
| 作废这张单 | stockCheck.voidDoc、stockDocs.voidAction、transfer.void | — | 否 |
| 送货方式 | store.fulfillCard、store.fulfillTitle、store.reach | — | 是 |
| 经营范围 | store.scopeTitle、store.text.ph、store.scope | — | 否 |
| 今天 | store.ttl.todayAt、members.today、customers.today | — | 是 |
| 已签收 | trace.step.signed、order.expressStatus.DONE、afterSale.stage.RECEIVED | — | 否 |
| 核销 | verify.couponRedeem、verify.submit、verify.doIt | — | 否 |
| 结束 | activities.end、activityEdit.end | — | 否 |
| 新建活动 | activityCreate.title、activityEdit.title | — | 否 |
| 满 {n} | activityEdit.comboS.AMOUNT、couponText.min | — | 否 |
| 减 {n} | activityEdit.comboS.CUT、couponText.ruleCash | — | 是 |
| 请填写名称 | activityEdit.need.name、couponEdit.needTitle | — | 是 |
| {i} / 2 | activityEdit.progress、couponEdit.progress | — | 是 |
| 剩余 | activityEdit.statLeft、couponSend.left | — | 否 |
| 确认 | activityEdit.stepConfirm、couponEdit.stepConfirm | — | 是 |
| 填写 | activityEdit.stepFill、couponEdit.stepFill | — | 是 |
| 平台介入中 | afterSale.status.ARBITRATING、afterSale.disputed | — | 否 |
| 联系人 | apply.contact、payment.contactName | — | 否 |
| 如 张老板 | apply.contactPh、payment.contactNamePh | — | 是 |
| 店铺名称 | apply.name、home.quickStartTitle | — | 是 |
| 如 张记粮油 | apply.namePh、home.quickStartPh | — | 否 |
| 食品经营许可证 | apply.qualFOOD_PERMIT、qual.typeFoodPermit | — | 否 |
| 长期有效 | apply.qualForever、qual.longTerm | — | 是 |
| 结算账户 | apply.settle、payment.settleAccount | — | 是 |
| 分层 | audience.level、memberSegment.level | — | 否 |
| 来源 | audience.sources、memberSegment.source | — | 是 |
| 我的标签 | audience.tags、memberTags.mine | — | 是 |
| 全部 | common.all、message.tab.all | common.all | 是 |
| 请选择 | common.choose、audience.none | common.choose | 否 |
| 停用 | common.disable、staff.dangerTitle | common.disable | 否 |
| 启用 | common.enable、staff.logEnable | common.enable | 否 |
| 更多 | common.more、goods.more | common.more | 是 |
| 保存 | common.save、goods.save | common.save | 是 |
| 搜索 | common.search、store.picker.tabSearch | common.search | 是 |
| 有修改未保存 | common.unsaved、store.unsaved | common.unsaved | 否 |
| 数量 | coupon.quantity、couponEdit.total | — | 是 |
| 门槛 | coupon.threshold、couponEdit.min | — | 是 |
| 类型 | coupon.type、couponEdit.kind | — | 是 |
| 最多支出 | couponEdit.maxSpend、couponSend.maxSpend | — | 是 |
| 在发 | coupons.tab.ACTIVE、coupons.status.ACTIVE | — | 是 |
| 已停 | coupons.tab.PAUSED、coupons.status.PAUSED | — | 是 |
| 券 | couponSend.coupon、reachTasks.tab.COUPON | — | 否 |
| 命中 | couponSend.matched、memberSegment.matched | — | 是 |
| 熟客 | couponSend.preset.LOYAL、members.level.LOYAL | — | 否 |
| 新客 | couponSend.preset.NEW、members.level.NEW | — | 否 |
| 发给 | couponSend.to、audience.title | — | 是 |
| 指定商品 | couponText.ruleGoodsAny、couponText.scopeSome | — | 否 |
| 默认店 | crossStore.default、storePick.default | — | 否 |
| 复购率 | crossStore.repeatRate、customers.repeatRate | — | 否 |
| 跨店对比 | crossStore.title、plan.crossStore | — | 否 |
| 配送费 | delivery.fee、store.fee | — | 否 |
| 起送价 | delivery.minOrder、store.minOrder | — | 否 |
| 已停业 | entities.stClosed、storePick.entityClosed | — | 是 |
| 待补执照 | entities.stPending、storePick.entityPending | — | 是 |
| 未开通 | goods.channelOff、payment.notOpened | — | 否 |
| 全选 | goods.confirmAll、stockCheck.pickAll | — | 是 |
| 新建商品 | goods.createTitle、goods.add | — | 是 |
| 运费模板 | goods.freightTpl、shipSetting.template | — | 否 |
| 上门服务 | goods.fulfillmentType.APPOINTMENT、fulfillmentLabel.APPOINTMENT | — | 是 |
| 到店核销 | goods.fulfillmentType.STORE_VERIFY、fulfillmentLabel.STORE_VERIFY | — | 是 |
| 查看 | goods.invView、order.traceView | — | 是 |
| 需资质 | goods.needCert、storeCategories.needCert | — | 否 |
| 划线价 | goods.originPrice、goods.fieldOrigin | — | 否 |
| 覆盖 | goods.overwriteApply、activityEdit.cover | — | 否 |
| 撤销 | goods.parseUndo、offlineSale.revoke | — | 是 |
| 识别中… | goods.parsing、store.text.parsing | — | 否 |
| 当前 | goods.pickCurrent、plan.tierCurrent | — | 否 |
| 快速录入 | goods.quickEntry、goods.revSrcQUICK_TEXT | — | 是 |
| 全国 | goods.restrictedNone、store.regionRoot | — | 否 |
| 分享 | goods.share、group.share | — | 是 |
| 去添加 | goods.toStoreCategories、stock.goAddLocation | — | 否 |
| 去开启 | goods.toStoreScope、geo.goSettings | — | 否 |
| 件 | goods.unitPiece、stock.uom.PIECE | — | 否 |
| 选择门店 | goods.verifyStorePick、storePick.title | — | 否 |
| 散团 | group.dissolve、group.dissolveTitle | — | 否 |
| 自提点 | group.pickup、groupOpen.pickup | — | 是 |
| 截止 | groupOpen.deadline、couponEdit.until | — | 否 |
| 已散 | groups.tab.FAILED、groups.status.FAILED | — | 是 |
| 已成团 | groups.tab.FORMED、groups.status.FORMED | — | 是 |
| 待自送 | home.cell.toDeliver、crossStore.toDeliver | — | 是 |
| 待备货 | home.cell.toStock、crossStore.toStock | — | 否 |
| 成交额 | home.gmv、stats.gmv | — | 是 |
| 在售 | home.inv.onSale、goods.statusON_SALE | — | 否 |
| 今日 | home.today、stats.today | — | 是 |
| 已发起，等通道确认 | income.inFlight、settle.statusSPLIT | — | 是 |
| 本月 | income.rangeThisMonth、stats.month | — | 是 |
| 已到账 | income.received、settle.statusSPLIT_CONFIRMED | — | 是 |
| 我已阅读并同意 | login.agreePrefix、apply.agreementAccept | — | 否 |
| 微信登录 | login.byWxMini、login.byWechat | — | 否 |
| {n} 天前 | members.daysAgo、customers.daysAgo | — | 是 |
| 合并 | memberTag.merge、memberTags.merge | — | 是 |
| 处在用 | memberTag.used、memberSegment.used | — | 是 |
| 用在哪 | memberTag.usedIn、memberSegment.usedIn | — | 是 |
| 系统 | message.tab.SYSTEM、locations.kind.TRANSIT | — | 是 |
| 添加 | mySpecs.add、store.addArea | — | 是 |
| 已全部移除 | mySpecs.catAllRemoved、mySpecs.catAllRemovedProps | — | 是 |
| 企业微信群 | notifySetting.channel.WEBHOOK、notifySetting.wecom | — | 是 |
| 群机器人地址 | notifySetting.wecomAddr、notifySetting.wecomPlaceholder | — | 是 |
| 可用 {n}，不可超出 | offlineSale.qtyHint、stockOut.qtyHint | — | 否 |
| 最多 {n} | offlineSale.qtyOver、stockOut.qtyOver | — | 否 |
| 券「{name}」 | order.discountCoupon、afterSale.impactCoupon | — | 否 |
| 已下单 | order.expressStatus.CREATED、memberDetail.reachOutcome.ORDERED | — | 否 |
| 平台补贴 | order.funderPlatform、platformApply.platformPer | — | 否 |
| 订单号 | order.no、statement.csvOrderNo | — | 否 |
| 已退款 | order.statusREFUNDED、afterSale.status.REFUNDED | — | 否 |
| 可以收款 | payment.ok、payment.status.ACTIVE | — | 是 |
| {q} / {m} 份 | periods.quotaOf、period.shortHead | — | 是 |
| 已成 | periods.tab.CONFIRMED、periods.status.CONFIRMED | — | 是 |
| 按商品 | picking.byGoods、period.byGoods | — | 是 |
| 共 {n} 件 | picking.total、offlineSale.qtyN | — | 是 |
| 升级 / 续费 | plan.contactBtn、plan.contactTitle | — | 是 |
| 免费试用 {n} 天 | plan.trialBtn、plan.blockedTrial | — | 是 |
| 满 {m} 减 {n} | platformActs.ruleAmount、couponText.ruleFullCut | — | 是 |
| 请选择商品 | platformApply.needGoods、activityEdit.need.goods | — | 否 |
| 份数 | platformApply.quota、activityEdit.quota | — | 否 |
| 结算 | platformApply.settle、me.settle | — | 否 |
| 时间 | platformApply.time、activityEdit.schedule | — | 否 |
| 撤回报名 | platformApply.withdraw、platformApply.withdrawTitle | — | 否 |
| 积分 | points.pointsUnit、points.title | — | 否 |
| 已知悉 | printCenter.acknowledge、printCenter.status.ACKNOWLEDGED | — | 否 |
| 取消 | printCenter.action.CANCEL、common.cancel | common.cancel | 否 |
| 暂停 | printCenter.pause、activities.pause | — | 否 |
| 模拟打印 | printCenter.simulate、perm.biz:print:simulate | — | 是 |
| 模拟校验通过 | printCenter.simulationPassed、printCenter.status.PASSED | — | 是 |
| 已撤销 | printCenter.status.REVOKED、offlineSale.revoked | — | 否 |
| 日期 | purchase.date、stockOut.date | — | 是 |
| 如 老周粮油 | purchase.supplierPh、suppliers.addPh | — | 否 |
| 发消息 | reach.entry、members.act.reach | — | 否 |
| {n} 单 · {m} | reachTask.hero、members.stat | — | 是 |
| 存为人群 | reachTask.saveTitle、members.act.segment | — | 否 |
| 消息 | reachTasks.tab.MSG、message.title | — | 是 |
| 天 | schedule.dayUnit、couponEdit.daysPh | — | 否 |
| 看全部 | settle.dayClear、stockDocs.showAll | — | 是 |
| 全部门店 | settle.scopeAll、members.allStores | — | 否 |
| 当前门店 | settle.scopeCurrent、storePick.current | — | 否 |
| 条码 | skuIdentity.barcode、goods.fieldBarcode | — | 是 |
| 货号 | skuIdentity.code、goods.fieldSkuCode | — | 否 |
| 导出 CSV | skuIdentity.export、statement.export | — | 是 |
| 商品编码 | skuIdentity.title、goods.secCode | — | 否 |
| 单位 | skuIdentity.unit、goods.fieldUnit | — | 是 |
| 角色 | staff.tab.roles、staff.roleDetailTitle | — | 否 |
| 员工 | staff.title、staff.tab.staff | — | 否 |
| 快递费 | statement.freightCost、settle.freightCost | — | 是 |
| 运费 | statement.freightIncome、settle.freightIncome | — | 是 |
| 货款 | statement.gross、settle.gross | — | 是 |
| 待对账 | statement.st_PENDING_RECON、settle.statusPENDING_RECON | — | 是 |
| 可用 | stock.available、stockDetail.available | — | 是 |
| 跨店库存 | stock.entry.cross、stockCross.title | — | 否 |
| 单据 | stock.entry.docs、stockDocs.title | — | 是 |
| 库位 | stock.entry.locations、locations.title | — | 否 |
| 进货 | stock.entry.purchase、purchase.title | — | 否 |
| 报表 | stock.entry.report、stockReport.title | — | 否 |
| 损坏 | stock.reason.BROKEN、stockDocs.label.BROKEN | — | 否 |
| 盘盈 | stock.reason.COUNT_GAIN、stockDocs.label.COUNT_GAIN | — | 是 |
| 盘亏 | stock.reason.COUNT_LOSS、stockDocs.label.COUNT_LOSS | — | 是 |
| 过期 | stock.reason.EXPIRED、stockDocs.label.EXPIRED | — | 是 |
| 赠送 | stock.reason.GIFT、stockDocs.label.GIFT | — | 是 |
| 期初 | stock.reason.INIT、stockReport.opening | — | 否 |
| 线下卖出 | stock.reason.OFFLINE_SALE、offlineSale.title | — | 是 |
| 其他 | stock.reason.OTHER、stockDocs.label.OTHER | — | 是 |
| 退货入库 | stock.reason.RETURN、stockDocs.label.RETURN | — | 否 |
| 调拨入 | stock.reason.TRANSFER_IN、stockDocs.label.TRANSFER_IN | — | 是 |
| 调拨出 | stock.reason.TRANSFER_OUT、stockDocs.label.TRANSFER_OUT | — | 是 |
| 以线上库存为准 | stockAlign.byMall、stockAlign.modeMall | — | 是 |
| 已对齐 | stockAlign.done、stockAlign.lineStatus.ALIGNED | — | 是 |
| 已过账 | stockCheck.posted、stockDocs.status.POSTED | — | 否 |
| 过账盘点 | stockCheck.submit、stockCheck.confirmTitle | — | 否 |
| 确认作废 | stockCheck.voidConfirm、stockDocs.voidConfirm | — | 是 |
| 差异原因 | stockDetail.reasonLabel、stockCheck.reasonLabel | — | 是 |
| 入库 | stockDocs.kindIn、stockDocs.kind.IN | — | 是 |
| 出库 | stockDocs.kindOut、stockDocs.kind.OUT | — | 是 |
| 内部领用 | stockDocs.label.INTERNAL、stockOut.purpose.INTERNAL | — | 是 |
| 已收到 | stockDocs.status.RECEIVED、transfer.status.RECEIVED | — | 是 |
| 线上可售 | stockOverview.online、stockSync.goodsRule | — | 否 |
| 本店默认 | stockOverview.STORE、stockSync.ruleDefault | — | 否 |
| 库存设置 | stockSettings.title、stock.entry.settings | — | 是 |
| 处理中 | stockSync.operationStatus.PROCESSING、stockAlign.lineStatus.PROCESSING | — | 是 |
| 全部可售 | stockSync.rule.ALL、stockSync.ruleOpt.ALL | — | 是 |
| 最多放几件 | stockSync.ruleOpt.CAP、stockSync.param.CAP | — | 否 |
| 给门店留几件 | stockSync.ruleOpt.RESERVE、stockSync.param.RESERVE | — | 否 |
| 发布 | store.noticePublish、activityEdit.publish | — | 是 |
| 已发布 | store.noticeSaved、activityEdit.saved | — | 是 |
| 地图选点 | store.pickAddr、store.picker.mapUnnamed | — | 否 |
| 已排除 | store.picker.excludedTag、store.areaExcluded | — | 是 |
| 排除 | store.text.exclude、store.picker.exclude | — | 否 |
| 默认 | stores.default、entities.primary | — | 是 |
| 链接已复制 | stores.linkCopied、group.copied | — | 是 |
| 门店代码 | stores.linkEditTitle、stores.slugPh | — | 是 |
| 至 {d} | verify.couponUntil、couponText.validUntil | — | 否 |

## 直写中文候选

| 位置 | 类型 | 内容 |
| --- | --- | --- |
| b-app/src/App.vue:61 | script-string | [automation] 票据登录失败 |

## 未找到样式声明的 sh-* 类（待核对 UnoCSS 与运行时注入）

| 源码 | 类名 |
| --- | --- |

## 三语一致性

| 语言 | 缺 key | 多 key | 占位符差异 |
| --- | --- | --- | --- |
| en | 0 | 0 | 0 |
| ar | 0 | 0 | 0 |

静态引用未命中中文词库：0 处。动态引用：264 处，详情见 audit.json。
