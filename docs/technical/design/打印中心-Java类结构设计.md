# 打印中心 Java 类结构设计

状态：设计参考；实施与验收进度见对应任务表和验收记录。

> 设计稿 · 2026-10-23 · 本文定义目标类结构，未创建Java产品源码、POM、Controller或迁移。
> 上游：[完整方案](./打印中心-完整方案与模块封装.md)、[API合同](./打印中心-API设计.md)、[数据库](./打印中心-数据库设计.md)、[16项开发任务](../../superpowers/plans/2026-10-23-print-center-implementation.md)。
> 真源：[类结构JSON](./schema/print-java-class-design.json)；详细索引：[逐类职责、字段与方法](./打印中心-Java类与方法清单.md)。本稿细化并统一此前候选文件路径，不另建第二套同义类。

## 1. 模块与包结构

沿用独立业务模块、先内嵌部署的方案。没有单独的print-web模块，HTTP及跨域装配留在shop-app，避免重复启动层。首个本地执行端是单独可执行的print-agent，服务端不依赖它。

```text
backend/print/
├── pom.xml                         聚合，不是第二个父工程
├── print-api/
│   └── ai.neargo.shop.print.api/
│       ├── PrintFacade             来源事实进入打印的核心入口
│       ├── Print*Service           设备/代理/配置/任务/查询/监控业务接口
│       ├── PrintSimulationFacade   模拟入口
│       ├── PrintExportFacade       PDF文件入口
│       ├── Print*Port              来源、字体、设备、文件、凭据边界
│       ├── PrintFact/Document/Layout等不可变record
│       ├── Print*Status等枚举
│       ├── OrderPrintFactReady等事件
│       └── dto/                    8个分组DTO容器，内部为record
├── print-domain/
│   └── ai.neargo.shop.print/
│       ├── service/impl/           对外Service和核心Facade实现
│       ├── template/               场景注册、模板解析和编译
│       ├── layout/                 字体无关排版、档案、受控系统插槽
│       ├── source/                 来源Port注册，禁止读订单Mapper
│       ├── dispatch/               纯状态策略与投递/租约/证据接口
│       │   └── impl/               短事务准备、事务外投递、短事务确认
│       ├── simulation/             独立模拟Runner、fixture及模拟Port
│       │   └── impl/               DefaultPrintSimulationFacade
│       ├── export/                 PDF后台执行、恢复和清理
│       │   └── impl/               DefaultPrintExportFacade
│       ├── monitor/                漏打巡检、故障待办
│       ├── operation/              命令幂等接口及impl
│       ├── audit/                  门店提交有序审计流
│       ├── support/                JSON规范化与哈希
│       ├── port/                   本域对外的加密凭据查询实现
│       └── persistence/
│           ├── entity/PrnRow       映射已有DDL共有六列
│           ├── entity/Prn*.java    22张打印表的实体
│           └── mapper/PrintMappers.java
│                                   22个内嵌BaseMapper接口
├── print-channel/
│   └── ai.neargo.shop.print.channel/
│       ├── feie/                   云端候选适配器、结果映射、验签
│       ├── font/                   同源字体包与字形度量
│       ├── pdf/                    PDFBox输出
│       ├── artifact/               本地私有文件/COS适配
│       └── credential/             凭据加解密
└── print-agent/
    └── ai.neargo.shop.print.agent/
        ├── AgentMain               常驻启动与单实例锁
        ├── AgentHttpClient         D01–D10协议
        ├── LocalDispatchStore      SQLite任务与证据持久化接口
        ├── AgentCredentialStore    本机受保护凭据接口
        ├── DesktopPrintExecutor    本地系统队列接口
        ├── AgentExecutionLoop      领取、落盘、发包、补传
        └── AgentRecoveryCoordinator
                                    重启先查原提交，不盲重印
```

订单和应用接线：

