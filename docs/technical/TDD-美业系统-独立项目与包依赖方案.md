# 美业系统独立 Java 项目与包依赖方案

> 状态：架构与目录设计，待评审 · 2026-10-20 · 未创建美业Java工程、POM、业务源码或数据库迁移。
> 依据：[餐饮独立项目与包依赖方案](./TDD-餐饮系统-独立项目与包依赖方案.md)、[实际餐饮聚合POM](../../backend-plugins/shop-industry-food/pom.xml)、[Java规范§七](../../.claude/skills/ai-shop-dev-standards/references/java.md)。
> 明细：[Java项目结构与类职责](./design/美业系统-Java项目结构与类职责.md)，由[结构JSON](./design/schema/beauty-java-project-design.json)生成；业务依据：[API清单](./design/美业系统-API清单.md)、[领域对象](./design/美业系统-领域对象与聚合边界.md)、[数据库](./design/美业系统-数据库结构设计.md)。

> 方法级细化：[Java类与方法清单](./design/美业系统-Java类与方法清单.md) · [逐API及Mapper方法](./design/美业系统-Controller-Service-Mapper方法清单.md)，沿零售统一规范，明确美业、共用候选接口与共用合同边界。

> 实施任务：[美业后端开发计划](./TDD-美业系统-后端开发计划.md) · [任务对照索引](./design/美业系统-开发任务对照索引.md)，先工程/数据，当前未实施。

## 一、结论与现状

**美业作为同仓独立Maven reactor：`backend-plugins/shop-industry-beauty`，采用`beauty-api/core/web/channel/server`五模块，Java根包为`ai.neargo.shop.beauty`。** 沿餐饮的交付边界组织，不按六端、20业务模块或11个聚合分别拆Maven工程。五模块是构建/接入/部署边界，业务模块是职责边界，聚合是业务一致性边界。

本轮采用`beauty`统一命名，与bty表、美业API及文档对应；不并行建立`service-industry`、`salon`等同义工程。美业不依赖餐饮工程，两个行业平级消费共用能力。

| 候选组织方式 | 判断 |
|---|---|
| 把美业包放在shop-core/shop-app | 减少构件但不满足美业独立交付目标 |
| 一个独立beauty jar同时装接口、持久化、Controller和启动 | 目录短，但宿主引Controller时会带业务数据库与实现，远程/本地难隔离 |
| **一个独立reactor，五个边界明确的jar** | 采用；与餐饮一致，既可嵌入也可独立业务运行，接口/协议不依赖持久化实现 |

### 1.1 实际核查与设计区别

- 当前[餐饮目录](../../backend-plugins/shop-industry-food/README.md)已有五POM、core/web条件配置、独立启动类与架构/装配测试源码。餐饮旧设计末尾“尚无项目目录”的历史描述已与当前工作区不同；本方案依据实际文件，不回写其历史验证结论，也未运行餐饮Maven来推断其营业能力已可用。
- [平台父POM](../../backend/pom.xml)沿neargo-parent管理Java21、Boot4及依赖版本，已有shop-base、shop-base-auth、shop-store-mybatis、svc-client与各业务提供方。美业沿同一基座，不升级父版本或复制零售源码。
- 美业目前只有目标需求、领域、数据库与API方案。本方案中的目录、类、配置及内部协议均是候选设计；已有接口和候选接口仍按API清单分开。

### 1.2 验收目标

| 编号 | 工程结构应满足 |
|---|---|
| BPKG-01 | 独立构建五构件，只有beauty-server是美业可执行jar；其余均是普通jar |
| BPKG-02 | Service/Req/VO在api，Impl/Entity/Mapper在core，用户Controller在web，协议客户端在channel |
| BPKG-03 | api不依赖core/web/channel/server；web不依赖core/server；channel不依赖core/web/server；整体无环 |
| BPKG-04 | 不依赖food-*、shop-app可执行jar、shop-core或支付/库存业务实现；权威SPI唯一、本地/远程实现互斥 |
| BPKG-05 | 20模块、160个API/合同接入项均有所有者；共享人员/资源HTTP不被搬入beauty-web |
| BPKG-06 | 仅25项bty候选表映射到美业；14项共用人员/资源结构不进入BeautyMappers或美业迁移 |
| BPKG-07 | enabled关闭不装业务Controller/Mapper/Job；standalone宿主无beauty-core，独立server无用户web |
| BPKG-08 | 预约工作台看得到具体任务与规范占用，快照不参与资源仲裁；未加载/延迟不当空闲 |

