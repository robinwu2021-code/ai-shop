# BM03 模块审查修复 round1

基线 c338e5722；审查输入 `/tmp/review-bm03-module.md` 已完整读取。Task53自有实现已获审查批准，BM03模块准出仍待I1/I2修复及独立复审。本轮不修改控制器主TDD/progress，不扩展BM04–06。

## 修复设计（实现前）

I1：在原持久查询中先筛 `next_retry_at<=now` 且 `(lease_until IS NULL OR lease_until<now)`，再按 `next_retry_at,id` 升序 LIMIT50。保留claim版本CAS、30秒租约和现有30/60秒重试延迟；重排不丢弃待授权操作、不造用户身份。无需DDL。原生MySQL回归创建51个真实无receipt待处理操作及后面的已绑定CONFIRM终态；模拟默认60秒cron时钟推进，两批后终态必须协调，而第一批所有等待行已再次到期。另验51个活租约在LIMIT前排除，不抢占租约也不阻塞终态。

I2：先按原商品/项目顺序建立全部需求槽位，保持指定resource/person的硬约束；候选人员/窗口/owner容量事实只在搜索前读取，不在搜索节点RPC或持DB锁。预计算每槽候选及规范5分钟bucket，STAFF按personnelNo归一且容量为1（同人不同resource别名不可重复使用），物理资源仍按resourceNo及真实capacity。按候选resourceNo稳定顺序迭代深搜/回溯，显式10000次候选尝试预算，不递归以避免高qty栈深；耗尽预算抛IncompleteSearch，只有完整穷举无解且候选集未截断才返回CONFLICT。每次撤销精确扣回已加bucket，最终解才生成assignments/demands/qualification，避免回溯残留。原owner最终仲裁/冻结安全不变。

RED覆盖：真实15/60分钟同SKU双角色P1整小时/P2前15分钟，自动成功与部分指定长角色成功；指定短角色=P1确实无解；多角色组合搜索预算耗尽须preview专用错误/availability complete=false；同人别名不增容、共享物理capacity及回溯扣还、搜索中无额外RPC。固定项不会被搜索改写。

验证计划：先记录I1原生/ I2算法RED，再对应GREEN，重跑beauty常规与native业务（owner/source实现未改，复用已有50/16专项并说明）；受影响beauty core/server/embedded重新install/package到独立输出目录，复用未变HOST/retail/worker构件，运行当前真实部署harness并保存新hash/result/logs。更新相关阶段文档，不声称复审已通过。

前置容量/候选bucket物化总预算100000，计入既有占用展开及所有候选5分钟bucket；超过返回IncompleteSearch，而不是继续占用无界内存或断言冲突。合法大数量请求也可能超过预算，其语义为未完成搜索；通过更小查询/明确指定可缩小工作量，不承诺所有合法最大组合能在一次搜索内穷尽。

## 实现与回归结果

I1生产修改只有AppointmentRecovery查询：到期、MAIN/PENDING及原逻辑删除过滤不变，增加与claim一致的lease NULL或严格早于now条件，先过滤后LIMIT，再nextRetryAt/id排序。CAS认领/原号/终态核验/永久栅栏/30和60秒延期不变；没有DDL、没有租约抢占或新授权。native测试通过真实Service创建51条无回执PENDING，再创建已在受控外部owner成功的CONFIRM；用Mockito仅替换时钟（CALLS_REAL_METHODS保留其余持久助手），时钟推进60秒后第二批必须完成原终态。不是趁30秒未到而立即扫描的假公平。另例51条活租约必须不占批次；原租约数量保持51、所有无回执操作仍pending，owner接受次数保持53，不因scanner新增命令。

I2建立需求槽位后迭代回溯，所有外部候选/人员/排班/容量读取仅在预取阶段，已有商品与定义读取仍按最多10个请求项进行；没有每节点RPC或数据库事务。按resourceNo稳定次序尝试，匹配成功后才组装不可变输出计划，撤销分支时扣还每个bucket。STAFF新增和已有占用按personnelNo归一、unit1，别名既有使用取同bucket的max，避免把同一owner规范用量重复累加；物理resource各自容量不合并。原qty展开、同项重复行和specified consumed语义保留。50候选找到解时仍返回解且complete=false；预算耗尽通过既有BEAUTY_SEARCH_INCOMPLETE/availability complete=false传播。

