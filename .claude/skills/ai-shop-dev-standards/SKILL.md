---
name: ai-shop-dev-standards
description: >
  ai-shop 仓库的开发流程规范：需求 → 设计 → 实现，三份产物、三处对账。
  在本仓库写代码、改功能、加端点/字段/迁移、重构、评审、补测试时用本篇。
  它按「契约动没动」分三档：改文案不写文档，动契约必须先有 TDD。
  Java 代码另见 references/java.md（Google Java Style + 本仓库的偏离与已踩的坑）。
  本仓库以本篇为准，通用的 project-dev-standards 在这里不适用。
---

# ai-shop 开发规范

> **需求说做什么，设计说怎么做，代码是交付物 —— 三者之间要能互相对账，对不上就是缺陷。**

---

## 零、先声明档位

接到任务，先输出一行，再动手：

```
档位：1 · 依据：docs/requirements/PRD-支付域.md §3.2 · 产出：docs/technical/TDD-退款回执.md
```

**判档只看一件事：契约动没动。**
契约 = 端点 · 库表/字段 · 权限码 · i18n 词条 · 配置项 · 对外 JSON 结构。

| 档 | 什么活 | 要什么 |
|---|---|---|
| **0** | 文案、样式、不改契约的 bug 修复 | 不写文档。说清照哪份文档改的，跑相关闸门 |
| **1** | 动了上面任一项契约 | TDD（四节就够）+ 三处对账 |
| **2** | 新域 / 新表族 / 跨端 / 不可逆决策（选型、拆服务、换存储） | PRD + TDD + ADR |

分档的意义是**让 1 档和 2 档真的被执行**。以前是所有活都要 PRD+TDD，
于是改一行文案也过不去，整套流程被**整体**跳过 —— 那比没有规范更糟。

---

## 一、三份产物

```
docs/requirements/PRD-*.md      ← 已有 40 份，先找，不要新建
docs/technical/TDD-*.md         ← 设计落在这里
代码 + 测试
```

- **需求文档大概率已经存在**：`docs/requirements/` 40 份、`docs/technical/reference/` 60 份。
  先 `ls` 再 `grep` 关键词，找不到再问。**不要开口就说「缺需求文档」** —— 这个仓库缺的不是文档。
- 找到了但和现状对不上：**先改文档，再改代码**，并在 TDD 里记一句「PRD §x 与现状不符，已更新」。
- 确实没有：把口头需求写成 PRD 的验收标准那一节（三五条 AC 就够），确认后再往下。
- 文档格式照 [docs/文档规范.md](../../../docs/文档规范.md)：四层结构、图一律 SVG 不用 mermaid。

---

## 二、三处对账 —— 「互相印证」的全部内容

每一处都是一次**可以失败的比对**，不是感觉：

| 交接 | 对账动作 | 不通过的样子 |
|---|---|---|
| 需求 → 设计 | TDD 开头一张表：PRD 每条 AC → 由哪个模块/接口/表满足 | 有 AC 没有落点；或有设计条目挂不上任何 AC |
| 设计 → 实现 | 实现完把 `git diff --stat` 的文件清单贴回 TDD，与 §模块设计 逐行比 | 出现 TDD 里没有的文件；或 TDD 列了却没动的文件 |
| 实现 → 需求 | 每条 AC 指名一个测试方法名，跑它，贴**真实输出** | 有 AC 没有对应测试；或测试名对不上 |

**第三处必须做一次消融**：把实现改回去（或注掉那一行），对应测试必须变红。
没变红说明它根本没测到 —— 这是本仓库出现频率最高的一类假绿，
`backend/known-failures.txt` 的头部记着它是怎么积出 128 条的。

**偏差不是罪。** 实现和设计对不上时，**改 TDD 并写清为什么**，不要悄悄改代码。
TDD 末尾的「偏差说明」一节就是干这个的。设计文档过时了没人说，下一个人还会照它做。

> 模板见 [references/tdd-template.md](references/tdd-template.md) —— 三张对账表已经在里面。

---

## 三、闸门：这个仓库真正拦得住人的东西

跑得起来的检查比写在文档里的原则有用。**下面这些是真的会挡住 push 的**：