## 二、目录与构件

![五模块依赖图](./diagrams/beauty-java-module-dependencies.svg)

图从结构JSON生成。Web和业务实现共同依赖api；server只装配内部业务。用户访问仍由平台三端入口接入，独立部署不会自动生成美业专属登录体系。若将来需要美业直接对外的独立入口，应另评审外部鉴权与网关装配，不在本次复制一份三端Controller。

以下是**目标目录说明**，不是现有文件清单；完整包名与API归属由生成清单维护。

```text
backend-plugins/
├── shop-industry-food/                 # 已有餐饮reactor，平级参考
└── shop-industry-beauty/               # 候选独立美业reactor
    ├── pom.xml                        # packaging=pom，统一五构件版本
    ├── README.md                      # 构建、部署组合、依赖与开关
    ├── beauty-api/
    │   ├── pom.xml
    │   └── src/main/java/ai/neargo/shop/
    │       ├── beauty/
    │       │   ├── service/           # AppointmentService等接口
    │       │   └── dto/               # Req、Query、VO、内部共同协议
    │       └── spi/beauty/            # 仅真实需要的美业自有跨域合同
    ├── beauty-core/
    │   ├── pom.xml
    │   ├── src/main/java/ai/neargo/shop/beauty/
    │   │   ├── service/impl/          # 各XxxServiceImpl
    │   │   ├── entity/                # BtyAppointment等候选Entity
    │   │   ├── mapper/                # BeautyMappers及嵌套Mapper
    │   │   ├── policy/                # 服务规则/资格要求等业务辅助
    │   │   ├── statemachine/          # 预约、任务、提交意图状态约束
    │   │   ├── assembler/             # 日历、账单、打印内容等组装
    │   │   ├── event/                 # 去重消费与领域事实接线
    │   │   ├── job/                   # 原号恢复、业务协调JobHandler
    │   │   ├── api/internal/          # 仅内部业务RPC入口
    │   │   └── config/                # 业务、持久化与内部入口条件配置
    │   ├── src/main/resources/
    │   │   ├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │   │   └── db/industry/beauty/     # 仅未来审核后的bty迁移
    │   └── src/test/java/ai/neargo/shop/beauty/
    │       ├── scenario/             # 服务流程与原号恢复
    │       └── concurrency/          # 版本、来源分配、协调一致性
    ├── beauty-web/
    │   ├── pom.xml
    │   ├── src/main/java/ai/neargo/shop/
    │   │   ├── beauty/
    │   │   │   ├── api/mp/            # 消费者公开/本人入口
    │   │   │   ├── api/biz/           # 前台/技师/管理/老板共同入口
    │   │   │   ├── api/ops/           # 平台受权治理与诊断
    │   │   │   └── config/            # Web自动配置，不引用core配置类
    │   │   └── beautyclient/
    │   │       ├── impl/             # RemoteXxxService implements XxxService
    │   │       └── BeautyClientConfiguration.java
    │   ├── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │   └── src/test/java/ai/neargo/shop/beauty/scenario/
    ├── beauty-channel/
    │   ├── pom.xml
    │   ├── src/main/java/ai/neargo/shop/beauty/
    │   │   ├── client/                # Http Interface/RestClient协议组件
    │   │   ├── port/                  # 权威SPI远程实现，按配置选装
    │   │   └── adapter/               # 第三方预约签名与供应商协议
    │   └── src/test/java/ai/neargo/shop/beauty/contract/
    └── beauty-server/
        ├── pom.xml                    # 仅此模块执行repackage
        ├── src/main/java/ai/neargo/shop/beauty/server/
        │   ├── BeautyApplication.java
        │   └── config/               # 内部安全、数据源、readiness
        ├── src/main/resources/application.yml
        └── src/test/java/ai/neargo/shop/beauty/
            ├── architecture/         # 整个reactor架构扫描
            └── scenario/             # 独立部署与内部入口验证
```

只因真实职责创建辅助包/类，不预建空Controller、每个表一个Service或完整DDD六层。先保持与餐饮相同的service/dto/entity/mapper结构，通过机器清单登记20模块职责；暂不把appointment、care、commission各拆出一套多层包树。

### 2.1 POM与构建规则