预算是明确工作边界，不承诺全部合法最大组合能在一次调用穷举：搜索最多10000候选尝试/需求槽位；预计算最多100000个5分钟bucket。较大但可行的合法输入可能返回未完成，而非错误宣称无解。测试含49资源、STAFF1及两个ROOM角色各qty100，旧预计算可展开超过115000个bucket，新边界返回未完成。没有把预算常量本身当行为断言。

### RED/GREEN记录

| 证据 | 结果与含义 |
|---|---|
| `/tmp/task53-fix-recovery-red.log` | 原生产实现18测试中2失败：跨60秒cron与活租约场景均为期望SUCCEEDED、实际PENDING |
| `/tmp/task53-fix-matching-red3.log` | 原匹配8测试中3失败/2业务异常：自动/部分指定15/60角色误冲突、预算误CONFLICT、同人别名新增/既有容量绕过 |
| `/tmp/task53-fix-matching-green.log` | 第一轮匹配8+既有截断语义1，共9通过 |
| `/tmp/task53-fix-business-green.log` | 公平扫描/匹配后原生业务18通过 |
| `/tmp/task53-fix-preparation-red.log` | 新预计算压力用例10测试中1失败，期望未完成但旧预计算无预算直接返回解；不是OOM制造的测试 |
| `/tmp/task53-fix-beauty-final.log` | 最终beauty reactor install，106测试、0失败/错误/跳过，含10个新匹配测试及既有协议/配置/架构/Job目标回归 |
| `/tmp/task53-fix-business-final.log` | 最终代码与格式的native业务18/18通过，真实MySQL V1–V4初始化、来源/栅栏/原终态业务，外部owner仍明确受控fixture |

初次补alias容量测试写错Capacity记录构造器参数数量，修正为实际5字段后才取得上述red3行为证据；编译错误不计作有效RED。预计算预算加入时停止上一构建链，未把其旧server作为最终证据；最终install/package重新完成。

### 复现命令

均在指定工作树，Java21 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；Maven统一 `-o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2`。

```sh
# RED后原样用于GREEN；I1两例随完整native业务执行
node scripts/verify-beauty-persistence.mjs --appointment-business-only
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-core -am test \
  -Dtest=AppointmentMatchingSearchTest,AppointmentIncompleteSearchTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# 最终完整beauty测试与依赖install；复制server后构建并复制embedded
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend-plugins/shop-industry-beauty/pom.xml install
mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 \
  -f backend/pom.xml -pl shop-app -am package -Pbeauty-embedded -DskipTests
BEAUTY_BM03_ARTIFACT_DIR=/tmp/beauty-bm03-fix-artifacts \
BEAUTY_BM03_EVIDENCE_DIR=/tmp/beauty-bm03-fix-final2 \
  node scripts/verify-beauty-bm03-deployment.mjs
```

### 构件身份及复用范围

本轮生产仅beauty-core的Matching/Recovery改变。最终beauty reactor重新install，构建SERVER及embedded，分别先复制再覆盖target；日志`task53-fix-beauty-final.log`、`task53-fix-embedded-final.log`。standalone HOST没有beauty-core，retail/worker也没有本轮修改，直接复制上一轮已审构件，SHA未变。实际gate仍启动验证全部形态。所有构件保存到新目录`/tmp/beauty-bm03-fix-artifacts`，没有覆盖旧已审`/tmp/beauty-bm03-artifacts`。

| 构件 | 当前SHA256 |
|---|---|
| beauty-server.jar | 9b78f988fb8126ca417665eec15e7f25e84ea047291ccab9284f532ea4567f03 |
| embedded.jar | 928b4e068d3c7acd78c185c38fac7e60a25ce451ff4f8f390b5b7dfb9822d17f |
| standalone.jar（未改复用） | 411a2633a05fcc58d355c5e983bb219d7a6fca244054626132b09d42a876e3d2 |
| retail.jar（未改复用） | 8e3a4d0c3da1b812a425bc9ef05d814ddab4ba5f996ff68d8784be68eb4d83fc |
| job-worker.jar（未改复用） | db4a44076b3faa11d8cf14f0a6d6c17a50701d97a3c24869d39045015a5a2846 |

