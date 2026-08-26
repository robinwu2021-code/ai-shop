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
| 自造形态实例 | **126 处 / 17 类** |
| 页面自己的样式 | 1 119 条选择器 · 5 583 行 |

**这 17 类要分成两堆看，责任完全不同：**

- **7 类是库里有、页面没用** —— 这是纪律问题，改页面就行
- **10 类是库里根本没有** —— 这是**库的缺口**，不是页面的错。
  在补上之前，要求「所有页面基于组件库」是做不到的

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

> `sh-tabs` 的类注释里写着：抽它出来时两端有两套实现（chip 横排 / 方块），
> **统一成了 chip 那套**。而 `my-specs` 又把方块那套画了回来（见 §六）。
> 一个被明确废掉的形态重新长出来，说明当时只改了代码，没有留下拦住它的东西。

---

## 四、库里没有 —— 缺件排行（10 类）

这十件是「所有页面基于组件库」的**前置条件**。按有多少页在重复造排序。

| # | 缺件 | 页数 | 现在各页怎么造的 |
|---|---|---:|---|
| 1 | **列表行** `sh-row` | **32** | 各自定义 `.row` / `.item`：`sku-identity` 是 `1rpx` 上边框，`store-categories` 是 `2rpx`，`goods-list` 干脆只有 `margin-bottom` |
| 2 | **键值行** `sh-kv` | 6 | `goods-edit .kv` · `sku-identity .rule/.prob` · `qualifications` … 左键右值，键宽各写各的（140rpx / 180rpx） |
| 3 | **统计数字格** `sh-stat` | 5 | `.trio`（coupon-issues 与 member-reach **逐字节相同**）· `.quad`（customers，40rpx/600）· `.nums`（sku-identity，40rpx/700） |
| 4 | **卡内标题行** `sh-section` | 5 | `goods-edit`/`sku-identity` 的 `.sec` 完全相同；`my-specs` 叫 `.cat__head`，标题用 `.cat__name` 而不是 `.sh-h2` |
| 5 | **图片上传格** `sh-uploader` | 4 | apply / payment / qualifications / goods-edit 各一份；**goods-edit 一页里就有两套**（`.imgs` 主图、`.dimgs` 详情图） |
| 6 | **可删标签** `sh-chip` 的可删形态 | 4 | `my-specs` chip 内嵌 `.val__x`；`goods-edit` 行尾独立的 `.del`；`store-notice .recent__x`；`store-scope .item__x`。**连字符都不统一**：三处用 `✕`（U+2715），`store-scope` 用 `×`（U+00D7） |
| 7 | **搜索框** `sh-search` | 2 | customers / goods-list |
| 8 | **＋ 加一项按钮** `sh-add` | 2 | `goods-edit` 与 `my-specs` 的 `.btn-add` **完全相同** —— 这一件已经是同一个东西了，只差收进库里 |
| 9 | **底部固定条** `sh-savebar` | 2 | `store` 与 `store-scope` 的 `.savebar` **逐字节相同** |
| 10 | 悬浮新建按钮 `sh-fab` | 1 | goods-list |

**第 8、9 两件已经是逐字节相同的复制品**，收进库里是纯搬运，没有设计决策要做 ——
建议从这两件开始，成本最低、示范作用最强。

---

## 五、逐页

