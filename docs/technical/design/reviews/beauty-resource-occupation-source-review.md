# C04c-c1 独立 SPEC / QUALITY source review

审查日期：2026-10-29。工作区：`/Users/robin/.codex/worktrees/beauty-unified-integration/ai-shop-codex`。BASE：`87311602bc7e1ce53eeace63a16ea29397164d03`。作者冻结：62 paths，manifest source seal `b706a4a0412f73d72474afdb1557fcb446630162dc99862b2db2c2c1ae7c6a99`。

**SPEC: NEEDS_FIXES。QUALITY: NEEDS_FIXES。Critical 0 / Important 4 / Minor 0。** 以下均为源码可确定的问题；未执行运行测试，不把 deferred runtime 本身作为拒收原因。用户最新范围为美业 embedded 及其必要共享依赖；不要求零售、餐饮或 standalone 扩展验证。

## 检查对象与边界

已读取绑定的《美业系统-实际资源占用实施细化.md》全文（含统一分支附录、最后 common 安装前置裁定）、TDD 最后 C04c-c1 清单、作者 plan/report/manifest。主视图为 `/private/tmp/beauty-resource-c1-review.diff`，按 hunk 分 passes 检查；大型 JSON 以字段/约束/索引/不可变清单等结构化形式阅读，并与同 diff 的生成 SQL/renderer 对照。补读限于具体风险所需的当前调用上下文：command/booking 执行顺序、维护 RC/根锁、原 mapper 查询及 pending 语义、H2 MICROSECOND 转换和精确定位行。

没有修改产品、Git/index 或根文档；没有运行测试、Maven、编译、SQL/Flyway/数据库、HTTP、Docker、应用，也没有委派子 agent。唯一产物为本报告。编译结果由 root 独立持有，本报告不把编译当运行证据。

检查范围包括：旧 BOOKING kind 保护与原 hash/receipt、consumed whole-hold 拒绝、容量四 counter 和逻辑需求 anti-join、候选/事实/维护真实入口、canonical scope、RC/cache 和锁序、V607/JSON/descriptor/native verifier、H2 hybrid 两套校验、shared wire/error、原生/source fixtures。c2 的实际 START/accept-seal/移交/Core ACK 及 c3/C05 生命周期/工作流仍未交付；这不是 c1 的实现缺陷。freeze 未加入新的公开 occupation route，未打开 canStart。

## SPEC findings

### I2 — Important：永久单位没有有限 origin 与完整 booking/direct 元组约束，守卫也放过 DIRECT 伪 booking 坐标

**精确位置**：`docs/technical/design/schema/resource-actual-occupation-schema.json:675-691`（尤其 678）；生成的 `backend/shop-app/src/main/resources/db/migration/V607__resource_actual_occupation.sql:189-217`；消费它的 `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java:289-295,345-353`。

**触发与证据**：unit 的 `origin` 是 NOT NULL，但 `ck_rcu_origin` 只要求 trim 后非空。六项 booking 坐标全部 nullable，各字段仅有独立非空文本检查，缺少 `BOOKING => 全部存在且 allocation_revision>0` / `DIRECT => 全部 NULL` 的互斥元组约束。因而在一份其他关联完整的 direct 证据中，插入 `origin='DIRECT', reservation_unit_no='某真实预约unit'`，其余 booking 坐标仍 NULL，符合当前 DDL；未知非空 origin 亦符合 DDL。`countInvalid` 只在 `x.origin='BOOKING'` 时检查预约原件、consume 反链以及 initial resource/group，因此该 DIRECT 畸形记录也不会被这两处检测。

**后果**：真实预约原件没有被消费，却能被一个永久 DIRECT 单位占据 `uk_rcu_original` 唯一身份；其后真实 handoff 会碰永久唯一冲突，而旧预约路径仍把原 unit 当未消费。owner 不再保证规格明确要求的“direct 不伪造 booking tuple”和“一份永久消费身份只能表示精确预约原件”。这项问题属于 c1 已交付的证据模型和故障关闭保护，不要求提前实现 c2 writer。

