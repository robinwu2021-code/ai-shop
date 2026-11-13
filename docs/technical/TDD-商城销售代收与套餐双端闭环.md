# TDD-商城销售代收与套餐双端闭环

状态：已实现并通过本地验证；待目标环境部署、正式目录核对及真实实收验收。未发布商品、未执行真实收款。
创建：2026-11-13。档位：2（运营端、平台资金、商户 App 跨端契约）。
需求依据：[平台端功能清单：套餐销售代收](../requirements/平台端功能清单.md#套餐销售代收与双端闭环2026-11-13增补)、用户 2026-11-13 最后一项答复“恢复销售代收：销售收钱，运营确认到账”。
关联：[运营端套餐商城](./TDD-运营端套餐商城.md)、[上线开关与商品清单](./商城上线开关与商品清单.md)、[ADR-058](./ADR/ADR-058-商城销售代收复用平台资金与权益链路.md)。

运营先确认并发布套餐，运营或 App 为同一门店按同一 SKU 正式报价、下单；销售实际收钱后，运营确认实收，平台资金账更新付款事实，再交付门店权益。App 回读订单和订阅确认结果。

## §0 需求 → 设计

| AC | 可观察的验收标准 | 落点 |
|---|---|---|
| SC01 | 运营先回读等级、SKU、周期、币种、售价、有效划线价、权益、适用行业、版本和发布/在售状态；未发布/停售不可报价 | CatalogTab、ProductsTab、CatalogQueryService、正式报价 |
| SC02 | 运营为指定门店按已发布报价下单，买方为 STORE，发起人为真实运营，重试返回原单 | AssistedTab、CommerceOfflineOpsHttpController、DefaultMallFacade |
| SC03 | SALES_COLLECTION 不要求银行收款档案、银行账号或银行流水；旧 BANK_TRANSFER 原流程继续有效 | 支付域版本化收款条款、线下报价、readiness |
| SC04 | 运营记录真实销售人员、金额、币种、时间、收款凭据号、私有凭证及确认原因；确认身份取会话 | 平台员工目录适配、运营订单抽屉、核款服务与资金账 |
| SC05 | 订单创建/联系销售均不发权益；核款金额币种匹配且未作废才结算并交付，App 回读一致 | PlatformCollectionService、PaymentFacade、CommerceFulfillmentService、订阅读口 |
| SC06 | 同 actionNo 原参重试恢复；换 actionNo 仍不能重复使用同一销售凭据付另一单，权益仅交付一次 | 原操作记录、资金唯一键、原履约身份 |
| SC07 | App 保持全部报价单列表，选中的有效 SKU 接正式报价/订单；权限、停售、切店/退出清除草稿和隔离迟到响应 | plan_catalog_pricing、onboarding_entitlements_page、mall_offline_api、既有 confirmPurchase |
| SC08 | 先完成运营端套餐和运营购买验收，再完成 App 购买；每笔订单在两端对应相同门店/版本/金额/付款和权益状态 | 同环境同门店集成证据 |
| SC09 | 金额不符、作废后实收、超时未知不伪装为付款成功；退款仍使用原退款审核与真实出款记录 | 既有 UNMATCHED/LATE、void/recovery、RefundFacade |

所有设计均落在上述 AC；没有独立销售佣金、销售缴款结算或新商城的需求。

## §1 现状与影响面

- `ops-web/app/mall/catalog-tab.tsx` 已支持行业/三档方案发布；`products-tab.tsx` 管 SKU 售价、划线价和版本。
- `assisted-tab.tsx` 已支持运营代客报价/下单/发银行付款指引；`order-drawer.tsx` 支持核款、凭证和三维订单状态。
- `DefaultOfflinePurchaseFacade` 使用支付域银行档案取条款。ReviewCommand 强制 bankReference；ShopMallOfflineCollectionAdapter 仅接受 OFFLINE/BANK_TRANSFER；PlatformCollectionServiceImpl 和 PlatformOfflineLedger 也硬约束银行方式。因此不能仅换界面文案，或伪造银行档案给销售代收使用。
- 订单、付款、权益交付和恢复已有正式实现；复用现有平台资金账及真实授权，不直改订阅、不新增通用 Outbox 或第二套购买状态机。
- 当前 `ShopMallConfiguration` 将 OfflineCollectionPort 装配到 PlatformCollectionService，含 embedded pay 装配；目标环境实施时先确认实际 Bean/进程，复用实际客户端，不假设改独立 near-pay 同名类就能上线。
- Flutter `ai-shop-bapp` 新方案列表是只读选择；既有商城已有正式报价、升级报价、订单、银行指引和付款申报。复用其订单/恢复能力，按收款方式分支展示。
- 共享工作区已有大量他人未提交修改。本方案只登记本任务落点，实施时逐文件读取最新差异，不覆盖或提交其他任务代码。

## §2 方案与实施顺序

### 2.1 采用方式与范围

采用在既有线下订单中新增 `OFFLINE / SALES_COLLECTION`。银行转账继续为 `OFFLINE / BANK_TRANSFER`，两种方式分别冻结条款和凭据身份。订单、资金账、数字权益、退款审核及恢复沿原实现。

用户已批准恢复销售代收及本方案；以下为已采用的业务细化：
- 销售人员先复用同租户启用的运营员工记录（staffNo），具备代购职责且在目标门店授权范围内；不新建销售人员主数据。核款人由当前会话确定，销售收款人可与核款人不同。现有岗位权限决定可否兼任，不在本稿额外强制双人审批。
- 核款表示运营确认销售代平台收款已发生；不另设“销售再缴到平台后才生效”的第二资金审批。销售佣金、个人借款、现金盘点与缴款对账另行处理。
- 首轮以已确认的台湾零售纯数字 Plan 为验收主线；硬件/组合套餐保持原报价和履约合同，不因本轮改写库存/物流。

### 2.2 业务顺序

| 阶段 | 操作与结果 |
|---|---|
| A 运营确认套餐 | 回读版本和发布状态；核对权益与有效周期、币种精度和原价/售价。历史已确认值为 PRO 月199/年1990，CHAIN 月599/年5990 TWD；以运营实际审核发布结果为准，不自动用原型99/990替换配置 |
| B 运营配置销售代收 | 为平台/币种启用销售代收条款，配置付款期限和联系说明，无银行字段；停用只影响新报价，不使旧真实收款失去核对入口 |
| C 运营购买 | 选择门店、在售 SKU、销售代收，获取服务端冻结报价后下单；展示应付金额与付款期限，准备原付款尝试 |
| D 销售收钱、运营核款 | 选真实收款员工，记录收款凭据号、实收金额/币种、秒精度收款时间、私有凭证和原因，明确确认操作 |
| E 资金及交付 | 支付域记录真实钱款并裁决 MATCHED/LATE/UNMATCHED；匹配款经原付款刷新/交付链路写订单和权益；交付处理中继续回读 |
| F App 确认及购买 | 先核对 A 的同一报价列表、再核对 C–E 的运营订单及权益；随后从 App 发起销售代收购买，运营按 D 核款，App 再次确认结果 |

销售代收订单在 App 显示“联系销售付款／等待平台确认”，不显示银行账号、转账备注或银行凭证申报表。商户不能在 App 自行核款。未付款保持 FREE/原权益；只有订单付款与权益交付均达到已完成才显示“已开通”。零元订单沿内部结算，不要求销售收钱。

### 2.3 契约增量

以下合同已随实现同步到 OpenAPI；旧银行请求/快照保留原有语义。

| 合同 | 增量及约束 |
|---|---|
| 线下管理档案/条款 | 增加 `collectionMethod` 区分 BANK_TRANSFER/SALES_COLLECTION；销售档案含平台收款主体、币种、付款期限、说明和启用状态；不用银行账号作为销售收款身份。旧无该字段的持久快照按其既有银行语义读取 |
| `/ops/biz-mall/offline/profile` | 增加按方式读写；银行保存继续原银行字段，销售保存禁止银行占位值；管理仍用原档案读写权限；readiness 分别按实际启用方式返回 |
| 运营及商户 `/offline/quotes`、升级报价 | 可明确选择已启用收款方式；缺省保留原银行行为。服务端获取可信平台条款并冻结方式；后续不能在订单上切换方式；升级仍走既有抵扣/版本规则 |
| 订单/报价/指引读模型 | 返回冻结的收款方式；准备销售付款尝试时返回销售说明和原订单，不生成 transferRemark。已存在的指引路径继续负责幂等准备支付，页面按方式命名 |
| `/ops/biz-mall/offline/orders/{no}/confirmations` | 销售方式新增 `collectorNo`、`collectionReference`；保留 actionNo、actualAmount、actualPaidAt、evidenceKey、reason。bankReference 仅银行方式必填。actualReceiver 仍表示平台收款主体，不能填写销售姓名或随客户端增权 |
| 受限员工候选查询 | 以代购/核款职责返回同租户且在目标店范围内的有效收款员工最小字段（staffNo/displayName）；复用现有员工主数据；服务端确认再次授权。不让业务操作员为选销售而额外获得完整 IAM 管理权限 |
| 商户订单读模型 | 可展示销售姓名快照和收款方式；不暴露运营私有凭证、内部原因或其他员工资料；已付信息仍取服务端 |
| 内部 Port/支付 DTO | 沿现有 OfflineCollectionPort、PlatformCollectionService 显式传方法、销售和凭据号；不把销售凭据塞入 bankReference 或伪造 provider 交易号 |

权限复用 `commerce:catalog:edit/publish`、`commerce:collection:profile:read/write`、`commerce:mall:assisted:purchase`、`commerce:mall:offline:evidence/confirm`、订单/订阅读权。App 购买沿 `commerce:mall:purchase`；没有职责不能购买/核款。新词条走 OPS 现有三语和 Flutter 四语词条及生成流程。

### 2.4 持久化、资金身份和恢复

- 已发布商品、报价、订单、条款快照、付款尝试和已付事实冻结；不能把旧银行订单读成销售代收。增量迁移编号在实施前查最新号，仅添加新迁移，不改已应用 SQL。
- 复用 `stl_payment` 原资金记录和离线操作原件，销售核款原始字段保留于不可变 proof；新增通用收款凭据键列/唯一索引，旧银行专用唯一键及其算法保持原样。
- 销售收款唯一身份为服务端规范化后的 `(tenantNo, SALES_COLLECTION, collectorNo, collectionReference)`，用于防同一真实凭据跨订单/换 actionNo 重复核款；该身份不含币种、金额、订单或档案版本，避免改参数绕过。不同销售的真实独立凭据不合并；凭据号去首尾空白、保留大小写，精确二进制摘要。
- 若一笔真实凭据覆盖多订单，本轮不拆分自动归集：先由运营按实际业务出具可追溯的单订单收款凭据；金额不符保持 UNMATCHED，不挪入另一单或强行开通。
- 金额/币种不匹配继续写真实异常款而不付款成功；作废后款继续按原晚到/退款处置，不自动恢复原单；付款截止仍由冻结条款和服务端裁决，不由 App 延期。
- 私有凭证沿原票据预留、上传和读取范围；不可跨订单/跨操作员/跨用途借用。原 actionNo 加原内容摘要恢复，响应未知不换号重做。
- 幂等唯一规范引用 [幂等设计规范](./reference/幂等设计规范.md)，这里只登记本动作落点：

| 参数 | 数据库 | 服务端 |
|---|---|---|
| quoteNo + idempotencyKey + 原门店/买方/initiatedBy | 原 mall_order/事件认领 | 原 DefaultMallFacade 下单/原键查回，不改锁序 |
| actionNo + 冻结方式 + collectorNo/reference + 实收/凭证 | 原 offline action + stl_payment 新销售凭据唯一身份；资金/回执同支付事务 | 原 ACCEPTED → 支付确认 → 付款刷新 → 完成/恢复，不新增 Outbox |
| 原 paymentAttemptNo/orderNo/组件来源 | 原付款/交付/权益唯一约束 | 原 PaymentFacade、CommerceFulfillmentService；重复核款/恢复不重复发权益 |

### 2.5 模块与执行批次

| 顺序 | 模块/路径 | 结果 |
|---|---|---|
| 1 | `ops-web/app/mall/catalog-tab.tsx`、`products-tab.tsx`、既有商品种子/只读回读 | 核对真实套餐资料，列差异；发布是可审阅的实际版本操作，非原型样例灌入 |
| 2 | `backend/commerce/commerce-api` offline/port 合同、`commerce-core` offline/mall/fulfillment、`commerce-spring-boot-starter` 既有 HTTP/配置/鉴权 | 销售条款、报价、付款方式冻结、核款和恢复；沿原包名/类名扩展 |
| 3 | `backend/pay/pay-domain` PlatformCollectionService/PlatformOfflineLedger、宿主 ShopMall*Adapter/Configuration、实际客户端装配、增量迁移 | 同一平台资金账记录销售实收及唯一凭据，原银行行为兼容 |
| 4 | `ops-web/lib/types/commerce.ts`、contracts/https、`app/mall/collection-tab.tsx`、`assisted-tab.tsx`、`order-drawer.tsx` 和词条 | 运营可配置销售代收、购买、录入实收、查询付款/交付；mock 不作为实收证据 |
| 5 | `/Users/robin/work/ai/ai-shop-bapp/apps/merchant_app/lib/features/standard_onboarding/{onboarding_entitlements_page,mall_offline_api}.dart`、`features/mall/mall_pages.dart`、词条 | 方案 SKU 接既有确认购买，按销售方式展示，回读运营订单和权益；没有月/年切换 |
| 6 | OpenAPI/接口文档/数据库字典及两仓相关测试 | 统一生成/对账，验收记录实际门店/版本/订单/支付/权益来源及真实操作人 |

只读核对可先执行；真实发布、部署和真实核款分别用实际配置/记录审阅。真实收款验收需要实际销售收款及真实私有凭证，本地测试数据只证明测试链路，不冒充实收。

## §3 取舍

| 方案 | 取舍 | 状态 |
|---|---|---|
| 在现有平台线下链路新增销售代收方式 | 资金、订单、交付、幂等恢复一致，需修改显式银行依赖和两端合同 | 已采用 |
| 另建销售订单/独立资金账再同步订阅 | 多一套对账和恢复边界，与用户要求两端同单不符 | 不采用 |
| 将销售收款填入银行转账字段 | 无法区分真实银行流水和销售凭据，审计与去重身份失真 | 不采用 |

## §4 边界和待核对

- 本轮恢复销售代收，覆盖此前 2026-11-06“暂缓”及 11-11 清单“不随本清单恢复”的范围限制；保留历史记录，不将本稿状态写成已上线。
- 默认复用平台员工为销售、核款即确认代平台实收、单凭据单订单。这三项已由用户确认采用。
- 不新增佣金、销售资金缴款台账、在线支付供应商、跨店共享权益；不改变门店向消费者收款/渠道进件链路。
- 实施者可决定内部 DTO/辅助方法的最小划分、精确迁移编号和测试组织，但不能自行更改以上资金身份、付款时点或权益交付语义。
- 实施/目标环境验证时先回读套餐发布和平台收款装配；本稿未确认当前线上商品价格、开关或收款事实。

## §5 实现 → 需求（拟验证，未运行）

| AC | 必要验证 | 状态 |
|---|---|---|
| SC01/SC02 | 正式报价/原单权限与版本专项、运营合同及页面检查 | 本地通过；目标环境正式目录待回读 |
| SC03 | 条款/路由、旧银行合同和宿主适配专项 | 本地通过 |
| SC04/SC05 | 平台实收 MySQL 与 SalesCollectionWorkflowMysqlTest，运营币种/员工页面 | 本地通过；未录入真实实收 |
| SC06 | 原生 MySQL9 同凭据跨单/并发、原键恢复、权益不重复及旧恢复专项 | 通过 |
| SC07 | Flutter 72 项：报价/字体、SKU、升级/同级购买、切店、迟到请求、未发说明销售状态 | 通过 |
| SC08 | 运营配置→运营购单→真实实收→App 查同单；App 发单→运营核款→App 查权益，两条独立证据 | 待真实联调 |
| SC09 | 原线下资金/晚到/退款及销售异币种专项，App订单状态回归 | 本地通过；真实款仍待事实验收 |

本地 MySQL9 使用 127.0.0.1:3308；模型/界面测试不能替代原生唯一约束与事务证据。沿项目增量验证约定，只跑本批实际影响部分；本稿仅文档评审，不运行无关业务测试。

## §6 设计 → 实现

本任务已修改后端、OPS 与 Flutter。完整本任务文件与预编辑基线保存在 .superpowers/sdd/sales-collection-20261113，未将共享工作区全部差异当成本任务产物；保留未提交改动，不合并或推送。

2026-11-13：独立只读 builder 评审结果为“nothing important left”，未提出阻塞实施计划的问题。方案与 ADR 的本地链接检查通过；本次仅文档，不宣称业务测试、目标环境或真实资金验收通过。


## §2.6 实施计划与进度（2026-11-13 用户授权连续执行）

目标：在同一商城/资金/权益链路支持 SALES_COLLECTION，运营先配置、下单、核款，App 使用同一 SKU 购买并回读结果。
执行：本会话原生实施；复用已授权工作区，不切换共享分支、不提交其他任务。跨端接口顺序为条款/核款→HTTP/装配→运营→App。

- [x] Task 1 资金与条款：OfflineCollectionTerms 增加派生 collectionMethod（旧银行快照字段不变）；ProfileCommand/支付 Profile 增加销售方式与联系说明；ReviewCommand/OfflineProof 增加 collectorNo/collectionReference，保留旧构造器。PlatformOfflineLedger 在现有账上存销售唯一凭据，增量迁移仅加列/唯一键。验证旧银行、无银行销售配置、匹配/异额、凭据跨单拒绝、原键恢复。
- [x] Task 2 商城接线：OfflinePurchaseFacade quote/upgrade 显式方式，独立销售档案；原付款准备按冻结方式；HTTP/宿主适配/readiness/员工候选权限接线。验证旧合同、员工范围、STORE原单商户可读、方式不可变。
- [x] Task 3 运营端：types/contracts/https 和三语词条，collection-tab 按方式维护，assisted-tab 选择销售方式，order-drawer 录入销售与凭据。验证序列化、前端类型与相关单测。
- [x] Task 4 App：mall_offline_api 解析方式、显式报价，方案单列表选中 SKU 进入既有 confirmPurchase，订单按方式显示销售说明并隐藏银行申报。验证SKU、切店、订单/权益状态回读和四语生成。
- [x] Task 5 对账：更新 OpenAPI/接口/迁移登记；只跑相关 Java/MySQL9/OPS/Flutter 检查和构建，原型列表样式不变。独立只读最终review，修复重要问题。
- [x] Task 6 本地可视与构建交付：运营页面本地可见检查及任务独立模拟器5560；记录真实套餐回读是否可取得、业务状态。部署/真实核款需真实环境/收款事实，不能由本地示例代替。

检查重点：旧银行持久快照不被改写、销售无银行资料也可报价、跨单重复凭据、STORE代购订单对授权商户可见、权限/切店导致原价和旧响应失效。各测试名和实际命令随任务完成登记于§5/§6。


### 实施记录（持续更新）

- 条款方式冻结在现有 instructions map，新增派生 collectionMethod；旧银行快照字段及银行凭据算法保持原样，不向旧快照强塞新默认字段。
- 销售档案沿部署 profile-no 加 `-sales` 后缀，与银行版本独立；不要求配置银行。新资金列/唯一索引由 V666 追加。
- 当前员工范围解析支持全量/主体范围；已有社区/网点限制的员工不默认扩权成商城销售，需将来按对应范围正式扩展。
- 运营沿已有 zh/en 文案体系（框架既有语言回退），Flutter 新增四语；未凭空重建一套 OPS 词条体系。


### 代码评审与修订

独立只读 review：无 Critical，三项 Important（付款方式报价迟到、空档案切换表单、真实收款币种）及一项销售状态/文案问题。销售状态影响付款理解，按 Important 一并修订。报价请求按代次与请求方式隔离；运营表单按方式/币种重建；核款使用实际币种；App 以冻结订单方式处理未取得付款说明的销售单。浏览器已复现空档案切回丢表单，修复后可反复切回；App 增加异序报价和未发说明专项。支付实体补齐 V666 字段。

review 未判定的事项：正式价格/发布/部署/真实凭据以目标环境及真实事实验收，不由本地测试替代；社区/网点销售范围延续已登记边界；佣金/缴款及库存物流重构不在本轮范围。


### 最终本地证据

- Java：69 个独立相关测试报告通过（包括销售资金/凭据并发、原银行、权益交付、原键恢复、HTTP/宿主）。后端最终 package 成功。
- OPS：16 项单测、TypeScript、完整生产静态构建及产物断言通过；三处生产组件浏览器检查无页面脚本错误，空档案方式来回切换恢复，实际 USD 被原样提交、保留销售凭据而无银行占位。
- Flutter：72 项相关测试、六个修改实现/测试文件 analyze 无问题。NearBoss 166 正常 APK 与离线示例 APK 均构建完成。
- V666：在本任务创建并清理的 MySQL9 3308 临时库执行原迁移 SQL，销售唯一索引存在；资金唯一冲突由原生并发专项另证。
- 发布产物清单：artifacts/sales-collection-2026-11-13/manifest.json。未部署、未改真实商品或收款条款、未确认真实收款。

原生人工检查补充：5560已走通选择报价→销售代收确认单→本地待付款单，发现并修正共享订单标题和付款期限中的旧银行措辞，新增销售截止/逾期四语文案并补断言。正式目录及真实资金未改。部署V666必须指向实际PlatformCollectionService的stl_payment数据源；独立PAY部署需采用相同合同/版本，不能仅更新OPS页面。

额外终态回归：商户订单模型现同时识别服务端CLOSED及旧CANCELLED，关闭的销售单不再读取付款指引或提示再次付款；72项App测试和静态检查通过。构建包为共享工作区快照，正式发布需集成核对，不声称已上线。

2026-11-13 最终原生画面：5560显示“订单·待付款”、销售联系说明、付款截止和刷新按钮，不含银行转账引导。正式环境发布、目录与真实资金事实仍未执行；SC08真实同店联调不标为通过。


## 166 发布与新店验收（2026-11-13）

本地验收后，已按用户确认发布到台湾 166，迁移使用 V666；新建 ST261113Y693VWBMKT 测试店完成运营原单到 App、App 原单到运营的双端回读。4 个历史零售测试 SKU 已按用户确认停售，App 显示正式四个报价。当前 App 年付测试单待付款、权益仍 FREE；真实实收核款与权益生效待真实收款事实，未伪造资金。详见[166 发布记录](../handoff/sales-collection-166-2026-11-13/README.md)。
