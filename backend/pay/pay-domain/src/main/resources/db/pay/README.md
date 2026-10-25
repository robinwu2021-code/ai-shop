# db/pay · 支付服务 v4 的迁移

由 `shop-app` 的 `PayDataSourceConfig#payFlyway` 执行，历史表 `pay_flyway_history`（不是平台的 `flyway_schema_history`），
**在平台迁移之后**执行。开发计划 P2；TDD-支付服务-I0地基 AC2。

- 命名 `V<n>__<说明>.sql`，号从 1 起、只在本目录内递增，与 `db/migration` 互不相干。加之前 `ls | sort -V | tail`。
- 方言：生产是 **MySQL 9.7**。不写 `ENGINE` / `CHARSET` / `COLLATE`（用库默认 `utf8mb4_0900_ai_ci`），
  不写 `ADD COLUMN IF NOT EXISTS` 这类 MariaDB 专有写法；`node scripts/check-sql-portability.mjs` 0 命中。
- 已应用的迁移一个字符都不改（校验和）。
- 新表实体继承 `ai.neargo.shop.pay.entity.PayBaseEntity`（没有 `tenant_no`）；表结构以
  `docs/technical/design/schema/pay-service-database.json` 为真源，改表先改模型再生成文档。
