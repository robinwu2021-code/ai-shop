# TDD-商品描述带参数生成

状态：**已实现**（2026-10-07）
档位：1（动了 `/biz/goods/describe` 的请求体 = 对外 JSON 结构；不新建表、不加权限码）
关联需求：[TDD-C端商品详情页·内容丰富度](design/TDD-C端商品详情页-内容丰富度.md) §2.B「商家侧一键生成」（2026-09-27 已确认，本轮主力）
用户 2026-10-07：「不只是布局，包含商品的文案，包含标题，说明，描述等等」
创建：2026-10-07 · 最后更新：2026-10-07

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 生成图文详情时，商家**已填的商品参数**要一并给模型，模型可以引用这些值 | `DescribeReq` 加 `params`；`GoodsVisionPort#describe` 加一参；`describePrompt` 列出「已知事实」 |
| AC2 | 参数**之外**的事实仍然一个字都不许编（v3 的禁写清单一条不放宽） | `describePrompt` 的禁写清单原样保留，新增的只是「已知事实」白名单 |
| AC3 | 没填参数时，输出与改动前**逐字相同** | `params` 为空/null 时不拼那一段，提示词与 v3 字节一致 |
| AC4 | 模型不能把参数名当商品属性去发挥（填了「储存条件：常温」不等于可以写「常温保存更香甜」） | 提示词写明「这些值只能照抄，不许在它们之上引申」 |

**孤立项**

- 挂不上 AC 的设计：无。
- **没落点的需求：标题与卖点。** 见 §2「明确不做」—— 它的生成通道是 `recognize`（快速录入一键识别），
  属于 [PRD-商品快速录入-压缩包与文字识别](../requirements/PRD-商品快速录入-压缩包与文字识别.md) 的范围，
  当前有并行会话正在改那条链路。本 TDD 不碰，只在 §2 留下公式供那边取用。

## §1 现状与影响面

### 现状：提示词在严格按设计工作，是输入太少

`describePrompt` 是 **v3**，`GoodsVisionGateway` 的类注释记着完整演进：

- v1 只写「不要编产地品牌保质期」→ 输出「散养土鸡蛋…蛋黄饱满紧实、色泽金黄诱人」，而标题里只有「本地土鸡蛋 30枚」；
- v2 各自举例，单样本很干净 → **跑 5 个样本 4 个违规**，最要命的是「明早截单，后天一早送到」（替商家对顾客做送达承诺）；
- v3 把「**你只知道商品名、卖点、类目这三项，别的一概不知道**」提到最前面，可写内容收窄到**不依赖这件货具体信息**的常识。5+4 个样本，编造与时间承诺为 0。

线上实测（2026-10-07，15 件在售商品）：

| 事实 | 数 |
|---|---|
| 标品的标题/卖点 | 合格（`金龙鱼 大豆油 5L` + `非转基因大豆压榨一级`） |
| 生鲜商品 | **1 件**（脆柿子） |
| 脆柿子 `title` | `脆柿子`（3 字，无产区/品种/规格） |
| 脆柿子 `subtitle` | **空** |
| 脆柿子 `detail` | 80 字，**全是催熟与清洗说明** |
| 脆柿子已填 `params` | 4 条（产地 国产 / 保质期 7天 / 储存条件 常温 / 口感风味 脆爽） |

模型手上只有「脆柿子」三个字 + 空卖点 + 类目，v3 允许它写的只剩常识 ——
**于是它写了催熟和清洗。这是提示词的正确输出，不是缺陷。**

而参数里那四条是商家自己填的、经过候选值核验的结构化事实，**模型一个都没见过**。

### 可直接复用

- `suggestParams` 已经把类目候选值喂给同一个模型，并在返回前逐条核验（维度名在 candidates 里、值也在那一维的候选里）。**本次只做反方向**：把**已填**的值作为输入给 `describe`，不涉及核验（输入是商家填的，不是模型产出的）。
- `categoryPath()` 已把类目号翻成中文名喂模型，同一条取舍（模型认得中文名，不认得 CAT120）—— 参数同理，传 `name`/`label` 的中文，不传 `dimNo`/`code`。

### 会被改到的

- `/biz/goods/describe`：请求体加一个**可选**字段。老客户端不发 = 走 AC3 的空分支，行为不变。
- B 端建品页的「自动生成」按钮：调用处多带一个入参。

### 明确不受影响

