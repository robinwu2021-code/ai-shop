# Task60A-A3 跨动作幂等竞争夹具

Status: IMPLEMENTATION_APPROVED after A2 product 15b7143192da05eb6ae3242dffe25049cf685815 accepted. IMPLEMENTATION_BASE supplied after root docs commit. 档位0，测试纠正；无API/表/业务规则变更。需求来自 task-60a-design.md A3 与主TDD §2.25。

唯一工作树 /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation。唯一产品owned3现有文件：
- backend-plugins/shop-industry-beauty/beauty-core/src/test/java/ai/neargo/shop/beauty/scenario/ServiceSubmissionIT.java
- scripts/verify-beauty-bm04-wire.mjs（只aliases ServiceSubmissionIT精确方法名单）
- scripts/tests/beauty-bm04-wire-runner.test.mjs（精确新选择的正反例）
允许同目录task-60a-a3-report.md和task-60a-a3-evidence/。不得改生产Java、SourceFixture、SQL/POM/config/known/支付runner/根文档/其它切片；真实缺口带精确证据交根裁定。不派子agent，不stage/commit/push/merge/deploy。

## 当前事实与预期

ServiceSubmissionIT raceEndpointKey(boolean changed,boolean added)原本只保留submissionNo/CONFLICT，最终固定查action='SUBMIT_SOURCE'为1。但同一个BAPI-056永久namespace允许SUBMIT_SOURCE或ADD_SOURCE先接受；只应有一个端点赢家。旧三方法 concurrentSameEndpointKeyReplaysOneCommittedSubmission、concurrentChangedEndpointPayloadHasOneWinner、concurrentDerivedActionsShareOnePermanentEndpointKey 保留。

最小修复仍在同一个测试类：每个future保留原请求索引和完整ServiceSubmissionVO/真实BizException，区分成功结果与失败；不吞其它异常当冲突。changed=true恰好一个winner/一个CONFLICT且code为实际CONFLICT，changed=false两次都成功并同原始receipt。由实际成功结果识别赢家，不固定线程/动作优先。

查entity/store/workOrder/本次RACING namespace的永久提交行，action IN ('SUBMIT_SOURCE','ADD_SOURCE')恰好1；与赢家action/submissionNo、request_key_hash、payload_hash及原SOURCE_SUBMITTED安全回执对应。requestHash沿现IdempotencyScope与同序列化合同读取核实；不要从被测DB行自己构造expected。事务/来源准备恰好1，真实来源line/task范围与赢家请求对应，输家不能留下添加项目/额外来源。对赢家原请求同key重放完整VO与original不可变回执完全一致，无再次prepare或新提交/日志；输家changed请求重试仍CONFLICT。保存before/after不可变源事实，不能只比较编号。

原真实无偏向竞争保留（两请求在customerResolution合流）。另新增 @ParameterizedTest crossActionRaceRetainsEitherWinnersImmutableReceipt，ValueSource booleans两项明确分别先放行SUBMIT_SOURCE和ADD_SOURCE；利用现测试类可设的customerResolution边界和受控latch，在首个真实调用成功/接受后放行另一方，强制覆盖两种结果。该定向案例只是可重复覆盖两条合法结果，不能替代/声称无偏向并发。线程finally清SecurityContext，latch/future有界超时，重置fixture回调；失败不死锁。首RED必须定向ADD获胜下现旧固定SUBMIT断言失败，不靠随机调度碰运气。

aliases只增加原三race方法和新crossActionRaceRetainsEitherWinnersImmutableReceipt；原有两方法和整类选择保持。不拓展模式/selector grammar。Node增加四方法可选及unknown同名前缀/跨mode仍拒绝且invalidCLI空PATH无资源effect。没有新生产能力。

## 步骤与有限验证

- [ ] 先增加可重复ADD赢家覆盖，原错误action断言保留，安全别名方法名单加精确方法；Node选择正反例先RED/GREEN。
- [ ] 以BEAUTY_BM04_TESTS=ServiceSubmissionIT node scripts/verify-beauty-bm04-wire.mjs --mode=aliases取得真实定向RED；保存freshXML并定位assertion，不以setup/编译/skip当RED。
- [ ] 修复winner-neutral完整回执/冲突/来源守恒断言，保持三旧场景，按同一有限整类命令GREEN。预期实际tests>0且failures/errors/skipped全0，参数化两结果实际出现，不固定总项数。
- [ ] 最小消融只恢复旧固定SUBMIT_SOURCE最终断言，跑crossActionRaceRetainsEitherWinnersImmutableReceipt定向选择应有ADD案例RED，恢复精确bytes后同selectorGREEN。另用临时夹具突变将本次观察的winner原回执一个非编号字段改为不同值（不写生产库/改生产逻辑），执行同一真实比较断言应RED，逐字恢复后GREEN；这仅为覆盖强度证据，不保留“测试assertEquals”的镜像单测。不改生产合同/弱化guard来凑绿色。
- [ ] node --test scripts/tests/beauty-bm04-wire-runner.test.mjs最终GREEN。Java格式只新改行，AOSP4空格/正常import/120列，必要方法注释，保留原未改内容。
- [ ] 输出3file manifest/full diff，实际命令/退出码/XML/SHA/逐字恢复/资源清理，根独立spec+quality审查再精确提交。

native使用现安全aliases runner同串行锁/tmp/codex-beauty-bm04-native.lock，cached mysql:9.7.2 --pull never/context colima-hxkey-tests/VM hxkey-tests/DISK64M/MemAvailable>=524288KiB，自有UUID/randomloopback/env-only secrets/finally只清自己；不改VM/停止他人/共享远程库。JDK21 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home、Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。不clean、不跑其它业务类/14类/正式双JVM。日志与XML分享副本脱敏临时密码，记录原/副本SHA及实际suite/testcase未变，不回显任何凭据。旧Task59runner其它安全边界不在本片重写范围。
