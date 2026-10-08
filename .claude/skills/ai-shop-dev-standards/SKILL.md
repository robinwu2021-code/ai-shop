---
name: ai-shop-dev-standards
description: >
  ai-shop 仓库的文档规范与分档规则：在本仓库写代码、改功能、加端点/字段/迁移、重构、评审、补测试时用本篇。
  它按「契约动没动」分三档决定要不要写文档、写哪份；三份产物之间怎么对账；哪些闸门真的会挡 push。
  开发流程本身走 superpowers（brainstorming → writing-plans → test-driven-development → verification-before-completion），本篇不重复。
  Java 代码另见 references/java.md。
---

# ai-shop 文档规范与分档

> 需求说做什么，设计说怎么做，代码是交付物 —— 三者之间要能互相对账，对不上就是缺陷。

流程走 superpowers：动手前 **brainstorming**（Bounded ≈ 0/1 档，Architectural ≈ 2 档）→ **writing-plans**（计划写进 TDD §2，不写 `docs/plans/`）
→ **test-driven-development** → **verification-before-completion**。本篇只管三件事：写哪份文档、怎么对账、哪些闸门会挡你。

---

## 一、先声明档位

接到任务，先输出一行，再动手：

```
档位：1 · 依据：docs/requirements/PRD-支付域.md §3.2 · 产出：docs/technical/TDD-退款回执.md
```

**判档只看一件事：契约动没动。** 契约 = 端点 · 库表/字段 · 权限码 · i18n 词条 · 配置项 · 对外 JSON 结构。

| 档 | 什么活 | 要什么 |
|---|---|---|
| **0** | 文案、样式、不改契约的 bug 修复 | 不写文档。说清照哪份文档改的，跑相关闸门 |
| **1** | 动了上面任一项契约 | TDD（§0 §1 §2 §5 四节）+ 三处对账 |
| **2** | 新域 / 新表族 / 跨端 / 不可逆决策（选型、拆服务、换存储） | PRD + TDD + ADR |

分档是为了让 1、2 档**真的被执行** —— 所有活都要 PRD+TDD 的规范，改一行文案也过不去，整套就会被跳过。

---

## 二、三份产物

```
docs/requirements/PRD-*.md      ← 已有 40 份，先找，不要新建
docs/technical/TDD-*.md         ← 设计落在这里；状态：草稿 / 已确认 / 已实现
docs/technical/ADR/ADR-*.md     ← 只为不可逆决策
代码 + 测试
```

- **先找再建**：`docs/requirements/` 40 份、`docs/technical/reference/` 60 份。`ls` 再 `grep` 关键词，找不到再问。
  **不要开口就说「缺需求文档」** —— 这个仓库缺的不是文档。
- 找到了但和现状不对上：**先改文档，再改代码**，TDD 里记一句「PRD §x 与现状不符，已更新」。
- 确实没有：把口头需求写成三五条 AC（Given / When / Then），确认后再往下；模板末尾有写法。
- 文档格式照 [docs/文档规范.md](../../../docs/文档规范.md)：四层结构、图一律 SVG 不用 mermaid。
- 模板见 [references/tdd-template.md](references/tdd-template.md) —— 三张对账表已经在里面。

---

## 三、三处对账 —— 每一处都是一次可以失败的比对

| 交接 | 对账动作 | 不通过的样子 |
|---|---|---|
| 需求 → 设计 | TDD §0：PRD 每条 AC → 由哪个模块 / 接口 / 表满足 | 有 AC 没有落点；有设计挂不上任何 AC |
| 设计 → 实现 | 实现完把 `git show --stat` 贴回 TDD §6，与 §2 模块设计逐行比 | 出现 TDD 里没有的文件；或列了却没动的文件 |
| 实现 → 需求 | TDD §5：每条 AC 指名一个测试方法，跑它，贴**真实输出** | 有 AC 没有对应测试；或测试名对不上 |

**第三处必须做一次消融**：把实现改回去（或注掉那一行），对应测试必须变红。不变红说明它根本没测到 ——
这是本仓库出现频率最高的一类假绿，`backend/known-failures.txt` 头部记着它是怎么积出 128 条的。

**偏差不是罪。** 实现和设计对不上时，改 TDD 并写清为什么（§6 偏差说明），不要悄悄改代码。

---

## 四、闸门：这个仓库真正拦得住人的东西

| 闸门 | 怎么跑 | 管什么 |
|---|---|---|
| `.githooks/pre-push` 14 道 | push 时自动；提交后 `bash .githooks/pre-push </dev/null` 整套跑 | UI 清单 · 契约 · 孤儿页 · i18n（**只扫端上**）· RTL · Controller 内聚 · SQL 方言 · 生成文档 · vue-tsc · c-app 单测 · 后端编译 |
| `ArchitectureTest` 13 条 | `mvn -pl shop-app -am test -Dtest=ArchitectureTest` | 域间依赖 · Controller 位置 · Service 接口化 · Controller 不碰 Mapper · Port 只在 spi |
| `BackendI18nParityTest` 5 条 + `message-placeholder` 两向 | `mvn -pl shop-app -am test -Dtest=BackendI18nParityTest` · `packages/shared` vitest | **后端** i18n：三语键集一致 · 每个 ErrorCode 有文案 · 带 `{0}` 的码必传参。上一行的「i18n」管不到这些 |
| `backend/known-*.txt` 5 份棘轮 | 各自的守卫 | 存量欠账**只准变短**。先读文件头 —— 「待办型」可减，「止血线型」一个字都改不得 |
| `npx vue-tsc --noEmit` | `b-app` / `c-app` 各一次 | `.vue` 的类型。`npx tsc` 一行都不看，却会给你一个安静的空输出 |

**闸门绿 ≠ 规则被遵守。** ArchitectureTest 的「不得用全限定名」只扫 `shop-app`，`shop-core` 里 24 处、spi 里 347 处它从没看过。
说「检查过了」之前，先说清扫了哪些目录 —— 扫描面就是结论的边界。

---

## 五、写代码时的三条

1. **先复用，再扩展，最后新建。** 新建前在 `docs/technical/reference/` 搜同名概念 —— 这个仓库同一个东西有过三份实现。
2. **改已经在跑的功能前，先说影响范围。** 已应用的 Flyway 迁移是冻结的：改一个字符 checksum 就对不上（见 references/java.md §2）。
3. **生成物改完要重新生成。** 闸门读的是产物；跑生成器前看 `git status`，它会把别人未提交的改动一起读进去。

Java 见 [references/java.md](references/java.md)：Google Java Style + 本仓库两处有意偏离 + 十几条这里反复出事的工程约束。

---

## 六、收工前的六行

```
[ ] 档位声明过，实际产出的文档路径与声明一致
[ ] 需求→设计：AC 映射表齐，没有孤立的 AC、也没有挂不上 AC 的设计
[ ] 设计→实现：show --stat 与 TDD §2 对得上（有偏差已写进「偏差说明」）
[ ] 实现→需求：每条 AC 有测试、跑过、贴了输出；做过一次消融，红了
[ ] 闸门：相关那几道跑过并说清扫了哪些目录；known-* 没变长
[ ] 生成物重新生成了；TDD 状态改成「已实现」
```

---

## 七、边界

- 它管**写哪份文档、怎么对账**，不管文档长什么样 —— 那在 [docs/文档规范.md](../../../docs/文档规范.md)
- 它不管**流程怎么走** —— 那是 superpowers 的事；也不管架构对不对 —— 那在 `ArchitectureTest`
- 它不管界面清单与共享工作区礼仪 —— 那在 CLAUDE.md