- groupId为`ai.neargo.shop`；聚合artifactId为`shop-industry-beauty`，子构件为`beauty-api/core/web/channel/server`。聚合与子POM都沿`shop-parent → neargo-parent`版本管理，平台兼容基线为当前`0.1.0-SNAPSHOT`，五构件一起构建发布；不新增美业BOM、不自行固定第三方版本。
- 与餐饮当前使用宿主相对父POM路径的骨架区别：美业设计按已发布/安装的shop-parent解析，parent的`relativePath`置空，避免独立reactor必须读取`../../backend`源码。这只消除源码路径依赖，不能消除平台构件与父POM依赖；受控仓库或本地缓存必须预先具备兼容版本。
- 只有内部构件依赖可沿`${project.version}`；直接使用的框架/平台类型显式声明依赖。api只使用协议/基础类型，不能导入本域Entity/Mapper/Impl或Web注解；`@HttpExchange`放channel。
- Web/Security/Validation、MyBatis-Plus、Boot自动配置、Actuator、Flyway/MySQL等按模块实际使用及父BOM声明。JobHandler首次实现时core再加job-api；不为将来报表/打印提前引入供应商SDK。
- 独立构建目标命令是`mvn -f backend-plugins/shop-industry-beauty/pom.xml clean verify`，前提是工程实现与平台构件已准备；本轮没有该POM，**未运行该命令**。不把“同仓独立reactor”解释成已经拥有独立Git仓库、数据库或部署实例。

## 三、业务所有权与共享边界

20模块逐项Service主责、完整包名、44个候选Controller及160项API/合同归属见[生成清单](./design/美业系统-Java项目结构与类职责.md)。这160项含106个候选HTTP接口与54个共用合同接入点；共用HTTP仍由其所有者接入，不为凑完整接口数生成美业Controller。

| 所有者 | 持有内容 | 美业允许做什么 |
|---|---|---|
| beauty-core | 美业营业规则/执行定义、预约安排、工单、任务步骤、实际参与、提交来源绑定、护理、疗程计划、业绩提成 | 保存自身事实与规则，经合法引用读共享信息 |
| 共用人员/资源提供方，目标落shop-merchant既有边界 | 人员主档/可选账号关系/任职岗位资格、排班、规范资源、容量桶、hold、occupation | 调用匹配/查询/原子资源命令，不引Mch Entity/Mapper或直接写mch表 |
| product/Core/Pay/inventory等权威所有者 | SKU门店价格、普通订单、可信服务来源、付款、储值、余次、使用账、库存、耗材 | 受权调用正式Service/SPI并保留原号/版本/结果；不在beauty-core建同义账 |
| 共用营业/协作能力 | 等候、回访任务、案件/审批、班次钱箱、日结对账、导出、通知/打印任务与设备 | 美业接待/回访/争议接口只薄编排或关联服务事实；不另造同义聚合/持久化账 |
| shop-app装配与跨域桥接 | 现有三端登录权限、行业挂载、现有本地业务到权威契约的适配 | 必要胶水候选放`ai.neargo.shop.industrybridge.beauty.port`；不复制美业业务Controller或向美业反向暴露宿主实现 |

共用新能力尚未落地时，先在其所有者补正式契约和实现，而不是临时写在beauty-core再要求餐饮依赖美业。共用人员/资源的14项结构与所有`mch_*`表从美业Entity/Mapper/迁移中排除。人员有无登录不影响统一人员号；业务停用与账号停用仍分别处理。

### 3.1 SPI唯一归属与现状

| 能力 | 已核查的合同依据 | 本方案处理 |
|---|---|---|
| 商品 | [GoodsQueryPort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/product/GoodsQueryPort.java) | 使用原接口和门店SKU快照，不建BeautyGoodsPort副本；不足方法由商品所有者评审 |
| 门店 | [StoreDirectoryPort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/user/StoreDirectoryPort.java) | 沿现有完整包名使用，不因逻辑门店归merchant就伪造spi.merchant路径 |
| 旧预约时段 | [AppointmentSlotPort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/user/AppointmentSlotPort.java) | 旧tryBook不具备人员/多资源能力；新仲裁合同按资源所有者评审，不冒充已实现 |
| 资产来源 | [RechargeSourcePort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/storedvalue/RechargeSourcePort.java)、[EntitlementUseSourcePort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/entitlement/EntitlementUseSourcePort.java) | 先确认真实来源链与授权；不把它们直接当“美业可随意扣款/扣次”的通用命令 |
| 美业对外事实 | 候选`ai.neargo.shop.spi.beauty`，源码归beauty-api | 仅当核心/报表等确需消费美业事实时新增；不重复商品、人员、资源、支付SPI |

