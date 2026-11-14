# superpowers · 已冻结

> 状态：**冻结（2026-10-25）** · 新内容不再写到这里

本目录是 2026-10-11 至 10-21 期间 superpowers 流程的产物：`specs/` 是方案，`plans/` 是逐步执行计划。
开发规范（[ai-shop-dev-standards](../../.claude/skills/ai-shop-dev-standards/SKILL.md)）此后改为
**TDD 就是 spec、计划写进 TDD §2**，所以这里不再新增文件 —— 新设计写 `docs/technical/TDD-*.md`。

## 还在这里的，为什么还在

| 文件 | 为什么不动 |
|---|---|
| `specs/2026-10-14-订单状态机-完整方案` · `退货退款逻辑` · `第5步-对象模型` · `第5步-售后写路径与行级退款-方案` | [技术文档索引](../technical/README.md)「订单重构专题」的当前入口 |
| `specs/2026-10-14-订单与售后-三端状态关联` | TDD-订单售后三端断点修复 引用 |
| 名字出现在 `scripts/release/*.json` 里的 specs / plans | 发布清单按路径冻结了范围，移动会让重放失败 |
| 仍有未勾选步骤的 plans | 执行中，完成后移入 `archive/` |

## `archive/`

抬头自称「历史方案快照」「已延期」的 specs，以及步骤已全部勾完、且没有被发布清单钉住的 plans。
只用于追溯当时的判断，**不能拿来判断现状** —— 现状以 `docs/technical/` 下对应 TDD 的状态行为准。
