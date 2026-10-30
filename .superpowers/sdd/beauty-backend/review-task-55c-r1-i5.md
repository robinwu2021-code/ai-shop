### Spec Compliance

- ✅ **I5 ADDRESSED。** `scripts/verify-beauty-persistence.mjs:21` 把 docker run 纳入与主体相同的 try/catch/finally；`:85`–`:87` 原样重抛主异常；`:98` 始终尝试按本 UUID 清理；`:99`、`:108` 使次要清理失败不覆盖主异常，而无主异常时清理失败仍使 runner 失败。诊断与终态事件失败各自隔离。
- ❌ **Task55C 整体仍有未解决事项。I1–I4 全部 OPEN，Task quality 仍 Needs fixes。** 本轮四文件 diff 没有修改 Java 生产实现，也未新增或运行 I1–I4 的真实 DB RED；不得因 I5 GREEN 宣称整个任务完成。
- ⚠️ **本轮新增方案文档有一项 Important 和一项 Minor，见下。** 两者是新增修复方案的准确性问题，不是新发现的已运行 Java 回归，也不影响 I5 代码缺陷已关闭的结论。

### Strengths / I5 Closure Evidence

- **启动失败已进入清理保护范围。** `scripts/verify-beauty-persistence.mjs:20`–`:27` 先初始化主异常标记，再在 try 内执行原样 docker run；只读 Lima gate 仍在创建之前。run 的报错或 10 秒客户端 timeout 都会经过 finally 的自身 UUID rm。
- **主异常与清理异常有明确优先级。** `scripts/verify-beauty-persistence.mjs:85`–`:87` 记录“主体已失败”并 `throw failure`，不是构造替代错误；`:98`–`:108` 捕获清理错误，只有无主异常才再抛清理错误。lowMemory 诊断与 events 的 catch 保持局部，不阻断清理，不覆盖主异常。
- **测试验证真实进程行为及副作用。** `scripts/tests/beauty-persistence-runner.test.mjs:69` 真实 spawn 当前 runner，`:71` 的 PATH 仅含临时 fake CLI；fake run 先创建本 UUID 的模拟 daemon 状态，再报错或实际超时，fake rm 根据实际参数删除状态。`:82` 检查自身状态消失，`:83` 检查 foreign 状态不变，`:84`–`:89` 检查 rm 恰好一次且所有后续 Docker 命令只针对自身 UUID。这不是源码字符串测试。
- **六项覆盖与已有日志一致。** `scripts/tests/beauty-persistence-runner.test.mjs:97`、`:105`、`:114`、`:124`、`:132`、`:141` 分别覆盖默认 run 错误、实际低内存客户端 timeout、诊断及清理双失败、默认启动/清理双失败、Maven 主体/清理双失败，以及无主错误时清理失败。`/tmp/task55c-fix1-i5-red.log:1`–`:14` 为 6 tests/1 pass/5 assertion failures；前四项确为状态未删除，第五项确为原主体异常被 cleanup 异常覆盖。`/tmp/task55c-fix1-i5-green.log:1`–`:14` 同一六项全部 PASS，timeout case 实际 10706.9365ms，总 14401.257917ms。未将真实 Docker/Lima/Maven 故障当 RED。
- **参数与任务边界保持。** fixdiff 未更改默认64MB和显式低内存配置、原 gate/镜像/pull-never/UUID/localhost 参数；新测试不接触真实容器。`task-55c-report.md:130` 起明确 I5 GREEN 只证明控制流，不证明128MiB MySQL可运行；`:143` 起明确 I1–I4 和最终 native/B/host/wiring 仍未完成。

### Issues In This Fix Diff

#### Critical (Must Fix)

- 无。

#### Important (Should Fix)

1. **[R1-N1 / P2 / plan-mandated] I2 新修复方案把 openingRevision 推进限定为 OPEN_PENDING 前态，与实际 RESOURCE_PENDING 链接窗口冲突。**
   - **位置：** `.superpowers/sdd/beauty-backend/task-55c-design.md:358`。
   - **问题：** 新增文字要求“未成功仅允许合法 OPEN_PENDING 前态”。但成功 REPLACE 后首次进入 openingRevision 短事务时，外层行的合法持久状态是 RESOURCE_PENDING：既有 `AppointmentPreparationService.java:243` 在绑定 appointmentOperationNo 的同事务明确写入该 phase，appointment advance 推进的是预约 operation，并不替外层接待 operation 提前改 phase。如果后续照这个新方案实现仅允许 OPEN_PENDING，会拒绝正常的首次 REPLACE 成功续行及 UNKNOWN 恢复，I2 修复将引入新的可用性错误。
   - **修正：** 文档应明确允许经过完整原 outer/appointmentOperationNo/receipt/sourceRevision 绑定验证的 RESOURCE_PENDING→OPEN_PENDING，并说明已 OPEN_PENDING 的幂等恢复；先处理 SUCCEEDED 且绝不回退的要求保留。此处是新设计错误，I2 的生产缺陷仍保持原 OPEN 状态；本轮没有重审整个 I2 实现。

