# ShareHub × 进销存 · 交接原稿（参考用）

> 2026-10-29 · 未提交 · 进销存独立服务改造的全部交接文档统一放在本目录。
>
> **已转正式文档（2026-10-29）**：[TDD-进销存独立服务与多业态](../../docs/technical/design/TDD-进销存独立服务与多业态.md) · [ADR-046](../../docs/technical/ADR/ADR-046-进销存独立为服务与业态档案化.md) · [进销存-需求](../../docs/requirements/进销存-需求.md) §十三；两张图已改名后移入 `docs/technical/design/diagrams/`。本目录只作追溯，冲突处以正式文档为准。
>
> - **主方案**：`进销存-独立服务改造方案.md`（图在 `diagrams/`），已按 ai-shop 文档规范写（无 mermaid、SVG 带 title / desc、有状态行），优化以它为准。
> - **原稿**：下表其余五份，从 powerbank 仓库 `docs/technical/` 复制，供追溯来源；含 mermaid、链接指向 powerbank 路径，不按 ai-shop 规范。
> - 本目录在 `docs/` 之外，文档规范校验不扫描。定稿后若把主方案移入 `docs/technical/design/`：图移到 `docs/technical/design/diagrams/`，链接里的 `../../docs/technical/design/` 改回 `./`，并在 `docs/technical/README.md` 登记。

| 文件 | 内容 | 在主方案里的去向 |
|---|---|---|
| `进销存-多业态独立服务-改造计划.md` | **改造计划**：进销存脱离 ai-shop 成为独立服务 `inventory`（`backend/inventory/`，基础包白名单），一个实例服务全部业态（含商城切流），业态档案、能力包；M0 ~ M8 任务 | 以主方案为输入 |
| `进销存-独立服务改造方案.md` + `diagrams/` | **主方案**：合并 A + C + B 中库存侧要求，按 ai-shop 规范 | — |
| `评审-ai-shop库存服务复用可行性.md` | 第一轮评审：数量型 vs 逐台、当时建议不复用 | §二 被否选项 C 的理由来源（后被用户裁决改为独立服务） |
| `评审-独立进销存方案.md` | 对 v4/17 独立进销存方案的评审 + 用户裁决 R1 ~ R4d 与待确认 | §〇 差异、§七 取舍、§八 待确认 |
| `A-ai-shop库存服务-需求方案与开发计划.md` | 需求 IS-xx、方案、M0 ~ M5 计划 | §四、§六 |
| `B-业务系统对接-租户身份与权限方案.md` | 运营方即租户、运营人员与商家如何接入（宿主侧） | §4.1；宿主侧细节留在 powerbank |
| `C-进销存补充功能清单.md` | 复用 / 改造 / 新增 / 不做 + 依赖清单 | §五 |
