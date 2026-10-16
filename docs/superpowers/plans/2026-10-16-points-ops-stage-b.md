# 积分第二阶段运营接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

依据：docs/technical/TDD-积分域重整.md 的 B1–B5、docs/requirements/PRD-积分模块.md、第一阶段验收记录。用户已授权启动第二阶段；第一阶段基线 5d1e24be9。继续使用 codex/points-v3 隔离工作树。积分保持 pay 内业务模块、门店维度；订单 C 最后。

## 已核对的入口

- OpsPointsController 的 /ops/points/overview 仍调用旧 PointsService.overview，不能沿用单池展示模型。
- OpsPointsPolicyController 的 /ops/points/client-policy 仍经 PointsPolicyAppService 读写旧端策略。
- 新 PointsQueryService 已提供内部有界查询；其 Map 含持久化正文，不可直接作为公开运营 DTO 返回。
- 原路由账单组由 draftGroups 一次固定，运营须按门店/账期聚合，操作仍带具体 statementNo/revision。

## 执行约束

逐端点明确权限，operator 由 SecurityUtils 获取，不能接受请求体伪造操作人；Controller → payclient 应用门面 → points 内核，不直写表。审批不能自批，未知资金只能查原单，不开放手工成功入口。每个 URL 只有一个实现。使用白名单 DTO，不返回银行交易正文或任意 payload_json。平台财务权限与门店筛选分别验证，不能依靠前端过滤隔离。旧运营入口在各自替换完成后移除，旧订单 SPI 保留至 C。

定向测试使用 runner --timeout 0，15 秒进度及 >=1 秒慢日志；先单方法 RED→GREEN，再本任务相关类，不跑全仓。生产通道、任务默认关闭。不得使用真实主体/门店或真实资金。

## Task B1.1 新运营查询门面与看板替换

文件：新增 backend/shop-app/src/main/java/ai/neargo/shop/payclient/PointsOpsAppService.java 及专用 DTO；修改 portal/ops/pay/OpsPointsController.java；新增定向运营接入测试。

- [x] 先编写旧看板错误依赖的反例：新看板按 typeCode 返回 U/C/D/B、锁定额、退款预留、R 独立应收；不存在类型明确拒绝，不把 R 加入现金。
- [x] DTO 显式列出字段；日期/金额/比例不使用旧 1:1 假设。不直接暴露 PointsQueryService.Page.rows。
- [x] 接资金池/类型与门店筛选查询，验证分页 1–200 和非法游标。
- [x] 保留 /ops/points/overview 单一实现，FINANCE_SETTLE_READ 权限；同步 API 契约和受影响调用方，不提供旧模型兜底。
- [x] 验证无权限拒绝、门店条件不串数据、响应无 payload_json；运行本类和积分架构测试后提交。

## Task B1.2 配置、协议与开关 API

- [x] 接种类、规则、协议及 EARN/REDEEM/BOTH 范围开关应用服务；替换旧 client-policy 读写，明确新请求模型。
- [x] 主体仅用于归属和签署授权；受理协议、结算应付、退款应收按门店。
- [x] 逐端点权限和操作审计；异常操作不留下部分配置。测试未知场景不默认开启、冻结不修改余额、旧交易快照不随配置变更。
- [x] 更新 OpenAPI/权限种子及相关文案；局部测试后提交。

## Task B1.3 资金申请与复核 API

- [ ] 接备款、U→B、提取及周期计划；外部计划先待回执声明，审批仍核验真实证据。
- [ ] 申请人/复核人从登录态取得；自批、越权、金额/版本冲突反例先失败。
- [ ] 受理/未知/成功状态分离；不提供换号重付或手工到账捷径。局部测试后提交。

## Task B1.4 账单、退款及异常 API

- [ ] 按门店/账期展示路由组，具体确认/争议/重算携带 revision。
- [ ] R 只在同店抵销；退款准备/补收/补偿/恢复按内核状态机执行。
- [ ] 人工重试必须操作人和原因，保留原事件；查询只读，不能伪造付款/退款成功。
- [ ] 验证过期 revision、未知资金、另一门店及重复请求；局部测试后提交。

## Task B2–B5 页面与运营验收

- [ ] B2 类型/规则/协议/资金池/开关基础页，门店筛选、三语及导航。
- [ ] B3 备款/复核/计划/执行工作台，未决状态和原号回查。
- [ ] B4 账单组、R、退款与异常工作台，界面操作符合可执行状态。
- [ ] B5 账户分格、凭证、统计、对账及重试查询，清除被替换的旧运营入口。
- [ ] 修改 ops-web 前读取其 AGENTS.md；按页面工作同步 UI 清单、权限和 OpenAPI，不登记尚未实现页面。
- [ ] 每任务做局部验收，阶段末一次受影响运营/积分集合及独立 review；订单生产者仍不接入。

Review Focus：运营权限不能仅靠前端；登录操作人不可伪造；付款证据与任意持久化正文不能泄露；同店多个原路由账单不可丢组；R 不能算现金；审批自批及 UNKNOWN 换号重付必须拒绝。

状态：第二阶段已启动，入口盘点及实施拆分完成；B1.1 已完成看板替换、门店应收查询和前后端契约；B1.2 已完成配置、开关及门店协议 API 和旧端策略页替换；B1.3–B5 继续。
