# SVC-B03 整体审查及修复

独立只读审查一次；Critical无，Important七组。之后同一修复批次用真实原生/SQL事务/HTTP反例确认，不重审、不强制回退正确代码造失败。初始审查不是完整上线认证，App联调/双进程部署/营业资金不在本轮证据中。

| 审查发现 | 修复及有效验证 |
|---|---|
| INTENDED失败后永久挡住任务 | 清洁形状由框架校验，原待清洁段由Source业务校验；Native同根NO_EFFECT_SEALED/已APPLIED分流。`failedOriginalRequiresRealNoEffectSealBeforeNewKeyAndRejectsLateExecute`、`acceptedHumanApplyAndDurableCancelRaceToOnePermanentNativeOutcome`、源端取消观察、active段拒绝，以及APPLIED先赢竞争时保持原终态/不重复人员事实 |
| 首开缺当前资格证明 | 真VALIDATE_WORKFLOW原件、当前全部roster/资格/资源兼容、Core当前活跃及最后SELF。`currentParticipationProofHasImmutableJournalAndRevokedEmploymentDoesNotFreeCapacity`、Source native HUMAN证明投影 |
| 机器恢复能造人事实 | RESUME/HANDOVER观察只RESOURCE_APPLIED/HUMAN_REQUIRED，当前本人重新验证才落参与。`machineResumeAndHandoverObservePhysicalEffectsButNeverCreateHumanFacts`、`lastSelfFailureAfterCurrentNativeValidationLeavesParticipationAndTaskUntouched`、源端真实SQL完整链 |
| 撤权未接stop义务 | 真实账号/门店grant/角色定义写者同实体独占根、精确角色身份和实际active人员关联，保留容量。`currentRoleRevocationRacesActualStartWithoutDeadlockOrFalseRelease`（追加小写角色参数定点通过）、`disablingActualStaffWriterRecordsStopObligationAndKeepsOriginalCapacity` |
| 接班被要求覆盖过去区间 | 新交接计划保留原source groups/资格，仅覆盖剩余实际窗口，历史段不改。`successorStaffNeedOnlyCoverActualHandoverRemainder` |
| START重放回旧状态，原号无法recover | 原始回执与Native当前状态分列；START与TASK_FLOW原号分流。`startReplayAndOldLifecycleReceiptReadTheCurrentNativeRevisionRosterAndStop`、`unappliedOriginalViewKeepsPendingReasonAndNeverQueriesOccupation`及typed route |
| SELF读取同店其他技师 | 无VIEW/COORDINATE时匹配真实人员与原岗位/参与关系。`sameStoreSelfPermissionCannotReadAnotherPersonsTask`精确断言FORBIDDEN；公开门店范围/内部token及typed归属反例 |

后续静态对账同时补齐旧预约allocation缺口、初始版本/MySQL真实来源列、CGLIB装配、生成器V15冻结历史过滤及当前状态修订。对应MySQL/Source/typed/cold-context验证均已执行通过，详见b03-acceptance.json。

扩大架构检查发现本批sql基础设施包未登记，已正确登记且定点通过；其他五组违规来自继承代码，未修改其业务或放宽守卫，没有回退执行纯净基线。后端i18n六项通过。全仓ArchitectureTest不宣称通过。