**最小修复**：在 JSON 真源添加不同名称的有限 origin CHECK 与显式 all-or-none 元组 CHECK（每个所需字段使用 IS NOT NULL，避免 SQL UNKNOWN 放过半元组），并校验 booking allocation revision 正值。同步生成 V607/descriptor 及 hash/projection 绑定。guard 的 native evidence 检查也应显式拒绝未知 origin、DIRECT 非空 booking 坐标，保持损坏证据失败关闭。

**必要 source fixture**：真实 V595→V607 fixture 下写正向 DIRECT 全空/BOOKING 全量，再对未知 origin、DIRECT 带 reservation_unit_no、BOOKING 缺任一坐标、非正 allocation revision 做直接 INSERT/UPDATE 负例；另保留 native guard 读损坏元组时 typed unavailable 的源码用例。仍只编写，运行按用户统一测试阶段。

### I4 — Important：成功 START 原 journal 缺少反向 occupation 效果完整性检查

**精确位置**：`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java:302-328`（`countInvalid` 的 header→owner/command 分支；完整 query 为 275-385）；`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/policy/ResourceOccupationGuard.java:25-29,61-76`。

**触发与证据**：在完整安装的 native schema 下，仅存在关联一致的 common execution owner 和 `OCCUPATION_V1 / START / SUCCEEDED / APPLIED` 命令（含合法 original result/planned/reference 字段），但四张 actual evidence 表无对应行。当前 DDL 没有跨表 INSERT 外键/触发器要求 header 同时存在；作者 fixture 的 owner+START 两个 INSERT（`ResourceOccupationNativeFixture.java:91-105`）已给出可用的该前缀。`countInvalid` 对 START 关联的检查从 `mch_resource_occupation h` 出发，没有 journal→header 的反向存在性分支。四张证据表为空、bucket actual counters 为零时，全部 count 分支均为零。

**后果**：`inspect` 接受此损坏原件，`projected/missing/countWork` 都返回零，因此美业实际调用的容量/事实和维护保护把“journal 已声明 APPLIED，但完整效果缺失”当成无实际占用。已有 `countPendingMaintenance`（同 mapper 879-880）只计 ACCEPTED，SUCCEEDED 也不会被它阻挡。这违反原件和效果完整关联、缺失/部分证据失败关闭要求；不能用未来 c2 的原子写事务替代 c1 已承诺的损坏证据校验。

**最小修复**：在相同 canonical scope、无新增反向 owner 锁的 current evidence query 中，反查每个成功 APPLIED START 必须存在唯一、未删除、完整绑定的 header，再由现有 header→unit/segment/projection 校验继续覆盖。不得误判合法 ACCEPTED 尚无效果、SEAL_START/no-effect 或 valid ended 历史；保留 all ACCEPTED maintenance guard。

**必要 source fixture**：真实 native owner/common 成功原件、不插 header 的 fault fixture，经实际 candidates/facts 和一个真实维护入口断言 `RESOURCE_DEPENDENCY_UNAVAILABLE`，并有合法 ACCEPTED/no-effect、完整 APPLIED、已结束历史的对照。不要用 mock(countInvalid) 代替此反向 SQL 分支。

## QUALITY findings

### I1 — Important：独立 Java H2 校验拒绝本次正确保留的 MICROSECOND 等价投影

**精确位置**：`backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDdl.java:210-223`；调用方 `backend/shop-app/src/test/java/ai/neargo/shop/infra/H2FixtureProjection.java:298-304`。对照：JSON 真源 `resource-actual-occupation-schema.json:2576`，生成 fixture `backend/shop-app/src/test/resources/schema-test.sql:11224`，既有 Python 转换 `backend/scripts/gen-test-schema.py:187`。

**触发与证据**：native/common descriptor 的 `ck_resource_bucket_grid` 为 `MICROSECOND(start_at)=0`；Python H2 renderer 正常生成 `EXTRACT(MICROSECOND FROM start_at)=0`。新 `verifyCommonFixture` 用 `SchemaDdl.h2Check` 转换 descriptor 后做整张表定义精确比较，但 h2Check 只转换 literal-IN 和 JSON_VALID，没有 MICROSECOND 的对应转换。`normalized` 仅移除空白/处理大小写，不能把这两个 token 序列变相等。