| 闸门 | 怎么跑 | 管什么 |
|---|---|---|
| `.githooks/pre-push` 13 道 | push 时自动 | UI 清单 · 契约 · 孤儿页 · i18n（**只扫端上**）· RTL · Controller 内聚 · SQL 方言 · 生成文档 · vue-tsc · 后端编译 |
| `ArchitectureTest` 13 条 | `mvn -pl shop-app -am test -Dtest=ArchitectureTest` | 域间依赖 · Controller 位置 · Service 接口化 · Controller 不碰 Mapper · Port 只在 spi |
| `BackendI18nParityTest` 5 条 + `message-placeholder` 两向 | `mvn -pl shop-app -am test -Dtest=BackendI18nParityTest` · `packages/shared` vitest | **后端** i18n：三语键集一致 · 每个 ErrorCode 有文案 · 每条文案有码指着 · 带 `{0}` 的码必传参、传参的码必有 `{0}`。上一行的「i18n」管不到这些 —— M6 加了 ErrorCode 没加文案就是这么漏的 |
| `backend/known-*.txt` 5 份棘轮 | 各自的守卫 | 存量欠账**只准变短**。先读文件头 —— 分「待办型」和「止血线型」，后者一个字都改不得 |
| `npx vue-tsc --noEmit` | `b-app` / `c-app` 各一次 | `.vue` 的类型。`npx tsc` 一行都不看，却会给你一个安静的空输出 |

### 闸门绿 ≠ 规则被遵守

ArchitectureTest 的「不得用全限定名书写」这条，扫的是 `Path.of("src/main/java")`
—— 相对于 `shop-app` 的模块目录。那里确实 **0 条**，测试常年绿。
而同一条规则在别处从没被看过一眼：

```
shop-core/src/main      24 处行首 @org.springframework.…
shop-merchant/src/main   8 处
spi 全限定引用（非 import）  347 处
```

**所以：说「检查过了」之前，先说清检查扫了哪些目录。扫描面就是结论的边界。**

---

## 四、写代码时的四条

1. **先复用，再扩展，最后才新建。** 新建之前在 `docs/technical/reference/` 搜一遍同名概念 ——
   这个仓库同一个东西有过三份实现。
2. **零硬编码。** 状态字符串走枚举、数值走常量、环境值走配置。SQL 里的魔法值同样算。
3. **改已经在跑的功能前，先说影响范围。** 尤其是已应用的 Flyway 迁移：
   改一个字符 checksum 就对不上，线上直接起不来（见 references/java.md §2）。
4. **生成物改完要重新生成。** 闸门读的是产物，改了源码不跑生成器等于没改。
   跑生成器之前先看一眼 `git status` —— 它会把别人未提交的改动一起读进去。

---

## 五、Java

见 [references/java.md](references/java.md)。基准是 **Google Java Style Guide**，
本仓库两处有意偏离（缩进 4 空格、行宽 120），外加十几条 Google 不管、
而这里反复出事的工程约束：迁移冻结 · 加列漏补实体 · `@Valid` 的两条接线 ·
`updateById` 跳 null · `@ConditionalOnProperty` 不可叠 · 全局信封裹住 internal 端点 ·
第二数据源被 `@Primary` 接走 · 带域表要 `executeWithoutScope`。

---

## 六、收工前的六行

```
[ ] 档位声明过，实际产出的文档路径与声明一致
[ ] 需求→设计：AC 映射表齐，没有孤立的 AC、也没有挂不上 AC 的设计
[ ] 设计→实现：git diff --stat 与 TDD §模块设计 对得上（有偏差已写进「偏差说明」）
[ ] 实现→需求：每条 AC 有测试、跑过、贴了输出；做过一次消融，红了
[ ] 闸门：相关那几道跑过并说清扫了哪些目录；known-* 没变长
[ ] 生成物重新生成了；TDD 状态改成「已实现」
```

---

## 七、这份规范自己的边界

- 它管**流程**，不管文档长什么样 —— 那在 [docs/文档规范.md](../../../docs/文档规范.md)
- 它管**要不要写**，不管架构对不对 —— 那在 `ArchitectureTest` 与
  [架构评审-分层与Controller粒度](../../../docs/technical/reference/架构评审-分层与Controller粒度.md)
- 它不管界面清单 —— 那在 CLAUDE.md，改了页面就要重跑 `gen-ui-catalog.py`
- **共享工作区**：这个目录常有多个会话同时在改。`git add <目录>`、`git checkout <共享文件>`、
  `git stash` 都会伤到别人。提交前 `git diff HEAD -- <file>` 自己读一遍，只提交自己认得的行。