`suggestParams`（出参不变）、`recognize`/快速录入、C 端一切、`prd_goods` 库表、权限码、所有既有测试。

## §2 方案

### 契约变更

- **端点**：`POST /biz/goods/describe` 请求体新增可选字段 `params`（数组，元素 `{name, label}`）。响应不变。
- **库表 / 字段 / 迁移号**：无。
- **权限码**：无（仍是 `BizPerms.GOODS`）。
- **i18n 词条**：无。
- **配置项**：无。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `shop-core/.../api/biz/BizGoodsController.java` | `DescribeReq` 加 `List<ParamIn> params`；`describe()` 透传 |
| 修改 | `shop-base/.../spi/product/GoodsVisionPort.java` | `describe(...)` 加一参 |
| 修改 | `shop-channel/.../ai/port/GoodsVisionGateway.java` | `describePrompt` 加「已知事实」段；空参数时不拼 |
| 修改 | `b-app/src/api/requests.ts` | `DescribeGoodsReq` 加 `params?` |
| 修改 | `b-app/src/pages/goods-edit/*`（调用点） | 把当前已填参数带上 —— **见下方「协同」** |
| 新增 | `shop-app/.../scenario/DescribeWithParamsTest.java` | 判据见 §5 |

**协同**：`b-app/src/pages/goods-edit/` 当前有并行会话在改（压缩包导入 / 快速录入）。
调用点那一行要等那边落定再动，或由那边顺手带上。后端先上不影响任何人：
不发 `params` 就是今天的行为。

### 关键接口

```java
// GoodsVisionPort
String describe(String imageUrl, String title, String subtitle, String category,
                List<Map.Entry<String, String>> facts);   // (参数名, 参数值)，可空
```

### 提示词增量（只加这一段，v3 原文一字不改）

```
已知事实（商家自己填的，可以写进正文，照抄即可）：
· 产地：山西运城临猗
· 口感风味：脆爽
这些值只能照抄，**不许在它们之上引申** —— 填了「常温」不等于可以写
「常温保存更香甜」，那仍然是你不知道的事。
清单之外的一切，上面的禁写规则照旧。
```

### 明确不做（写在这里，免得下次当待办捡起来）

- **标题与卖点的生成**：通道是 `recognize`（快速录入一键识别），归
  `PRD-商品快速录入-压缩包与文字识别`，且有并行会话在改。本 TDD 不碰。
  **生鲜标题公式**记在这里供那边取用（水果无品牌，套不上标品的「品牌+品类+规格」）：

  > `产区 + 品种 + 品类 + 规格`，卖点栏放口感。
  > 例：`山西临猗 阳丰脆柿 带箱9斤` + 卖点 `脆甜无核`。
  > 对标拼多多同类目标题去掉营销词后的骨架（`带箱9-10斤】正宗阳丰脆柿…无核冰糖心`）。

- **放宽 v3 的禁写清单**：不做。两次回归（v1/v2）的代价记在 `GoodsVisionGateway` 类注释里。
- **让模型直接落库**：不做。端上「结果只填进输入框、不直接保存」是这个功能成立的前提
  （类注释：日用品这类没存放常识可讲的货，模型仍会退回营销腔，关键词探针查不出，靠人读）。

## §5 对账三 · 实现 → 需求（测试）

测试类实际叫 `GoodsDescribePromptTest`，且在 `shop-channel` 而不是 `shop-app`（见 §6 偏差）。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `GoodsDescribePromptTest#knownFactsGoIntoPrompt` | ✅ | 不拼「已知事实」段 → 变红 ✅ |
| AC2 | `GoodsDescribePromptTest#forbiddenListStaysIntact` | ✅ | 见下（这条**有意**不随消融变红） |
| AC3 | `GoodsDescribePromptTest#promptUnchangedWhenNoFacts` | ✅ | 同上 |
| AC4 | `GoodsDescribePromptTest#factsMustBeCopiedNotExtended` | ✅ | 不拼那一段 → 变红 ✅ |
| 附 | `GoodsDescribePromptTest#afterSaleParamsAreFiltered` | ✅ | 去掉售后过滤 → **只有这一条**变红 ✅ |
| 附 | `GoodsDescribePromptTest#blankFactsAreSkipped` | ✅ | 不拼那一段 → 变红 ✅ |

