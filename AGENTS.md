# ai-shop 项目执行入口

- 新会话先读取一次根目录 [MEMORY.md](MEMORY.md)，按其中的长期约定执行；不要在同一任务中重复加载已读且未变化的资料。
- 代码与文档分档沿用 [.claude/skills/ai-shop-dev-standards/SKILL.md](.claude/skills/ai-shop-dev-standards/SKILL.md)。
- 输入校验、异常处理、简洁实现和验证次数的唯一规范正文在 [Java规范§八](.claude/skills/ai-shop-dev-standards/references/java.md#八简洁实现框架校验与增量验证2026-10-30-用户确认)，不要在其他设计文档复制一套规则。
- 本项目用户确认的增量验证约定优先于通用技能模板中的额外防御、强制消融、重复测试或无关全量检查要求；用户后续明确指令继续优先。
- 保持原有领域/包/类命名、权限、业务规则及数据库原子语义。共享工作区只改、提交本次任务的文件，不覆盖其他会话改动。
