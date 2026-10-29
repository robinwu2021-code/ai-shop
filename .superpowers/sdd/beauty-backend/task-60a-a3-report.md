# Task60A-A3 跨动作幂等竞争夹具报告

Status: DONE_WITH_CONCERNS — A3实现、根批准的有限验证、三种消融和精确恢复已完成。 根独立 spec/quality review 与精确提交仍由根负责。档位0；无生产行为、API、库表或配置变更。

## 范围与执行基线

- 唯一工作树：`/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`；分支 `codex/beauty-foundation`。
- IMPLEMENTATION_BASE：`5b8c8f0195e887f079e5d41a2a3139259f550aef`；dispatch 前 tracked clean 已核对。A2 predecessor `15b7143192da05eb6ae3242dffe25049cf685815` 已接受。
- 唯一产品文件：`backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java`、`scripts/verify-beauty-bm04-wire.mjs`、`scripts/tests/beauty-bm04-wire-runner.test.mjs`。
- 报告/证据只写本报告与 `task-60a-a3-evidence/`。没有改生产 Java、SourceFixture、SQL/POM/config/known、支付 runner 或根文档；没有 stage/commit/push/merge/deploy，没有派子 agent/reviewer/helper agent。
- 规范：先读 A3 brief，再读 CLAUDE、ai-shop-dev-standards/Java、TDD 与 verification-before-completion。验证严格限制为本报告所列 focused Node 与 aliases ServiceSubmissionIT，不跑其他业务套件/native14类/双 JVM 正式部署。

## 实现与合同核对

三旧无偏向 race 保留，客户解析回调中的两个首调用汇合，不预定赢家。新增 `crossActionRaceRetainsEitherWinnersImmutableReceipt(boolean)`，`@ValueSource(booleans={false,true})` 分别先放行 SUBMIT_SOURCE、ADD_SOURCE；另一方在首真实调用成功/返回后才通过同一真实客户解析边界。定向案例用于稳定覆盖两种合法结果，不作为无偏向调度的证据。latch/future 有界，worker finally 清 SecurityContext/ThreadLocal，fixture 回调 finally 恢复。

每个 future 保存原请求索引、完整 VO 或真实 BizException。changed=true 必须1成功、1实际 CONFLICT；changed=false 必须2成功并引用同一接受提交。不把任意异常当冲突。namespace 从明确的 BEAUTY_SUBMISSION_V1/MAIN/E/S/MERCHANT/MCH/A/BAPI-056/RACING 合同独立算出；payload 从实际赢家请求与同 ObjectMapper 序列化算出，不从 DB 行倒推。永久行按 entity/store/workOrder/namespace 与两合法 action 查得恰好1，对照实际赢家 action/编号/两个 hash 与 SOURCE_SUBMITTED schema1 安全回执。验证冻结任务选择、scope、来源行、SKU/数量与赢家请求一致，prepare 恰好1；ADD 输家不能留下额外任务/来源。

根合同澄清已先落 brief/main TDD/ledger：`ServiceSubmissionServiceImpl.resume:699-728` 与 `BeautyMappers.claimSourceRecovery:767-780` 说明 SOURCE_PENDING 重放会领取恢复租约并推进 revision。VO 是当前投影。测试因此比较重放完整 VO 与重放后 getSubmission 完整 VO；首次 VO 递归比较仅忽略已证实的顶层 revision，并要求 revision 不倒退。根另核实 DECIMAL(18,6) 首次数量1.000000与 frozen JSON 重放数量1.0 数值等价，批准 BigDecimal::compareTo 比较所有 BigDecimal 数值；qty/tasks 没有被忽略，并要求额外1→2数量突变真实 RED。status/phase/quote/source/tasks/blocked facts 等都不忽略。原不可变接受回执全行逐字相等、frozen_command 与其他提交稳定字段逐字相等；没有新提交/接受日志/prepare。before/after 还保存实际 prepare/quotes/sourceFacts/created 与 tasks/source_observation/trade_binding/allocation_guard/全日志事实；changed 输家重试仍为 CONFLICT。没有修改生产合同。