```text
shop-core/ai.neargo.shop.trade.order.printing/
  OrderPrintFact / OrderPrintFactMapper
  OrderPrintFactRecorder
  service/OrderPrintFactService
  service/impl/OrderPrintFactServiceImpl

shop-app/ai.neargo.shop.printbridge/
  port/OrderPrintSourceFactsPort
  PrintFactReadyConsumer / PrintControlChangedConsumer
  PrintBridgeConfiguration
  PrintArtifactWakeConsumer / PrintArtifactJobHandlers
  PrintJobHandlers / PrintNotifyBridge

shop-app/ai.neargo.shop.portal/
  biz/BizPrint*Controller           11个商家Controller
  ops/OpsPrintController
  device/DevicePrintController
  callback/PrintCallbackController
  printsupport/PrintActorFactory / PrintAgentAuthenticationFilter
```

## 2. 强制依赖边界

| 调用方 | 可依赖 | 不可依赖 |
|---|---|---|
| print-api | shop-base、JDK | Spring Web、MyBatis、实体、厂商SDK、PDFBox |
| print-domain | print-api、shop-store-mybatis、平台公共组件 | trade/pay/行业实现、PDFBox/COS、Servlet |
| print-channel | print-api、存储公共合同、必要SDK | print-domain实体/Mapper、订单实体/Mapper |
| print-agent | print-api、HTTP/SQLite/本机系统库及必要本地渲染库 | print-domain、服务端数据库、商家登录Token |
| shop-core/行业所有者 | print-api合同 | print-domain、print-channel实现 |
| shop-app | API、装配所需实现、既有认证/Outbox/Job | Controller直连任何业务Mapper |

跨域架构闸门仅放行`ai.neargo.shop.print.api..`这个合同包；不能将整个print域加入免检基线。class清单中的dependsOn是关键协作边，不是允许无约束导入的清单，实体不能跨模块传递。

业务Service使用接口＋`*ServiceImpl`，实现进`..impl..`。已有命名DefaultPrintFacade、DefaultPrintSimulationFacade、DefaultPrintExportFacade保留，因为是Facade实现，不另建同义ServiceImpl。纯算法（PrintStatePolicy/PrintJsonCodec等）不为凑层数再拆接口。

普通CRUD由Mapper继承，业务服务直接使用本域Mapper。不新增SimulationRepository或每表Repository包装；此前任务中的这些名称只是职责占位，本稿统一为Mapper。跨域来源使用Port，不使用Repository穿透读取订单。

## 3. 对外接口与类职责

| 接口（print-api） | 实现（print-domain） | 负责 |
|---|---|---|
| PrintFacade | DefaultPrintFacade | 可信事实/人工来源进入统一需求生成链 |
| PrintDeviceService | PrintDeviceServiceImpl | 物理设备、连接、凭据版本、检测/测试 |
| PrintAgentService | PrintAgentServiceImpl | 代理配对、撤销、凭据轮换 |
| PrintScenePolicyService | PrintScenePolicyServiceImpl | 场景目录、策略版本、字段及样例 |
| PrintTemplateService | PrintTemplateServiceImpl | 模板草稿、发布、预览及解析预演 |
| PrintRouteService | PrintRouteServiceImpl | 有序路由、版本、分单演练及发布 |
| PrintRequestService | PrintRequestServiceImpl | 需求查询、阻断恢复及初打生成幂等 |
| PrintQueryService | PrintQueryServiceImpl | 批次/任务/票据/操作/订单摘要投影 |
| PrintReprintService | PrintReprintServiceImpl | 原票关联的补打、批量补打和加印 |
| PrintJobCommandService | PrintJobCommandServiceImpl | 安全重试、取消、人工确认、清闸、来源控制 |
| PrintMonitorService | PrintMonitorServiceImpl | 健康、故障确认、审计及变化流 |
| PrintOpsService | PrintOpsServiceImpl | 受权目标范围的诊断与对账 |
| PrintAgentAuthenticationService | PrintAgentAuthenticationServiceImpl | 过滤器委托的凭据认证，不允许app读取Agent Mapper |
| PrintAgentProtocolService | PrintAgentProtocolServiceImpl | 代理独立认证后的会话、领取、租约、回传 |
| PrintSimulationFacade | DefaultPrintSimulationFacade | 隔离模拟记录及结果，不调用真实投递 |
| PrintExportFacade | DefaultPrintExportFacade | PDF申请、查询、当前授权下载 |
| PrintCallbackService | PrintCallbackServiceImpl | 原始回调验签→可靠证据收件箱 |

