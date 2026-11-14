### Finding Verdicts

- **I1：接待安排比较丢失同资源多份数量语义 — ADDRESSED。** `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/ReceptionCoordinator.java:196` 区分空/null 的服务端沿用与显式输入；显式 exact duplicates 在 shortcut 前拒绝。`:217` 的比较逐份删除已消费旧行，先处理指定 resourceNo 的请求，再处理仅指定 personnelNo 的请求，并要求余量为空。旧 `[staff,R1,R1]` 与显式 `[staff,R1,R2]` 不再相等；空输入与原 start 直接确认，空输入与新 start 将 retained 标记传入 matcher。
- **I1 内部沿用新时段的数量处理 — ADDRESSED。** `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/AppointmentMatching.java:130` 的 package-private retained 路径仅豁免服务端已有分配的值重复检查，继续复用原 allocatePlan；public frozen 路径仍拒绝显式重复。`:182`、`:205` 用输入索引 Set 跟踪消费：相同值的两份 retained 记录各自计数，普通 item.qty 展开复用同一显式选择时仍只算一个输入，未改资源桶容量算法。
- **原 I1 可达性表述修正。** 原报告说这种旧安排已能真实持久化，证据不足且不正确。冻结 `V1__beauty_p1_tables.sql:220` 的 uk2 实际阻止同 item/revision/requirement/resource 多份行落库；`/tmp/task55b-fix1-red2.log:172` 的 3 errors/0 failures 证实 CREATE 阶段先失败。原 `AppointmentMatchingSearchTest.java:147` 明确支持容量多份，故本次确认的是“既有 matcher 与持久化合同冲突 + 多重集比较缺陷”的组合修复。未改写原报告历史，也不把该 3-error 轮算作接待行为 RED。
- **I1 迁移前置冲突 — ADDRESSED。** 新 `V7__beauty_appointment_assignment_units.sql:4` 仅删除 uk2 并新增同列普通索引；V1 的 `(tenant_no,entity_no,planned_assignment_no)` 唯一键仍保留。真实 V7 后、比较修复前的 `/tmp/task55b-fix1-v7-business-red.log:183` 为 3 failures/0 errors：KEEP/CHANGE_TIME 为 BAD_REQUEST，CHANGE_RESOURCE 为意外 null。最终 `ReceptionBusinessIT.java:383` 的真实 publish→preview→CREATE→arrival→reassign 三场景及显式重复拒绝，已在 `/tmp/task55b-fix1-quantity-final2.log:175` 取得 3/3 GREEN。
- **M1：GREEN 日志告警分类不清 — ADDRESSED（报告层面）。** `.superpowers/sdd/beauty-backend/task-55b-report.md:166` 分类环境兼容、Sequence 性能、继承 mapper wiring、预期 negative-context 告警，明确 GREEN 仅表示断言/build 通过；后续恢复章节也区分连接错误与业务失败，没有声称日志无警告或借此扩改其他平台模块。

### Spec Compliance