#### Minor (Nice to Have)

1. **[R1-N2 / P3] I3 新方案使用不存在的 guest owner 状态名 ACTIVE。**
   - **位置：** `.superpowers/sdd/beauty-backend/task-55c-design.md:362`、`:364`。
   - **问题：** “ACTIVE 保持原 GUEST”和“真实 ACTIVE owner 成功”容易使后续夹具/守卫接受错误状态名。既有真实规范 `ReceptionIdentity.java:58`–`:59` 接受的是 REGISTERED/revision1 或 CLAIMED/revision2；ACTIVE 不是该 guest fact 的状态。
   - **修正：** 改成 REGISTERED，并保持对应 revision/USER 为空等完整 owner fact 约束；不为纠正文档扩展生产状态合同。I3 生产缺陷仍 OPEN。

### Previous Findings Status

- **I1 OPEN：** 同原键竞态重放的 Java 实现未改，无新增真实 DB RED/GREEN。
- **I2 OPEN：** 成功 phase 回退的 Java 实现未改，无新增真实 DB RED/GREEN；新增方案还有 R1-N1 须纠正。
- **I3 OPEN：** 预约 GUEST 新动作 owner 核验的 Java 实现未改，新增 host 反例仍是方案，未声称已编译或执行。
- **I4 OPEN：** 已有孤立实际 task 防线的 Java 实现未改，新增反例仍是方案，未声称已编译或执行。
- **I5 ADDRESSED：** 启动清理与原异常保留具备本轮实现及实际 subprocess RED→GREEN 证据；未发现 I5 runner/test 新增的代码缺陷。
- **M1 分类要求已落实：** `task-55c-report.md:149` 起准确区分预期 negative context、Mockito/JDK/Flyway 依赖提示与宿主重复 mapper/H2/测试 OTP 夹具噪声。原噪声仍存在；没有把宿主 H2 提示冒充 native 证据，也没有降级生产日志。本轮 GREEN 日志本身为六项 PASS，无新异常输出。

### Checks / Scope

- **固定范围：** FIX_BASE `b06f773aadb588d65b40ce7dae31853c270e2155` → NEW_TREE `2db283f10b6eaa765c45a0eb345e3b1880153ebd`；HEAD 仍 `edbbefeb941aa3c27c5029f7c616f226c805748a`，无产品 commit。读取 `/tmp/task55c-fix1-review-snapshot.json` 与 `/tmp/task55c-fix1-b06-to-index.patch`；四文件完整 diff 仅读一次，不重算 git diff/tree。根另确认 index 与 NEW_TREE 完全相同、四文件215+/3-。
- **只读证据：** 阅读 report 末尾 Fix round1、新测试147行、runner 改动及既有 RED/GREEN 完整日志。没有重跑任何测试、语法检查、Docker、Lima、Maven或数据库。
- **唯一 focused 检查理由：** 新方案 R1-N1 的合法前态可能与已有链接写者冲突，只定位 `AppointmentPreparationService.java:243` 的 phase 写入；新方案 R1-N2 状态名可能与已有 owner 验证合同冲突，只定位 `ReceptionIdentity.java:58`–`:59`。未扩展到 D/checkout、环境资源调查或整个 Task55C 实现重审。其余 rg 仅用于已读变更的精确行号定位。
- **写入限制：** 未改 checkout/index/HEAD/branch、未 commit、未派 agent。本轮唯一写入为 `/tmp/review-bm04-55c-r1-i5.md`。
- **证据边界：** 本轮不重新判定以前未运行的 native/B/host/wiring，也不据 fake CLI 通过推断真实 MySQL配置可运行；此前 INCOMPLETE/pending 保留。

### Assessment

**I5 scoped code quality: Approved / ADDRESSED。**

**Task55C quality: Needs fixes。** I1–I4 全部 OPEN；本轮新增方案应纠正 R1-N1/R1-N2，后续按既定范围取得真实 DB RED/GREEN及必要最终回归，当前不能标任务完成。