```
Tests run: 6, Failures: 0, Errors: 0   BUILD SUCCESS（MVN_EXIT=0）

消融 A1（不拼「已知事实」段）：Failures: 4 —— AC1 / AC4 / 售后过滤 / 空值跳过 四条红
消融 A2（去掉售后过滤）    ：Failures: 1 —— 精准红在 afterSaleParamsAreFiltered
还原后重跑：6/6 绿
```

**AC2 与 AC3 在消融 A1 下保持绿是对的，不是漏测。** 它们钉的是「补事实不等于
放宽规则」与「空参数时逐字不变」—— 这两件事**本来就不依赖那段拼接存在**。
消融 A1 真正证明的是：那四条确实在量「已知事实」这段，而不是在量别的东西。
AC2/AC3 自己的消融是反方向的（删一类禁写、或让空参数也拼一段），
那等于把实现改成**错的**而不是**撤掉**，不在本轮做。

**判据取提示词文本，不取模型输出。** 模型是外部依赖、输出不确定，拿它当断言就是把闸门
建在别人家的服务上（`known-failures.txt` 头部记着恒红闸门等于没有闸门）。
输出质量靠 §1 那种**跑样本读输出**来验，不进自动化闸门 —— 这一条沿用 v1→v3 的做法。

## §6 对账二 · 设计 → 实现（实现完再填）

```
 b-app/src/api/requests.ts                          |  11 +++
 .../neargo/shop/spi/product/GoodsVisionPort.java   |  15 ++-
 .../shop/channel/ai/port/GoodsVisionGateway.java   |  45 ++++++++-
 .../channel/ai/port/GoodsDescribePromptTest.java   | 104 +++++++++++++++++++++
 .../shop/product/api/biz/BizGoodsController.java   |  18 +++-
 5 files changed, 187 insertions(+), 6 deletions(-)
```

| 差异 | 说明 |
|---|---|
| TDD 列了、实际没动：`b-app/src/pages/goods-edit/*`（调用点） | §2 就写明要等并行会话落定，照计划没动 |
| 测试位置与类名与 §2 不同 | 见偏差说明第 2 条 |

### 偏差说明

**1. 关键接口不是 `Map.Entry`，是复用已有的 `ParamKV`。**

§2 的签名草稿写的是 `List<Map.Entry<String,String>>`。落地时发现
`GoodsVisionPort` 里**已经有** `ParamKV(name, value, dimNo)`，还带一个
`ParamKV(name, value)` 的两参构造器 —— 形状与这里要的逐字相同。
按「先复用，再扩展，最后才新建」改成复用它；我中途一度新造过一个 `Fact` 记录，
发现 `ParamKV` 后撤掉了。

**2. 测试在 `shop-channel` 而不是 `shop-app`，类名是 `GoodsDescribePromptTest`。**

§2 写的是 `shop-app/.../scenario/DescribeWithParamsTest.java`。但判据是**提示词文本**，
而 `describePrompt` 在 `shop-channel` 且是 `private`。放在 shop-app 就得把它改成
`public` —— 为了一个跨模块的测试把实现细节公开出去，比把测试放到同包里更糟。
于是：方法降为**包内可见**（加了注释说明为什么不是 private），测试与它同包。

**3. 多了两条 §0 没有的 AC：售后参数过滤、空名空值跳过。**

写提示词时才想到：`params` 里可能有商家自由起名的「售后说明」（「坏果包赔」）。
喂给模型，它会把这些织进正文 —— 而那正是 v2 栽过的那一类
（「明早截单，后天一早送到」），对顾客是一条我们兑不了的承诺。
C 端详情页出于同一理由也把这一格挡掉了。这条属于 AC2「不许编造承诺」的延伸，
不是超范围，但 §0 当时没写出来，补记在这里。

**4. 一次写错了判据。** `blankFactsAreSkipped` 最初用 `containsOnlyOnce("· ")`
数项目符号 —— 而 v3 正文里本来就满是「· 」（格式规则与禁写清单都用它），
量的根本不是「已知事实」那一段。改成用「· 名：值」整串做正负例。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-07 | 草稿，待确认 |
| 2026-10-07 | 用户确认，开始实现 |
| 2026-10-07 | 已实现。`GoodsDescribePromptTest` 6/6 绿，两次消融各自变红；b-app `vue-tsc` 0 错。<br>闸门：`mvn -pl shop-channel -am test`（扫 shop-channel 测试源码）+ 后端全量。<br>**整套 pre-push 未跑完** —— 生成物闸门被并行会话的未提交改动挡着。 |