核心声明：

```java
public interface PrintFacade {
    PrintSubmission submit(PrintIntent intent, PrintActor actor);
    PrintOperation submitBySource(SubmitPrintRequestReq request, PrintActor actor);
    PrintSubmission onFact(PrintFactRef ref);
    PrintJobView getJob(PrintJobRef ref, PrintActor actor);
}

public interface PrintSourceFactsPort {
    Set<String> sourceTypes();
    PrintFactRef resolveReference(PrintSourceReference reference, PrintScope scope);
    PrintFact snapshot(PrintFactRef ref);
    PrintFactPage scan(PrintFactCursor cursor, int limit);
    PrintExecutionGuard executionGuard(PrintFactRef ref);
}
```

submitBySource是HTTP业务写入口，返回统一PrintOperation，result引用内部PrintSubmission；避免Controller把提交结果误当打印完成。submit供已解析的可信业务引用调用，也要验证scope/hash；onFact只供可靠事件/扫描的内部调用，不注册任意factRef的HTTP入口。

PrintSourceRegistry在PRT-03提供，PrintAdapterRegistry和基础PrintIncidentServiceImpl在PRT-04提供；空来源/真实适配器List允许启动，仅调用缺失能力时明确不可用。PRT-09/12只添加实际来源和厂商，PRT-14添加巡检/通知，不能成为M2启动必需Bean。

客户端的sourceRef不含可信主体/hash时，服务端先通过resolveReference取事实身份与hash，不能让Controller从请求体补出tenant/entity/打印内容。来源注册器按sourceTypes唯一选择Port，冲突启动失败。

### 3.1 身份、时间与不可变数据

```java
public record PrintActor(
    String tenantNo, String entityNo, String storeNo,
    String actorType, String actorNo, String idempotencyKey,
    Set<String> dataScopes
) {}

public record PrintFact(
    PrintFactRef ref, String orderNo, String subOrderNo,
    String triggerType, String triggerInstance, Instant occurredAt,
    int payloadSchemaVersion, FrozenJson immutablePayload,
    Printability printability, String blockCode, List<String> diagnostics
) {}

public record PrintMoney(String minor, String currency, int scale) {}
```

PrintOperation固定包含operationNo、type、status、targetType/targetNo、result、error、createdAt、finishedAt，error使用PrintOperationError(key,retryable,traceNo,conflicts)，与API原OperationVO一致。PrintDispatch另外携带adapterCode、providerScope、connectionSnapshot（冻结厂商账号/设备身份及受控端点），适配器不需要回查domain Mapper确定目标。

record声明仅展示结构，实现时必须校验非空/长度/枚举，List/Set做防御拷贝；PdfArtifact/PrivateArtifact里的byte[]在构造与访问时均防御拷贝。FrozenJson保存已验证的规范JSON文本与SHA256，不把可变JsonNode/Map作为“不可变快照”。JSON codec使用项目Jackson3（tools.jackson），拒绝重复键、非法数和超深/超大输入；不能hash后继续修改底层对象。

PrintActor只由可信边界工厂产生，商家读/下载每次从当前认证与字段授权重建；查询idempotencyKey可空，写命令必填。tenant沿现有一期MAIN语义，不引入新的租户体系。Ops目标由PrintOpsActor授权集合裁剪，Agent身份由独立过滤器产生，均不从请求字段“升级身份”。

公开时间使用Instant/RFC3339；Entity用UTC语义LocalDateTime。revision为Long，API预期版本由锁内CAS校验。金额minor为整数字符串，显示时用BigInteger/BigDecimal，禁止double；打印域不重算定价/税额/分摊。

## 4. 模板、布局与PDF类协作