Runner 仅给 aliases 的 ServiceSubmissionIT 精确名单增加3旧 race 与1新 parameterized 方法；原2方法和整类选择保持。Node 执行精确四方法正例，以及同名前缀/后缀和跨 mode 拒绝，CLI 空 PATH 证明无资源 executable 解析。

## 实际 RED/GREEN 与消融

根明确调整有限验证：修复后的四个精确 selector 共5例已实际 GREEN；不再重跑整类。之前全类29/5 failures/0 errors/0 skipped 中另外24例通过，5例唯一差异为 BigDecimal scale；最终报告**不声称存在最终单次29全绿**。

精确命令均在 sole worktree 执行：

```sh
node --test scripts/tests/beauty-bm04-wire-runner.test.mjs
BEAUTY_BM04_TESTS=ServiceSubmissionIT node scripts/verify-beauty-bm04-wire.mjs --mode=aliases
BEAUTY_BM04_TESTS='ServiceSubmissionIT#concurrentSameEndpointKeyReplaysOneCommittedSubmission,ServiceSubmissionIT#concurrentChangedEndpointPayloadHasOneWinner,ServiceSubmissionIT#concurrentDerivedActionsShareOnePermanentEndpointKey,ServiceSubmissionIT#crossActionRaceRetainsEitherWinnersImmutableReceipt' node scripts/verify-beauty-bm04-wire.mjs --mode=aliases
BEAUTY_BM04_TESTS='ServiceSubmissionIT#crossActionRaceRetainsEitherWinnersImmutableReceipt' node scripts/verify-beauty-bm04-wire.mjs --mode=aliases
```

