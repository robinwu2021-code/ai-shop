### Spec Compliance

- ❌ **Issues found — Task55C 当前快照不符合完整合同，不能验收完成。** 静态确认 5 项 Important：同原键成功竞态仍会返回旧 revision 冲突（I1）；REPLACE 成功后可回退外层 phase（I2）；新预约 GUEST 开单省略真实 owner 核验（I3）；未阻断预约已有孤立实际 task（I4）；新增低内存 runner 的启动超时可绕过自身容器清理（I5）。定位见下。
- ⚠️ **Cannot verify — 最终 native/B/host/装配收口仍缺证据。** 精确未运行项目以 `.superpowers/sdd/beauty-backend/task-55c-report.md:64` 的最后清单为准，详本报告“Cannot Verify”。环境阻塞不抹去已有 GREEN，也不使未运行测试成为通过。
- **Task quality: Needs fixes.** 除修复以下代码缺陷外，须在环境可用后取得对应真实 RED/GREEN 及准确的最终必要回归；当前报告明确 INCOMPLETE 是准确状态。

### Strengths

- **两入口共用真实写体且依赖无环。** `AppointmentServiceImpl.java:65` 和 `WorkOrderServiceImpl.java:25` 委托同一 `WorkOrderCoordinator`；`ReceptionCoordinator.java:173` 只委托 `ReceptionReassignment`，后者没有反调 Service/接待 Coordinator。`BeautyReceptionConfiguration.java:65` 起注册强构造依赖，未用 Lazy/ObjectProvider 隐藏内部循环。
- **范围写入确实是一个本域短事务。** `WorkOrderCoordinator.java:175` 起在 root→fence→current→外层 operation 之后核本域 heads；`WorkOrderCoordinator.java:228`–`:280` 写 OPEN/1 工单、逐项目 PENDING/0 任务、完整 typed context、OPEN/RECEIVED 草稿、ActionLog、安全 Outbox、成功 result。没有创建执行 step/participation；草稿不含凭据或外域下单指令。`BtyServiceSubmission.java:37` 的 retry 字段无默认赋值，V1 SQL `next_retry_at` 可空；既有 `BeautyMappers.java:755` 明确只扫描非空 retry，因此新草稿不会自动外送。
- **语义范围和容量份额保留。** `WorkOrderCoordinator.java:316` 的 scope hash 排除了 ObservedHeads/HoldEvidence 及最终 task/WO/submission 随机号；`:255` 的 context 保留完整 frozen 对象、原安排/hold 引用与真实 taskRefs。`WorkOrderPreparation.java:134` 的定义读取共用于原预约与未安排路径；冻结 assignments 按原 item 取全量，没有按 resourceNo 去重。`WorkOrderScopeIT.java:171` 的未安排断言及容量/数量用例具有真实 SQL/事务断言。
- **先 SQL 授权根，再投影真实子行。** `WorkOrderServiceImpl.java:29`、`:34` 直接调用已实现的 `WorkOrderMapper.selectScoped/selectOwned`；`BeautyMappers.java:391`、`:404` 绑定 root/context 的完整 scope 与 canonical identity，C 只接受 USER 或同 scope receipt。`WorkOrderQuery.java:40` 又核原 OPEN_WORK_ORDER/SUCCEEDED operation，不用常量伪报成功；task/step/participation 查询各核本域活跃父链。legacy B 路径 UNKNOWN/动作空，C 根查询拒绝无 context。
- **证据边界诚实且可定位。** 已有真实首能力 RED、政策 hash RED、无预约指纹 RED、语义 hash RED、原 operation 父链 RED，以及有效父链消融；`/tmp/task55c-ablation-red.log:137` 显示实际 `[REAL, POISON]` 对期望 `[REAL]`，恢复后 GREEN。报告没有把编译/Docker/JDBC 故障当业务 RED，也没有声称公开 wire/部署/直客/交易/支付已交付。

### Issues

#### Critical (Must Fix)

- 无静态确认的 Critical。

#### Important (Should Fix)