52 行全表在 [原型页的「页面 × 组件库」一节](https://claude.ai/code/artifact/6bc8adb0-967f-4f26-8838-35b93723f5bd#pages)
与 `ui-lib.json` 的 `pages` 段，此处只摘两头。

**自造最多的 12 页**（选择器数 / 库类命中 / 用了哪些库件 / 自己造的）：

| 页面 | 选择器 | 库类 | 用了库件 | 自己造的 |
|---|---:|---:|---|---|
| `goods-edit` | 110 | 108 | cover, icon, sheet | 系统弹框, 文字当箭头, 选中态自画, 白块自画, 卡内标题行, 键值行, ＋加一项, 可删标签, 图片上传格 |
| `my-specs` | 58 | 21 | empty, icon, sheet | 分栏切换, 系统弹框, 选中态自画, 白块自画, 卡内标题行, ＋加一项, 可删标签 |
| `goods-list` | 40 | 7 | cover, empty, tabs, store-tag | 弹层/遮罩, 系统弹框, 白块自画, 列表行, 搜索框, 悬浮新建按钮 |
| `sku-identity` | 29 | 29 | — | 系统弹框, 选中态自画, 卡内标题行, 统计数字格, 列表行, 键值行 |
| `store-scope` | 49 | 25 | pickup-sheet, region-picker, store-tag | 系统弹框, 文字当箭头, 选中态自画, 列表行, 底部固定条 |
| `verify` | 39 | 40 | empty | 分栏切换, 系统弹框, 文字当箭头, 选中态自画, 列表行 |
| `customers` | 19 | 21 | empty, icon | 系统弹框, 选中态自画, 统计数字格, 列表行, 搜索框 |
| `marketing` | 21 | 59 | empty, icon | 系统弹框, 文字当箭头, 白块自画, 列表行 |
| `home` | 30 | 43 | store-tag | 空态, 系统弹框, 白块自画 |
| `qualifications` | 25 | 23 | cover, empty | 列表行, 键值行, 图片上传格 |
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

1. **`.btn-add` 与 `.sec` 收进库**（两页已逐字节相同）→ `sh-add` / `sh-section`
2. **`my-specs` 的分栏换成 `sh-tabs`**、`.cat` 换成 `.sh-block` —— 删代码，不写代码
3. **统一选中态**：全域走 `.sh-chip--primary`，多选加 `✓`，单选不加；
   去掉 `.opt--on` 的描边与 600
4. **统一删除手势**：一律 `sh-icon` 图标按钮（52×52，与 `my-specs` 现状一致），
   并**给参数补上删除入口** —— 现在加得进去删不掉
5. **两个上传器合一** → `sh-uploader`（goods-edit 一页里就有两套）

---

## 七、要补的十件（建议形态）

| 组件 | 形态 | props（建议） |
|---|---|---|
| `sh-section` | 卡内标题行：左标题（`.sh-h2`）+ 右动作槽 | `title`, slot `action` |
| `sh-row` | 列表行：左主体 + 右值 + 可选箭头，整行可点 | `title`, `sub`, `value`, `arrow`, `@tap` |
| `sh-kv` | 键值行：定宽键 + 自适应值 | `label`, `keyWidth?`, slot |
| `sh-stat` | 数字格：2–4 格，数字 + 标签，可点即筛 | `items`, `active?`, `@pick` |
| `sh-savebar` | 底部固定条，自带安全区与 tabbar 让位 | slot |
| `sh-search` | 搜索框 | `modelValue`, `placeholder`, `@confirm` |
| `sh-uploader` | 图片格：已传缩略 + 「＋」格 + 上限提示 | `list`, `max`, `@change` |
| `sh-add` | ＋ 加一项胶囊按钮（已有现成实现） | `text`, `size?`, `@tap` |
| `sh-fab` | 悬浮新建按钮（自动避让 tabbar） | `text`, `@tap` |
| `.sh-chip` 可删形态 | chip 内嵌 ✕ | `removable`, `@remove` |

**加这十件是「让所有页面基于组件库」这条目标的前置条件**，不是锦上添花：
在它们存在之前，任何一页要画一行列表、一个数字格、一个上传格，
除了自己写没有第二条路。

---

## 八、待决

| # | 待决 | 说明 |
|---|---|---|
| 1 | **`uni.showModal` 要不要一刀切掉** | 26 页在用。全换成 `sh-sheet` 是一笔不小的改动，但留着就等于承认「一半的弹层不归设计系统管」。折中方案：只换**带输入**的那 10 处（系统弹框的输入框最难看），确认型的先留 |
| 2 | 十件补齐后，要不要立**「新页面不许自造」的闸门** | 判据可以直接复用本文的 `ROLLED` 规则（已在生成器里），按棘轮：现有 126 处登记为已知欠账，新增的拦下 |
| 3 | `goods-edit` 3 933 行 / 110 条选择器要不要拆 | 它一页占了商品域样式的四成。补完组件后应该能掉一大半，届时再看还剩什么 |

---

确认记录：2026-08-26 由「B 端所有页面都要基于组件库开发 + 商品/规格/参数交互与组件不一致」两条立。
