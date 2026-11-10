# 本地验证记录（初轮）

2026-11-11；Java21、MySQL9（127.0.0.1:3308隔离随机库，测试后删除）；Flutter固定项目SDK；未使用166业务写入或真实银行账户。

|验证|结果|
|---|---|
|EntityPlanPurchasePolicyTest + EntityPlanPurchaseMysqlTest|16条通过。包含容量快照、override/试用保留、明确拒绝可纠正、未知恢复、迟到申报、取消对账、成员身份USR/MCH隔离、旧owner字段不作权威、成员撤销/主体删除转人工处理、停售定义隐藏报价|
|EntityPlanOfflineLedgerMysqlTest|通过。实际MVC及方法权限+真实stl_payment：商家下单/申报→缺少核款权限被拒→运营核款→仅一笔到账/一次开通→恢复ack；取消后迟到转账进入人工处理、不续期、不覆盖后续待付款订单|
|MallOfflineHostAdapterTest + MallPaymentRoutingTest|9条通过，保留资金适配及恢复分流语义|
|SchemaDriftTest|6条通过，V649与生成schema字段一致、迁移版本无重复|
|既有商城新增/相关Java检查|8条MySQL工作流、6条HTTP契约、1条响应fixture通过|
|OPS针对性检查|113条通过（API、OpenAPI实现对应、文案和设计token），另商城原动作恢复等38条通过；TypeScript全项目通过|
|App针对性检查|24条通过：15条主体购买/原键恢复/身份选店/取消后补报、6条既有wire、3条商城离线readiness；新增及修改功能Dart分析无问题|
|接口登记检查|OPS无新增空接口（8条既有欠账）；商户端无新增孤儿（88条既有欠账）；新9条Flutter接口有真实Handler/OpenAPI及外部消费登记|
|人工代码复查|发现的明确拒绝卡死、超时补报、主体失效、取消后发现真实款项均已修复并复查；review不替代实际测试|

金额只在运营真实核验的可信资金接口返回后生效。媒体底层存储适配已有针对性检查；新主链路数据库联调使用受控证据端口，不宣称已完成真实对象存储/真实银行/真机登录验收。

## 全量检查限制（未宣称全绿）

- OPS全量运行当时935通过、2失败（共937）。本次裸rounded违反设计token已修复，随后design-tokens及相关113条专项通过。剩余 `lib/mock/db/finance.test.ts > 执行回退后清除标记，队列自然消掉` 为先前已复现的财务mock失败，本轮未改财务文件。
- Flutter全量 `--no-test-assets` 运行972通过、14跳过、204失败，多数涉及AssetManifest/图像资源，也有其他断言；没有完成逐条基线归因，不能将全量标绿。本轮相关24条单独通过。
- commerce全量反应堆API/core110条通过后中止；没有完成全量persistence，相关15条已独立通过。后端本轮相关模块已test-compile，未运行所有业务测试。此前已知全局ArchitectureTest存量失败也未假称修复。
- 商用启用仍需补真实收款/售价。二阶段已补异常款项退款、对账解除及运营代购代码；本页数字仅为初轮证据，不代表二阶段验证结果；见[二阶段记录](./phase2-refund-assisted.md)。