1. **[I1 / P1] 同原键调用取得旧 pending/absence 后，另一连接先成功时，输家仍会走旧 revision/事实校验而冲突。**
   - **位置：** `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator.java:108`、`:115`、`:126`；`ReceptionReassignment.java:47`。无预约对应 `WorkOrderCoordinator.java:95`、`:98`、`:103`。
   - **触发与影响：** 成功检查只在最初 `operations.find` 后进行。输家若已拿到 OPEN_PENDING，赢家随后完成同一 operation，输家仍以 original.expectedRevision 调 `reassignment.reassign`；即使只是显式传入原 startAt、没有真正变更，重排 helper 在进入最终原 operation 锁之前就因根 version 已推进而抛 CONFLICT。若最初 find 看到 absence，而赢家在读取根之前成功，也会在 `:115` 直接失败。成功后的原键不再稳定返回原冻结结果，违反原键合同。未安排路径也有同类窗口：begin 可能已返回赢家成功行，但仍进行准备 scopeHash 比较，且未重读成功行，当前定义变化可挡住应返回的成功结果。
   - **证据：** 新测试 `WorkOrderScopeIT.java:550`、`:556`、`:598` 暂停真实 begin 返回的旧 pending，待赢家真实提交后恢复输家，恰好覆盖该窗口；报告 `task-55c-report.md:68` 明确仅 compile，尚无执行 RED/GREEN。历史 `same-key.log` 的非确定性同时起跑 GREEN 不能排除此调度。
   - **修复要求：** 保留“当前身份/权限→完整 namespace/hash”的顺序；begin 返回已成功行时立即返回其原 result。对之后会因竞争过期的 revision/安排/准备校验，只允许重读**同 namespace/key/hash、同原 outer operation** 的 SUCCEEDED 结果；不存在该成功时仍返回原冲突，绝不能借异 key 赢家结果。覆盖上述预约与未安排窗口。

2. **[I2 / P1] REPLACE openingRevision 的后续短事务无条件把 SUCCEEDED 回写成 OPEN_PENDING，已成功工单可失去读取与重放能力。**
   - **位置：** `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator.java:153`–`:160`，关键赋值 `:159`；影响查询 `WorkOrderQuery.java:40`–`:42`。
   - **触发与影响：** 两个同原键调用都取得成功 REPLACE receipt，输家暂停在真实 `appointments.advance` 返回后；赢家写完范围及外层 SUCCEEDED。输家恢复后只校关联 appointmentOperationNo，随后无条件 setPhase(OPEN_PENDING)。该事务单独提交，之后最终写体因根 revision 已再次推进而失败；已建工单/context/submission 留在库里，外层原操作却永久退回待决。新详情守卫要求 phase=SUCCEEDED，B/C 正常读取都变 NOT_FOUND，原键也不能恢复到成功结果。这不是仅返回码竞态，而是持久状态被破坏。
   - **证据：** `WorkOrderScopeIT.java:553`、`:561`–`:574` 的真实 advance 调度测试覆盖此窗口；目前仅编译，生产无成功 phase 防回退代码，报告 `task-55c-report.md:68` 与此一致。
   - **修复要求：** 在该短事务取得外层行锁后先处理 SUCCEEDED 并返回原冻结结果；已成功行不能再改 frozenPayload/phase。对未成功行核合法前态、原 outer/appointment op 绑定，再推进 openingRevision。独立 RED→修复→GREEN，验证最终 phase 与详情读取保持成功。

3. **[I3 / P2] 新预约 GUEST 开单在 052 及省略客户字段的 054 中没有调用真实 guest owner。**
   - **位置：** `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator.java:117`–`:121`、`:283`–`:296`；`WorkOrderPreparation.java:129`。绑定设计 `.superpowers/sdd/beauty-backend/task-55c-design.md:155` 明确要求 GUEST owner.get 的真实同店事实。
   - **问题与影响：** fromAppointment 直接由持久 root.guestRef 组装 VerifiedIdentity("GUEST", ...)。新 052 从不走 `identity()`；054 也仅在请求显式携带 customerRef/guestNo 时走 `verifyFull`。因此这两种合法入口可在没有当前真实同 scope guest 事实时创建新的 customer/context/草稿身份。已核过的预约根不是本次设计要求的 guest owner 核验替代品；CLAIMED 归一化及同 scope 不可变 receipt 的边界也没有在这些新动作入口执行。
   - **focused 核对：** 既有 `AppointmentCoordinator.java:114` 的 currentArrangementEvidence 只核授权根、成功安排/receipt/hold；不读 guest owner。`ReceptionIdentity.java:22`–`:27` 已有真实 `GuestRegistrationPort.get` 和 fact 校验，`CustomerOwnership.java:19`–`:24` 具有同 scope receipt 归属逻辑。故不存在未见的下层补验。
   - **修复要求：** 在成功原键 fast path 之后、创建新 intent 之前，锁外对原 GUEST 做真实 owner 核验并与原预约身份/完整 scope 相交；如采用 CLAIMED→USER，须按设计验证同 scope 不可变 receipt，不能自动认领。保留成功原键不重验过期票据的行为。host 增补直接 052 和 054 省略身份字段的真实 guest/错误 scope/CLAIMED 边界，现有 host 用例只覆盖显式 guestNo 的 054。

