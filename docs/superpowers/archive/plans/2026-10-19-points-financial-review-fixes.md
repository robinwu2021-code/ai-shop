# 积分资金Review修复执行计划

目标：修复完整Review列出的10项问题，积分仍是pay内业务模块，门店为业务维度。
依据：docs/technical/积分结算分账-完整Review-2026-10-19.md，PRD-积分模块，TDD-积分域重整。
执行：当前生产整合工作树分模块推进；测试集中执行避免target冲突，每批保存逐例时长/慢日志。此次修复不等于生产部署或开启真实门店。

- [x] A SR1–SR5：SettleServiceImpl.executeSplit改为无事务编排，统一SplitPreparation/Result持久意图；直连与归集均经PointsSettlementHook；金额用splitAmount，缺可信路由拒绝。先运行PerOrderSettlementTest复现，添加归集及缺路由反例，回归退款分账边界。
- [x] B R11：可信终局时间索引与BUILDING分批冻结集合、路由分组；固定高水位，退款/并发出单不重复绑定。用10001跨期与大账期续跑反例验收。迁移V467。
- [x] C R12：复用PointsCursorStore保存两个扫描id，单调watermark及同事务进度；每批限制候选检查和周期生成，游标回绕避免未到期/待审批记录饿死后页。三项批次/重启/回绕反例。
- [x] D G-BIZ/G-AUTO：按BizContext固定门店提供账单读/确认/异议，revision及权限校验；修订期限、通知和有限批次自动确认，异议停止自动确认。待B接口稳定后顺序实现。
- [x] E G-ADJUST：独立调账申请与双人审批，调增U→C及ISSUE→USER.AVAILABLE；调减USER.AVAILABLE→ISSUE.ISSUABLE、C→U。用户确认仅动可用余额，不能动在途；原申请/修订/幂等与统一posting。迁移V468，运营端完整对接。
- [x] F 分模块定向回归、独立复核、更新Review问题状态与证据；未完成项不得标完成，不引用旧测试报告证明当前代码通过。

业务口径：用户最终批准调增必须消耗U预算，调减只扣AVAILABLE退回发放户并C→U释放预算；不能凭调账请求制造真实外部入款证据。已有同币种/同种类/汇率及余额非负约束继续生效。

验收与修复状态详见 docs/technical/积分资金Review修复-执行记录-2026-10-19.md。所有勾选表示本地修复/约定范围验收，不代表生产通道认证或部署。
