# 二阶段：异常款退款、对账解除与运营代购

2026-11-11。范围承接初轮明确剩余的异常款处置，并增加“App购买→运营审核”及“运营直接代购”验收。用户已授权修复、验证后直接部署现有166服务器；真实收款账户和真实售价配置仍后补。API、OPS后端及静态页面已发布，制品和健康证据见 [release-166.json](./release-166.json)。真实银行业务验收后补。

## 实现与契约

- 两套订单复用 `OfflineCashRefundPort` 与原 `stl_payment` 资金事实。V13只保存退款协调命令和核验动作，退款金额为所选真实原款的剩余可退额；保留待退款占用，禁止按销售应付额虚构退款。正常已交付匹配款沿用权益退款。
- 运营读取原款、申请退款、实际线下退款后上传独立 `REFUND_PROOF`，录入原动作核验，未知时恢复原命令；明确拒绝保存动作审计，用新动作纠正。产品不执行银行转账。买家可查安全退款状态，不返回平台凭证、退款方核对标识或银行流水。
- 可信退款完成后，仍须原订单银行对账关闭栅栏才释放购买/库存占用。主体订单最终可为 `REFUNDED`，不改已有订阅/override，也不覆盖后续新订单。商城正常有效订单的额外异常款可独立退回。
- 主体运营代购使用真实老板/门店上下文，运营创建者与老板受益人分离。V650新增创建者字段并将购买请求幂等域绑定创建者；`purchaseSource` 为 `MERCHANT` 或 `OPERATOR`。代购仍冻结原报价、容量、收款版本，并经真实核款开通。
- 机器契约：[门店商城](../../api/commerce-mall-openapi.json)、[主体额度包](../../api/entity-plan-offline-purchase.openapi.json)。商家端由独立 `ai-shop-bapp` Flutter消费，两份 `backend-ui-delivery-*.json` 登记本仓Vue无入口的真实外部消费。

## 验证证据与状态

初轮结果保留在 [local-verification.md](./local-verification.md)，不作为新增功能通过证据。

|检查|二阶段结果|
|---|---|
|后端组合专项（主任务执行）|主任务报告初次79项通过；随后主体新增1用例、merchant增量24项通过，当前组合计80项通过（主任务核对XML）|
|JSON/OpenAPI及外部消费登记|JSON解析、本地$ref、路径参数、operationId唯一性校验通过：商城73项操作、主体20项；两份退款消费登记各10条均唯一且有公开方法；另保留热补丁契约3项操作|
|商家孤儿端点（文档任务执行）|`python3 scripts/check-app-backend-orphan.py --check`通过：141外部/分阶段登记、88既有欠账；未修改基线。保留热补丁三路由已核对真实Flutter消费，并标记DELIVERED|
|OPS孤儿端点（文档任务执行）|`node scripts/check-ops-orphan.mjs --check`通过：617端点、582有出口、22分阶段、13既有欠账；未增加基线|
|OPS全量|最终96文件、955测试通过。TypeScript与生产构建通过；20个首页资源均HTTP200。|
|Flutter与最终评审|专项38测试通过；两项原P2已定向复审关闭，连续拒绝迟到核款回归通过。|
|166发布|API/OPS健康及实际运行摘要核对通过，页面与代理检查通过；最终补充记录见发布JSON。|
|真实收款/报价/银行验收|后补，未执行，不以本地合成资金数据替代|

发布与验证更新应追加当前构建、受影响测试及实际结果；不得将本地通过、HTTP成功或模拟凭证写成真实银行验收。其他会话的独立储值DDL/迁移不属于本阶段。


## 已上线窄补丁保留

发布审计识别到166当前API的门店创建上下文、连锁授权选项及命令回执行为必须保留。新 `preserved-store-chain-hotfix.openapi.json` 记录三个原有Handler，独立Flutter已在 onboarding_api.dart:397、http_chain_repository.dart:451/:524实际调用。外部消费登记标记 `consumerStatus=DELIVERED`，未将已有入口写成未来开发，也未增加known-orphan基线。精确源码恢复与运行制品核对由主任务负责；原工作区其他未提交内容保留。

## Final verification and release evidence

Backend relevant suite: 80 passed; final host incremental recheck: 16 passed. OPS: 96 files / 955 tests passed; Flutter: 38 targeted tests passed. Both original P2 findings are closed by targeted review. Runtime source fix additionally removes an ineffective BAD_REQUEST argument without changing the generic message. Native endpoint guard now scans commerce and bare Mapping; 3 tests passed. Related metadata checks: 40/40; broken Markdown link check: 1/1; 26 generators current.

The first API start could not create V649 tables with the DML-only runtime account. The original API was restored UP; no new tables existed. A backup-based rehearsal then applied unchanged V649/V650 and commerce V13. Flyway validation identified only failed V649; repair removed that failed record before formal migration. The scoped temporary migration account, rehearsal schema and credential files were removed. Runtime permissions remain SELECT/INSERT/UPDATE/DELETE. No history SQL or successful checksum was changed.

Whole-repository shared guards still contain inherited failures in other B-app types/enums, resource lineage, documentation state/SVG checks and related baseline domains. Baselines were not expanded and hooks were not bypassed. Local host commits must not be described as a successful remote push. Independent near-pay, inventory and stored-value migrations were not changed. Real collection accounts, approved prices and bank acceptance remain deferred.