4. **[I4 / P2] 无 REPLACE 的预约开单只防已有 workOrder，未防同预约已存在的实际 task。**
   - **位置：** `backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/service/impl/WorkOrderCoordinator.java:187`、`:197`–`:221`；`BeautyMappers.java:132`–`:137`。合同见 `task-55c-design.md:176`、`:179`。
   - **触发与影响：** 预约仍有合法成功安排、fence/current 为空，但已有 service_task 通过 appointment_item 归属该预约，且对应 workOrder 缺失/不构成已有本预约根。普通空 assignments/startAt 的开单不走 `replacementForReception`；最终 verify 也未调用实际 task 防线，markWorkOrderOpened 的 NOT EXISTS 只查工单。因此会再生成一套 task 和草稿，违反“已有实际 task 也确定冲突”的保守范围边界。V1 的 workOrder appointment 唯一键只能防第二个工单，不能防这类已有 task。
   - **证据：** 同一 diff 内已有 `BeautyMappers.java:140`–`:149` 的 `countWorkOrders` 把 workOrder 与原预约实际 task 两类情况都计入；B 重排在 `AppointmentCoordinator.java:100` 和 prepareReception 中使用该防线，C 直接开单遗漏。schema 没有使这种孤立父链不可存在的 FK 防线。
   - **修复要求：** 在同一预约根/fence/current/原 operation 锁事务中复用完整本域已有工单/实际 task 守卫，并保持软删历史工单不可重复转换的现有 CAS 防线；增补真实孤立 task 反例，断言无新增范围、草稿及外部调用。

5. **[I5 / P2] 新低内存 Docker 客户端超时发生在 try/finally 之外，可遗留本任务创建的运行中容器。**
   - **位置：** `scripts/verify-beauty-persistence.mjs:8`–`:9`、`:20`、`:26`、`:84`、`:93`。
   - **触发与影响：** 本次新增 lowMemory 模式给 `execFileSync('docker',...)` 10 秒超时，但 detach run 仍在 try 之前。Docker daemon 已创建/启动容器、客户端迟迟未返回时，超时只终止客户端并抛异常，不会终止 daemon 中的 detached 容器；控制流没有进入后面的 finally，UUID 容器不清理、终态事件也不采集。`--rm` 仅在容器退出时删除，不能替代停止仍运行的实例。这会在本就紧张的测试 VM 中继续占资源，违反“finally 仅清自己且必清自己”的隔离合同。
   - **修复要求：** 把启动也放入受保护的 try/finally，按唯一 UUID 清理，即使 run 报错/超时也尝试自身清理并保留原错误；不得改动其他实例。该分支可以用 stub 模拟“daemon 已创建后 CLI timeout”验证清理调用，本次审查没有启动 Docker 或数据库。

#### Minor (Nice to Have)

1. **[M1 / P3 / 继承验证噪声] 测试输出并非 pristine，报告应保持分类且最终测试夹具可逐步收敛噪声。**
   - **位置：** `/tmp/task55c-reactor2.log:85`（动态 Java agent）、`:93`（预期负例 context 初始化告警）、`:284`（既有连接关闭反例）；`/tmp/task55c-op-parent-green2.log:68`（Flyway 对 MySQL8.4 的版本提示）；`/tmp/task55c-host1.log:577`（既有重复 mapper）、`:586`（host 启动 H2 的 Flyway 提示）、`:603`（测试配置 OTP 限流关闭告警）。
   - **判断：** 这是可见的验证输出问题，不是本轮新增的业务行为漏洞；不能将这些告警升级成“C 使用 H2 验 native”或“C 生产关闭限流”。应在报告中区分预期负例、依赖/宿主夹具噪声与新的异常；按归属收敛测试日志，避免生产日志降级或为本任务扩改无关业务。

