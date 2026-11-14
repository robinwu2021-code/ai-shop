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

查entity/store/workOrder/本次RACING namespace的永久提交行，action IN ('SUBMIT_SOURCE','ADD_SOURCE')恰好1；与赢家action/submissionNo、request_key_hash、payload_hash及原SOURCE_SUBMITTED安全回执对应。requestHash沿现IdempotencyScope与同序列化合同读取核实；不要从被测DB行自己构造expected。事务/来源准备恰好1，真实来源line/task范围与赢家请求对应，输家不能留下添加项目/额外来源。对赢家原请求同key重放：original不可变SOURCE_SUBMITTED接受回执与frozen_command逐字不变，稳定业务字段完整比较（BigDecimal按compareTo数值等价），无再次prepare、新提交或新接受日志；输家changed请求重试仍CONFLICT。完整重放VO必须与重放后getSubmission当前完整VO相等；与首次返回VO比较所有稳定字段，仅明确允许已证实的提交revision因恢复租约推进（若其它动态字段实际变化先附来源证据给根，不能宽泛忽略）。ServiceSubmissionVO是当前投影，不是不可变接受回执。保存before/after不可变源事实，不能只比较编号。

原真实无偏向竞争保留（两请求在customerResolution合流）。另新增 @ParameterizedTest crossActionRaceRetainsEitherWinnersImmutableReceipt，ValueSource booleans两项明确分别先放行SUBMIT_SOURCE和ADD_SOURCE；利用现测试类可设的customerResolution边界和受控latch，在首个真实调用成功/接受后放行另一方，强制覆盖两种结果。该定向案例只是可重复覆盖两条合法结果，不能替代/声称无偏向并发。线程finally清SecurityContext，latch/future有界超时，重置fixture回调；失败不死锁。首RED必须定向ADD获胜下现旧固定SUBMIT断言失败，不靠随机调度碰运气。

aliases只增加原三race方法和新crossActionRaceRetainsEitherWinnersImmutableReceipt；原有两方法和整类选择保持。不拓展模式/selector grammar。Node增加四方法可选及unknown同名前缀/跨mode仍拒绝且invalidCLI空PATH无资源effect。没有新生产能力。

## 步骤与有限验证

- [ ] 先增加可重复ADD赢家覆盖，原错误action断言保留，安全别名方法名单加精确方法；Node选择正反例先RED/GREEN。
- [ ] 以BEAUTY_BM04_TESTS=ServiceSubmissionIT node scripts/verify-beauty-bm04-wire.mjs --mode=aliases取得真实定向RED；保存freshXML并定位assertion，不以setup/编译/skip当RED。
- [ ] 修复winner-neutral完整回执/冲突/来源守恒断言，保持三旧场景。已记录整类29/5fail/0error/0skip后，按后文根裁定只跑四个精确方法selector共五例GREEN；fresh非零且failures/errors/skipped全0，参数化两结果实际出现，不把分批说成单次整类29全绿。
- [ ] 最小消融只恢复旧固定SUBMIT_SOURCE最终断言，跑crossActionRaceRetainsEitherWinnersImmutableReceipt定向选择应有ADD案例RED，恢复精确bytes后同selectorGREEN。另用临时夹具突变将本次观察的winner原回执一个非编号字段改为不同值（不写生产库/改生产逻辑），执行同一真实比较断言应RED，逐字恢复后GREEN；这仅为覆盖强度证据，不保留“测试assertEquals”的镜像单测。各次恢复均证明同一最终bytes/SHA时，只需最后一次同selector恢复GREEN，不重复三次相同源码GREEN。不改生产合同/弱化guard来凑绿色。
- [ ] node --test scripts/tests/beauty-bm04-wire-runner.test.mjs最终GREEN。Java格式只新改行，AOSP4空格/正常import/120列，必要方法注释，保留原未改内容。
- [ ] 输出3file manifest/full diff，实际命令/退出码/XML/SHA/逐字恢复/资源清理，根独立spec+quality审查再精确提交。

native使用现安全aliases runner同串行锁/tmp/codex-beauty-bm04-native.lock，cached mysql:9.7.2 --pull never/context colima-hxkey-tests/VM hxkey-tests/DISK64M/MemAvailable>=524288KiB，自有UUID/randomloopback/env-only secrets/finally只清自己；不改VM/停止他人/共享远程库。JDK21 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home、Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。不clean、不跑其它业务类/14类/正式双JVM。日志与XML分享副本脱敏临时密码，记录原/副本SHA及实际suite/testcase未变，不回显任何凭据。旧Task59runner其它安全边界不在本片重写范围。

根A3合同澄清（2026-10-24）：ServiceSubmissionServiceImpl.resume:699-728与BeautyMappers.claimSourceRecovery:767-780证明SOURCE_PENDING重放会重新领取租约并推进version；Task58设计接口明确VO为当前owner投影。故不要求首次VO.revision与重放VO.revision相等，不改生产行为；不可变接受回执schema1及稳定业务事实仍完全相等。

根A3数量/验证澄清（2026-10-24）：已核ServiceSubmissionServiceImpl FrozenTask取DB qty、command读冻结JSON、view直接投影qty及V1 DECIMAL(18,6)。完整VO比较中BigDecimal按compareTo数值等价（1.000000与1.0），不忽略qty/任务/报价；除已准root revision外全部稳定字段完整比较，冻结command/原接受回执仍逐字不变。不得把真正数量差异视为等价。新增临时qty数值突变应使真实比较失败。
最后整类实际29/5fail/0error/0skip，五个race均仅scale比较差异，其余24不受该helper比较改动影响。后续只验证四个方法selector（原三race与新参数化两行，共五用例），fresh非零零failure/error/skip；保持完整记录，不把分批结果相加宣称最终整类单次29全绿。原整类GREEN步骤据此收敛为整类基线+全部受影响五例定向GREEN。消融/恢复仍仅新参数化方法，不再重复无关24例。

根A3格式例外（2026-10-24，最终自检）：根逐项核对基线旧行306/1100/1191/1453/1459/1466的六个AOSP附带hunk。前两处仅同一调用链换行，后四处仅常量SQL字符串重分段（拼接后字符完全相同）。批准保留这六处同一owned文件内的纯格式变化，范围到此为止，不允许进一步全文件格式化；最终已测源SHA93b9983b746a6ec063a9c115302346314705b1e429553b43b0b7c9fdfdfae6ee保持不变。原“只新改行”要求对此六处精确例外，报告独立列明。