共享新增契约默认按现有shop-base/spi的所属能力命名管理；若后续拆独立契约jar，保持唯一权威接口和包语义。不要新建包罗所有行业的巨型IndustryService或通用WorkflowPort来隐藏不同不变量。

## 四、业务类和DTO落点

### 4.1 11聚合如何承载

| 逻辑聚合 | beauty-api接口，包前缀ai.neargo.shop.beauty.service | beauty-core职责 |
|---|---|---|
| BeautyStorePolicy | StorePolicyService | 政策版本冻结与门店能力校验，不修改共用门店主档 |
| ServiceDefinition | ServiceDefinitionService | 执行规则与要求版本，不计算第二份商品价格 |
| Appointment | AppointmentService | 预览、创建、改约、取消、安排版本与共用资源协调 |
| WorkOrder | WorkOrderService | 服务范围、任务引用、账单组合、关闭检查；任务不塞进一个巨型事务 |
| ServiceTask | ServiceTaskService | 开始/步骤/暂停/实际数量/交接参与与完工，重验资格和来源 |
| ServiceSubmission | ServiceSubmissionService | 持久意图、来源分配/绑定版本、原号查询/恢复，UNKNOWN不新号重做 |
| CustomerCareProfile | CareProfileService | 最少必要护理事实、加密/私有媒体引用、追加更正与权限 |
| TreatmentPlan | TreatmentPlanService | 分次服务计划及工单引用，余次/有效期/核销仍归共用Pay |
| PerformanceAllocation | PerformanceService | 实际参与及可追溯业绩分摊，不把售卡收入自动当服务产出 |
| CommissionPolicy | CommissionPolicyService | 提成口径及不可变规则版本 |
| CommissionEntry | CommissionService | 计提、核对、追加冲回、发放凭证，不创建工资代付通道 |

接口完整路径规则由生成清单明确；各Impl在`ai.neargo.shop.beauty.service.impl`且同名后缀Impl，直接调用本域BeautyMappers。Service可按实际职责组合美业内部能力，不必为每个聚合再生成DomainService/ApplicationService/Repository三层。

### 4.2 预约切片示例

| 类完整名 | 模块 | 职责 |
|---|---|---|
| ai.neargo.shop.beauty.api.biz.BizAppointmentController | beauty-web | BizContext、权限、代客范围、Req校验，调用AppointmentService |
| ai.neargo.shop.beauty.api.mp.MpAppointmentController | beauty-web | 本人身份、公开/本人预约范围；不允许传customerNo提升身份 |
| ai.neargo.shop.beauty.service.AppointmentService | beauty-api | preview/create/change/cancel/query等稳定签名 |
| ai.neargo.shop.beauty.dto.CreateAppointmentReq | beauty-api | 项目、时段、指定意愿及previewNo等业务输入，不接受自报主体/操作人 |
| ai.neargo.shop.beauty.dto.AppointmentDetailVO | beauty-api | 安排版本、项目任务引用、来源/动作与脱敏信息 |
| ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl | beauty-core | 校验与持久化提交意图，调用共享原子资源合同，保存安排事实 |
| ai.neargo.shop.beauty.entity.BtyAppointment | beauty-core | bty_appointment映射，沿BaseEntity |
| ai.neargo.shop.beauty.mapper.BeautyMappers.AppointmentMapper | beauty-core | 限租户/主体/门店的本域SQL、锁与版本CAS |
| ai.neargo.shop.beautyclient.impl.RemoteAppointmentService | beauty-web | standalone时实现同一Service，只做调用/异常映射与上下文传递 |
| ai.neargo.shop.beauty.client.AppointmentInternalClient | beauty-channel | HTTP协议接口，调用受保护内部预约接口 |
| ai.neargo.shop.beauty.api.internal.InternalAppointmentController | beauty-core | 内部服务调用接入与委托范围校验，调用同一Service，不写Mapper |

Controller按业务资源命名，不机械加Beauty/Food前缀，也不以BAPI编号命名。多行业相同短类名可能导致默认Spring Bean名冲突，实施时使用受控显式导入/合适的全限定Bean命名策略并测试；包名不同本身不保证Bean名不冲突。

## 五、预约工作台的专门落点