所有 native 的底层真实 Maven 结构是：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend-plugins/shop-industry-beauty/pom.xml -pl beauty-core -am test -Dtest=<精确选择> -Dsurefire.failIfNoSpecifiedTests=false -Dbeauty.test.admin-url=<该次XML中实际127.0.0.1随机端口/mysql JDBC>`。逐次展开后的命令、退出、counts、case names 与 SHA 在 evidence 最终索引中提供；数据库凭据只在环境中，不写命令。

| 步骤 | exit | tests/failures/errors/skipped | 证据目录 |
| --- | ---: | --- | --- |
| Node 精确新名单 RED | 1 | 8项，7 pass/1 fail；invalid selector method | node-red.log |
| Node allowlist GREEN | 0 | 8项全部pass，cancel/skip0 | node-green.log |
| Java 原固定断言真实定向 RED（整类） | 1 | 29/1/0/0，仅 ADD boolean[2] count1实际0 | red/ |
| 首次 winner-neutral 测试查询误用 store_no | 1 | 29/0/5/0；测试 SQL 错误，非 GREEN | green-attempt-query-error/ |
| Decimal scale 实际比较失败（整类） | 1 | 29/5/0/0，仅 tasks[0].qty 1.0 vs1.000000 | green-attempt-decimal-scale/ |
| 最终四个精确 race selectors | 0 | 5/0/0/0 | focused-green/ |
| 恢复旧固定 action 谓词消融 | 1 | 2/1/0/0，仅 ADD boolean[2] size1实际0 | action-red/ |
| 最终 Node | 0 | 8项全部pass，cancel/skip0 | node-final-green.log |
| 观察的原接受回执 payload 非编号字段临时突变 | 1 | 2/2/0/0，两种赢家都在原 immutable receipt 全行比较失败 | receipt-red/ |
| 观察的 winner VO quantity 1→2 临时突变 | 1 | 2/2/0/0，两种赢家都在 tasks[0].qty 数值1.0 vs2真实比较失败 | quantity-red/ |
| 三次精确恢复后的统一最终 selector GREEN | 0 | 2/0/0/0，boolean[1]/[2]都执行 | restored-green/ |

完整逐次 command、expanded Maven command（含各次实际随机端口）、exit、fresh XML suite/testcase 名称、XML SHA 与 archive manifest SHA：`task-60a-a3-evidence/verification-index.json`。三个临时 mutation 的实际 diff、源码检查点和恢复 proof 在 `mutations/`。恢复 action/receipt/quantity 三次均 bytesEqual=true 且恢复SHA相同：`93b9983b746a6ec063a9c115302346314705b1e429553b43b0b7c9fdfdfae6ee`。按根明确裁定，仅最后统一跑一次相同 selector 恢复 GREEN；没有冒充每次恢复各自重新执行过 GREEN。

最终真实 class 为 `ai.neargo.shop.beauty.scenario.ServiceSubmissionIT`，method 为 `crossActionRaceRetainsEitherWinnersImmutableReceipt(boolean)`，fresh XML 两行均0 failure/error/skip。最终 compiled class SHA256：`0087331cd7cdf66b15bc0170e4f28e92bedfa994ecf14d8c7c8e9fb764e4d3cb`。全部5个受影响案例的最终 GREEN 检查点为 focused-green5/0/0/0；没有扩大验证到其它业务类。

## 偏差、环境与资源

- 初始 Java 定向夹具编辑误落同文件另一方法，首次 native 尝试因编译失败未产生 fresh XML。已纠正；此尝试只记 setup failure，**不计 RED**。真实 RED 是后续整类29项仅 ADD 定向旧断言失败。
- Node 首次 RED 的 wrapper 后置使用 zsh 保留变量 `status` 失败；Node 本体已实际执行并保存7 pass/1 fail输出。后续 wrapper 用 run_exit。此 shell wrapper 错误不冒充 Node 失败原因。
- 首次 winner-neutral 整类尝试29/0 failures/5 errors/0 skipped；五个 race 皆因测试 count 查询误用任务表不存在的 store_no。实际 schema 与 mapper 均通过关联 work_order 提供 store 范围，已修正测试 join；此尝试不是 GREEN，也不是生产缺口。执行源检查点为 green-before-refine.java.txt。
- 后续一次提前启动的尝试在 bytecode 检查中仍含旧查询，未产生有效 fresh XML；只对自己 aliases runner PID64619 的唯一直接 Maven child PID65159 发 SIGTERM，让 runner 自己 finally 清理 UUID 容器。归属断言、signal、退出1、无 fresh XML 和 cleanup 保存在 owned-maven-interruption.json/aborted-stale-compile；不计业务 RED/GREEN。最终修正源在新调用前完成格式化/冻结，并验证 compiled class 内确含 scoped JOIN。
- 最终全类比较29/5/0/0的5个差异均为 tasks[0].qty：首次1.000000，重放1.0；先给根 precise XML 与源码证据，再获准数值比较。证据 decimal-scale-evidence.json、pre-decimal-fix-ServiceSubmissionIT.java.txt 保留；immutable frozen JSON/接受 receipt 不放宽。根批准后只四个 selector 定向5例GREEN，不再全类循环。
- 最终自检发现 AOSP --lines 还产生6处未编辑旧语句换行：base旧行306/1100只把 taskNo 调用链换行，1191/1453/1459/1466只把常量SQL重新分段。根逐一核对并在最终brief/ledger明确批准保留，保持已测SHA93b9983b...，不再编辑/还原/重跑。`aosp-approved-exceptions.diff/json`列明精确6 hunk；两处去空白后token完全相同，四处拼接后SQL字节完全相同。独立quality review仍自行判断，不预设severity。
- receipt 临时突变的第一次 guard 因全文件存在多个同名 var replay 安全拒绝，未改变恢复后的源字节、未启动测试；随后收窄到本 helper 的完整行后再突变。
- 最终 Node 首次受限 sandbox 调用中，既有 loopback fault-proxy listen(127.0.0.1) 被 EPERM 拒绝（7 pass/1 environment fail）。已保留 node-sandbox-port-denied.log；窄权限重跑同命令8/8通过。未改测试去规避 socket 检查。
- 一次证据归档工具调用被自动审查拒绝，原因是未显式 cwd 被判断为另一个 checkout；该调用未执行、未写文件。随后只读 pwd/realpath/git-show-toplevel 证明 sole beauty worktree，再以显式 workdir 与脚本 cwd assert 重新获准执行同一归档。未扩大权限，未写默认 checkout。
- 所有 native 使用现 aliases runner 的共享 `/tmp/codex-beauty-bm04-native.lock`、`colima-hxkey-tests`、cached `mysql:9.7.2 --pull never`、随机 UUID own container/127.0.0.1 随机端口、DISK64M、MemAvailable>=524288KiB。凭据只经 env；不打印。runner finally 只清 own container 与其锁；不调整 VM，不停止其他资源，不碰远程共享库。
- 环境警告保持原文归档并继续归 A11：MySQL9.7 新于 Flyway 最新验证8.1、MyBatis Sequence 初始化慢，以及实际日志出现的 javac 默认批注处理提示与 BeautyCatalogIT 存量 deprecated API 提示；不 suppress，不声称已修复。
- 一次已授权归档的自动权限审查超时未执行，按工具指示仅重试一次后成功；无数据写入遗漏或测试替换。
- XML/log 分享副本做 credential marker discovery/redaction；每次记录原始/归档 SHA 与 suite/testcase 完全不变。没有发现 marker 的档案原/副本相同。env-only 数据库口令不作为命令参数/源码/报告内容。

## 最终清单与自检

最终三产品文件 manifest：`task-60a-a3-evidence/owned-manifest.json`，schema `ownedProductFiles[{relativePath,path,bytes,sha256}]`，base为5b8c8f0195e887f079e5d41a2a3139259f550aef。

| 产品文件 | bytes | SHA256 |
| --- | ---: | --- |
| `backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java` | 101495 | `93b9983b746a6ec063a9c115302346314705b1e429553b43b0b7c9fdfdfae6ee` |
| `scripts/verify-beauty-bm04-wire.mjs` | 28375 | `6da5014e0f0b1fc22875617bd0dc793ad5448c444104a7cceb3ac1ff37f0b8c4` |
| `scripts/tests/beauty-bm04-wire-runner.test.mjs` | 6836 | `65e535f804dc784c26417d120af165489e6d8241d082e32a97f3afe314ead3e7` |

- Manifest SHA256：`3adb5d5f58b38f92ccc812bef23ed67ac49803d00e28d639a27ac58ebd71a988`。
- 完整 owned base diff（只3文件，无截断）：`task-60a-a3-evidence/full-owned-base.diff`，35666 bytes，SHA256 `9c6d1908bb2a25607dbc9c55d7ee9bdf2c8ca05383f2f7206f92d2c5e7329863`；stat为3 files/365 insertions/115 deletions。没有重排全份存量Java；下面6处有界换行例外已获根明确批准。
- Verification index SHA256：`917a14b04e1bf221ce192caa41f4e6c0096bdc7e05658d30a50122676272e490`。
- 根最终brief SHA256：`cb666cb9b8c6c810807eb47e84d694c8ccbbe0e2511d84f15103a536d7637a2c`，只作需求溯源，不纳入3产品文件。
- `git diff --check <base> -- <owned3>` exit0；新改Java最大104列；最终源与冻结baseline精确bytes一致，保留正常imports/AOSP4空格，按变更区传入AOSP --lines；工具另产生下述6处经根批准的有界换行例外，没有全文件格式重排。最终三产品当前SHA与manifest逐项再次核对一致。
- `frozen-sql-known-proof.json`：`git diff --exit-code <base> -- '*.sql' 'backend/known-*'` exit0，504份tracked SQL与5份known无改动，无新增未跟踪SQL/known。
- `resource-cleanup.json`：仅对本片10个精确UUID作name-only Docker inspect，均不存在；每个完整名字/inspect exit已列明，共享native锁不存在。没有打印Docker环境或凭据。
- `tmp-cleanup.json`：清理前26份原始artifact与归档SHA核对成功，再删除本片10个精确 raw aliases evidence 目录及16个显式自建scratch脚本/log；全部列表路径已absent，共享锁absent。env-only aliases未创建临时凭据目录。原始路径现在是历史provenance，审查使用A3归档副本。root的 `/tmp/build-task60a-a3-review.py`、`/tmp/task60a-a3-root-facts.md`、共享Maven cache及target fresh XML/class没有触碰。
- 所有归档的 credential marker discovery 为0；原/副本SHA相同，实际 suite/testcase facts 在archive-manifest中确认未变化。没有把XML失败、setup错误或skip重标为成功。
- `self-review.json` 对照需求/源码/实际结果，无剩余产品问题发现。A11警告、最终没有单次29全绿这一根明确验证边界、6处根批准AOSP换行例外与已透明保留的setup失败是交付concerns；根独立spec/quality审查和提交尚未进行。本人没有stage/commit/push/merge/deploy。
