# Task60A-A5 精确非ORM表登记与未知表拒绝

Status: IMPLEMENTATION_APPROVED after accepted A4 product a018b4dc09956fe84f937543bd85d07512c3e0b2. 档位0，仅测试守卫；不改业务持久化/API/DDL。IMPLEMENTATION_BASE由根提供。

唯一worktree /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation。唯一owned产品 packages/shared/tests/entity-alignment.test.ts。允许task-60a-a5-report.md/evidence。禁止改实体/Mapper/SQL/known/ddl parser/schema-lineage/gen-erd/生成文档/POM/package/lock/root文档/其它片，不派子agent，不stage/commit/push/merge/native/deploy。

## 需求

相对实施基线，NO_ENTITY_YET只新增以下12个精确键（不是把整个record改为12项），原57项当前观察到的合法键/值全部保留且数量不是runtime固定合同：
sys_outbox_stream,sys_outbox_delivery,sys_outbox_operation,prd_stock_sync_operation,prd_stock_alignment_batch,prd_stock_alignment_line,ord_refund_coupon_scope,ord_refund_obligation,inv_outbox_stream,inv_outbox_delivery,inv_outbox_operation,inv_event_consumed。

理由必须分别说明真实读写owner/替代读取方法/为何无ORM实体：sys/inv三类由JdbcOutboxStore固定Source.PLATFORM/INVENTORY及JDBC维护；stock sync由StockReconcileService、batch/line由StockAlignmentService JDBC；refund两类由RefundObligationMapper持久SQL/record维护；inv_event_consumed由TransactionalOutboxHandler与OutboxConfiguration inventory配置消费。具体源码位置只读准备 /tmp/task60a-a5-root-facts.md，原登记raw快照 /tmp/task60a-a5-original-registration.txt及baseline.json供根diff，不能把复制名单写成自证单测。

抽取真实共用分类函数unregisteredBusinessTables(schemaTables:Iterable<string>,entities:ReadonlyMap<string,unknown>,registrations:Readonly<Record<string,string>>):string[]。真实“每张业务表都有实体”守卫必须调用它，新增负例也调用同一函数。保留原精确anchored IGNORE对flyway/idempotent/sys_outbox/shedlock与三realm会话/登录日志的判定，不扩prefix/wildcard，不变相豁免未知表。登记仅自身精确键可用，不凭prototype继承键。原实体列缺失/ghost/stale登记检查不减弱。

新增真实行为测试：登记的非ORM表通过分类而未登记的新业务表仍被拒绝（实际parsed schema/ORM集合/currentrecord+quality_unregistered_business_probe，只返回probe；从record副本删除一个本次真实新增项后，同时返回probe+被删除真实表）。既有非ORM登记与新增登记共同参与表分类（真实points/logistics代表及新增owner表都经同一helper，不做列表镜像比较）。可增prototype键负例证明仅own精确登记，不能虚构迁移/无用Entity/宽泛IGNORE。

## TDD与验证

- [ ] 先沿真实旧表守卫取得12missing行为RED；helper提取若先做应保持原分类语义，新增probe缺登记也必须真实断言失败，不把缺helper/导入编译当RED。
- [ ] 仅补12具体理由并接同一helper/行为测试，以 packages/shared 为明确 cwd，运行 ../../node_modules/.bin/vitest run tests/entity-alignment.test.ts --cache false GREEN。根已核实该现有二进制为4.1.11且CLI支持cache选项；不运行pnpm安装，不改package/lock/别人的node_modules；报告实际命令与版本。
- [ ] 最小删一个本次精确登记消融，真实表守卫/同一分类行为应红；恢复精确bytes后同命令GREEN。实际记录总数，不固定测试项数。
- [ ] 不重跑有文档生成副作用的schema-lineage套件；A4旧/新parser证据已证明九业务键/18ERD缺项+voucher分叉是A5b另片，不能为本片GREEN把它修掉/忽略。A5只声称entity-alignment通过。
- [ ] ROOT exact diff gate：新增键差集恰好12，原键/值不变，IGNORE不宽化，真实守卫使用同helper，未知probe拒绝；无额外source/lock/generated变化。报告manifest1file、完整base diff/SHA、RED/GREEN/消融逐字恢复、自检/警告/依赖边界。根独立spec+quality gate后精确提交。

不能把所有60A/BM04/P2-P4或部署写完成；本片没有Java/native/正式数据库操作。遇到真实额外列/实体漂移先给根精确证据，不扩本文件外范围。环境setup失败单列，不冒充业务RED。根已授权必要只读/本worktree可逆写入，无需向用户重复确认。