| 类完整名 | 模块 | 职责 |
|---|---|---|
| ai.neargo.shop.beauty.api.biz.BizAppointmentWorkbenchController | beauty-web | summary/calendar/pages/exceptions等工作台查询，不接其他业务写接口 |
| ai.neargo.shop.beauty.service.AppointmentWorkbenchQueryService | beauty-api | 窗口快照、分页、水位、未分派与异常摘要签名 |
| ai.neargo.shop.beauty.dto.CalendarSnapshotVO | beauty-api | 资源泳道、规范占用、预约与任务摘要、完整性标记及快照令牌 |
| ai.neargo.shop.beauty.service.impl.AppointmentWorkbenchQueryServiceImpl | beauty-core | 受权资源目录/占用查询＋本域预约任务投影组合，生成不可变短期快照 |
| ai.neargo.shop.beauty.assembler.CalendarSnapshotAssembler | beauty-core | 组装卡片/泳道，引用稳定任务与occupation标识，避免重复扣量 |
| ai.neargo.shop.beauty.service.AppointmentAvailabilityService | beauty-api | 匹配候选时段/人员/工位；不把快照空白当可约承诺 |
| ai.neargo.shop.beauty.service.ResourceChangeService | beauty-api | 资源变更预览和提交协调，真正容量替换调用共享仲裁 |

快照缓存按主体/门店、操作者受权范围与权限版本、窗口、筛选hash隔离，过期或权限变化后重载。占用权威版本和预约/任务投影水位分别输出，不宣称一个跨域数据库快照覆盖全部数据。默认15秒轮询、2分钟快照TTL及窗口/分页参数沿[工作台契约](./design/美业系统-预约工作台与日历API设计.md)，均须实施压测确认。

同一人员跨店唯一STAFF资源；无他店业务查看权限时只显示匿名忙碌，不暴露对方预约/任务号。实际参与时间线与计划占用分开，持有原资源时不重复计容量；超时未知结束由资源所有者的open occupation guard仲裁。未分派任务/预约独立待办展示，不生成虚假的人员占用。工作台不新建一套人员、资源表或通用工作流引擎。

## 六、部署、配置和内部安全

### 6.1 两种部署组合

```text
embedded（美业嵌入）
  shop-app → beauty-web → beauty-api
  shop-app → beauty-core → beauty-api + 共享持久化
  本地权威SPI由平台装配；不重复激活远程同接口实现。

standalone（美业独立业务进程）
  shop-app → beauty-web → RemoteXxxService → beauty-channel
                                               ↓ 内部HTTP
  beauty-server → beauty-core → beauty-api
                → beauty-channel → 正式共享服务内部合同
```

两种模式不改变用户API语义、业务号、幂等键与DTO。不要求支付/库存部署方式与美业同一个开关；美业嵌入不等于可以跨独立Pay/库存数据库直写。

| 候选配置/装配 | 约束 |
|---|---|
| shop.beauty.enabled | 默认false；false时core/web的Controller、Mapper、Job、消费者全部不注册 |
| shop.beauty.deployment | enabled时显式embedded/standalone，缺少或非法值拒绝就绪；只在装配层选择，不散进业务方法 |
| beauty-web自动配置 | 自有Web条件，不引用core内的Properties/注解；C/B路由与ops路由沿平台profile及realm隔离 |
| beauty-core自动配置 | 本域Mapper扫描、持久化与Job条件明确；内部Controller仅standalone内部接入配置激活 |
| beauty-channel组件 | 默认只是协议组件，按调用方场景装配必要远程Service/Port；不自动覆盖宿主本地提供方 |
| beauty-server扫描 | 主扫描限定启动包，通过自动配置显式导入业务与内部安全；不扫描整片ai.neargo.shop、不引beauty-web/shop-app |

用户Controller由beauty-web交付，内部Controller由core随业务服务交付，server提供内部安全链。core因此在内部接线切片需要相应Web API依赖，api/channel仍不引Web starter；不能为了搬内部Controller把用户web挂到server。

内部HTTP使用平台已有svc-client/内部调用基础，需服务身份、时间/重放防护及可信委托上下文。商家/顾客动作还须携带可验证的tenant/entity/store、操作者/本人、动作范围与权限版本，服务端重验业务范围；不能只凭内部密钥接受任意请求体中的operator或customer授权。远程重试保留原幂等键与requestHash；超时先查原operationNo/sourceNo。

readiness按启用能力逐项核查必需共享合同及配置。缺少资源原子仲裁、来源/支付查询或内部身份时，受影响能力拒绝启用；不提供成功的no-op Port。技术诊断/恢复不能代顾客授权、代技师完工或任意改资金账。

## 七、持久化、迁移与跨域一致性