- ✅ **PASS（本次 scoped fix round）。** 原 I1 的比较/内部数量行为及最新绑定 V7 方案均落地，无剩余 Critical/Important finding。FIX_BASE `115d1cb74a71ef90dcbbdd9b232d15f18eb40522` → HEAD `7145421152938f09624d25a3f1a9204a392b6e66`；工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`。本文相对代码路径均以此为根。
- ✅ `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/config/BeautySchemaValidator.java:114` 对 V7 做 exact SHA/SQL 校验，只从 manifest 移除指定资源组合唯一键并加入普通索引；其他 columns/checks/modifiers/indexes 沿旧合同保留。`BeautyPersistenceConfiguration.java:32`、`:39`、`BeautySchemaReady.java:6`、`BeautyWriteGuardConfiguration.java:46` 一致要求 7；定点检索 BeautySchemaReady 调用方没有发现遗留的生产 6 判断。
- ✅ `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/BeautySchemaMigrationIT.java:235` 验证 V6→V7 旧行/旧 checksum 不变、重复 migrate、同资源多份新增、重复 business assignment number 仍拒绝；`:274` 覆盖普通索引缺失、错误 UNIQUE、列序错误、业务唯一键丢失、Flyway checksum 漂移五种拒绝与恢复验证。对应既有日志 `/tmp/task55b-fix1-v7-upgrade-final.log:176` 为 2/2 GREEN。
- ✅ 无需为本步引入 ordinal：`AppointmentCoordinator.java:302`–`:329` 逐 snapshot assignment 产生独立 planned_assignment_no 并完整保存不可变 arrangement snapshot，`BeautyMappers.java:359`–`:370` 返回 assignment 列表且以 planned_assignment_no 作最终排序，不依赖资源组合唯一的 selectOne。资源 unit 的 plan/group 语义继续来自冻结快照；V7 没有合并单位或丢失数量。该结论限于当前 B 的安排/重排，不替未来逐单位执行合同作设计承诺。

### New Breakage in the Fix Diff

- **Critical：无。**
- **Important：无。**
- **Minor：无新增项。**
- ✅ 修复继续使用同一 bounded allocator，未收紧为“每个需求必须不同资源”；`AppointmentMatchingSearchTest.java:155` 覆盖同容量资源跨展开 items 的数量/plan demand 和外部已占容量拒绝，`:174` 覆盖原 item.qty 复用显式输入。`/tmp/task55b-fix1-matching-final.log:89` 为 original10+new2 共12/12 GREEN。

### Checks and Evidence

- 检查：只读分块读取完整 fix diff；工具截断部分仅补读缺失区段。报告新增部分、控制器裁决与 preexecution SQL/hash 记录均已读取。未重跑 git diff、Maven、native suite；未修改工作树、索引或 HEAD；未派生代理。
- 检查（命名风险：去掉 uk2 是否丢掉逐份业务身份或让读取错误合并）：定点读取未变化 `V1__beauty_p1_tables.sql:198`–`:224`、`AppointmentCoordinator.java:302`–`:329`、`BeautyMappers.java:355`–`:371`。确认旧 uk2、仍存业务号唯一键、逐份 insert 与列表读取。没有扩查任务执行或资金模块。
- 检查（命名风险：ready 从6升7是否存在遗漏调用方）：只检索美业 Java 的 BeautySchemaReady/schema.version 调用点，生产配置和两个构造夹具均同步7；未展开未变化的平台部署代码。
- 检查：独立读取当前 V7 文件并计算 SHA256，结果 `8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2`，与 controller 预执行记录和 validator pin 一致。fix diff 未修改 V1–V6；已有 `/tmp/task55b-fix1-generation-final.log:12` 记录对 FIX_BASE 的逐字节一致性检查，本复审没有重复 git 比较。
- 检查：读取真实业务 RED `/tmp/task55b-fix1-v7-business-red.log:183`（3 failures/0 errors），以及输入复用中间反例 `/tmp/task55b-fix1-expanded-selection-red.log:81`（1 failure/0 errors），均为断言失败，未计入最终 GREEN。
- 检查：最终已有日志分别为 matching12（`/tmp/task55b-fix1-matching-final.log:89`）、quantity3（`/tmp/task55b-fix1-quantity-final2.log:175`）、boundary2（`/tmp/task55b-fix1-boundaries-final.log:148`）、upgrade/drift2（`/tmp/task55b-fix1-v7-upgrade-final.log:176`）、server10（`/tmp/task55b-fix1-server-final.log:151`），各自均 0 failures/errors/skips，且 BUILD SUCCESS。未把这些分批执行描述成单次全套运行。
- 检查：`/tmp/task55b-fix1-generation-final.log:1`–`:13` 含 V7 generator、数据库/方法生成检查、SQL portability、diff whitespace 和 frozen hash 结果；SQL 无新增 dialect dependency，V7 真源和生成器输出对应实际 DDL。

### Out-of-Scope Observations / Cannot Verify

- 无新增的范围外代码 finding。
- ⚠️ `.superpowers/sdd/beauty-backend/task-55b-report.md:180` 后披露的 26-test 中断轮为 4 JDBC 错误，不是最终 GREEN；后续有上述针对性恢复结果。EOF 根因仍未知，退出137与 finally 强制清理相邻不足以证明 OOM。本复审不诊断未变更的 Docker/harness 或将其升级为代码缺陷。
- ⚠️ 真实 resource owner 跨服务部署、HTTP/remote 接待、C/D 工单及后续逐单位执行能力仍由控制器后续任务承接；本轮没有重跑 HOST/full115/native48，也不宣称这些跨任务验收新增完成。
- ⚠️ 专属 currentArrangementEvidence 实现前 RED 的历史限制不受此修复影响；现有日志能证明其记录的结果，不能重建该历史。后续无需以补跑伪造此前 RED。

### Verdict

**Fix round：All findings addressed, no new Critical/Important breakage。**

**Spec compliance：PASS。Code quality：Approved（scoped re-review）。**
