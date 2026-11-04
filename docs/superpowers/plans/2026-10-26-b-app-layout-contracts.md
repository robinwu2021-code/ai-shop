# B 端组件排版约束与页面复核 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in the current mixed workspace. Preserve existing work; do not commit or publish mixed changes.

**Goal:** 防止组件粘连、分组混乱和默认尺寸误用，审查全部B端页面并修复同类排版问题。

**Architecture:** 在现有共享CSS和B端UI检查器上增加明确的组合约束，页面显式选择纵向分组/横向动作布局。静态检查只拦截可证明的错误，浏览器测量验证真实间距；全量源码审查与代表页视觉验收分别记录。

**Tech Stack:** Vue SFC/compiler AST、PostCSS、Node test、Vitest、uni-app H5/MP/App。

**Spec:** `docs/technical/design/B端文案与Layout规范.md`；用户已授权继续修改并要求其他页面复核。

## Global Constraints

- 使用现有UI库、三语词库与布局数值；不修改业务命令、权限、金额、库存或发布行为。
- 当前混合工作区保留原有改动；本轮改前快照 `/tmp/b-app-layout-contract-before`。
- 字段标签到控件12rpx、字段组20rpx、动作间16rpx；页面/卡片沿用现有密度变量。
- 中文截图验收；源码复核不等于96页全状态真机验收。

## Review Focus

- 条件分支、循环和弹层中的控件也须识别，注释不能满足布局约束。
- 横排金额/搜索组合允许紧凑排版；不把同一复合控件误判成独立表单字段。
- 布局间距只有一个所有者，避免父级gap与子级margin叠加。
- 长标签/空值/忙碌状态不挤压按钮；底部操作占位与滚动维持原行为。
- 校验组件真实props；错误的别名不能悄悄回退默认尺寸。

## Task 1：规则与全量审查

- [x] 解析96页及业务组件，按表单、卡片、动作组和组件参数逐一记录候选。
- [x] 更新规范：每类组件的摆放、间距责任、例外与验收指标。
- [x] 确定少量共享组合类，验证不会改变未采用类名的页面。

## Task 2：自动约束

- [x] 增加独立AST/CSS分析模块与失败fixture，覆盖注释、条件分支、非法参数、连续输入和组合布局边界。
- [x] 将确定性错误接入现有check:ui；人工候选单列，不按整页豁免。
- [x] 先确认fixture失败，再实现并验证通过。

## Task 3：页面修正

- [x] 复核候选源码，修正确定问题，维护96页逐项处置记录。
- [x] 使用共享纵向组/动作组或既有field/sh-row；保持所有事件、条件和数据绑定。
- [x] 浏览器中文代表页在320/375/430px核对组件间距、对齐、长文案及底部内容。

## Task 4：验证与交付

- [x] B端完整测试、规则fixture、类型检查、共享检查与三端构建。
- [x] 更新文案/组件/布局生成清单、独立复核本轮差异，修复实际发现。
- [x] 交付规范、页面处置清单、实际截图与验证边界；不声称未执行的全页真机验收。

- Completed: source review 96 pages/42 components; 19 pages and one business component changed. Final rules identify 13 pre-change spacing-risk sites and 14 heading candidates, now resolved.
- Ruling: keep the mixed workspace and compare against a task-start snapshot; no commit/push/deploy of unrelated changes.
- Ruling: shared stack/actions are opt-in rather than changing all cards globally; this avoids altering layouts not reviewed in this pass. Incorrect adoption can still require device-specific follow-up.
- Final review: three Important guard findings fixed in one pass. Template/repeat boundary summaries, flex-axis gap selection and inline shorthand expansion each reproduced RED then verified GREEN; 29 UI fixtures and full B282 tests pass. No deferred minors.
- Parent disposition of review limits: browser 11 pages/36 observations checked; exact route/component ledger sets verified. Media queries, all 96-page states and native keyboard/safe-area acceptance remain explicitly outside completed runtime coverage.
- Verification: B282/C599 pass, B/C types and H5/MP/App builds pass; shared539 pass/27 fail with names/messages in layout-review.json. All modified Vue script contents and event/v-model bindings match the task-start snapshot.