```text
PrintSourceRegistry → PrintSourceFactsPort.snapshot
  → PrintSceneRegistry                 校验scene/source/schema/必显
  → DefaultPrintTemplateResolver       同scene＋variant解析版本
  → DefaultPrintDocumentCompiler       白名单业务内容＋强制系统插槽
  → DefaultPrintLayoutEngine           通过PrintFontMetricsPort测字宽
  → PrintLayout                        冻结坐标、分联、字体版本与hash
      ├→ FeiePrintAdapter / 本地代理     真实设备
      ├→ PdfReceiptRenderer             PDF副本
      └→ SimulationRunner               仅模拟轨迹及模拟文件
```

PrintFontMetricsPort的实现PdfBoxFontMetrics与PdfReceiptRenderer共用PdfFontBundle。domain不读取TTF/PDFBox；同fontBundleVersion/hash必须使用同一字形advance，PRT-03就提供度量，不等到PRT-06再换字体。

PrintDrawing为sealed interface，仅允许TextRun、RuleLine、ImageRun；页面坐标统一毫米，文字字号pt，渲染边界显式转换，禁止同时混用设备像素与毫米。ImageRun只含冻结内嵌图像及hash，不访问外部URL；第一版不支持的原语在发布/编译时拒绝，不能静默忽略。

PrintSystemSlotPolicy负责小票号、补打/加印、原票未知警示、模拟水印和PDF副本标识。PrintLayout里保存有界系统插槽的几何/style，补打只替换这些插槽；标识超出保留区域则明确阻断，不覆盖金额或偷偷重排业务块。源业务正文、原业务时间和模板版本保持不变。

PdfReceiptRenderer实现PrintPdfPort，输入只有PdfRenderInput（冻结布局、输出profile、文件身份与元数据），不注入PrintSourceFactsPort/订单服务。PDF字节固定元数据/ID与字体版本，同export重试保持相同hash。导出前检查50票/200页/20MiB，实际大小检查仍必须执行。

## 5. 任务状态机与事务责任

| 类 | 状态/副作用责任 |
|---|---|
| PrintStatePolicy | 纯decide(before, observation)，输出允许迁移/新状态/是否可释放；不访问数据库或真实时钟 |
| PrinterLeaseServiceImpl | 单物理机未决闸门、租约、fence与提交序列；失联不自动清闸 |
| PrintDispatchServiceImpl | 云端prepare事务→外网submit/query→apply事务；不持锁等待设备 |
| PrintReceiptServiceImpl | 验证后证据去重、冲突/乱序裁决、CAS更新、Outbox通知 |
| PrintBatchProjection | 原票真实结果与replacement解决状态分开计算 |
| PrintReprintServiceImpl | rootJob行锁防重复补打；跨request分批、同一操作原子创建 |
| PrintJobCommandServiceImpl | 人工确认/取消/原设备清闸必须有可信证据，不能只改状态 |
| PrintActionAppender | 先锁门店stream头，分配提交有序storeSequence并写审计 |

内部服务签名在逐类清单；例如：

```java
public interface PrintDispatchService {
    PrintWorkReport dispatchCloud(PrintWorkerBudget budget);
    void reconcileAttempt(PrintAttemptRef ref);
}

public interface PrintReceiptService {
    ReceiptAck ingest(VerifiedPrintReceipt receipt);
    PrintWorkReport applyPending(PrintWorkerBudget budget);
}
```

PrintStateModels只承载纯计算record（PrintStateSnapshot/PrintObservation/PrintTransition等），不能夹带Entity/Mapper。实际变更由Service在短事务内执行；update影响行数必须等于1，否则读取原状态判断幂等/冲突，不能忽略CAS失败。

锁顺序保持storeStream→printerNo排序的runtime→rootJobNo排序的任务→子行。迟到结果只作用于其所属attempt、原job/batch；释放runtime必须匹配activeAttemptNo＋fenceEpoch，不能清除下一任务闸门。明确NOT_ACCEPTED且不会晚执行才可重试；部分出纸走带风险补打。

### 5.1 云端与本地是两种执行方式

PrintDispatchPort只代表服务端主动调用的真实云适配器。LOCAL不通过该接口返回一个假的ACCEPTED；由PrintAgentProtocolServiceImpl在D04领取时准备同一生产attempt与lease，经D05本地可靠接收、D07受控证据推进状态。