**后果**：对冻结的原样 `schema-test.sql` 调用新增 `verifyCommonFixture` 就会在 common resource bucket 处失败。已新增调用它的 H2FixtureProjectionTest、SchemaGeneratorTest、SchemaDriftTest、SchemaParityTest 无法接受作者自己生成的正确 fixture；这是源码本身的逻辑矛盾，不是“尚未跑测试”的推断。

**最小修复**：独立 Java 有限 H2 等价转换补齐 MICROSECOND→EXTRACT（或采用保留语义的等价解析），保留完整 grid 和约束比较，不删除该 CHECK、不跳过 common verification、不用刷新 hash 掩盖差异。

**必要 source fixture**：冻结 fixture 正向验收；针对该单一 CHECK 的 native/H2 等价正例，以及去掉 MICROSECOND=0、改成错误列/条件的负例。未知 ALTER/trigger/body/hash 拒绝继续保留。

### I3 — Important：native evidence fixture 未填写新表强制审计字段，所有实际证据场景在 seed 阶段终止

**精确位置**：`backend/shop-app/src/test/java/ai/neargo/shop/infra/ResourceOccupationNativeFixture.java:157-163`；首个 new-table INSERT 为 106-112。DDL：`backend/shop-app/src/main/resources/db/migration/V607__resource_actual_occupation.sql:114-116,156-157`，另外三张新表相同规则。

**触发与证据**：helper `insert` 只自动补 tenant_no/entity_no/created_at/updated_at。directEvidence/bookingEvidence 的 header、unit、segment 入参也没有 created_by/updated_by。四张新表这两列均 NOT NULL、无 DEFAULT，且 CHECK 禁止空字符串；JdbcTemplate 直接 INSERT 不经过 MyBatis AuditMetaObjectHandler。

**后果**：`directEvidence` / `bookingEvidence` 的第一个 occupation header INSERT 就违反新 DDL，根本到不了 OPEN/PAUSED/cleaning、multibucket、consumed、维护或 native permanence 业务断言。它影响 ResourceBookingExecutionIT、ResourceMaintenanceIT 及 ActualResourceExecutionIT 新增核心证据场景。不能把之后宽泛的 BizException/RuntimeException 断言视为 fixture 合格，seed 本身必须成功到达目标状态。

**最小修复**：helper 为这些 native evidence 显式写入非空、可追溯的 fixture actor（created_by/updated_by）；不放宽产品 DDL，也不伪造真实 START owner/授权执行。确认所有直接创建的四类证据行都符合完整约束。

**必要 source fixture**：通过真实 V595 baseline 与原字节 V604–V607 Flyway 安装后，正向建立 header/unit/segment/projection 并检查预期行/关联；再分别进入实际入口测试目标防线。manual seed 仍只证明保护，不升级为实际 START 流程或 runtime 通过声明。

## 已核范围的结论与后续边界

源码上可见旧 hash helpers/Receipt 形状未改；kind 在 receipt/执行入口解析 Action 前检查，写 CAS 限 BOOKING；pending 维护仍覆盖全部 ACCEPTED。whole-hold consumed 拒绝、consume-is-null release CAS、共享 resource roots 下 RC 读取、required guard 注入、逻辑 segment anti-join、OPEN/PAUSED/人工 cleaning 未按 expected end 自动释放、capacity>1 分离 actualUsed/旧 exclusive 标志、10505 三语与 internal 503，以及 H2 仅省四 owner tables/声明 triggers 的方向符合绑定设计。此处是检查范围和源码观察，不是上述四项关闭前的通过结论。

四项修复后按同一美业 embedded 链做限定复审即可，不扩多行业或多部署矩阵。运行测试、原生 DB/触发器实际行为、并发、HTTP 与部署继续统一后置。初期 native EXTRACT 猜测已撤回，不计入任何 finding；本报告 I1 仅是有明确源码对照的 H2 独立转换问题。
