# C04c-c1 FIX1 限定复审

2026-10-29。范围：美业 embedded 及必要共享依赖；仅复审原 I1–I4 和 FIX1 直接回归风险。工作区 `/Users/robin/.codex/worktrees/beauty-unified-integration/ai-shop-codex`；BASE `87311602bc7e1ce53eeace63a16ea29397164d03`；当前 source seal `0462b1371b6972f4e1b7f88e40a153909c5e2e20a276420543fb5d5c9d1925d5`。

**SPEC: PASS（source stage）。QUALITY: APPROVED（source stage）。I1、I2、I3、I4 全部 ADDRESSED；本轮限定范围无新增 Critical / Important / Minor，原四项 Important 无未关闭项。**

证据为 `/private/tmp/beauty-resource-c1-review-fix1.diff` 全部 505 行、12 个变更路径，作者 report 顶部 FIX1 说明、`/private/tmp/beauty-resource-c1-fix1-manifest.json` 与更新 full manifest。仅对精确定位和故障库隔离补读相邻源码。没有重审无变的旧 scope，没有扩展零售/餐饮/standalone 验证。root 的完整 SHA 核验及 embedded 编译由 root 独立记录；本报告不复用其编译结果作为运行证据。

## 四项处理结论

| Finding | 结论 | 源码依据及判断 |
|---|---|---|
| I1 — H2 MICROSECOND 等价转换缺失 | **ADDRESSED** | `backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDdl.java:223-233` 对 masked SQL code 中的有限 simple-column `MICROSECOND(column)` 转成 `EXTRACT(MICROSECOND FROM column)`，用原字符串片段保留其余内容；没有删除 grid 条件或放宽整表比较。`H2FixtureProjectionTest.java:96` 同时包含该语法等价正例、冻结 fixture 正例，以及去掉条件、错误列、错误比较值三种负例。native descriptor/verifier 未实施已撤回的 native EXTRACT 猜测。 |
| I2 — 永久 unit origin/booking tuple 不完整 | **ADDRESSED** | JSON 真源 `docs/technical/design/schema/resource-actual-occupation-schema.json:692-693` 添加独立有限 origin 与完整元组 CHECK，生成 V607 `:218-219` 与 packaged descriptor 同步。BOOKING 六坐标逐一 IS NOT NULL 且 allocation_revision>0；DIRECT 六坐标逐一 NULL，不存在半元组借 SQL UNKNOWN 通过的问题。`MerchantMappers.java:285-291` 独立拒绝未知/null origin、DIRECT 伪 booking 字段、BOOKING 缺坐标/非正 revision。`ActualResourceExecutionIT.java:104` 有真实 native 正向 INSERT/有效审计 UPDATE、全部指定反例及永久 UPDATE 拒绝；负向 INSERT 使用不同原件号，避免 unique 碰撞代替目标 CHECK。 |
| I3 — native fixture 缺强制审计字段 | **ADDRESSED** | `ResourceOccupationNativeFixture.java:194` 显式填写非空 `RESOURCE_OCCUPATION_FIXTURE` created_by/updated_by；未放宽产品 DDL。`ActualResourceExecutionIT.java:73` 的正向 native 场景建立 header/unit/segment/projection 后，先逐表核对一行、两个 audit 字段与 projection→segment，再进入原 immutable/no-delete 断言。原 seed 首次 occupation INSERT 的必填字段遗漏已消除。 |
| I4 — APPLIED START 缺反向效果校验 | **ADDRESSED** | `MerchantMappers.java:309-321` 从同 canonical tenant/entity 中每个 OCCUPATION_V1 START/SUCCEEDED/APPLIED 原 journal 反查恰好一个未删除、store/owner/start/payload/reference/planned/plan/result 全绑定的 header；缺失或字段错配计 invalid，随后既有 header→owner/unit/segment/projection 校验继续工作。未新增 owner 锁、未更改全 ACCEPTED maintenance fence。`ResourceBookingExecutionIT.java:355` 先确认真实 native INSTALLED，再经实际 candidates/facts 拒绝无 header 的 APPLIED 原件；`:374` 保留 ACCEPTED/no-effect 容量对照。`ResourceMaintenanceIT.java:285,297,308` 分别覆盖损坏 APPLIED typed unavailable、ACCEPTED 仍冲突、no-effect 不虚构忙碌。完整 APPLIED/valid ended 原场景保留。 |

以上路径简称的基目录：infra 文件位于 `backend/shop-app/src/test/java/ai/neargo/shop/infra/`；scenario 文件位于 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/`；mapper 位于 `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/`。

## 故障注入与直接回归范围

已核 `ResourceBookingExecutionIT.java:389` 的 I2 semantic-read 故障夹具：仅在该 case 所属独立 native 数据库删除两个新 unit CHECK，随后插入其他关联完整的损坏记录；只 stub schema inspection，让真实 mapper 与真实 ResourceOccupationGuard 到达 semantic 分支，未 mock countInvalid。该类 `@BeforeEach database()` 为每个 case 创建新的 UUID 数据库，因此此故障不污染正常 native DDL/source 场景。它只能证明独立语义读防线的源码设计，不能冒充 healthy-native conformance 或实际 START；作者说明与实现一致。

FIX1 只新增两项 native unit CHECK；common ALTER/保留 statement hashes 没有改写。diff 中 schema-test.sql 仅更新 V607 source/descriptor 绑定头，policy 仅更新对应 entry 的两个 SHA；packaged descriptor 同步 CHECK、migration SHA 和 Flyway checksum。没有出现放宽 H2 unknown SQL/trigger/body 校验、旧 migration 改写、变更旧 BOOKING 原字节或新增业务入口的修复。没有发现这 12 paths 引入的新可行动问题。

## 验证状态

本 reviewer 只读取源码并写本报告；没有运行测试、Maven/编译、SQL/Flyway/DB、HTTP、Docker、应用或子 agent，没有产品/index/git 变更。四项 ADDRESSED 表示源码缺陷及对应 source fixtures 已达到本轮关口，**不表示 native DDL/触发器、H2 执行、并发、业务运行或部署已通过**。运行验证继续按用户统一阶段后置；root 后续只需持有当前美业 embedded 编译与 source seal。c2 START/移交/Core ACK、c3 生命周期及 C05 工作流仍未交付，canStart 边界保持原裁定。