PrintAdapterRegistry.requireCloud只返回真实云适配器；模拟Port不继承该接口。本地代理的DesktopPrintExecutor是独立本机接口，系统队列受理仅为ACCEPTED，无法确认出纸就保留UNKNOWN/人工核实。

## 6. 模拟与PDF独立封装

| 入口 | 数据 | 使用的核心组件 | 不可发生 |
|---|---|---|---|
| DefaultPrintSimulationFacade | PrnSimulationRun | 同模板/编译/布局、纯状态策略、SimulationFixtures | 创建生产request/job/attempt，发送真实设备命令 |
| SimulationRunner | 模拟事件轨迹 | SimulatedDispatchPort、受控虚拟时钟 | 把PASSED写为生产COMPLETED |
| DefaultPrintExportFacade | PrnExportTask | 冻结job/batch/simulation文档、字段权限集合 | 重新选模板、修改订单或任务状态 |
| PrintExportWorker | 生成中任务和私有对象 | PrintPdfPort、PrintArtifactStorePort | 在上传前无记账，或exists=true就当字节完整 |
| PrintExportRecoveryJob | 租约超时导出 | 固定key/epoch/hash/renderer与字体版本 | 换新订单内容生成另一个文件冒充原结果 |
| PrintExportCleanupJob | 已过期导出 | 先EXPIRED再幂等删对象 | 删除来源票据或改变打印结果 |

PrnSimulationRunMapper与PrnExportTaskMapper是各自持久化入口，不加等义Repository。模拟可写operation/actionLog和自身记录，生产request/batch/job/attempt/receipt/runtime及订单保持不变。

下载入口重新构造当前PrintActor，校验source读取、export及requiredDataScopes权限并集；不能凭创建时actor或历史operation成功永久放行。文件7天、元数据180天、下载授权60秒；URI及密钥不写日志。

M2的PrintArtifactJobHandlers和PrintArtifactWakeConsumer即装配模拟执行、PDF生成、恢复和清理。唤醒是优化，durable任务周期扫描才是恢复保障；Outbox consumer不在外层数据库事务里等待PDF/COS调用。M4的PrintJobHandlers只扩生产投递/设备对账，不能成为M2首次运行的依赖。

## 7. Entity与Mapper设计

22张prn_*表分别映射PrnAgent、PrnPrinter、PrnCredentialVersion、PrnPrinterRuntime、PrnTemplate、PrnTemplateVersion、PrnRoute、PrnRouteVersion、PrnScenePolicy、PrnPolicyVersion、PrnRequest、PrnBatch、PrnJob、PrnAttempt、PrnReceipt、PrnOperation、PrnIncident、PrnStoreStream、PrnActionLog、PrnReconcileCursor、PrnSimulationRun、PrnExportTask。

### 7.1 PrnRow与现有BaseEntity区别