最后仅做新Java120列/测试imports格式整理，native最终测试重新编译后，`javap -c -p`比较当前Matching/Recovery与其内部类共9个class，执行指令和签名逐个一致（不比较debug行号）；两个新运行构件beauty-core nested JAR SHA均`e658eb4bcc06d7db8d007fbc2f2389e9df6eb8f0172c8bd78721e673a3e1a244`。证据`/tmp/beauty-bm03-fix-final2/current-source-artifact-equivalence.json`。

复用既有原生owner50/source16/迁移strict17及平台执行27/餐饮执行88证据，理由是本轮未改这些所有者、端点、DDL/manifest或shared依赖；美业原生业务18和美业全reactor106已重跑，当前真实构件gate验证受影响运行路径。未重跑不相关全平台套件，不把复用证据说成新的执行。

### 环境失败披露

首个新runtime保留于`/tmp/beauty-bm03-fix-final`及`/tmp/task53-fix-runtime.log`：embedded业务链通过，独立HOST登录前MySQL消失，导致连接拒绝/10500。`/tmp/task53-fix-runtime-docker-events.jsonl`明确记录本任务mysql:9.7.2容器在17:18:00UTC退出137并发生OOM。此时另一个本任务native MySQL8.4同时运行；Docker VM总内存4096294912字节/2CPU，已有6个非本任务food容器共约2.2GiB，当前单一native部署MySQL约857MiB。没有停止或修改这些无关容器。native结束并finally清理后串行重跑到fix-final2，不修改业务逻辑或放宽断言。Java沿原-Xmx640m，MySQL沿tmpfs/localhost/--pull never。失败run不是通过证据。

## 归属与边界

自有修改：AppointmentMatching、AppointmentRecovery、AppointmentBusinessIT、新AppointmentMatchingSearchTest，task-51-design及历史task-51-report说明、README、领域对象说明、task-53-report链接增量及本报告。控制器的主TDD/progress排除。没有改迁移、owner/source协议/安全/认证/支付/通知或Job调度框架。

原Task53证据边界继续适用：平台nativefixture不是完整历史迁移；ARCHIVED不是通知投递；整体readiness503不等于BM04到店接待/工单/交易来源、BM05服务作业/履约、BM06任务日历/UI完成。修复和本地验证完成后仍等待控制器安排的独立窄范围复审，不提前写Approved。


生成器/脚本收尾：`node scripts/gen-beauty-{schema,resource-intent-schema,appointment-decision-schema,appointment-business-schema}.mjs --check`逐一执行全部通过，`python3 scripts/gen-beauty-database-design.py --check`通过（41基线、10输出）；日志`/tmp/task53-fix-generated-checks.log`。当前部署入口与runtime helper的`node --check`通过，`git diff --check`通过；冻结V1–V4无diff，SQL方言/manifest未更改。


## 最终真实部署结果

`/tmp/beauty-bm03-fix-final2/result.json`状态 **BM03_RUNTIME_CASES_PASSED**，运行退出0；完整日志`/tmp/task53-fix-runtime2.log`，所有进程日志、构件内容、HTTP结果、owner/source代理事实保留在同目录。已重新计算result中的5个SHA全部一致。

embedded已停止后才启动真正standalone HOST；B/C确认/原键去重/列表详情/重叠改约/冲突保旧/取消全部通过。独立SERVER私有23业务表+history+6infra及正式history0/1/2/3/4不变。owner调用316/source反向调用19。真实accept掉响应后重启、撤权、另一manager原号恢复通过；CONFIRM提交掉响应后真实cron协调成功。job_run为SUCCESS、run_count=2，job_log两条CRON/SUCCESS/HTTP200记录直接包含在本次result.recovery.jobRecords中，跨worker重启持久增长。真实预约等待到01:25后HTTP爽约成功，最终NO_SHOW；没有SQL修改预约/hold时间。

停止SERVER后实际owner execute返回503，命令仍ACCEPTED；默认retail美业入口404且真实资源/目录API可用。整体readiness503按既有后续模块门控保留。所有本任务进程/代理及tmpfs容器由finally清理，未停止无关容器。

本轮状态：I1/I2实现、RED/GREEN与当前实际构件部署验收完成；**独立窄范围复审尚待控制器安排，不标记模块准出Approved**。
