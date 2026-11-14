# Task60A-A5b implementation report

Status: DONE — awaiting root fresh spec + quality review, no staging/commit/push/merge performed.

档位0，业务契约不变。唯一工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`，IMPLEMENTATION_BASE `74a071a1bf3b345db3522ced15497a6ba74148fd`。执行依据为本目录 brief、semantic-audit、key-memberships 与主 TDD §2.28。未修改 SQL、Java、DDL parser、A5 entity-alignment、known、package/lock、DOMAINS/TOPICS 或其它生成器；无子 agent、native、部署、暂存或提交。

## 实现与源审计映射

- 新纯模块 `scripts/lib/schema-lineage.mjs` 提供全部约定导出；仅从 ddl.mjs 引入 AUDIT，不读取 schema、不生成文件。`resolveReference` 是 relations 与 FK badge 的唯一解析实现；验证源/目标列、scope 双方列、禁止 self；先查精确表列限定，再屏蔽全局冲突。保留原有效 global owner 的 table/col 与原测试最终有效 collision 理由，删除重复 literal。
- 接受的 11 个普通 owner 全带 tenant scope：instance/usage/recharge/lot/exception/recon/stage/alias/checkout/import/return；真实非 self 消费方逐项独立断言。对应源审计 §“Eighteen proposed…” 1（V487/V489/V498/V505/V509/V515/V517/V519/V524/V526 及审计列出的 writer/consumer）。三个 role owner target_template/template_version/activity_version 保留 template_no/version_no 的真实目标列与 tenant（V478/V484/V527 与 grant/recharge/issue writers）。
- buyer 四实际表 → usr_account.user_no、operation confirmation → ord_checkout_confirmation、迁移 return → pmt_migration_return，均带 tenant。依据 V539/V540/V526 与审计中 CheckoutServiceImpl、PaymentOperationServiceImpl、HistoricalReturnWriter 的 producer/consumer 对照。
- voucher 仅 points 两条与 sv 两条，sv 两条带 tenant；六实际成员精确登记，无跨族、根互连、self 或未知第七表边。依据 V425/V498、PointsPostingMapper/StoredValuePostingService、EntReconciliationFactsMapper 审计证据。
- plan 仅两个 migration 消费方；source_order 仅 legacy operation→ord_order.order_no；grant 仅 sale reservation/pay coupon projection；tier 限 tenant+version；catalog 限 tenant+entity；payment actor 两表→mch_account.mch_account_no 限 tenant。依据审计 §九键与 §18项 3–6，保留 staff login userNo、pts plan 独立命名空间、SERVICE_USE/子单、MIGRATION_BASELINE、PRF-退款条件路径说明。
- receipt/source_event/entry/line/execution 均按精确实际成员登记冲突，无无条件边。所有 12 个本片审计歧义键，追加未知表 fixture 必须无边/无 FK 且 coverage 报未登记。旧未触及分类保持原语义。
- 生产 gen-erd 消费该模块，不再维护第二份 owner/collision/关系循环；Markdown/HTML 的实际引用、scope 列表均调用 describeRelation，HTML 转义。UK 保持既有优先级。LINEAGE 9 个端点、真实 DDL coverage、owner 目标、stale collision、原 Python 产物表集合对照守卫保留；旧 parity 换成独立真实关系/角色列/scope 断言。没有把 Python 旧守卫夸大为新的逐列完整性证明。

`source-audit-verification.json` 实测 363 表及附件全部 14 组实际键成员精确一致，旧非本片 owner 的 table/col 均保留。363 只作为本次观察，不写进永久测试常量。该证据证明源码与解析视图，未检验历史实际数据库行完整性。

## 真实 RED / GREEN 与消融

全部 Vitest 运行 cwd 为 `packages/shared`，二进制 `../../node_modules/.bin/vitest`，实际版本 4.1.11；无安装。命令均显式 `--cache false`。

| 运行 | 实际结果 | 证据 |
|---|---|---|
| `run tests/schema-lineage.test.ts --cache false` 原基线 | 16 项：14 pass / 2 fail | baseline.log；九类未分类、旧双表 parity 失败 |
| 纯提取旧生成器行为，再新增独立断言，同命令 | 57 项：27 pass / 30 fail | behavior-red.log；真实 voucher 跨族、角色列/限定关系缺失、缺目标/scope 时 badge 等断言失败，无缺模块/导出/编译错误 |
| 首次修正后同命令 | 57 项：54 pass / 3 fail | initial-uk-expectation-failure.log；三个错误测试预期把 DDL unique 的 plan 两表/catalog 强制要求 FK，按 brief 的 UK 优先规则修正测试；这不是三个生产缺陷 |
| 定向 GREEN 同命令 | 57 / 57 pass | lineage-green.log |
| `run tests/entity-alignment.test.ts tests/schema-lineage.test.ts --cache false` | 67 / 67 pass | combined-green.log |
| voucher resolver 改回全局 pts；`-t 'voucher_no 精确四条'` | 1 fail / 56 skipped | voucher-global-pts.log；真实 sv→pts 负例失败 |
| plan resolver 改回全局 migration；`-t '协议/条件/局部键'` | 1 fail / 56 skipped | plan-global-migration.log；真实 pts_topup_plan 假边失败 |
| resolver 去 scope；`-t '限定正向关系'` | 9 fail / 48 skipped | strip-scope.log；限定正向完整 scope 断言失败 |
| 精确恢复后最终 combined，同上双文件命令 | 67 / 67 pass | final-combined-green.log |

三次消融仅改实现；每次 finally 精确恢复。mutation-restoration.json 给出全部源码前后 SHA、每次 mutation SHA/命令/返回码；三个 diff 与日志完整保存。最后一次 combined 后 resolver、测试与 A5 字节一直不变。

原基线测试 eager-import 生成器，实际写了六个既有 tracked 产物。仅使用 IMPLEMENTATION_BASE 的 `git show HEAD:path` 字节恢复六件（见 baseline-restoration.json）；预生成 freeze 的所有 23 个 tracked ER 路径均与 BASE 同 SHA，证明之后纯测试没有再写 ER 输出。测试已无 gen-erd import。旧纯行为与 test-first 断言快照保存在 test-first-old-behavior.mjs / test-first-semantic.test.ts。

## 冻源、生成与唯一偏差

初次冻源并完成两次 `npm run gen:erd` 后，自审发现新共享的旧 collision 理由含换行，直接插入 Markdown pipe table 会断行。根明确批准最小 generator-only 格式修正：CRLF/LF→`<br>`，literal `|`→`\|`。精确一行 hunk 见 markdown-format-correction.diff。没有修改 resolver/test/A5，没有重复三次消融或冒称新增测试；最终 67/67 是该三个未变文件的真实已测证据，生成器格式 hunk 另以 `node --check scripts/gen-erd.mjs` 和最终两次实际 ER CLI、最终产物检查验证。

最终新 freeze 后仅再执行两次 `npm run gen:erd`（gen-erd-first.log / gen-erd-idempotence.log）；均 exit 0，输出观察值 363 表 / 603 关系 / 16 既有域 / 20 SVG。初次格式修正前的两次日志与 freeze/hash 以 initial-* 保留，未隐去这次偏差。

最终全部 23 个 tracked ER 路径（包含既有未写/未变文件）及 ignored HTML 在两次生成间 SHA 一致。只改变下表 7 个既有 tracked 产物，未新增图文件或域；全 source+generated diff 含未跟踪新纯模块，见 full-product.diff。精确每件 BASE/current SHA、字节数、节点差与说明见 product-manifest.json。

| 实际 generated delta | 原因 |
|---|---|
| `docs/technical/diagrams/db-mbr.svg` | Existing renderer refreshes mbr_guest_registration already in DDL (11→12 nodes); resulting node placement/segment edge routing changes. |
| `docs/technical/diagrams/db-ord.svg` | Existing checkout operation/quote/confirmation/line/payment-request tables refresh 32→37 nodes; newly proven operation→confirmation inner edge and graph routing/layout changes. |
| `docs/technical/diagrams/db-overview.svg` | Counts and weighted cross-domain references refresh to actual DDL and corrected resolver; removes false sv→pts voucher family and incorporates scoped positive links, without changing DOMAINS. |
| `docs/technical/diagrams/db-pmt.svg` | Adds proven reservation→recharge-tier relationship; unchanged node set; tenant+version scope appears in generated reference explanations. |
| `docs/technical/diagrams/db-prd.svg` | Existing catalog header/version tables refresh 33→35 nodes; adds version→catalog scoped relationship, with resulting node placement/routing changes. |
| `docs/technical/diagrams/db-stl.svg` | Existing payment-order-guard/payment-operation tables refresh 18→20 nodes; same shared resolver refreshes references and routing. |
| `docs/technical/reference/数据库-ER图.md` | Refreshes stale 353/526 totals to observed 363/603, existing domain table lists and references; includes real target columns, scope predicates, all retained collision reasons and conditional paths. Newline/pipe-safe collision cells preserve one row per reason. |

Ignored HTML 由 `.gitignore: docs/**/*.html` 忽略，未 force-add、未 track。`final-artifact-verification.json` 检查 16 个实际字段标记（negative 标为 noFK，允许合法 UK；包括 suppressed negatives、四 buyer 的既有 UK/FK 优先、两个 actor FK、tier FK/catalog UK）、54 条 scope/target-column 描述在 HTML/Markdown 中存在、条件说明保留、61 条 collision 理由各占一行且换行/pipe 安全。HTML 无 sv/ent/pay 详情卡是既有 DOMAINS 限制；其真实正向边由关系测试与 scoped 引用列表覆盖，不宣称扩了视觉域。

## 产品 manifest 与 SHA

产品只有下列 3 份源码 + 上述 7 个实际变化的既有产物；report/evidence 自有。根并行修改 `.superpowers/sdd/beauty-backend/progress.md` 属根，未触碰或纳入产品 diff。

- `scripts/lib/schema-lineage.mjs`（新增） — SHA256 `4071d9e6c2c9e26d02c18c81c1391223754e8644af45f2f6defabc3cbcc6c9e8`
- `scripts/gen-erd.mjs` — SHA256 `3aed77acfd0b42da8621a139d3b90bd5327a93239a561bcd2f7dea7d79359e72`
- `packages/shared/tests/schema-lineage.test.ts` — SHA256 `3515082fb2a3f59f71225dc759e118199d46bf61750b2e178dc1349a97675d98`
- 冻结 A5 `packages/shared/tests/entity-alignment.test.ts` — SHA256 `2756d20054045dcc8cd6443130fa53d223ac4d84a602f402f4eda411180e06ea`，与接受值一致。

所有命令的实质参数、返回码与源码快照见日志、mutation-restoration.json、保存的 setup/implementation/mutation/freeze/artifact-verification 命令文件；最后 `git diff --check` exit 0。期间一次恢复脚本因 Git 中文路径 quoting 失败，改为 NUL-delimited 路径后完成；一次测试预期编辑脚本 cwd 路径写错，在改文件前失败，随后使用明确 `tests/...` 路径纠正。两者不计语义 RED；无额外产品写入。

## 自审与结论边界

已逐项核对 brief 的 exports/shape、table-qualified 优先级、target/scope/source 列验证、精确四 voucher/未知成员拒绝、普通/角色正向、全部实际负例、UK 优先、单 resolver、无 eager test import、原守卫保留、三次实际消融、精确恢复、冻源、真实生成差异、幂等、ignored HTML、完整新文件 diff。完整 product diff 已包含供根独立 spec + quality gate 复查。

保留已有边界：未扩 sv/ent/pay 等详情域；未全面重审旧 globals（例如 checkout 新表复用 quote_no 的旧全球归属仍可能需要另片源审计），未改 parser 的 composite uniqueness 表示，未证明历史行完整性；旧硬编码 prose 数量不在本片修复。定向 67 项通过不代表全库/全 P1–P4/部署完成。本片没有 native、其它生成器或全仓套件运行。
