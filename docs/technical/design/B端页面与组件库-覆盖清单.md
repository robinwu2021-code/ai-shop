# B 端页面 × 组件库 · 覆盖清单（含商品域专项）

> 状态：**生成物 · 长期有效** · 2026-08-26 立。数字全部来自 `ui-lib.json`，一条命令跑出来。
> 清单本体：[`ui-lib.json`](./ui-lib.json) 的 `pages` / `gaps` 两段 ·
> **可视化版**：[UI 标准库 → 页面 × 组件库](https://claude.ai/code/artifact/6bc8adb0-967f-4f26-8838-35b93723f5bd#pages)
> 生成：`python3 scripts/gen-ui-lib.py` · 闸门：`scripts/check-generated-docs.mjs`
> 配套：[UI标准库清单](./UI标准库清单.md)（有哪些件）· [B端App-组件清单与规范落地](./B端App-组件清单与规范落地.md)（规范落地审计）

---

## 一、结论先说

目标是「**所有页面都基于组件库开发**」。逐页扫完 52 页，离这个目标还差多少：

| | 数 |
|---|---|
| B 端页面 | **52** |
| 完全没有自造形态的页 | **5**（`income` `member-add` `points-records` `staff-detail` `stats`） |
| 自造形态实例 | **108 处 / 13 类** |
| 页面自己的样式 | 1 119 条选择器 · 5 583 行 |

**这 16 类要分成两堆看，责任完全不同：**

- **8 类是库里有、页面没用** —— 这是纪律问题，改页面就行
- **3 类是库里根本没有** —— 而这 3 类**都不是「没人做」，是「没定」**：
  列表行（32 页，§九已论证**不该做成组件**）· 键值行（6 页，键宽 140/180 两种要拍）·
  搜索框（1 页，两种形态要拍）。**「库的缺口」这一堆实际上已经清空了**

> **已收编（2026-08-26）**：`sh-add`（＋ 加一项按钮）· `sh-savebar`（底部未保存条）·
> `sh-section`（卡内标题行）· `.sh-link`（文字动作，两端 12 处定义 / 9 种写法 / 2 个名字）·
> `sh-uploader`（图片格，四页各一份）· `sh-stat`（数字格，五页四种档位）·
> `sh-icon-btn`（图标按钮，收掉「文字当图标」5 处）· `.sh-chip--dashed`（候选药丸）· `sh-fab`（悬浮新建）。
> 前三件在页面里本来就是**逐字节相同**的复制品，收进库是纯搬运；`.sh-link` 见 §九。
> 缺件从 10 类降到 **3 类**，自造实例 126 → 108。
> **顺带把 §4.2 那个真缺陷修掉了**：`customers` 的 `var(--sh-card)` 拼错导致四个筛选格透明 ——
> 格子归 `sh-stat` 之后底色走 `--sh-surface`，`skin-vars` 那道守卫从红转绿。
> 收益不平均：`store` 从 4 处自造降到 1 处，`sku-identity` 少了一整类；
> 而 `goods-edit` 仍有 9 类 —— 它剩下的都是真缺件（列表行、键值行、上传格）。

**最大的一条不是缺件，是弃用**：**26 个页面（正好一半）**用 `uni.showModal` /
`uni.showActionSheet` 做确认与输入，而全 B 端只有 **2 页**用了 `sh-sheet` ——
`sh-sheet` 存在的全部理由就是「不要用系统弹框」（它的类注释第一句就是这个）。
半数页面的弹层**在设计系统之外**：字号、行高、按钮位置都不归我们管，也不跟皮肤走。

---

## 二、这份扫描能信到什么程度

**判据是正则，会有误判。** 所以判据本身也写进了 `ui-lib.json` 的 `rule` 字段 ——
读的人能自己核，不必信这份扫描。规则宁可判得保守：拿不准的不算「自造」。

已知的判法边界，如实列出：

- 「列表行」判的是 `^\.(row|item)\b` 这类选择器名。**同一个名字未必是同一个形态** ——
  32 页里一定有几页只是恰好起了这个名字。它说明的是「32 页各自定义了一套行的样式」，
  不是「32 页画的是同一种行」。恰恰因为如此，它才是缺件的证据。
- 「选中态自画」判的是 `--on / --off / is-on` 出现在选择器里。用 `.sh-chip--primary`
  表达选中的页面不会被判进来（那正是库件用法）。
- 「白块自画」判的是同一条规则里同时出现 `background: var(--sh-surface)` 与 `border-radius`。
  这是 `.sh-card` / `.sh-block` 的形状被重写了一遍。
- 「搜索框」先前按名字判，把 `customers` 的 `.search`（只有一条 `margin-top`）算了进来 ——
  **这是按名字判第六次误命中**，改判成「有底色的那种才算」。
- 「统计数字格」先前按名字判，把 `coupons` 的 `.nums`（其实是两行并排的灰字）算了进来 ——
  **这是按名字判第四次误命中**。名单去掉 `nums`、补上 `effect__`（`activities` 画的
  就是这个东西，只是没叫这个名）。
- 「可删标签」判的是**标签上那个 `✕` 字符**，不是类名。先前按 `.del` 判会把
  `role-detail` 的危险按钮（`sh-btn sh-btn--danger del`）算进来 —— 已改掉。
  这条留在这儿，是想说明这类扫描的误判长什么样。

---

## 三、库里有，页面没用（7 类）

改这七类不需要新增任何组件，是**纯纪律**。

| 形态 | 页数 | 库里对应 | 哪些页 |
|---|---:|---|---|
| **系统弹框** | **26** | `sh-sheet` | activities, coupons, cross-store, customers, goods-edit, goods-list, home, login, marketing, me, member-reach, member-segments, member-settings, member-tags, my-specs, picking, plan, role-detail, settle, sku-identity, store, store-categories, store-notice, store-scope, stores, verify |
| **选中态自画** | **16** | `.sh-chip--primary` | activity-edit, apply, cross-store, customers, goods-edit, login, member-settings, my-specs, points, sku-identity, store-categories, store-notice, store-pick, store-scope, stores, verify |
| 文字当箭头（`›`） | 8 | `sh-icon name="chevronRight"` | entities, entity-detail, goods-edit, marketing, role-detail, staff, store-scope, verify |
| 白块自画 | 7 | `.sh-card` / `.sh-block` | goods-edit, goods-list, home, marketing, me, messages, my-specs |
| 分栏切换 | 3 | `sh-tabs` | my-specs, picking, verify |
| 弹层 / 遮罩 | 2 | `sh-sheet` | goods-list, order |
| 空态 | 1 | `sh-empty` | home |
| **文字当图标（✕/×）** | 3 | `sh-icon-btn` / `sh-icon(close)` | goods-edit · goods-list · my-specs。收编前是 11 处 / 5 种角色，**连字符都不统一**（`store-scope` 用 `×` U+00D7，其余 `✕` U+2715）。行尾删除与弹层关闭 5 处已换成真图标；剩下的输入框清空、chip 内嵌、图片角标**尺寸受宿主约束，是另一套几何** |
| 分段标题（只有标题） | 4 | `.sh-h2` + 间距档 | goods-edit(`.sec__h`), groups, plan, store-categories(`.grp`)。**这一条不缺组件，缺的是间距档** —— 各页写的是 24rpx / 40rpx 8rpx 16rpx / 28rpx 0，差别是真实的版面决定 |

> `sh-tabs` 的类注释里写着：抽它出来时两端有两套实现（chip 横排 / 方块），
> **统一成了 chip 那套**。而 `my-specs` 又把方块那套画了回来（见 §六）。
> 一个被明确废掉的形态重新长出来，说明当时只改了代码，没有留下拦住它的东西。

---

## 四、库里没有 —— 剩下 3 类，全是「要拍板」不是「要动手」

**能干净做的都做完了。** 剩下这三条卡在决定上，不在工作量上：

| # | 形态 | 页 | 卡在哪 |
|---|---|---:|---|
| 1 | 列表行 | 32 | **不该做成组件** —— 行的躯干是各页业务（`reviews` 一行八个条件块），见 §九 |
| 2 | 键值行 `sh-kv` | 6 | 键宽两种（140rpx / 180rpx）。定哪个是版面决定，不是收编 |
| 3 | 搜索框 | 1 | 两种形态：`goods-list` 是 surface 底的胶囊，`customers` 是 faint 底的 `.field__input`。哪个算标准要拍 |

下面是收编前的原始排行，留作对照。

| # | 缺件 | 页数 | 现在各页怎么造的 |
|---|---|---:|---|
| 1 | **列表行** `sh-row` | **32** | 各自定义 `.row` / `.item`：`sku-identity` 是 `1rpx` 上边框，`store-categories` 是 `2rpx`，`goods-list` 干脆只有 `margin-bottom` |
| 2 | **键值行** `sh-kv` | 6 | `goods-edit .kv` · `sku-identity .rule/.prob` · `qualifications` … 左键右值，键宽各写各的（140rpx / 180rpx） |
| 3 | ~~候选标签（虚线药丸）~~ ✅ | 3 | 已收成 `.sh-chip--dashed`。**`cross-store` 的 `.tag--demo` 没有跟着改** —— 它用同一个视觉标「演示数据」，那是**误用不是候选**：虚线药丸在这套界面里的意思是「点一下就加」。列进 §八 待决 |
| 4 | **搜索框** | ~~2~~ → **1** | `customers` 的 `.search` 只是 `margin-top: 16rpx` 包着一个 `.field__input`（手机号查询）—— **按名字判第六次误命中** |
| 5 | ~~悬浮新建按钮~~ ✅ | 1 | 已收成 `sh-fab`。**一个调用点也收**，因为收的是那一行 `bottom` 里的知识：它此前写死 `190rpx`，而 tabBar 的真高在 `--sh-tabbar-h` 里 —— 改菜单高度那个数不会跟着动，**且没有任何症状** |

~~原第 3、4、5、6、8、9 六件（统计数字格、可删标签、卡内标题行、图片上传格、＋ 加一项按钮、底部固定条）~~ **已收编**，见 §七。
「可删标签」那一条**判错过**：它其实不是缺件 —— 库里一直有 `close` 图标，缺的是纪律。改判之后它挪到了 §三。

---

## 五、逐页

52 行全表在 [原型页的「页面 × 组件库」一节](https://claude.ai/code/artifact/6bc8adb0-967f-4f26-8838-35b93723f5bd#pages)
与 `ui-lib.json` 的 `pages` 段，此处只摘两头。

**自造最多的 12 页**（选择器数 / 库类命中 / 用了哪些库件 / 自己造的）：

| 页面 | 选择器 | 库类 | 用了库件 | 自己造的 |
|---|---:|---:|---|---|
| `goods-edit` | 96 | 118 | **add**, **section**, **uploader**, cover, sheet | 系统弹框, 文字当箭头, 选中态自画, 白块自画, 卡内标题行, 键值行, 候选标签, 可删标签, 图片上传格 |
| `my-specs` | 52 | 21 | **add**, **section**, empty, icon, sheet | 分栏切换, 系统弹框, 选中态自画, 白块自画, 候选标签, 可删标签 |
| `goods-list` | 40 | 7 | cover, empty, tabs, store-tag | 弹层/遮罩, 系统弹框, 白块自画, 列表行, 搜索框, 悬浮新建按钮 |
| `sku-identity` | 28 | 27 | **section** | 系统弹框, 选中态自画, 统计数字格, 列表行, 键值行 |
| `store-scope` | 45 | 22 | **savebar**, pickup-sheet, region-picker, store-tag | 系统弹框, 文字当箭头, 选中态自画, 列表行, 可删标签 |
| `verify` | 39 | 40 | empty | 分栏切换, 系统弹框, 文字当箭头, 选中态自画, 列表行 |
| `customers` | 19 | 21 | empty, icon | 系统弹框, 选中态自画, 统计数字格, 列表行, 搜索框 |
| `marketing` | 21 | 59 | empty, icon | 系统弹框, 文字当箭头, 白块自画, 列表行 |
| `home` | 30 | 43 | store-tag | 空态, 系统弹框, 白块自画 |
| `qualifications` | 22 | 23 | **uploader**, cover, empty | 列表行, 键值行 |
| `login` | 23 | 18 | — | 系统弹框, 选中态自画, 列表行 |
| `member-reach` | 19 | 18 | — | 系统弹框, 统计数字格, 列表行 |

**前四页里有三页属于商品域** —— 这不是巧合，见下一节。

---

## 六、商品域专项：商品 / 规格 / 参数

商品域五页：`goods-list`（商品列表）· `goods-edit`（建品/改品，**3 933 行**）·
`my-specs`（商品规格和参数）· `sku-identity`（货号与条码）· `store-categories`（本店类目）。
它们合计 **253 条选择器**，占 B 端全部页面样式的 **22%**，而页数只占 10%。

### 6.1 「这一项被选中了」，同一个域里六种画法

这是本域最刺眼的一条。库里的标准答案只有一个：`.sh-chip--primary` ——
**tint 底 + primary-text 字，无描边、不加粗**。域内实际出现的：

| 在哪 | 类 | 底 | 字色 | 描边 | 字重 |
|---|---|---|---|---|---|
| **库标准** | `.sh-chip--primary` | tint | primary-text | 无 | 400 |
| goods-edit 规格档位 | `.opt--on` | tint | primary-text | **2rpx 实线主色** | **600** |
| goods-edit 参数值 | `.param__chip--on` | tint | primary-text | 无 | 400 |
| goods-edit 语言切换 | `.lang.is-on` | tint | primary-text | 无 | **600** |
| goods-edit 字段分段 | `.seg.is-on` | tint | primary-text | 无 | 400 |
| my-specs 顶部分栏 | `.tab--on` | **主色实底** | **on-primary** | 无 | **600** |
| store-categories 类目 | `.opt--on` | tint | 继承 | **主色描边** | 400 |
| sku-identity 命中数 | `.num__v--on` | 无 | primary-text | 无 | — |

**未选中态也有两套**：`goods-edit .opt--off` 是 `faint 底 + 2rpx 透明描边`
（为了与选中态等高），而 `.param__chip` 未选中直接是裸 `.sh-chip`。
于是同一张卡里上下两块 chip，高度差 2rpx。

### 6.2 「加一项」三种入口，「删一项」三种手势

| | 加 | 删 |
|---|---|---|
| **goods-edit · 规格** | 候选 chip `＋ 名字`（`.addbar`，faint 底） | `.del`：行尾一个**文字 `✕`**（28rpx，灰） |
| **goods-edit · 参数** | 标题行右侧 `.btn-add`（tint 胶囊 + `sh-icon plus`）；每项内还有 `.btn-add--sm` | **没有删除入口** |
| **my-specs · 规格/参数** | `.cat__head` 里的 `.btn-add`（与 goods-edit 逐字节相同 ✓） | `.ic` 图标按钮（52×52，`sh-icon`），值上是 chip 内嵌的 `.val__x` |

三处「加」有两种形态（chip vs 胶囊按钮），三处「删」有三种手势
（文字 ✕ / 无 / 图标按钮 + chip 内 ✕）。
**同一件事在同一个域里长了三张脸**，而 goods-edit 的代码注释里明确写过
「同一件事在两页别长两张脸」—— 那句话说的是「加参数」按钮的位置，
说到做到了；删除这一路没人管。

### 6.3 规格与参数：长得几乎一样，行为相反

这一条**不是疏忽，是已知的取舍**，代码注释里写着：

> 参数是单值，规格是多值 —— 一件货有三档重量，但只有一个产地。
> 所以这里的 chip 是单选（再点取消），而规格那边是开关。
> **两块长得像、行为不同，得说出来。**

现在「说出来」的方式是一句 `sh-muted` 提示文案。问题在于：**两块的视觉重量还不一样**
（规格档位带主色描边 + 600，参数值不带），于是商家看到的信号是
「上面这排更重要」，而不是「上面这排能多选」。**形态差异指向了错的那件事。**

建议：让**形态承载语义**而不是让文案承载 ——
多选用带 `✓` 的开关型 chip，单选用不带 `✓` 的实心选中态；两者字重一致。
这与设计语言里「危险操作靠形态而不是颜色区分」是同一条原则。

### 6.4 分栏：同一个域两种

- `goods-list` 用 `<sh-tabs>` —— 胶囊 chip，内容宽
- `my-specs` 手写 `.tabs / .tab` —— **等宽方块**（`flex:1`、`radius 16rpx`、选中是主色实底）

而 `my-specs` 只有「规格 / 参数」两项，正落在 `sh-tabs` 的非滚动分支里，
是它最标准的用法。这是 §三 提到的「被废掉的形态又长回来」。

### 6.5 白块：`my-specs` 自己画了一个 `sh-block`

```
my-specs .cat  = background: var(--sh-surface); border-radius: 24rpx; overflow: hidden
库里的 .sh-block = background: var(--sh-surface); border-radius: 32rpx; padding: 24rpx 0; overflow: hidden
```

结构也对得上：`.cat__head`（`padding: 24rpx 26rpx 16rpx`）对应 `.sh-block__head`
（`padding: 0 26rpx 16rpx`）。**这就是 `sh-block`，只是圆角少了 8rpx。**
而 `.sh-block` 在 B 端引用数是 0 —— 一个没人用的库件，和一个人人重画的形状，是同一件事。

`.cat__name` 是 `34rpx/600` ＝ `.sh-h2` 的值再加一条 `letter-spacing: 0.01em`
（字阶明确规定不含 letter-spacing）。

### 6.6 同名类已经开始漂了

同一个名字在域内多页出现、值却不同 —— 这是「复制粘贴过、然后各自改过」的指纹：

| 类名 | 出现 | 是否一致 |
|---|---|---|
| `.btn-add` / `.btn-add__t` | goods-edit, my-specs | ✅ 完全相同 |
| `.sec` | goods-edit, sku-identity | ✅ 完全相同 |
| `.build` | goods-edit, my-specs | ⚠️ `margin-top:20rpx` vs `padding-top:12rpx` |
| `.build__input` | goods-edit, my-specs | ⚠️ 一个是完整输入框样式，一个只有 `flex:1` |
| `.build__ok` | goods-edit, my-specs | ⚠️ 28rpx/600 vs 26rpx |
| `.chips` | goods-edit, my-specs | ⚠️ gap 12rpx vs 14rpx |
| `.opt` / `.opts` / `.opt--on` | goods-edit, store-categories | ⚠️ 三条全不同 |
| `.mt` | goods-edit, sku-identity, store-categories | ⚠️ 16 / 20 / 8rpx |
| `.row` | goods-list, sku-identity, store-categories | ⚠️ 三条全不同 |
| `.mini` | goods-edit, goods-list | ⚠️ tint 底主色字 vs faint 底灰字 |

`.mini` 那条最值得看一眼：**同一个名字，一个是主操作的样子，一个是次要标记的样子**。

### 6.7 收敛顺序（商品域）

按「改动小 / 收益大」排，前两条基本是纯搬运：

1. ~~**`.btn-add` 与 `.sec` 收进库**~~ ✅ **已做**：`sh-add`（4 处）+ `sh-section`（8 处）
2. **`my-specs` 的分栏换成 `sh-tabs`**、`.cat` 换成 `.sh-block` —— 删代码，不写代码
3. **统一选中态**：全域走 `.sh-chip--primary`，多选加 `✓`，单选不加；
   去掉 `.opt--on` 的描边与 600
4. **统一删除手势**：一律 `sh-icon` 图标按钮（52×52，与 `my-specs` 现状一致），
   并**给参数补上删除入口** —— 现在加得进去删不掉
5. **两个上传器合一** → `sh-uploader`（goods-edit 一页里就有两套）

---

## 七、要补的件（建议形态）

**已做（2026-08-26）**：

| 组件 | 形态 | props | 收编了谁 |
|---|---|---|---|
| `sh-add` | ＋ 加一项胶囊按钮，`active` 时换 ✕ 变描边 | `text`, `activeText`, `active`, `small` | goods-edit ×2 · my-specs ×1 |
| `sh-savebar` | 底部未保存条（说明 + 放弃 + 保存），**自带流内占位** | `visible`, `text`, `discardText`, `saveText` | store · store-scope |
| `sh-section` | 卡内标题行：左标题 + 右动作（插槽**不套壳**，保住 space-between 的三孩子布局） | `title`, `pad` | goods-edit ×5 · sku-identity ×2 · my-specs ×1 |
| `sh-uploader` | 图片格：缩略图排一行 + 末尾「＋」。**格子尺寸留给调用点**（执照是横的、主图是方的），**圆角/「＋」字符/上传中提示三处漂移收掉** | `list`, `max`, `w`, `h`, `uploading`, `removable`, `badge` | apply · payment · qualifications · goods-edit |
| `sh-stat` | 数字格：大数 + 小标签。**档位先定后收**（40rpx/600，见 §八 待决 3），语义色四档，`boxed` 才可点 | `items`, `boxed`, `active` | coupon-issues · member-reach · customers · sku-identity · activities |
| `sh-icon-btn` | 图标按钮：方形点按区 + `sh-icon`。**收编的是「文字当图标」**，不是某个形状 | `name`, `size`, `box`, `color` | store-notice · store-scope · goods-edit ×2 · my-specs ×2 · sh-sheet |
| `.sh-chip--dashed` | 候选药丸（点一下当场加进来）。**只声明与默认 chip 的差别** —— 字号/内边距/圆角一律吃 `.sh-chip` | `--dashed-quiet` 压一档 | goods-edit ×3 · my-specs ×3 |
| `sh-fab` | 悬浮新建按钮，自动避让 tabBar（走 `--sh-tabbar-h`，不再抄一个 190） | `text` | goods-list |

**还要补的两件（都要先拍板）**：

| 组件 | 形态 | props（建议） |
|---|---|---|
| `sh-row` | 列表行：左主体 + 右值 + 可选箭头，整行可点 | `title`, `sub`, `value`, `arrow`, `@tap` |
| `sh-kv` | 键值行：定宽键 + 自适应值 | `label`, `keyWidth?`, slot |
| `sh-savebar` | 底部固定条，自带安全区与 tabbar 让位 | slot |

**补齐这几件是「让所有页面基于组件库」这条目标的前置条件**，不是锦上添花：
在它们存在之前，任何一页要画一行列表、一个数字格、一个上传格，
除了自己写没有第二条路。

---

## 九、「列表行 32 页」查下去，结论和预期不一样

原以为这是最大的一件缺件，做形态提案时把 25 个有 `v-for` 列表行的页面
逐个拆开看，**结论是它不该做成一个组件**。如实记下来，因为直觉在这里是错的。

### 9.1 成分惊人一致，容器却各不相同

| 成分 | 出现 |
|---|---|
| 次要行（一句灰字） | **25 / 25** |
| 可点 | 23 / 25 |
| 整行就是一张 `.sh-card` | 19 / 25 |
| 首行（标题 + 状态 chip） | 15 / 25 |
| 主列 `__main` | 14 / 25 |
| 右值（金额/数量） | 7 / 25 |
| 封面 | 5 / 25 |
| 箭头 | 4 / 25 |

看起来像一个组件。但把模板打开就不是了：`reviews` 的一行里有
**八个条件块**（评分维度 / 晒图 / 我的回复 / 回复输入 / 申诉状态 / 申诉输入 / 两组按钮），
`coupons` 有六个。**行的躯干是各页的业务，不是版式** ——
做成组件只能包住前两行，剩下的仍旧是页面代码，而调用点会多出一层 props。

### 9.2 真正在重复的是行**里面**的三小件

| 小件 | 页数 | 写法数 | 判断 |
|---|---:|---:|---|
| **文字动作** `.act` / `.link` | 13 | **9** | ✅ 已收编为 `.sh-link` |
| 行尾动作排 `.acts` | 9 | 7 | 差别只在 `margin-top`（12/16/20/24rpx）—— **属于间距档，不是组件** |
| 行内首行 `.row__head` | 14 | 7 | ✅ **已定，见 §9.4** —— 「7 种写法」是我归类归错了：它是**三件不同的东西共用了一个名字**，每件内部本来就一致 |

### 9.3 `.sh-link`：为什么它值得收，而「行」不值得

文字动作是**纯外观**（色 + 字号 + 字重），没有结构、没有业务。
它却漂成了两端 12 处定义、9 种写法、2 个名字（`.act` 与 `.link`）：
字号 24 或 26、字重 400 或 600、色值 `--sh-primary-text` 或 `--sh-primary`。

三个取值都由规则定，不是投票：

- **400 不是 600** —— 字阶写着「600 只给标题与按钮」，文字动作两者都不是
- **24rpx** —— 行内的次要动作，比正文轻一档；26 与正文只差 1px，分不出主次
- **`--sh-primary-text` 不是 `--sh-primary`** —— 主色是为「压白字的按钮底」调的，
  当文字压在页面底上不够 AA。`entity-detail` 用的正是主色 ——
  **没有任何症状，只是弱视用户读不清**

收编后 `.sh-link` 在两端有 38 处引用，`design-tokens` 守卫的
「把主色当文字色用」从 13 个文件降到 **12 个**。

一处如实说明：**C 端 `store` 页的那条链接跟着变小变轻了**（26rpx/600 → 24rpx/400）。
这是收敛的本意，但它是 C 端的一处可见变化，不是零风险搬运。

`stores` 页的「切店」保留了本页的 `.act--go`（600 加重，页面注释解释了
「这是这一页最常用的一下」）—— 它与字阶那条冲突，**留作待决**，
没有偷偷塞进库里当一个 `--strong` 档。

### 9.4 「行内首行 7 种写法」——归类归错了

把 14 处逐个打开看**里面装的是什么**，结论变了：不是七种写法，是**三件不同的东西**
被起了同一个名字，而每一件内部本来就是一致的。

| 是什么 | 布局 | 页 | 右边那个东西与左边的关系 |
|---|---|---|---|
| **A 行的横向骨架** | `gap: 20rpx` | goods-list · groups · picking | 不是「首行」—— 是封面在左、内容列在右 |
| **B 标题 + 紧跟的标签** | `gap: 12rpx` | activities · coupons · customers · staff | chip **修饰**标题（这张券暂停了 / 这个会员沉睡了），读作「名字（状态）」 |
| **C 标题 + 贴右的字段** | `space-between` | after-sale · coupon-issues · member-segments · marketing · quotes · orders · reviews | 右边是**独立一列**（时间 / 数量 / 状态 / 评分），与标题并列，不修饰它 |

**判据一句话**：右边那个东西，是**修饰**左边的，还是与左边**并列**的？
修饰 → 紧跟（`gap`）；并列 → 贴右（`space-between`）。

**值先不动，理由要说清**：B 的 12rpx 与 A 的 20rpx 都不在间距档（8/16/28/40/64）上，
但 375pt 下 12 与 16 只差 2px、20 与 16 也只差 2px。为这 2px 改 7 个页面、
而且**这一轮没有任何办法在真机上验**（本机 dev server 连真后端，登录要凭据），
风险大于收益。真要收敛应当**先给间距立守卫**，一次性处理全端 ——
那时改动是被判据推着走的，不是凭手感。

---

## 八、待决

| # | 待决 | 说明 |
|---|---|---|
| 1 | **`uni.showModal` 要不要一刀切掉** | 26 页在用。全换成 `sh-sheet` 是一笔不小的改动，但留着就等于承认「一半的弹层不归设计系统管」。折中方案：只换**带输入**的那 10 处（系统弹框的输入框最难看），确认型的先留 |
| 2 | 缺件补齐后，要不要立**「新页面不许自造」的闸门** | 判据可以直接复用本文的 `ROLLED` 规则（已在生成器里），按棘轮：现有 108 处登记为已知欠账，新增的拦下 |
| 3 | **数字格用 40 还是 44、600 还是 700** | ✅ **已定：40rpx / 600**。不是投票 —— 字阶写着「700 只给价格」，而这四处是发放数 / 触达数 / 会员数 / 命中数，一个都不是价格；44rpx 也不在字阶上。40/600 恰好是 `customers` 现在的值：四处里唯一合规的那一个 |
| 4 | **`stores` 的「切店」用 600 加重** | ✅ **已定：换形态，不加字重**。改成 `sh-btn sh-btn--soft sh-btn--sm`（一枚 tint 小胶囊），旁边三个仍是文字动作。**加粗只是把同一个形状描得更黑**，一行里四个都是文字时，重的那个仍要找；换成胶囊一眼就分得出。顺带补了库里缺的 `.sh-btn--sm`（`login` / `sku-identity` / `store-scope` 三处早就各自覆盖 `.sh-btn` 内边距取 26rpx，这一档本来就存在，只是没有名字） |
| 5 | **图标按钮的点按区只有 56rpx（28px）** | 新浮现。`b-app/App.vue` 里写着「88rpx ≈ 44pt，是点按目标的下限」，而 `my-specs` 的 `.ic` 一直是 52rpx，`sh-icon-btn` 沿用了 56rpx。**没有在收编时夹带改大** —— 改大会顶高所有列表行，而这一轮无法在真机上验。要么承认图标钮是例外，要么单独做一次并验过 |
| 6 | **`cross-store` 用虚线药丸标「演示数据」** | 新浮现。虚线药丸在这套界面里的约定意思是「候选：点一下就加」，而它标的是「这是演示数据」——**同一个视觉两个意思**。要么给「演示/占位」另立一档，要么换个形态 |
| 7 | **超大数字（60 / 72rpx）没有档** | ✅ **已定：字阶加第八档 `.txt-mega` = 60rpx**。三处独立越过 48（`order` 应收 72、`income`/`points` 结存 60）——**三处走到同一个方向，说明缺的是档不是纪律**。取 60 顺着字阶自己的顶端比例（40→48 是 1.2，48→60 是 1.25），`order` 的 72 一并收进来（弹层里只有它一个大数，30px 与 36px 买不到什么）。**只加这一档**，再有页面想更大先问它是不是也属于收款台。规范本体《画原型的十二条》条 4 已同步改成八档 |
| 6 | `goods-edit` 3 933 行 / 110 条选择器要不要拆 | 它一页占了商品域样式的四成。补完组件后应该能掉一大半，届时再看还剩什么 |

---

确认记录：2026-08-26 由「B 端所有页面都要基于组件库开发 + 商品/规格/参数交互与组件不一致」两条立。