### Cannot Verify / Outstanding Acceptance Evidence

- **V1 — 最终新增父链断言未跑：** `WorkOrderScopeIT.java:279` 后新增错误 appointment-item 父链与错误工单参数断言，报告 `task-55c-report.md:66` 明示 parent-full 只到容器启动失败。已有基本删除/跨 entity 父链和真实消融 GREEN 保留，不能据此推断新增断言通过。环境恢复后只跑该精确 case。
- **V2 — 新原 operation 守卫后的 legacy/receipt 全 case 未复验：** `WorkOrderScopeIT.java:335` 的 fixture 现在同步 operation/context/root GUEST 身份，报告 `task-55c-report.md:67` 留待运行。已有单独 `scopedReadRequiresOriginalSucceededOperation` 的 RED→GREEN 支持新 guard，不能替代 legacy/receipt 的最终组合回归。
- **V3 — 两个确定调度竞态仅 compile：** `WorkOrderScopeIT.java:550`、`:553`；`/tmp/task55c-race-compile.log:55`、`:71` 只有 javac/BUILD SUCCESS，没有测试统计。I1/I2 静态缺陷不能以 compile 或旧 same-key 同时起跑 GREEN 消除。修复前应取得真实业务 RED，修复后取得对应 GREEN。
- **V4 — B 抽取后的指定回归未收口：** 报告 `task-55c-report.md:69` 的 `reassignsCheckedInWithFrozenRules`、`replacementRejectsWithoutChangingOldArrangement`、`replacementUnknownKeepsOriginalCommandAndFence` 尚未在本轮队列运行。C 的 UNKNOWN/拒绝测试已实际 GREEN，仍不能替代已约定的 B 定向抽取回归。
- **V5 — 最终 Wiring/host 证据版本不同：** `task-55c-report.md:70` 明确 118 普通 reactor 和真实身份 host 成功都早于新 Query constructor/phase guard。应在完成本轮修复后做必要 targeted wiring/host 新消费链复验；不需要重跑无关全量 backend。旧结果不删除，也不推广为最终快照全绿。
- **V6 — 继承宿主架构守卫仍红：** `/tmp/task55c-host-guards.log:581`–`:610` 的 30 tests/2 failures/2 skipped，具体是旧 OpsPoints FQN 和顶层 `[beauty,store]` 未登记。按父任务冻结归属，beauty 是 BM01、store 是 BM03，Task60 前须正常登记/迁移；本次 C diff 没有引入跨域实现依赖或架构豁免。该结果不是 backend 全绿，也不是本轮 C 范围内必须顺带修复的独立新缺陷。
- **V7 — 低内存配置只证实失败，未证实可运行：** `/tmp/task55c-low-calibration.log:1` 的 gate 为 197316KiB；`:13`–`:15` 精确 UUID/ID `ece2a700...` 的 oom→die1→destroy，未 readiness/SHOW VARIABLES/Maven，没有可回读 peak。`/tmp/task55c-kernel-own-termination.log:2`–`:5` 另证实 recovery1 和 parent-full 自身容器的 global_oom；不把所有历史 EOF 都归因 OOM。本轮禁止新建容器是有真实证据的环境约束，不能把 lowMemory 实验当已验证能力。
- **V8 — 前置 TDD 历史不能补写：** 报告 `task-55c-report.md:7`、`:9` 与最终说明承认部分 SQL/Query 和 AC 是实现后验证，没有逐项专属前置 RED。已见真实 RED/消融证据如实保留；控制器应按既定过程裁决该历史缺口，不能把后验 GREEN/消融倒填为前置 TDD。
- **V9 — 公开 wire/真实跨进程部署不在本任务：** `AppointmentService.java:30` 的临时 fail-closed default、本地 override，以及 Service-only 合同与 Task59/60 边界一致。没有真实 module deployment 的证据也没有将其列为已交付，不计 C 漏做未来任务。

### Checks Performed / Focused Inspection Reasons

