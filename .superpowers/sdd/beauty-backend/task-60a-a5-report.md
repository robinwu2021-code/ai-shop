# Task60A-A5 实施报告

Status: DONE（本片实施与定向验证完成；根独立规格/质量 gate 和提交待根处理）。

档位：0。依据：`docs/technical/TDD-美业系统-后端开发计划.md` §2.27 与 `task-60a-a5-brief.md`；仅加强测试守卫，不改持久化/API/DDL 等契约。唯一 worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`。IMPLEMENTATION_BASE：`6744b462ee13006d08f43216266a79f1bd4d482a`。

## 产品交付与边界

唯一 owned 产品文件：`packages/shared/tests/entity-alignment.test.ts`。

- `NO_ENTITY_YET` 精确新增 12 键；基线观察到的原 57 键及值逐字保留，最终观察到 69 键。数量仅用于本次差集证据，不写成 runtime 固定合同。
- 抽取纯函数 `unregisteredBusinessTables(schemaTables: Iterable<string>, entities: ReadonlyMap<string, unknown>, registrations: Readonly<Record<string, string>>): string[]`；真实业务表守卫和全部新增分类行为调用同一函数。登记通过 `Object.hasOwn` 检查，继承键不能豁免。
- 保留原 anchored IGNORE 的两个模式字符串原字节，仅把声明及原注释移到 helper 前；没有 prefix/wildcard 扩容。实体缺列、ghost 字段和 stale 登记的原检查未改。
- 新行为使用真实解析 schema、真实 ORM 扫描结果与当前登记；未知 `quality_unregistered_business_probe` 只返回自身；从登记副本删除 `sys_outbox_stream` 后返回真实表和 probe；积分 `pts_account`、物流 `lgs_push_event` 与新增 owner 表共同经 helper 分类，且验证真实迁移存在、无 ORM；另证继承 probe 和 `toString` 不能放行。没有名单镜像自证、dummy Entity、虚构迁移。

## Owner 核对

编写理由前已读取实际源码，而非只复制准备清单：

| 新登记 | 实际 owner 与替代读取 |
|---|---|
| `sys_outbox_stream` / `inv_outbox_stream` | `JdbcOutboxStore` 的固定 `Source.PLATFORM/INVENTORY`（sys/inv）构造表名；JDBC 按 stream_key 加锁查询、递增 next_sequence |
| `sys_outbox_delivery` / `inv_outbox_delivery` | 同一 source-bound JDBC adapter 追加、认领、回写投递；按投递 id 或 event_no/handler_id 查询 |
| `sys_outbox_operation` / `inv_outbox_operation` | 同一 adapter 追加重放/跳过审计并按 request_id 查幂等操作 |
| `prd_stock_sync_operation` | `StockReconcileService` JDBC，按 operation_no 读取结果、按门店取历史；OperationResult/OperationRecord 与 JSON 结果 |
| `prd_stock_alignment_batch` | `StockAlignmentService` JDBC，按 batch_no/entity_no/store_no 读取 Batch 并生成 BatchView |
| `prd_stock_alignment_line` | 同服务 JDBC，按 batch_no/line_no 读取 StoredLine 并生成 LineView |
| `ord_refund_coupon_scope` | `RefundObligationMapper` 持久 SQL；RefundCouponScopeServiceImpl 冻结 Scope record JSON，RefundObligationServiceImpl 按 order_no 读取与反序列化 |
| `ord_refund_obligation` | `RefundObligationMapper` 持久 SQL 创建、锁定、更新及按 execution_no 读取 Map 行；退款服务生成 Progress record |
| `inv_event_consumed` | `TransactionalOutboxHandler` JDBC 与消费效果同事务插入，唯一键冲突识别重复；`OutboxConfiguration` 的 INVENTORY 选择该 inbox 和库存数据源/事务 |

各项按已有 JDBC 或 SQL mapper/record 设计登记，无需添加无用 ORM 实体。

## 实际命令与 RED/GREEN

以下每次测试的明确 cwd 均为：

```text
/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/packages/shared
```

版本命令及实际输出：

```text
../../node_modules/.bin/vitest --version
vitest/4.1.11 darwin-arm64 node-v26.0.0
```

每个阶段执行的完整测试命令相同；只更换日志文件名：

```sh
../../node_modules/.bin/vitest run tests/entity-alignment.test.ts --cache false > /tmp/task60a-a5-oldguard-red.log 2>&1
../../node_modules/.bin/vitest run tests/entity-alignment.test.ts --cache false > /tmp/task60a-a5-behavior-red.log 2>&1
../../node_modules/.bin/vitest run tests/entity-alignment.test.ts --cache false > /tmp/task60a-a5-green.log 2>&1
../../node_modules/.bin/vitest run tests/entity-alignment.test.ts --cache false > /tmp/task60a-a5-ablation-red.log 2>&1
../../node_modules/.bin/vitest run tests/entity-alignment.test.ts --cache false > /tmp/task60a-a5-final-green.log 2>&1
```

每次均保存并读取退出码和完整输出。结构化记录为 `task-60a-a5-evidence/commands.json`；同目录保留全部实际日志。

| 阶段 | 实际结果 | 证明 |
|---|---|---|
| 未修改旧守卫 RED | exit 1；1 failed、5 passed、6 total | 真实“每张业务表都有实体”守卫恰报 brief 的 12 missing，无编译/导入伪 RED |
| helper 保持旧 `in` 分类 + 新行为测试 RED | exit 1；5 failed、5 passed、10 total | 原守卫及四项新行为实际失败；未知 probe 实际断言失败，prototype 负例收到 `[]`，不是缺 helper 错误 |
| 最小实现 GREEN | exit 0；10 passed、10 total | 12 精确登记及 own-key 修正后通过 |
| 删除一条登记消融 RED | exit 1；3 failed、7 passed、10 total | 只删除 `sys_outbox_stream` 一行，真实守卫报告该实际表，probe 分类及积分/物流/新 owner 共路行为也红 |
| 精确恢复后最终 GREEN | exit 0；10 passed、10 total | 同一命令，1 test file passed |

消融仅删一条新增登记，不动 helper、测试、IGNORE 或 schema；删除登记副本的行为测试在消融下仍通过是预期，因为它本就要求拒绝该真实表。消融完整一行 diff 见 `ablation.diff`，原始行为 RED 源码见 `test-first.ts`。

消融前/最终恢复 SHA-256 均为 `2756d20054045dcc8cd6443130fa53d223ac4d84a602f402f4eda411180e06ea`；消融态为 `11bccb6f73b738f7e507b60bf1e9beb79a82fc52172061ebd3059ff2208e2c82`。用 Python `read_bytes/write_bytes` 精确保存/恢复，恢复后断言 bytes 相等；见 `exact-restoration.json`。

## BASE diff、manifest 与自检

在上述 worktree 根目录执行：

```sh
git diff 6744b462ee13006d08f43216266a79f1bd4d482a -- packages/shared/tests/entity-alignment.test.ts
git diff --check
git status --short
git diff --name-only 6744b462ee13006d08f43216266a79f1bd4d482a
```

`git diff --check` exit 0。完整 owned 产品 BASE diff 为 `task-60a-a5-evidence/product-base.diff`，SHA-256：`09c7fb4124ed3b91c2657f48e9f923b9848fe2a1d3c478ede451e87385c92639`。

`owned-product-manifest.json` 列 1 个产品文件，基线 SHA-256 `3229eda16f7b7104731763c8f4331b197edd1d4591a05734244858d4702d3fad`，最终 SHA-256 `2756d20054045dcc8cd6443130fa53d223ac4d84a602f402f4eda411180e06ea`，最终 25489 bytes。报告/evidence 是本片非产品记录。工作树状态中的 `progress.md` 是根并行维护，不属于本片改动；未触碰或恢复根文件。

独立于 runtime 的 baseline gate 读取 `git show BASE:<owned-file>`、原登记 raw 快照及当前文件；删除新增 12 行后整个原登记块逐字一致，新增键差集恰 12、无删除、无原值变更、IGNORE 模式字面量不变，见 `registration-gate.json`。该 gate 不复制到产品单测。

作者自审完整 BASE diff：没有弱化列/ghost/stale 检查；真实守卫和负例同路；新增项理由来自实际 owner；未知及继承键拒绝；没有修改实体/SQL/parser/source-lineage/known/POM/package/lock/生成物。没有派子 agent、stage/commit、部署、Java/native、数据库或远端操作。独立审查由根执行。

## 偏差、警告与依赖边界

- 首次受限执行同一测试命令在加载配置时 EPERM，无法写 worktree 内 `packages/shared/node_modules/.vite-temp`；该次只有 startup error，无业务测试计数，单独记录 `environment-startup.log`，不冒充 RED。随后使用明确 cwd 的窄范围 escalated 工具执行授权测试；未安装依赖或改 package/lock。
- 没有产品范围偏差。IGNORE 声明因提取 helper 仅缩进位置变化，模式字符串逐字不变；一次证据脚本把声明缩进也当成模式比较而触发断言，随后改为比较模式字面量并保留原登记整块字节校验，产品文件未因此修改。
- 按 brief 仅运行 entity-alignment 单文件，不运行有生成文档副作用的 schema-lineage，也不声称整个 shared 套件通过。A4 已归属的九业务键/18 ERD 缺项及 voucher 分叉是 A5b 的血缘语义决策，本片未修复、忽略或重跑该套件。
- 本报告只结清 Task60A-A5 的授权实施与定向验证，不代表整个 Task60A、BM04、P2-P4、BM05 或部署完成。