```java
public abstract class PrnRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantNo;
    private String entityNo;
    @Version
    private Long revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

这是DDL映射说明，非已生成源码。当前BaseEntity另含version、deleted、createdBy、updatedBy；直接继承会访问不存在列，故PrnRow只映射本DDL六列，不扩展BaseEntity，不增加隐式逻辑删除。写入时用注入Clock显式初始化revision=0及UTC时间；审计操作者由已有实体专用字段/actionLog保存。需以实际MyBatis插入/更新SQL测试确认现有AuditMetaObjectHandler不会把不存在列写进去。

OrderPrintFact属于trade，不能extends PrnRow（否则反向依赖print-domain）；其自身声明与订单事实DDL完全一致的字段，也不直接extends旧BaseEntity。不能在print-domain再复制一个订单事实Entity。

实体JSON列映射String并由PrintJsonCodec验证；金额/精度按来源快照，不把JSON解析成Object后随意修改。不可变版本/快照的内容禁止update，状态/审计字段按允许的操作单独变更。

### 7.2 Mapper组织

```java
public final class PrintMappers {
    public interface PrnJobMapper extends BaseMapper<PrnJob> { }
    public interface PrnAttemptMapper extends BaseMapper<PrnAttempt> { }
    public interface PrnPrinterRuntimeMapper extends BaseMapper<PrnPrinterRuntime> { }
    // 其余19个内嵌Mapper见类结构JSON的tableMappings。
}
```

MapperScan必须包含这些嵌套接口并验证22个bean齐全；不另外生成22个同名独立Mapper文件。普通CRUD继承BaseMapper，自定义SQL仅用于带完整scope的行锁、CAS、到期扫描和必要批量投影。禁止用业务号调用selectById，禁止删除历史票据/回执来“清理异常”。

自定义CAS使用revision原值与revision+1，参数采用明确的字段/patch，不额外传带@Version的Entity给同一次更新，让插件重复处理版本。底层CAS失败必须向Service暴露；所有唯一键冲突按幂等身份判定，不能catch后直接当成功。

## 8. 订单事实与可靠事件类

OrderPrintFactRecorder只构建快照并分类printability，不渲染、不调外网；OrderPrintFactServiceImpl.recordPaid在源事务内锁定/保存不可变事实并发布OrderPrintFactReady。业务不支持的金额组成写BLOCKED/blockCode/diagnostics并继续提交事实事件；SQL或Outbox持久化失败仍按源事务规则失败。

OrderPrintSourceFactsPort位于shop-app.printbridge.port，委托trade的OrderPrintFactService，绝不直接注入OrderMapper。PrintFactReadyConsumer.handlerId固定print-fact-ready，PrintControlChangedConsumer为print-control-changed；不再同时消费旧OrderPaid/SubOrderPaid生成第二份票。

控制消费者调用PrintJobCommandService.applyControl(PrintControlFact)，该入口不注册为可伪造的商家HTTP命令。生成前和发包前使用executionGuard区分历史支付凭证、取消未投递作业票、需冲销、未决待核实；后续退款不能把曾经真实支付的历史收据改成从未支付。

PrintTaskCompleted/PrintBatchResolved/PrintIncidentOpened/PrintIncidentResolved仅表示真实打印/故障领域事件；PrintExportRequested/PrintSimulationRequested只是后台工作唤醒。通知消费者不把模拟结果当真实完成，领域事件不带完整顾客正文。

## 9. Controller、DTO和83API追溯

| Controller | API范围 | 注入接口 |
|---|---|---|
| BizPrintDeviceController | P01–P10 | PrintDeviceService |
| BizPrintAgentController | A01–A06 | PrintAgentService |
| BizPrintSceneController | C01–C04、C19–C20 | PrintScenePolicyService |
| BizPrintTemplateController | C05–C11、C18 | PrintTemplateService |
| BizPrintRouteController | C12–C17 | PrintRouteService |
| BizPrintRequestController | J01–J04 | PrintRequestService、PrintFacade |
| BizPrintJobQueryController | J05–J12 | PrintQueryService |
| BizPrintJobCommandController | J13–J21 | PrintReprintService、PrintJobCommandService |
| BizPrintMonitorController | M01–M05 | PrintMonitorService |
| BizPrintSimulationController | S01–S03 | PrintSimulationFacade |
| BizPrintExportController | E01–E04 | PrintExportFacade |
| OpsPrintController | M06–M09 | PrintOpsService |
| DevicePrintController | D01–D10 | PrintAgentProtocolService |
| PrintCallbackController | 厂商独立协议，不计83项 | PrintCallbackService |

DTO按PrintDeviceDtos、PrintAgentDtos、PrintConfigDtos、PrintJobDtos、PrintProtocolDtos、PrintMonitorDtos、PrintSimulationDtos、PrintExportDtos分组，内部record分别承载Req/Query/VO。不是为每个endpoint再建一个Facade、Service、Repository。分页统一PageData，不将Entity放进响应。

Controller的Java参数只包含路径、Body/Query、必要幂等头；当前actor/identity由可信工厂或过滤器取出后传给Service，不允许绑定客户端PrintActor。DTO不能重复携带路径业务号或客户端自报entityNo/actorNo。普通语义输入可以直接复用API record；Simulation/Export command含可信scope，必须在服务端从Req构造，不从Body反序列化可信身份。

PrintOperation/PrintJobView/SimulationView/ExportView是对应HTTP文档中OperationVO/PrintJobVO/SimulationVO/ExportVO的实际Java合同名，不另建两份相同内容的VO转换链。逐API的请求类型、Controller声明、Service签名以类结构JSON和逐类清单为准。

## 10. 本地执行端类责任

AgentExecutionLoop在本地先落LocalDispatchStore，再D05确认接收，发包前持久化SUBMITTING并复核session/lease/cancelRequested。LocalDispatchStore.receiveAndCommit检查唯一dispatchKey/内容hash一致；markSubmittingAndAppendEvent、recordObservationAndAppendEvent各在同一SQLite事务内更新本地状态与事件，先提交再发送/回报；applyLeaseGrant保存续租的新token和有效期。LocalDispatchStore的SQLite实现不复用服务端PrnJob Entity；只保存LocalDispatch/LocalEvent与本机队列引用。

AgentRecoveryCoordinator对重启后的SUBMITTING先查系统队列/设备结果；查不清则报告UNKNOWN，不重打。DesktopPrintExecutor通过Java Print Service的Graphics2D按PrintLayout和相同字体hash绘制系统队列，不依赖服务端print-channel，也不要求PDF先生成；字体资源随agent校验，缺字/缺字体在入队前拒绝。DesktopPrintExecutor仅返回本机观察，不把dataTransferred/spoolAccepted翻译为物理完成。AgentHttpClient持续补传已持久化证据，旧session不能领取新任务。LocalEvent保存attemptNo/sessionEpoch/fenceEpoch，补传按原会话分组；本地dispatch及事件在终态且所有事件ACK前不可删除。

WindowsAgentCredentialStore封装本机受保护存储；不将代理Token放明文配置、商家会话或票据。PrintAgentAuthenticationFilter只从Bearer和路由purpose调用PrintAgentAuthenticationService.authenticate，不碰PrnAgent实体/Mapper。D01配对前仅精确放行配对路径，由服务校验单次配对码；其他设备路径必须当前凭据有效。

服务返回当前agent/credentialVersion/sessionEpoch，协议服务再验证Body声明的执行代次。CLAIM/ACKNOWLEDGE/RENEW_LEASE等新执行必须当前session；REPORT_EVIDENCE可携带同代理历史attempt的原session，仍须当前有效凭据和历史归属匹配。轮换/撤销后的旧凭据不得因补传免鉴权；认证失败时原任务保留待核实，可由重新认证代理/人工证据恢复。OpaqueAgentCredential、leaseToken及含它们的record必须覆盖toString或使用脱敏日志。

D05/D06必带leaseToken，响应保存新的leaseUntil/leaseToken；D10取消/停止结果必须携带原dispatchKey/attemptNo、connectionRevision、fenceEpoch，PROBE可无任务身份。具体record已纳入JSON及清单，不只在文字中说明。

实际部署首Windows；Android如后续接入，替换本机存储/Executor与包装，接正式uni离线包，不改服务端业务合同。

## 11. 对开发任务的路径细化与验证

此前开发计划中的DefaultPrintFacade、SimulationRunner等名称保持；Service统一为API接口和domain.impl实现；PrintTransactionStore、SimulationRepository占位职责分别落到明确Mapper、PrintOperationServiceImpl与短事务服务；PrintDispatcher统一为PrintDispatchServiceImpl。OrderPrintFactServiceImpl固定放trade.order.printing.service.impl，来源桥接放printbridge.port。

类结构JSON为文件/方法真源，逐类清单是生成阅读版；任务仍为PRT-01..16，不把枚举/DTO类型数误当开发任务数。未实现的来源保持禁用，不因类清单存在即声称支持20个场景。

实施时必须新增PrintBoundaryTest、PrnEntityMappingTest、PrintMapperWiringTest、PrintApiClassAlignmentTest验证依赖白名单、23实体与DDL列、22个嵌套Mapper装配、83项端点与方法归属。对模拟隔离、迟到回执/取消、缺字、布局插槽、PDF上传崩溃、下载撤权运行既有计划的行为测试。当前仅完成设计和静态一致性检查，不是Java编译或运行验证。