- **审查快照：** BASE `f1d5daf809bbc481c71402fef172e37c012ae7d6`；已提交 HEAD `edbbefeb941aa3c27c5029f7c616f226c805748a`；不可变 INDEX TREE `b06f773aadb588d65b40ce7dae31853c270e2155`。未自行重算 git diff/tree，使用根提供并核验的快照；无 C 产品 commit。差异包 `.superpowers/sdd/beauty-backend/review-f1d5daf80..index-b06f773aa.diff` 共 6024 行/459264 bytes，按块完整读取；只有工具截断的设计/生成文档区段补读遗漏。源文件 rg 仅用作精确行号定位，没有另读整份已变更实现。
- **模板：** 指定的 superpowers/6.4.2 路径实际不存在；使用当前可用 `/Users/robin/.codex/plugins/cache/openai-curated-remote/superpowers/7.0.1/skills/subagent-driven-development/task-reviewer-prompt.md` 的双结论、severity、具体 file:line 格式。未派子 agent。
- **F1 / CREATE current 行风险：** prepare 的 diff hunk 从函数中段开始，无法判断新增 scoped FOR UPDATE 是否会破坏首次 CREATE。只补看 `AppointmentPreparationService.java:164`–`:213`、`:269`–`:281` 及 `AppointmentPersistence.java:68` 的 current 读取；确认 createRoot 在 `:273` 先插 current/fence，未发现该回归。没有重读整个变更文件。
- **F2 / GUEST 下层是否补验：** 为 I3 只查 `ReceptionIdentity.java:22`–`:27`、`CustomerOwnership.java:13`–`:24`、`AppointmentCoordinator.java:98`–`:147`；确认 currentArrangementEvidence 没有 guest owner 查询，既有真实 owner/receipt helper 可用，I3 成立。
- **F3 / 草稿是否自动发送：** 只查既有 `BtyServiceSubmission` nextRetryAt 初值、V1 service_submission 列定义 `:374` 和既有 due SQL 条件 `BeautyMappers.java:755`。确认新草稿 retry 留空、扫描要求非空；未发现自动外送问题。
- **F4 / 无预约 SERVICE 是否真实 owner 事实：** 只查 `backend/shop-core/.../product/port/ServiceCatalogFactsPortImpl.java:43` 与其直接 `ServiceProductFactsPortImpl.java:29`–`:55`；真实 owner 核同 entity/store、SERVICE 类型、onSale、引用一致。没有因 C fixture 可任意返回 SKU 就误判生产没有 SERVICE 防线。
- **F5 / 一预约唯一工单物理防线：** 只查既有 V1 `:249` 的 `uk_bty_work_order_2(tenant_no,entity_no,appointment_no)`，含历史软删行，不以代码清理放宽。该防线存在，但不覆盖 I4 的已有 task。
- **F6 / V7 字节冻结：** 对实际 `V7__beauty_appointment_assignment_units.sql` 单文件只读 shasum，结果 `8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2`，与冻结值完全一致。diff 未改 V1–V7、未新增 V8；未重跑迁移或数据库。
- **现有日志核对（只读，没有重跑）：** `red1b` 1/1/0/0（未覆盖本地 default 的真实能力失败）；`red2/red3/red4` 各 1/1/0/0；`green1` 1/0/0/0、`green3/batch4/batch5/batch6` 各 3/0/0/0；有效消融 RED 1/1/0/0→GREEN 1/0/0/0；`op-parent-red` 1/1/0/0→`op-parent-green2` 1/0/0/0；`same-key/active-pointers/replace-rejected/catalog-scope` 各 1/0/0/0；UNKNOWN ACCEPT/EXECUTE 隔离各 1/0/0/0。`reactor2` 各模块 2+41+22+25+28=118，BUILD SUCCESS；host1 1/0/0/0。`recovery1` 的 3/0/2/0 及 parent-full/低内存未入 Maven 的历史未改写成成功。
- **未执行的操作：** 未启动 Docker/任何数据库，未重跑 Maven/测试套件，未改 checkout、index、HEAD 或 branch，未 commit。唯一写入是本报告 `/tmp/review-bm04-55c.md`。

### Assessment

**Task quality: Needs fixes.**

**Reasoning:** 主要范围写体、冻结数据、SQL 父链和事务回滚均有实质实现，但 I1/I2 已破坏原键与终态单调性，I3/I4 漏掉明确边界，I5 影响测试隔离收口。代码风险与最终验证缺口须分别解决；当前不能宣称 Task55C 完成或进入 55D/公开 wire。