美业首期沿现有核心主库连接保存bty表，独立服务不自动等于独立数据库。beauty-core只读写本域bty数据；商品/订单/人员/资源同库也不能跨域直接Mapper或SQL联表更新。工作台所需共享数据经投影/查询合同取得。后续拆库需另评审事务与历史迁移，不由目录规划提前承诺。

- 美业迁移资源候选位置为`beauty-core/src/main/resources/db/industry/beauty/`，历史表候选为`bty_flyway_history`，只收未来审核后的bty变更，沿主库连接但与默认core迁移及fnb历史分开。
- 共用mch人员/资源结构由merchant/资源所有者迁移，复用其既有历史策略；不把39项设计源整个转成美业V1。旧美业储值SQL不因文件名相近就整份导入，不重复创建资金/储值账户。
- 迁移只允许一个受控执行者；embedded/standalone不能同时抢着执行。主库共享时不得替换平台默认Flyway Bean或在启动中repair/clean；默认走部署明确安排的迁移流程，首个V1在执行前完成审查，执行后冻结。
- ServiceImpl本域短事务保存意图/事实/inbox/outbox；跨资源所有者、Core、Pay、库存使用可恢复协议，不在HTTP调用外包一个大@Transactional宣称全成全败。
- 来源分配guard的总量/版本CAS、任务绑定修订、工单关闭前冻结范围沿数据库及领域设计。跨域原子资源hold由共用资源实现，beauty-channel不持事务/容量桶、不做自动超卖补偿。
- 资源hold过期释放由共用资源所有者负责；美业Job只处理其预约/工单影响与原号恢复，不再跑一个直接释放同一资源账的第二所有者。

## 八、与餐饮的对应关系及实施顺序

| 餐饮目录/职责 | 美业对应 | 共性/差异 |
|---|---|---|
| food-api / food-core / food-web / food-channel / food-server | beauty-api / beauty-core / beauty-web / beauty-channel / beauty-server | 相同交付和依赖方向，无相互依赖 |
| 桌台/就餐/厨房任务 | 预约/接待/工单/服务任务 | 各自状态和事实；共享资源仲裁可复用，不共享同一业务状态机 |
| fnb实体与迁移 | bty实体与迁移 | 各自只维护本域，独立历史；美业无已执行V1需要从宿主移动 |
| 共用商品、订单、付款、库存、打印 | 同一共用所有者 | 不复制餐饮适配器中的行业判断；仅可复用正式公共协议组件 |
| 原号下单恢复与厨房执行 | 提交来源恢复与真实服务完工 | 幂等/恢复机制复用，领域动作独立 |
| 餐牌/营业配置 | 项目目录引用/执行定义/预约政策 | 目录销售时段可共用，护理步骤与执行要求归美业 |

实施先后建议作为后续计划输入，不是本次已执行步骤：①五模块与架构/装配验证；②共享人员/资源合同补齐；③执行定义、预约日历与改约；④接待工单、任务和普通先付来源闭环；⑤原号恢复/通知/打印；⑥按P2/P3/P4逐项接资产、护理、经营与高级服务。每个阶段只创建当期需要的类，不一次生成44个Controller空壳。

### 8.1 实施后必须验证

| 验证面 | 必须验证的行为 |
|---|---|
| reactor依赖 | 五模块POM/源码及传递业务实现依赖无环；禁止边的反例会失败，而非只扫server自身 |
| 实际Bean与路由 | disabled无注册；web无core可装配；独立server不存在用户路由；profile/realm正确；Bean名不跨行业冲突 |
| 数据所有权 | BeautyMappers及迁移只含bty；无跨域Entity/Mapper、Pay/库存直连；同库也受约束 |
| 独立构建 | 缓存/受控仓库提供平台构件后，不读取backend/shop-app源码即可编译打包；只有server repackage |
| 并发与恢复 | 同人跨店抢位、完整组合替换、来源分配上限、关闭冻结、未知查原号，均在真实仲裁实现上验证 |
| 工作台与权限 | 具体任务/泳道对应正确、快照续页同水位、延迟/过期不当空闲、他店忙碌匿名化、授权撤回 |
| 迁移与运行 | 隔离测试库验证历史/唯一迁移执行；内部安全fail closed，必需提供方缺失不能假就绪 |

本轮验证范围：结构生成器检查五模块依赖无环、全部20模块与160个API/合同有归属、25项私有表覆盖且共享表排除；检查本地文档链接、索引、状态行及SVG渲染。Java编译、Bean/HTTP装配、迁移与业务并发测试均留待工程实施，不能以设计检查替代。
