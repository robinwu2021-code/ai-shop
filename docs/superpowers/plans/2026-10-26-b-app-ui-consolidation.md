# B 端文案与布局整理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 落实已批准的五批整理方案，修正三语与布局缺陷、统一同义文案、精简说明并留下全量处置记录。

**Architecture:** 沿用 Vue/uni-app 的 sh-* 组件、公共 CSS 与 B 端三语词库。只改变界面表达与组装方式，交易、退款、库存与活动命令保持原有协议。以源码审查资料为基线，区分确认缺陷与合理例外。

**Tech Stack:** Vue 3、uni-app、vue-i18n、TypeScript、Vitest、共享 UI。

**Spec:** `docs/technical/design/B端文案与Layout规范.md`；本聊天已批准的五批实施方案。

## Global Constraints

- 保留现有工作区未提交业务实现；在 codex/food-next01 上就地工作，不提交混合变更。
- B 端页边距/卡内距24rpx、字段间距20rpx、公共输入高88rpx/横距24rpx/圆角24rpx；使用现有语义变量。
- 三语同时维护，命名插值保持一致；动作与状态不得按相同中文盲合。
- 保留风险、资金与库存口径；不引入新业务承诺。
- 调整共享 UI 时核对 C 端影响；纯文案/样式不新增照抄实现的测试，使用已有守卫与构建。

## Review Focus

- 阿语 RTL、长标签、长商品名与金额：不挤掉数值和操作，关键文字可读。
- 动态词条与枚举：新旧 key 迁移不能只检索静态调用。
- 退货质检与隔离库存：缩短文字后仍区分可售/不可售及整单退款确认。
- 同步结果未知与内部错误：显示可理解状态，不误报已完成，不改变重试/幂等机制。
- 固定栏、键盘和安全区：保持组件占位，特殊点击层与切店幕布保留职责。

## Task 1：修复明确缺陷与直写中文

Files: `b-app/src/i18n/locale/{zh-CN,en,ar}.ts`；`pages/{after-sale,goods-edit,stock-align,stock-settings,stock-sync-records,verify,marketing-assets,recharge-activity-edit,qualifications}/index.vue`；新增库存状态映射及对应行为测试（需要时）。

- [x] 运行现有 i18n、页面测试与类型检查，保存失败基线。
- [x] 对新增库存状态/结果文案映射先写行为测试，确认失败；覆盖零数量、未知状态、失败与等待。
- [x] 修 common.delete、无效 class、中文直写与公共表单规格；状态与结果使用完整三语插值。
- [x] 验证映射测试、对应页面测试、三语一致性和 key 存在性。

## Task 2：同义文案迁移

Files: 三语词库、B 端页面/业务组件、`docs/technical/design/reviews/b-app-ui/decisions.json`。

- [x] 将324组重复文案逐组记录合并/保留决定。先迁移 common 操作与反馈，再处理同领域同对象词条。
- [x] 替换静态及可确认的动态引用；无法证明无动态引用的旧 key 保留并注明原因。
- [x] 运行三语/占位符/key 检查与受影响页面测试，确认没有新增缺词。

## Task 3：说明瘦身

Files: 三语词库、相关 B 端页面。

- [x] 对57条长文案逐条落地或记录保留依据；删除重复导语，简化字段说明，必要口径就近呈现。
- [x] 保留全部金额/数量插值、风险后果与业务限定；翻译同步调整。
- [x] 对新增折叠规则交互先补行为测试；仅缩短字句使用现有校验，不为每句话添加测试。

## Task 4：组件与布局

Files: B 端页面/业务组件，必要时复用 `packages/ui/src/components` 和公共类。

- [x] 对96页登记页面类型和处置；41组件复核职责/使用情况。
- [x] 处理45布局候选：公共规格覆盖用页面语义类替代，修普通间距，保留基础库 fixed 与必要光学例外。
- [x] 页面自建 sheet 若可完整保留选类/取消/滚动行为，则迁移 sh-sheet；保持操作条占位。
- [x] 运行设计守卫、B/C 类型检查和多端构建；浏览器验证代表页面中/英/阿、窄/宽屏及底栏。

## Task 5：防回退、全量复核与交付

Files: `scripts/audit-b-app-ui.mjs`、B端审查资料、`b-app/tests`、相关文档索引。

- [x] 守卫新增用户可见中文、无效公共类与规格覆盖，明确合理例外；先确认守卫能发现原缺陷。
- [x] 重新生成文案/组件清单；台账覆盖324组、57词条、45候选、96页、41组件。
- [x] 运行 B 端完整测试、相关共享守卫、类型检查与构建，逐一记录现有失败。
- [x] 按本轮 scoped diff 做独立代码审查，修复重要发现，更新完成状态与实际限制。

## 执行记录

- 已获用户“按照建议执行”授权，直接执行，不重复询问计划确认。
- Ruling: 当前分支包含未提交的新活动页面及审查资料，保留工作区就地执行；独立 checkout 会漏掉依赖的未提交内容。改前快照存于 `/tmp/b-app-ui-before`，供本轮差异审查。
- 后续完成状态和验证结果在本节续记；不把静态复核记作真机验收。

### 完成与核对

- Tasks 1–5: complete for source consolidation, regression checks and builds. Per-device visual acceptance is explicitly not claimed.
- B端基线271项通过；实施后280项通过。类型检查通过。原i18n缺词已复现并修复。
- 131个旧key删除，44个动态/跨端兼容key保留；175项映射与324组/57词条/45布局/96页/42组件见 decisions.json。
- Ruling: 保留动态和跨端词库契约；删除这些兼容项会导致真实调用缺词，不能仅用静态未命中证明无用。
- Ruling: 12条必要长文案继续在决策处保留；强行缩短会丢失金额、库存或不可撤销后果。
- Final review: independent reviewer; two Important findings fixed. Print selection distinction and sync skip reasons each verified RED→GREEN.
- Final minor: tt scanner alias coverage fixed with two RED→GREEN fixtures; no reviewer minors deferred.
- 实际交付与验证入口：docs/technical/design/reviews/b-app-ui/implementation.md。未提交、未部署；保留当前工作区便于用户继续核对。

- 继续整理：打印sh-check/字段上限补齐；B端53文件281项通过，共享537通过/29失败。320px阿语资质和对齐页追加浏览器检查；详细边界见implementation.md与verification.json。

- 用户截图反馈复核：中文复现输入间距0并修正，补齐照片/操作分组、3处uploader尺寸参数、2处商品字段布局；282项B端测试和9项扫描fixture通过。中文320/375/430px实测间隔大于0，旧截图不再视为视觉验收依据。
