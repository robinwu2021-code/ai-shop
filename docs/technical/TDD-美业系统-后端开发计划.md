# 美业系统后端开发任务计划

> 状态：实施中 · 2026-10-25 · 最新优先级见§0.3：先现金/线下美业核心闭环，支付链条及依赖能力后置；原P2～P4目标保留，先代码/审查/测试/编译再统一部署；不再以逐模块部署通过作为下一模块编码前置。BM01～BM03历史隔离部署结果保留；BM04 Task54～59代码阶段已验收，60A-A1架构收尾已验收，A2已代码验收，A3已代码验收，A4已代码验收，A5已代码验收，A5b已按范围验收，A5c报价误连已修复验收，A6已代码验收，A7真实身份及资源组合已代码验收，A8词条已按范围验收，A8b会员码客户端补齐并通过B端类型检查，A9权限及生成契约已代码验收，A10确认夹具已代码验收，A11告警与SQL换行收尾已代码验收，BM04模块代码关口已通过，BM05服务作业方案深化中，整体部署待统一关口。完整作业和任务日历尚未完成。
> **执行方式：** 使用`superpowers:subagent-driven-development`逐任务实施及独立审查，每步使用复现过的红灯/绿灯证据；本会话只推进美业。
> **Spec：** [功能矩阵](../requirements/美业系统-多端功能矩阵.md) · [独立Java结构](./TDD-美业系统-独立项目与包依赖方案.md) · [API清单](./design/美业系统-API清单.md) · [类与方法](./design/美业系统-Java类与方法清单.md) · [逐API方法](./design/美业系统-Controller-Service-Mapper方法清单.md) · [数据库设计](./design/美业系统-数据库结构设计.md)。

## 当前执行记录（2026-10-22）

实现位于独立 beauty-data worktree，保护原工作区未提交的餐饮与前端变更。代码入口：[美业工程](../../backend-plugins/shop-industry-beauty/README.md)。

| 任务 | 实施状态 | 实际交付与边界 |
|---|---|---|
| ENG-01 | 完成 | 五构件可独立构建；只有server可执行；13项完整依赖架构测试及非法依赖反例 |
| ENG-02 | 完成 | 默认关闭、显式部署形态；宿主retail/embedded/standalone验证；独立服务无用户入口；实际JAR无数据库启动及200/503探针 |
| DATA-01 | 完成 | 首批16表归属、BaseEntity字段、tenant/entity索引、源行guard身份与可空唯一键审计；共享14项及P3/P4留在原任务 |
| DATA-02 | 完成 | 冻结V1、专属历史；MySQL实际DDL与Flyway重入/漂移测试；拒绝无归属旧美业表；平台Flyway共存 |
| DATA-03 | P1完成 | 主工厂/主事务/审计接线、16项P1实体数据域及真实SQL父链范围验证完成；后续阶段随各批联验 |
| DATA-04 | 本批切片完成 | 政策/定义5项实体Mapper；不可变发布、版本CAS、历史查询和父链范围已真实SQL验收，实际记录见§8 |
| ENG-03 | 部分完成 | 政策/定义可信入口、幂等/审计/outbox及双部署已验收，后续聚合仍须各自完成业务级前置 |
| POL-01 / DEF-01（BM-01） | 后端模块完成，隔离部署通过 | 政策/定义/顾客须知/公开门店及服务、共用销售目录；完整记录见§22，未交付新页面或预约营业闭环 |
| DATA-05/06 | 数据层完成 | 7项实体、15个原始Mapper方法、25项真实MySQL用例；见§9，不含预约/工单业务Service与工作台API |
| DATA-07 | 数据层完成 | 3项新实体、9条原始Mapper方法，复用已有动作日志；21项真实MySQL通过，见§10 |
| SHR-01a | 基础切片完成 | 共用人员5表及内部事实查询，见§11；SHR-01/02整体、写维护和远程接入仍未完成 |
| SHR-02a | 基础切片完成 | 规范资源主档及内部事实查询，见§12；排班/占用仲裁及维护入口后续 |
| SHR-01b | 基础切片完成 | 周班表及内部窗口查询、变更历史，见§13；审批/写维护与完整SHR-01/02后续 |
| SHR-02b | 02b-1a/1b/1c、2a、2b-1及2b-2a基础完成 | 归一/持久命令/锁内准入/来源消费防线及独立行业共用合同已交付；详细方案§5.4–5.9；原子预留、真实美业来源Service、端点和完整部署已由Task50–53及§31验收；实际服务占用仍属BM05 |
| BM03-a1 | 来源存储检查点完成 | 私有V2追加2表/2实体Mapper，来源冻结、拒绝形状与原预约写者保护；最终记录见§26，真实授权与预约业务闭环未完成 |
| BM03-a2-1 | 共用核验读取检查点完成 | 两个共用Port/实际owner/机器协议及美业远程；原始结果、异常503及实际宿主Mapper接线验收；来源provider仍待a2-2，见§27 |
| BM03预约后端（BM-03） | 后端模块完成，独立审查与隔离部署通过 | Task50–53及统一修复复审通过，真实来源/创建改约取消爽约/当前权限/原号与定时恢复落地；embedded/standalone及默认零售关口见§31，不含完整任务日历/UI |
| SHR-03及后续业务 | 待逐项闭环 | 已使用部分商品/公共写守卫合同，未完成订单/资金/履约/通知打印的整体接入；预约/日历/任务营业闭环未完成 |

首批基础阶段验证（后续批次增量见§8、§9）：独立reactor 42项测试通过，Node生成器6项通过，隔离MySQL DDL约束通过，23项真实JDBC IT通过。
宿主retail/embedded各6通过＋1个不适用形态用例跳过，standalone 7项全部通过；无H2替代MySQL约束结论。
设计API/Java/数据库/任务索引生成校验通过，SQL方言守卫无新增命中。独立服务readiness仍为OUT_OF_SERVICE。
全局文档守卫为5通过/3失败，剩余为既有方案输入中的Mermaid基线、餐饮断链和索引欠账；美业生成与链接检查不在失败清单中，未将全局守卫标记通过。

TDD证据：缺工程/配置→架构与装配绿灯；缺SQL生成器→6项绿灯；缺专属validator→真实MySQL绿灯；缺实体→政策3项绿灯。
复核修复均先增加反例：默认关闭数据源抢装、默认值/自增/主键/普通及前缀索引漂移、ACTIVE/INACTIVE状态合同。
CHECK比较使用闭合语法树保留运算优先级，5项专门测试通过。审查最初提出的非布尔超量CHECK例子被MySQL拒绝，未据此声称已接受超量。

取舍：先在隔离数据库完成持久化接线与政策切片；正式写入口仍由ENG-03控制，未绕过身份、幂等或outbox前置。
未连接任何共享营业库。目标库发布前由受控迁移执行者只读核对归属，未知历史直接拒绝；P3提成跨组仲裁仍是PERF-01前置。

**Goal：** 先完成美业工程与数据层，再逐步交付“项目定义→人员资源匹配→预约/日历→接待工单→先付服务/履约”的完整闭环，之后按P2/P3/P4扩展。

**Architecture：** 同仓独立reactor `backend-plugins/shop-industry-beauty`，beauty-api/core/web/channel/server五模块。Service接口与DTO在api，业务/Entity/Mapper在core，用户Controller在web，协议适配在channel，server仅独立内部业务启动。商品、订单、资金、人员资源、库存、通知打印由原所有者提供，不复制账本。

**Tech Stack：** 沿shop-parent/neargo-parent的Java21、Boot4、MyBatis-Plus、Flyway及现有认证/数据域/任务/outbox/可观测设施；不自行升级基础版本或建立第二套规范。

**任务真源：** [开发任务JSON](./design/schema/beauty-development-plan.json)；[完整任务/API/表/功能对照索引](./design/美业系统-开发任务对照索引.md)。索引由`python3 scripts/gen-beauty-development-plan.py`生成，校验追加`--check`。

## §0 当前事实与计划边界

目标设计包含20模块118项功能、160项API/合同（95个美业HTTP、11个共用候选HTTP、54个共用合同接入点），44个美业Controller、28组Service/Impl、101条Service方法、25个Mapper及38条自定义Mapper方法。**这些数量是目标清单，不是已实现接口数量。** 当前独立Java五模块、P1数据层及部分业务/共享基础已交付；已完成范围见当前执行记录和§21，初始“没有独立工程”的核查结论仅适用于开发前基线。未访问营业库，不推定目标环境的历史表或迁移状态。

此前误入餐饮的桌台/菜品/订单安排不作为美业任务，餐饮NEXT-01～04的进度不转记到美业。美业原计划从ENG-01开始，现已交付工程与多个基础切片；餐饮作为五模块与薄适配的参考。本轮不继续餐饮NEXT-05，也不改其未提交代码或迁移。

| 数据结构 | 数量 | 本计划如何交付 |
|---|---:|---|
| 美业P1 | 16项bty结构 | 首批审计/迁移/Entity/Mapper，DATA-01～07 |
| 美业P3 | 7项bty结构 | CARE-01护理3项、PERF-01业绩提成4项；当期追加迁移 |
| 美业P4 | 2项bty结构 | PLAN-01疗程服务计划2项；不提前建余次账户 |
| 共用人员/排班/资源 | 16项目标结构 | 当前41表设计中15项候选新增＋1既有mch_appointment_slot兼容；含后续实际占用等未交付结构。SHR-01/02由原所有者实施，不进入美业迁移 |

计划共49个任务；M0/P1是当前可细化执行主线，P2以后按正式合同另行展开小切片。每个API/表有唯一首次主责任务；联合验收可以涉及多个任务，但不重复计完成。

### 0.1 推进顺序与准出

| 阶段 | 主要任务 | 准出结果 |
|---|---|---|
| M0 工程和数据起步 | ENG-01/02；DATA-01/02/03；ENG-03 | 五构件可构建，关闭不影响零售；隔离库可执行/校验P1迁移，可信范围、事务/审计接线可检验 |
| P1 数据对象与共用前置 | DATA-04～07、SHR-01/02/03 | 16项美业Entity/Mapper与对应不变量通过；共用人员/资源合同真实存在，不能只返回成功占位 |
| P1 项目与预约 | POL-01、DEF-01、AVL-01、APT-01～03 | 同一SKU门店报价；资格/班表/完整资源匹配；无交易可预约，改约失败保留原位 |
| P1 接待、工单与执行 | WO-01、REC-01、SRC-01、TASK-01～03 | 真实来源就绪才开工，实际参与/完成量、核心履约与关闭分别收敛 |
| P1 工作台与恢复收口 | CAL-01、RECOVER-01、MSG-01、HIST-01、OPS-01，GOODS-01按实物需求 | 日历看到具体任务与真实资源占用，跨店匿名busy、快照完整性、原号恢复可验收；打印真机另验 |
| P2 资产接入 | ASSET-01/02 | 本次储值/已持有合格次数权益本人授权和未知恢复，门店隔离，不等于付费售卡 |
| P3 经营完善 | CARE/QUEUE/PERF/MARKET/MAT/FIN/RPT/DEVICE | 护理、协作、营销、耗材、财务报表按政策/权限与真实数据源分别验收 |
| P4 专项 | PLAN/SALE/ADV/ASSET-REV/MAT-02/EXT/EDGE | 疗程销售、复杂收银、团体/上门、资产逆向、外部/离线逐项合同和故障验收 |

CAL-01可先开发资源占用视图，但完整准出依赖TASK-01任务摘要及工作台合同联验；不能把只画出空泳道算“看得到具体预约任务”。任务依赖是业务前提，不要求等全部共用经营功能落地后才做单次服务。

### 0.2 全范围代码优先、统一部署（2026-10-24用户调整）

用户已明确“所有代码开发”包含后续P2～P4全部规划功能。本节覆盖此前“一个业务模块部署通过后才能开发下一个模块”的执行顺序；既有需求、共用域所有权、代码规范和历史验收证据不变。范围仍为既有美业后端计划；端上页面不因API完成自动视为交付。

| 顺序 | 代码开发批次 | 原任务范围 | 编码完成检查 |
|---|---|---|---|
| 1 | BM04接待、工单与交易来源收尾 | Task59；Task60中的代码、架构、SQL与文档欠账 | 公开/内部/反向交易协议、开单幂等兼容、权限及恢复代码；构建和回归通过 |
| 2 | BM05服务作业与履约 | TASK-01/02/03；共用实际占用、超时、清洁及履约合同 | 开工许可、参与/交接、步骤、数量守恒、占用释放、履约与关闭 |
| 3 | BM06预约与作业工作台 | CAL-01 | 实际预约任务及多资源日历合同、完整快照、匿名busy、受权操作刷新 |
| 4 | BM07异常与门店运营、P1尾项 | AFTER-01、RECOVER-01、MSG-01、HIST-01、OPS-01、GOODS-01 | 售后/恢复/历史、通知打印适配、监控对账、实物销售；设备实际验收后置 |
| 5 | BM08既有资产消费（P2） | ASSET-01/02 | 本人授权、门店隔离、次数/储值权威消费、未知恢复与财务待办 |
| 6 | P3经营完善 | CARE-01、QUEUE-01、PERF-01、MARKET-01、MAT-01、FIN-01、RPT-01、DEVICE-01 | 护理私有媒体、候位回访、绩效提成、营销、耗材、财务报表、终端治理 |
| 7 | P4专项 | PLAN-01、SALE-01、ADV-01、ASSET-REV-01、MAT-02、EXT-01、EDGE-01 | 疗程计划/销售发行、高级预约收银、资产逆向、BOM成本、第三方及离线协作 |
| 8 | 全范围统一部署验证 | Task60部署部分及BM05～P4各模块部署用例 | 固定构件/迁移清单；embedded、standalone、默认零售及餐饮受影响路径统一联验 |

同批内部服从开发任务JSON的依赖关系，不能仅照表序绕过前置。既有49项任务继续作为范围索引，Task54～60是实施细分，不重新按任务数累计完成率。后续批次编码前须读取已有PRD、功能矩阵、API/Java方法和数据设计，将字段、方法、状态机、真实所有者、权限、失败恢复及AC→测试映射写入本TDD §2，再实施；不得生成空接口、假成功或第二套商品/订单/资金/权益账代替完成。

代码阶段继续执行单元测试、必要的隔离MySQL/HTTP集成测试、迁移约束验证、架构/三语/SQL/生成守卫和独立审查。这些是开发证据，不是正式部署验收。实际构件启动及全链路部署验证统一后置；验证脚本、夹具及配置代码须先完成并可编译。后置项标记“待统一部署验证”，不得标记通过。

Task60拆分为“60A代码与质量收尾”和“60B统一部署关口”：60A在BM04代码结束时处理，60B最后执行。Task59真实双进程部署验收执行同样后置，相关生产接线和测试代码不删减。下一业务模块在前置代码、必要测试、审查和编译通过后可以开始；未实现、依赖不可用或结果未知的营业入口仍保持关闭。

统一关口必须逐模块保留独立业务用例和结论，不能以一次启动或全局readiness代替全范围验收。使用隔离环境及隔离数据库，不隐含授权发布至营业库、推送/合并共享分支或真实外部资金操作。第三方账号、打印设备等实际依赖若不可用，明确记载未验证项，不以测试替身计正式通过。

### 0.3 现金/线下核心闭环优先（2026-10-25用户最新调整）

用户明确：先完成美业核心逻辑，基于现金和线下方式，暂不接支付链条。本节优先于§0.2及§2.39/§33.3原“先实现Core/Pay保护”的近期排期；P2～P4完整目标保留为后续，不因暂缓而记作完成。已验收BM01～BM04与Task62-S0不回退、不重做。原Pay预算/永久managed标识/线上退款方案保留为后续接入设计，本轮不作为实际作业的前置。

当前交付主线：共用商品/订单与人工线下业务准入→预约预留交接实际占用→开放占用/结束/清洁→暂停/恢复/换人/延时→步骤/完成量/服务关闭与共用订单履约→实际任务/资源日历→必要异常、日志监控及通知打印适配。现有先收后服务作为默认，未授权新增赊账、后付或免费放行。

资金边界：现金/线下人工确认是业务声明及放行依据，不调用Pay下单/网关/回调/预算保护/资金退款，不伪造stl_payment或银行核验回执。服务状态、人工确认状态、正式资金状态分别表达；共用订单可依据真实受权人工确认记录现金/线下已收，但须明确MANUAL来源，不能冒称渠道核验；不将人工登记与正式回执重复累计。原正式收款/线上来源保持原合同，不能仅改payMode或前端布尔值转成人工模式以绕过原约束。原退款/支付路径及旧managed完成防线不放宽。

仍复用共用SKU、门店报价、订单与来源行、人员身份和资源容量；美业只保存预约/任务/实际参与/步骤/业务意图。人工线下准入的权威归属、来源绑定、数量防重与Core撤销/自动关单/履约相反写者需在Task63实际审计后冻结；不能在美业建第二资金账或用无依据的已付布尔值解决旧source()依赖。临客与真实买家使用现有身份所有者，不冒充顾客登录或把guestNo填成userNo。

部署顺序沿用§0.2：先代码、必要隔离测试/编译及审查，再统一正式部署；原生MySQL9.7.2/真实HTTP开发测试仍可执行，不能当成正式构件部署。

## §1 全局约束与审查重点

1. 严格沿[统一Java规范§七](../../.claude/skills/ai-shop-dev-standards/references/java.md)及零售现有约定：资源Controller、Req/VO、Service/Impl分包、Impl直接调本域Mapper、BeautyMappers嵌套接口、BaseEntity/PageData/MybatisPages、统一ErrorCode/BizException/三语；不建Repository或BeautyApiResult/BeautyAuth等同义层。
2. 独立工程只拥有bty事实，Mch/商品/订单/Pay/库存实体和Mapper不得导入美业业务实现。同库不提供跨域直写许可；缺SPI在权威所有者补，不能先放beauty-core让餐饮依赖美业。
3. 人员可没有登录账号；业务人员、操作账号、顾客、买家、付款人分开。任职、业务岗位、资格、班表与系统权限分别校验；同主体同一STAFF跨店共用规范资源。
4. 请求主体/操作身份来自可信上下文，顾客接口校验本人归属；平台恢复不代授权/代完工。资源或Query里的personnelNo不是访问权限，跨店busy不返回他店顾客/任务/门店引用。
5. 迁移、类型、索引与默认值从当前设计真源评审生成；已执行版本冻结，后续只追加。不执行clean/repair/任意baseline；发现历史bty结构或资产账冲突先核对归属和历史，禁止IF NOT EXISTS掩盖漂移。
6. 本域短事务完成状态/版本/事实/outbox；跨资源/Core/Pay采用持久意图和原号恢复。HTTP不在长锁事务内等待，UNKNOWN不重新生成来源/支付号。
7. SQL CAS显式校验可信范围、版本/前态/数量上限并检查影响行；标量自定义SQL显式写version与updatedBy/updatedAt。空行查询不算稳定锁；首次创建靠唯一键与幂等协议。
8. 预约指定、确认安排、实际参与独立保存。服务完成和履约确认分开，重复回调不再增加doneQty或扣资产；工单关闭冻结范围后校验真实任务/绑定版本。
9. 日历采用不可变窗口快照和短轮询，分页齐全/投影延迟分别标记。日历空白不是可约承诺，真正容量仲裁在共用所有者；护理正文、照片和余额不混入日历/公共打印/外部通知。
10. 当期只生成需要的类/DTO和Mapper，不一次创建44个空Controller、25个Entity和160个DTO壳。后续源码变动先对账方法/API/字段真源再重建文档。

| 审查重点 | 预期 | 主责任务 |
|---|---|---|
| 历史表、共享表或错误主数据源 | 漂移报告明确；不重建共用表，不落到Pay/库存库 | DATA-01/02/03 |
| 首次并发与同人跨店资源竞争 | 完整组合只确认一个；失败不留半组合、改约不丢原占用 | SHR-02、APT-01/03 |
| 来源超时/数量重复绑定/履约回调重放 | 原号恢复、分配上限守恒、服务完成只计一次 | DATA-07、SRC-01、TASK-03 |
| 旧权限/旧快照/缺页 | 撤权即失效；他店busy匿名化；不完整视图不当空闲 | ENG-03、CAL-01 |
| 换人、超时、关闭冻结竞争 | 保留历史参与，未交接不静默变人，未知来源不强关工单 | TASK-02/03、RECOVER-01 |

## §2 首批工程及数据层详细任务

下一阶段SHR-02b详细需求与子阶段见[专门方案§2](./TDD-共用资源容量与组合占用-SHR02b.md#2-需求清单与交付拆分)，本轮为设计细化，不启动实现。

共用排班SHR-01b本批计划见§13.2，验收见§13.3。

后续共用资源SHR-02a的当批实施计划见[§12.2](#122-实施计划本会话直接执行)，验收见§12.3。

本节是实际编码前的领取粒度；其余任务总表及全部160项入口见索引。下述为任务目标，实际交付和未完成项以本页执行记录为准。每个任务开始先复核相关需求、方法和真实提供方；若字段/行为调整，先更新设计真源及受影响验收，不把原草稿当永远不变的事实。

### 2.1 文件责任与首次执行准备

- `R=backend-plugins/shop-industry-beauty`，工程根；`A=R/beauty-api/src/main/java/ai/neargo/shop/beauty`。
- `D=R/beauty-core/src/main/java/ai/neargo/shop/beauty`；`W=R/beauty-web/src/main/java/ai/neargo/shop/beauty`；`C=R/beauty-channel/src/main/java/ai/neargo/shop/beauty`。
- `S=R/beauty-server/src/main/java/ai/neargo/shop/beauty/server`；`T=R/beauty-core/src/test/java/ai/neargo/shop/beauty`。
- 美业表与方法真源为`docs/technical/design/schema/beauty-database-design.json`及`beauty-java-method-design.json`；公共新增只修改其真正所有者路径。

实施前确认工作区/分支和未提交文件归属，独立美业改动不夹带餐饮或其他前端变更；保护先前已形成的美业设计源。目标分支遵循codex/前缀，工作树选择遵守当时工作区及权限，不能将未提交设计遗漏在另一个checkout。

### 2.2 ENG-01：五模块与可构建边界

**文件：** 新建`R/pom.xml`、`R/README.md`、五个子POM；最小`S/BeautyApplication.java`；`R/beauty-server/src/test/java/ai/neargo/shop/beauty/architecture/BeautyArchitectureTest.java`。

**消费：** 当前shop-parent及共享构件；**产出：** api/core/web/channel/server五构件，父POM版本沿平台，只有server repackage；不带营业接口。

- [x] 写`rejectImplementationDependencies`、`webDoesNotDependOnCore`、`channelDoesNotDependOnWebOrCore`、`onlyServerIsExecutable`；断言整个reactor而非空测试目录，非法依赖反例必须失败。
- [x] 运行目标测试，确认因缺少目标构件/行为而失败，再按既有独立结构补五POM与最小启动。
- [x] 运行reactor verify/依赖树/实际jar检查；既有共享构件缺失时按平台流程准备，不升级BOM或引用shop-app可执行jar。
- [x] 对账五构件/直接依赖/扫描范围与结构真源，记录结果并按定向文件提交；不生成全部业务壳。

### 2.3 ENG-02：条件装配与用户/内部入口隔离

**文件：** `D/config/BeautyAutoConfiguration.java`、`BeautyProperties.java`；`W/config/BeautyWebAutoConfiguration.java`及独立条件；两模块AutoConfiguration.imports；`S/config/BeautyServerConfiguration.java`、`BeautyInternalSecurityConfiguration.java`；宿主仅在实际可挂载时修改其POM/配置，不复制用户Controller。

**产出：** shop.beauty.enabled默认false，enabled时deployment显式embedded/standalone；web不引用core配置，独立server不引用户web；关闭时无Mapper/Controller/Job注册。内部身份采用平台既有协议，不引第二套用户登录。

- [x] 写`disabledRegistersNoBusinessComponents`、`webLoadsWithoutCore`、`standaloneHasNoPublicRoutes`、`missingRequiredDeploymentFailsClosed`，验证真实Bean/RequestMapping而非以401冒充路由不存在。
- [x] 看红灯后实现互斥装配，保留平台原数据源、安全链和全局扫描约束；新增组件自身也受开关保护。
- [x] 复验embedded、standalone、禁用、api/ops组合；缺必要配置或正式业务提供方保持受影响readiness关闭。

### 2.4 DATA-01：数据合同审计与首批冻结范围

**文件：** 核对数据库设计JSON、Java方法JSON、字段字典、现有BaseEntity/AuditMetaObjectHandler与数据域注册规则；修订真实发现的冲突后重生成既有文档。

**产出：** P1的16项私有表/字段/唯一键/索引/默认值/校验矩阵，P3七项/P4两项延后清单；共享14项所有者及兼容清单；不得在此任务把39项全变成美业DDL。

- [ ] 核对entityNo须由子Entity明确承载、BaseEntity已有id/tenant/审计/version/deleted不重复定义；LocalDateTime持久化与API带偏移时间的UTC转换一致。
- [ ] 核对唯一索引的tenant/entity前缀、可空唯一键、正数/精度/数量守恒、子表受权父链、不可变事实追加、历史绑定与逻辑删除风险。
- [ ] 对源行guard唯一键不含sourceRevision、工单冻结/任务绑定修订、安排历史、审计/幂等持久检查点逐项确认；DTO摘要不等于已具备完整字段类型，只定当期切片所需Req/Query/VO。
- [ ] 如目标环境已有bty表或旧美业储值脚本记录，先只读核对应用历史及结构；缺少归属证据时不migrate、不覆盖资产账户。
- [ ] 已知P3复核点：数据库提成规则写“锁门店政策头”，方法清单含“规则组首版锚点”；开PERF-01前需确定跨组规则重叠的串行仲裁，两种粒度不直接视为等价。此项不强行扩大P1数据范围。
- [ ] 审计结论、缺口及当期取舍形成记录，才进入DATA-02；未确定结构不凭猜测写迁移。

### 2.5 DATA-02：首批迁移及真实隔离库验证

**文件：** 候选新增`scripts/gen-beauty-schema.mjs`、`scripts/tests/beauty-schema-sql.test.mjs`；`R/beauty-core/src/main/resources/db/industry/beauty/V1__beauty_p1_tables.sql`；隔离MySQL/MariaDB验证工具按项目已有方式复用。

**消费：** DATA-01已审定的P1结构；**产出：** 16项bty建表迁移、独立bty_flyway_history及漂移校验；后续版本按首次执行事实冻结。文件名/版本为首次实施目标，开工先核对是否已有同名执行历史。

- [ ] 先写`containsOnlyP1BeautyTables`、`rejectsSharedOrAssetTables`、`preservesBusinessUniqueness`、`rejectsChangedAppliedMigration`；证明非P1/shared表或冻结文件变化会被拒绝。
- [ ] 红灯后实现从审定真源生成SQL；不包含mch/prd/ord/Pay/库存账，不用另一份手写DDL漂移。
- [ ] 在一次性隔离目标数据库验证创建、字段/索引/约束与Flyway validate、再次执行不重复迁移；表数对不是唯一判据，未知历史/漂移必须失败。
- [ ] 不使用H2替代MySQL锁/检查语义，不连接共享营业数据库跑破坏性夹具；部署migrate只有一个受控执行者，普通启动默认校验。
- [ ] 记录实际DDL摘要与测试结果；P3/P4今后追加版本，不回写已执行V1。

### 2.6 DATA-03＋ENG-03：主库、数据域、事务与可信写入基础

**文件：** `D/config/BeautyPersistenceConfiguration.java`、`BeautySchemaReady.java`、`BeautyDataScopeRegistration.java`；宿主必要的Mapper扫描排除与独立server明确主连接装配；`W`接入范围校验仅在确需辅助时先登记类，不自造BizContext。

**测试：** `T/scenario/BeautyPersistenceWiringTest.java`、`BeautyStoreScopeTest.java`、`BeautyIdempotencyTest.java`、`BeautyOutboxWiringTest.java`；独立server对应数据源/迁移安全测试。

- [ ] 先验证`bindsBeautyMapperToPrimaryFactory`、`disabledDoesNotSuppressRetailFlyway`、`doesNotUsePaymentOrInventoryDataSource`、`rejectsUntrackedSchema`；MyBatis沿主工厂/事务，不能注册一个全局Flyway替换掉零售迁移。
- [ ] 校验真实entity列及子行受权父链，默认fail closed；未来加入哪张表就登记哪张表，不一次虚构所有数据域锚点。
- [ ] 将`sameKeyDifferentPayloadRejected`、`crossStoreMutationRejected`、`actorComesFromLogin`、`transactionRollsBackIntentAuditAndOutbox`落实到首次真实写入，不只验证mock调用次数。
- [ ] 接线本身先做条件/工厂验证，**真实Mapper、事务与审计结果随DATA-04/07首写联验收口**；不能在尚无Mapper时宣称所有数据层已通过。

### 2.7 DATA-04～07：按真实聚合实现Entity/Mapper

所有实体和Mapper归beauty-core；公共完整根包`ai.neargo.shop.beauty`。`D/mapper/BeautyMappers.java`是唯一集合文件；以下方法完整参数和返回沿既有方法JSON/清单，不重命名成另一组。Controller不提前依赖这些实体。

| 任务 | 数据与精确类范围 | 关键方法/不变量 | 目标失败用例 |
|---|---|---|---|
| DATA-04 政策/定义5项 | entity/BtyStorePolicy、BtyStorePolicyVersion、BtyServiceDefinition、BtyServiceDefinitionVersion、BtyServiceRequirement及同对象内嵌Mapper | selectScopedForUpdate、switchCurrentVersion；发布版本与要求追加；头指针CAS、唯一门店/SKU范围 | `PolicyDefinitionPersistenceTest#rejectsStalePointerSwitch`、`#keepsPublishedSnapshotImmutable`、`#rejectsCrossEntityRequirement` |
| DATA-05 预约3项 | entity/BtyAppointment、BtyAppointmentItem、BtyAppointmentAssignment及对应Mapper | changeSchedule、markArrived、changeStatus、selectByAppointmentRevision；原安排不覆盖，指定意愿不当实际执行 | `AppointmentPersistenceIT#keepsPreviousAssignmentRevisionAndRequestedPersonSeparate`、`#rejectsStaleScheduleAndWrongStoreOrTerminalState`、`#limitsAssignmentsToAuthorizedUndeletedParentChain` |
| DATA-06 工单任务4项 | entity/BtyWorkOrder、BtyServiceTask、BtyServiceStep、BtyServiceParticipation及对应Mapper | freezeScope、advanceScope、closeFrozenScope、recordCompletion、switchBindingRevision、endParticipation；范围/绑定/根版本分开，参与区间追加 | `ServiceTaskPersistenceIT#rejectsOverCompletionAndStaleBindingAndKeepsPartialWorkOpen`、`#freezeBlocksTaskMutationAndScopeAdvance`、`#closesOnlyCurrentParticipationAndNeverOverwritesPersonnel` |
| DATA-07 来源编排4项 | entity/BtyServiceSubmission、BtyTradeBinding、BtyActionLog、BtySourceAllocationGuard及对应Mapper | selectByRequestKey、advanceProgress、selectSourceForUpdate、allocateQuantity、releaseUnconsumedQuantity、refreshSourceSnapshot；同源guard跨任务累计上限，原号/原绑定留存 | `SourceAllocationConcurrencyIT#allocatesOnlyWithinOriginalQuantityAndExactRevision`、`#sourceRevisionsCannotCreateAnotherGuardForSameCanonicalLine`、`ServiceSubmissionPersistenceIT#sameRequestReturnsOriginalPayloadAndFrozenSourceCommand`、`SourceAllocationConcurrencyIT#doesNotReleaseConsumedQuantityEvenWhenHistoricalBindingWasSoftDeleted` |

每批具体步骤：

- [ ] 先用本批真实表在隔离数据库写失败测试：范围/唯一键/版本/数量/历史/审计；跨事务并发使用独立连接和稳定仲裁行。
- [ ] 新增当批实体，继承BaseEntity，字段/表名完整驼峰一致；JSON快照按既有序列化约定，私有护理字段尚未进入本批。
- [ ] 新增当批嵌套BaseMapper；继承insert/select/update不重写空方法，自定义SQL只为实际锁/CAS/批量查询，影响行数为0必须转明确冲突而非成功。
- [ ] 纯任务按root→稳定排序task→子行；涉及来源统一按排序guard→root→task→绑定/提交锁序联验（§10收敛此前guard位置歧义）；自定义标量更新明确写审计/version；不能用synchronized、空SELECT FOR UPDATE或无版本update替代约束。
- [ ] 绿灯后复跑当批依赖和数据层套件，挑关键断言做消融，记录真实命令及结果；无业务Service时仅标数据合同通过，不声称开工/扣款/资源已可营业。

### 2.8 数据层首批完成的界线

满足：五模块可构建；16项P1 bty迁移可校验；16个Entity/对应Mapper与父链权限、CAS/数量/审计/事务真实测试通过；共享14项未混入；资料P3、疗程P4、资产账未提前造表。

尚不代表：共用多资源仲裁已存在，预约可成约，钱已收、权益已扣、任务能合法开工，或工作台具体任务与打印真机已验收。数据层与业务层分别有完成状态；数据层之后按SHR/POL/DEF/AVL/APT等任务解锁。

### 2.10 BM-01服务项目与门店政策实施（2026-10-21）

基线dc5a3b543；用户已授权自动执行并逐大模块部署验证。executing-plans本会话执行，沿用原隔离树和ledger。
启动审计时，本模块POL-01/DEF-01九个API/合同：001/002/007/008已落地，009/144/145缺入口，005复用商品维护，006缺销售分组/时段提供方；这些BM-01后端缺项现已实现并按§22通过模块关口。
不因三个读入口完成而将整个BM-01勾选；BAPI-006与实际构件部署均是本模块准出条件。

| 单元 | 文件/方法与边界 | 验收 |
|---|---|---|
| 公共目录事实 | base/spi/product/ServiceCatalogFactsPort及InternalApi；product.port实现聚合已有StoreDirectoryPort/ServiceProductFactsPort/GoodsQueryPort；PLATFORM内部Controller；channel远程适配 | 商品/门店事实仍归各所有者，无复制价格；有效主体/门店、SERVICE/CN在架SKU、真实门店价/名称/图片；凭证与错误保真 |
| 美业目录读 | ServiceCatalogQueryService/Impl/config；PublicStoreVO/PublicServiceVO/ServiceDetailVO/ServiceDetailQuery | 按真实门店推导主体，不接受公开请求自报主体/actor；当前有效政策和定义；未来/停用/缺失不冒充生效；公开DTO无内部权限/资格/审计/哈希 |
| 三端入口 | MpStoreController.getPublicStore；MpServiceController.getPublicService；BizServiceController.getService；对应内部typed API/远程Service | 商家依旧使用真实BizContext和门店权限；公开只读不授予可约/收费权限；独立/嵌入同合同 |
| 分组/销售时段 | BAPI-006归共用catalog所有者；先审计实际提供方及缺口，再冻结实现 | 不把餐饮私有餐牌反向导入美业；未实现不能标整个BM01完成 |
| 部署验证 | 模块脚本构建/启动真实宿主及独立构件、隔离MySQL、真实认证与商品提供方、历史版本/重复发布/回滚/重启 | 缺正式提供方不能用测试Bean冒充；readiness继续按能力门控，不强改200；完成后才转BM02 |

目录事实Port新增只读Store(entityNo,storeNo,name,logo,status,openHours,address)及Service(store,skuNo,goodsNo,title,cover,priceMinor,durationMin)。
公开门店返回公开门面及有效取消/预约须知；公开服务返回当前门店报价/展示、时长/缓冲/注意事项和生效版本，不返回人员技能/内部要求。
商家详情可含既有完整定义。外部事实查询在本地主库事务外，读快照时间由服务端确定；拒绝调用方事务包住远程查询。
部署预检发现原归档YAML强制全局PLATFORM切UNIFIED，导致宿主尚未迁移的其它消费者因缺稳定handler ID无法启动。
本模块增加明确`shop.beauty.publication.archive-only=true`：仅两个不可变版本事件使用共用PLATFORM存储写ARCHIVED证据，不切换宿主全局来源模式、不启另一套投递泵。
存在真实消费者时拒绝该归档操作并回滚，非白名单事件拒绝；缺少该显式配置仍要求正式全局路由。不得通过批量为资金消费者补ID、删除消费者或禁用投递守卫来使BM01启动。
注意事项notes明确为最多20条、每条非空且不超过500字符的文本列表；纳入不可变版本及摘要。旧空notes命令保持原摘要与构造器兼容，发布/查询及公开投影都回读已发布内容。
无本批新表，不改已应用迁移。公开返回不是完整可约/库存/资金许可，BAPI-006提供方补齐前不承诺时段目录已闭环。

- [ ] 原生目录SQL测试先RED；实际政策/定义版本、隔离与公开投影。
- [ ] 公共事实/DTO及美业读服务实现，原生GREEN；正式owner SQL另验。
- [ ] Controller与双部署transport、权限/坏请求/错误保真；独立reactor回归。
- [ ] BAPI-005/006真实合同缺口收敛，不跳过原POL/DEF范围。
- [ ] 消融、一次独立只读审查与必要修复；模块完整业务/实际构件部署验收后登记完成。

### 2.11 BM-01共用销售目录与时段（BAPI-006，档位2）

需求为功能矩阵BTY-B03，沿商品域V2草稿Collection/Availability概念，决策见ADR-044。
V2草稿的整体商品/规格/listing重构尚未实现；本模块不重命名既有表、不复制价格库存、不自动插入店级上架行改变其它门店的覆盖语义。
本次在product所有者内增量提供“门店销售目录不可变版本”，零售/餐饮/美业共用。现有商品/SKU/货架/门店报价仍是交易权威。

| 单元 | 文件与方法 | 验收 |
|---|---|---|
| 正式合同 | base/spi/product/StoreSalesCatalogPort：read(entity,store,at)；StoreSalesCatalogService：publish(entity,store,actor,Req,key)；Req(expectedRevision,validFrom,timezone,groups,entries) | 读版本与头revision分开；分组顺序/显隐、SKU显隐、UNRESTRICTED/WINDOWED及周时段；固定公开渠道，不能冒充资源排班 |
| 存储 | 追加平台迁移，prd_store_catalog、prd_store_catalog_version；Prd实体及ProductMappers两个嵌套Mapper | 一店一头、版本不可变、唯一修订、有效时间和完整JSON/摘要，主库原子CAS；不改冻结美业V1 |
| 维护业务 | product.service.StoreSalesCatalogService/Impl、对应PortImpl；共用biz入口/实际权限和门店所有权 | 强制幂等键、真实SKU/店级报价校验、同键异参冲突；并发首建/旧版本冲突，审计/事件与版本同事务 |
| 只读接入 | ServiceCatalogFactsPort增加目录投影并提供实际内部HTTP；Beauty公开Store/Service投影消费 | 未配置沿明确旧口径“无额外时段/分组限制”，不能当资源可约；配置后缺项/隐藏项不公开；销售时段外显示不可售，保留详情和当前报价 |
| 部署验收 | BM-01真实共同商品维护/配置→发布政策/定义→客户读取；改组/时段边界/隐藏/旧版查询/恢复 | 真实owner/SQL/HTTP，无默认成功provider；完整模块完成后才转BM-02 |

目录payload仅含分组与SKU引用/可见性/销售窗口，最多50组、200项、64KiB UTF8；SKU引用必须由商品所有者解析并按规范号保存。
时区为显式ZoneId；周掩码周一至周日，时间以整数分钟[0,1440)半开区间表示，跨夜拆两条。UNRESTRICTED无窗口，WINDOWED须显式窗口，重复/无效/跨夜定义拒绝。
按服务端/受限查询Instant换算本地周时段，DST重复时刻按各自Instant投影；这不是员工排班或容量判定。
新版本只影响其生效后的售卖展示，不改写历史版本或既有预约/订单。既有引用可读取原版本，接受新预约仍由BM-03冻结其真实政策/版本。
未来完整V2可映射这些稳定分组/条目/窗口及版本，不在本批将草稿物理表全部落地。

- [x] 原生contract/service测试先RED：版本、范围、真实引用、首建/CAS/幂等、显隐、周时段/DST与失败回滚。
- [x] 追加DDL/Entity/Mapper、共用Service/Port；不得在beauty-core维护陈列表或销售价格。
- [x] 显式版本证据归档基础复用共用实现；不改变宿主旧投递、不隐式归档有消费者事件。
- [x] 商家实际权限/目录维护HTTP与跨部署读适配；重生成平台派生结构和相关设计索引。
- [x] 与BM-01既有查询/发布统一原生、权限、异常/重启、零售餐饮回归及实际构件部署关口；一次独立审查及重要问题修复。


### 2.12 BM-02人员、排班与资源维护实施（2026-10-21）

基线6d5f83fa5；BM-01隔离部署已通过。档位1，沿ADR-040/041/042及已批准功能矩阵，使用既有V532～535，旧迁移冻结。
人员仍归shop-merchant，账号/操作权限仍归原成员与角色业务，美业不新增员工表或账号池。

| 单元 | 文件/方法与范围 | 验收与顺序 |
|---|---|---|
| BM02-a 人员维护 | PersonnelService/Impl；BizPersonnelController；CreatePersonnelReq、AssignPersonnelReq、PersonnelQuery、ChangePersonnelStatusReq、LinkPersonnelAccountReq及VO；MerchantMappers必要锁/计数/CAS | BAPI012～016，先原生RED→GREEN；无账号建档及当前店初始任职，原子建立唯一STAFF资源；实际名单/岗位/资格事实可读 |
| 任职与资格 | 父人员revision统一保护孩子；当前门店任职区间不可重叠；TECHNICIAN/ASSISTANT/CONSULTANT/WAITER/CHEF/CASHIER/RECEPTIONIST业务岗位；资格只接受本店真实服务SKU及同商品，VERIFIED核验记录实际actor | 不授予登录权限；初始资格有效期绑定任职；更新通过新任职保留旧行并撤销旧店资格；跨店同一personnelNo/STAFF资源 |
| 账号及停用 | 当前可选成员关联；账号归本主体、ACTIVE、非owner；一人一当前账号，唯一索引裁决竞争；停用复用MerchantStaffService.setStatus，恢复业务人员不自动恢复登录账号 | 原账号角色不由岗位生成；解绑不删除成员/授权，严格审计保存旧/新账号引用；LEFT终态不复职；暂停/离职拒绝未处理占用/在途命令，失败不提交人员改动 |
| BM02-b 资源维护 | ResourceService/Impl、BizResourceController；BAPI020/021，实体STAFF不改身份，物理ROOM/SEAT/DEVICE限本店 | 不修改旧slot；容量/状态/人员身份和占用指针受保护；继承人员根→资源根锁序及必要版本CAS |
| BM02-c 周班表 | ScheduleService/Impl、BizScheduleController；BAPI022及新增P1周规则写入口（具体签名编码前在此补齐） | 复用ScheduleWindowsPort展开/跨店冲突，周规则真实维护；P3请假/例外审批BAPI023/024与协作BAPI018不冒充P1完成 |
| 模块关口 | 人员→任职资格→规范资源→周班表→匹配事实；真实宿主、standalone反向事实、默认零售/餐饮受影响链；权限/幂等/冲突/恢复 | BM02-a内部检查点不表示BM-02完成；全部必要单元完成后执行§21.4的一次模块部署关口 |

BM02-a具体合同：
- GET /biz/personnel：PersonnelQuery(page,size,status,keyword,storeNo?,skill?)；skill明确为有效服务资格goodsNo；当前门店任职名单，额外主体/门店不能由客户端选择；PersonnelVO带当前/未来有效任职及资格、父revision，禁止实体物理id/联系资料导出。INACTIVE/REVOKED/已到期历史保留存储但不累计挤占目录上限；新增/替换后活跃子集上限200在事务内检查，避免提交后目录不可读。
- POST /biz/personnel：CreatePersonnelReq(displayName,avatarRef,assignment)；初始AssignPersonnelReq必须是当前店、expectedRevision=0且assignmentNo为空；返回PersonnelVO；原子保存人员/任职岗位资格/规范STAFF/审计/事件/幂等。
- POST /biz/personnel/{personnelNo}/assignments：AssignPersonnelReq(expectedRevision,assignmentNo?,storeNo,validFrom,validTo?,publicVisible,levelCode?,sortOrder,positions,skills)；skills包含goodsNo/skuNo/levelCode；同店更新撤销旧店资格并保留历史任职行；不改变另一门店任职。
- POST /biz/personnel/{personnelNo}/account-links：LinkPersonnelAccountReq(expectedRevision,accountNo?)；null为解绑，用显式NULL更新，不依赖updateById忽略null；关联非owner同主体成员，不复制账号数据。
- POST /biz/personnel/{personnelNo}/status-changes：ChangePersonnelStatusReq(expectedRevision,status,reason)；ACTIVE/SUSPENDED/LEFT；停用登录账号先锁同主体规范成员行，再复用原service并回查DISABLED，不自行改密码/令牌；在途影响存在则409，未实现自动交接时不得返回已完成。
- 所有入口用biz:store:admin，可信actor/entity/currentStore来自现有会话；原始X-Store-No必须校验，强制幂等key及父revision，storeNo只做当前店相等校验。人员主体级停用/账号关联仅owner，防止某门店管理权影响其他店。
- 商品事实在本地TX外读取，异常保存后仅新幂等动作执行时抛出，已提交请求仍可原号恢复；SQL内部再次验证实体/门店父关系及 canonical业务键，拒绝大小写别名/越界引用。
- 每个变更短事务锁实体/门店活跃父链→人员根→规范STAFF资源根。已有任职历史保留；任职编辑只允许其原门店。
- 暂采用保守影响守卫：本主体存在任一ACCEPTED资源命令、该STAFF任一未释放单位或开放占用指针时拒绝破坏性变更，不宣称无影响/已自动交接。没有资源的异常旧人员拒绝修改，不伪造匹配事实。未来完整交接/外域实际任务写者须接入同根与影响协议。
- 审计复用mch_staff_log直接原子写入（不使用吞错logger）；主记录保存安全业务引用/状态/修订及请求摘要。停用原因最长500字，因现有detail上限512，追加独立PERSONNEL_STATUS_REASON记录并由主记录reasonRef关联；不截断原因或向事件广播自由文本。MERCHANT_PERSONNEL_CHANGED为不可变变更证据，沿共用PlatformPublicationPublisher；显式shop.personnel.publication.archive-only=true才归档，无消费者降级默认不开。

- [x] a1 原生反例：无账号建档/幂等/真实事实、任职交集及跨店、资格所属、账号唯一/解绑、revision冲突、占用/待处理阻断、停用联动、事件失败整笔回滚。
- [x] a2 DTO/Service/Mapper/Controller按规范实现，实际SQL与权限测试及公共事实回归；同步JSON/API/方法与对象文档。
- [x] a3 一次独立只读审查，重要问题一轮RED→GREEN；记录内部检查点，BM02-b/c尚未完成时不启动模块部署或宣布BM-02完成。

### 2.13 BM02-b规范资源维护实施（2026-10-21）

基线1679a32b0；延续已批准BM02-b，档位1，资源仍由shop-merchant统一所有，沿ADR-041和既有表，不新增迁移。

**合同与边界：**
- GET /biz/resources：ResourceQuery(page=1,size=20,storeNo?,type?,status?,keyword?)，页1..10000、大小1..100，当前门店物理资源及有当前/未来ACTIVE任职的STAFF；含暂停资源便于维护。返回PageData<ResourceVO>，VO只含resourceNo/type/storeNo/personnelNo/name/capacity/status/revision/openOccupied，不暴露物理id、占用凭证或剩余容量。
- POST /biz/resources：SaveResourceReq(resourceNo?,type,personnelNo?,storeNo?,name,capacity,status,expectedRevision)；强制Idempotency-Key，作用域及actor取可信会话。新建revision=0、状态ACTIVE；ROOM/SEAT/DEVICE限当前店，容量1..1000。更新保持resourceNo/type/storeNo/personnelNo不变，名称1..64、ACTIVE/SUSPENDED、revision严格CAS。STAFF仅查询，拒绝在此新建/改写，避免绕过人员状态/资格流程和同人唯一身份。
- 所有端点沿biz:store:admin及原始X-Store-No校验，不新增权限码。storeNo只允许省略或等于当前店；personnelNo必须空（STAFF由人员建档）。不提供迁店、删除、占用指针写入口，不修改旧slot。
- 写事务沿主体/门店活跃父链→物理资源根；STAFF修改直接拒绝。每次更新均保守检查主体ACCEPTED命令、资源开放占用、未释放单位/计数及异常孤儿证据。容量变更额外拒绝该资源已有任意容量桶（即使已释放），避免历史桶capacity快照与主档产生分歧；不批量重写历史桶。未来容量版本化另作明确设计。
- 复用IdempotencyService、严格mch_staff_log和PlatformPublicationPublisher，MERCHANT_RESOURCE_CHANGED仅广播不可变安全引用；显式shop.resource.publication.archive-only=true才允许归档。事件/审计失败整笔回滚，返回原请求回放而非再次写入。

**文件与接口：** 新增merchant.dto.ResourceQuery/SaveResourceReq/ResourceVO、service.ResourceService及impl.ResourceServiceImpl、api.biz.BizResourceController；shop-base.spi.resource.ResourceChanged；必要MerchantMappers.ResourceMapper容量桶计数/CAS。原生测试ResourceMaintenanceIT及scripts/verify-resource-maintenance.mjs；HTTP测试ResourceControllerTest；输入边界测试ResourceInputTest。复用现有人员影响计数（暂不另造同义SQL）。

**实施顺序与验收：**
- [x] b1 先原生RED：物理资源建档与真实ResourceFactsPort读取、分页范围/STAFF唯一查询、幂等及哈希冲突、跨店/大小写别名/身份不可变、stale revision、容量/暂停影响阻断、历史桶容量冻结、事件失败回滚。
- [x] b2 DTO/Service/Mapper/Controller实现，原生GREEN、输入边界/真实安全过滤器HTTP回归，执行一次守卫消融RED→恢复GREEN。
- [x] b3 同步矩阵/对象/API/方法清单，一次独立审查及必要重要修复，保存检查点。BM02-c及整体部署仍为后续；不把资源维护通过当作预约可用或BM-02部署完成。

### 2.14 BM02-c周班表与远程事实接入实施（2026-10-21）

基线226d46dc8；延续已批准BM02-c及模块部署关口，档位1，Java21/既有包规范、旧迁移及美业私有V1冻结。

**周班表合同：**
- GET /biz/schedules：ScheduleQuery(Set<String> resourceNos,OffsetDateTime from,to)，1..50规范资源号，正区间最多31日。ScheduleVO含当前门店ACTIVE规则、展开窗口、各resourceRevision及查询区间；规则和窗口只是工作时间事实，不是预约余量，历史/例外/请假不伪装已实现。
- POST /biz/schedule-rules：新增P1共用写入口，SaveScheduleRuleReq(ruleNo?,resourceNo,expectedRevision,timezone,weekdayMask,startMinute,endMinute,endDayOffset,validFrom,validTo,status,reason)。expectedRevision是资源根revision，初始资源也须传现revision；返回ScheduleRuleVO(ruleNo,resourceNo,resourceRevision,status)。时区沿ZoneId，mask1..127，开始0..1439、结束0..1440、跨日0/1、实际本地持续1..1440分钟。validTo首版必填且包含最后开始日，区间最多366日，日期留SQL/UTC两日边界余量；reason1..500。无限规则写入暂不开放，便于完整有效期验证；已存无限规则仍可按查询窗口读取。
- 新建仅ACTIVE；替换/停用须命中当前店ACTIVE旧ruleNo，旧行只标INACTIVE且原内容留存，新ACTIVE版本另号写入；INACTIVE命令返回旧号且不新建规则。resourceNo/storeNo不可迁移；重放在重新修改前返回原号。每资源最多200条ACTIVE规则，保留INACTIVE历史不占上限。
- 写锁主体/店活跃父链→STAFF人员根（适用）→规范资源根；锁内重新验身份及canonical引用，物理限本店，STAFF必须有本店任职。ACTIVE规则须存在有效任职裁剪后的窗口；暂停人员/资源不能新增ACTIVE。同资源根CAS同时保护物理资源和班表写入，不建立独立可绕过修订。
- 人员任职新增/替换在相同人员→资源锁下，遇本店任一ACTIVE周规则明确拒绝（含历史日期规则，避免跨日/时区筛漏）；须先显式停用相关规则再变更任职/资格，防止激活已存规则被任职裁剪掉的跨店重叠。代价是维护前需处理旧ACTIVE规则，不自动重排。
- 更新/新建周规则均采用同一保守影响守卫：主体任一ACCEPTED命令、该资源开放占用/正容量计数/未释放单位/异常孤儿证据拒绝。只证明守卫检查通过，不宣称自动迁移预约任务。
- 每次ACTIVE提交按31日分片复用ScheduleWindowsPort/Assembler检查整个最多366日期间并留跨日UTC边界，跨店重叠/任职歧义/DST端点缺失或重复明确CONFLICT；任职之外的窗口裁剪，不把同人多店当不同容量。变更记录严格写mch_schedule_change_log（affectedRefs=[]表示守卫下无已接受占用，非完整任务影响报告），原子审计/幂等/事件沿共用发布器；shop.schedule.publication.archive-only显式开启才归档。
- 商家入口沿biz:store:admin、可信entity/currentStore/actor与原始X-Store-No检查；STAFF班表是店内维护，不授予账号权限。BAPI023/024的P3例外审批保持未实施。

**远程事实合同：** shop-base新增MerchantFactsInternalApi的三条受凭证保护POST：/internal/merchant/facts/personnel、resources、schedules，使用既有三种Port.Query及各List事实返回。平台portal.internal.InternalMerchantFactsController复用InternalServiceResponses，在执行前校验MAIN、32字范围/号码及真实StoreDirectoryPort规范主体门店。无匿名/用户目录入口，不传账号/联系资料，失败不返回空闲；三个拥有方的任职歧义/窗口冲突明确转为HTTP409，不能由全局异常包装改成HTTP200错误对象。
独立服务beauty-channel分别实现RemotePersonnelFactsPort/RemoteResourceFactsPort/RemoteScheduleWindowsPort，导入BeautyMerchantClientConfiguration；embedded继续本地提供者。沿InternalHttp内部凭证和ServiceCalls错误语义，不新增数据副本或独立员工表。

**文件/方法与顺序：**
- [x] c1 原生ScheduleMaintenanceIT RED→GREEN：Service接口ScheduleService.listSchedules/saveScheduleRule、Impl；ScheduleQuery/SaveScheduleRuleReq/ScheduleVO/ScheduleRuleVO；BizScheduleController；ResourceMapper必要revision CAS；ScheduleChanged；历史保留/完整区间冲突/裁剪/夏令时/作用域/占用/事件回滚。
- [x] c2 ScheduleControllerTest及ScheduleInputTest、一次消融；真实TCP MerchantFactsTransportTest及内部凭证/范围入口测试RED→GREEN；上述远程事实三适配器与装配。
- [x] c3 一次独立审查、重要问题一轮RED→GREEN、共享/独立reactor回归；同步矩阵/对象/API/Java清单。继承原160项设计编号，新增周规则写入口作为BAPI022的P1补充，不把P3影响审批改成写入口。
- [x] c4 干净重建实际embedded/standalone宿主、beauty-server、默认零售构件；隔离MySQL正式登录→人员/任职/资格→资源→周班表→远程事实链、跨店冲突/停用/幂等/回滚/重启/依赖失败验证；通过才认定BM-02后端模块完成，继承既有平台结构夹具/历史迁移债务的证据边界。

### 2.15 BM-03预约模块启动深化与首个内部任务（2026-10-21）

基线6d675af2f，BM-02隔离部署通过。档位2，沿ADR-043及SHR02b§2.8/2.9既有批准方案；先完成真实来源持久化前置，之后才接提供者与容量事务，不用无交易模式绕过来源fence。

| 内部顺序 | 需求和成功条件 | 本次范围 |
|---|---|---|
| BM03-a1 来源意图/栅栏存储 | 预约根→fence→intent，完整冻结计划原UTF8字节、命令/来源修订/摘要不可被重试覆盖；同预约一个活动fence；过许可时点不自动解除 | 本次实现，两表追加私有V2；Mapper/实体/迁移验证及原预约写者NOT EXISTS守卫 |
| BM03-a2 真实来源权限与决策 | 正式经营成员/门店授权，按持久预约/版本/需求生成完整计划，freeze/query同Binding幂等；取消/改约/签到不绕fence；可信资源终态协调后解除 | a2-1核验读取已交付（§27）；真实来源provider/计划生成/决定与协调仍待a2-2，不开放预约写HTTP |
| BM03-b 共用容量 | 零售/餐饮/美业同一命令所有者；组合预留全成或全回滚、跨店人员防双占；保护/确认/续期/释放/过期/完整替换，失败保旧与原号恢复 | 完成a2及三行业真实来源适配验收后实施 |
| BM03-c 预约业务闭环 | 顾客身份/代约临客、当前政策/商品/定义版本、时段/资格/自动或指定匹配、创建/详情/列表/改期/取消/爽约/未知恢复 | 实际Service/Controller，冻结真实输入，不能只写holdNo返回成功 |
| BM03-d 大模块关口 | 创建→查询→改约→取消；并发、失败保旧、许可到期与未知结果保护；实际embedded/standalone/默认零售/餐饮影响 | 全部必要单元齐备后一次模块部署验证；a1不启动完成宣告 |

**a1物理合同：** bty_appointment_resource_intent保留预约/门店/命令号、sourceRevision/action/commandHash/planHash/frozenPlan、phaseCode（0 PREPARED/1 AUTHORIZED/2 DENIED/3 RECONCILED）、decisionNo/reasonCode/issuedAt/validUntil、terminalCode（1成功/2拒绝）与terminalHash；单命令主体内唯一。bty_appointment_resource_fence主体内每预约一个永久根，fenceNo稳定、activeIntentNo可空，version CAS；唯一活动意图引用不随软删释放。完整载荷LONGTEXT而非JSON列，保留UTF8原字节，JSON_VALID及64KiB检查；不使用数量guard或提交JSON代替。

**拒绝形状：** 对齐共用ResourceSourceAuthorityPort.Decision：DENIED保存decisionNo/reasonCode且许可时间为空；AUTHORIZED保存有效窗口且无reasonCode；RECONCILED保留原决定形状，拒绝意图只能对账为资源拒绝终态。a1不实现拒绝业务编排。

**边界：** Mapper只提供持久化原语，未来a2必须真实鉴权、比较完整Binding与持久业务计划、查询真实资源终态；不得把receipt参数或SQL种子视为权威结果。releaseFence仅允许已有RECONCILED及终态摘要，调用者仍须先核实原命令；无TTL自动解锁或网络调用。本批没有授权provider/预约写入口/收费承诺。

**文件/验证：** 新增V2__beauty_appointment_resource_fence.sql与独立增量JSON/生成检查（原41表设计及V1冻结）；BtyAppointmentResourceIntent/BtyAppointmentResourceFence及BeautyMappers两内嵌Mapper；AppointmentMapper四种修改均检查无活动fence。BeautySchemaValidator合并严格V1/V2清单且readiness版本升2，原工厂/数据域/写前置同步。保留原V1摘要；校验模式对未升级V1库必须拒绝，显式migrate才升级。既有16+新增2私表，不把共享资源表迁入美业。

- [x] a1-1 原生反例ResourceIntentPersistenceIT、V1→V2升级/新库/validate只读/数据保留、原计划字节、唯一活动fence、修订/CAS、四预约写者阻断与手工终态前禁止释放、事务回滚/跨店隔离。
- [x] a1-2 实现迁移/实体/Mapper/版本与严格结构检查；必要CHECK语法仅显式扩展json_valid/octet_length及MySQL同义length封闭函数，不放宽冻结V1验证；一次守卫消融RED→恢复GREEN。
- [x] a1-3 一次独立只读审查，重要问题一轮RED→GREEN；独立reactor/全原生回归及文档对账，保存内部检查点。真实a2/b/c/d仍未完成；不按技术切片宣称大模块交付或提前开始BM04。

### 2.16 BM03-a2-1共用权限与原命令核验前置（2026-10-21）

基线e2d89042f，档位1，沿ADR-043。沿已授权自动执行，使用brainstorming/writing-plans深化现有设计，executing-plans原位实施，先RED后GREEN。

**缺口与选择：** MerchantScopeFacts只证明主体/门店归属，不含成员权限；ResourceCommandService是merchant内部合同，独立美业不能直接引用。不能用服务令牌、人员资格、请求中的terminalCode或当前owner指针代替真实成员和原命令事实。先在shop-base增加两个所有者只读Port及独立机器协议，再接美业来源Service。原V1/V2、旧slot、49任务定义不变。

| 需求 | 设计/实现 | 验收 |
|---|---|---|
| 当前经营成员权限 | MerchantOperatorAccessPort.allowed(Query(tenantNo,entityNo,storeNo,operatorNo,realm,subjectKind,permission))；merchant.owner实际主体验证ACTIVE/未归档、店ACTIVE、成员ACTIVE，MCH只按账号号/USR只按user_no，不能跨身份命名空间；当店授权及当前角色权限精确或裸*匹配，无静态角色兜底 | 真MySQL：owner、USR/MCH、跨店、错误类型、停用/软删/已归档、收回角色/权限下一读生效、大小写别名拒绝 |
| 原命令核实 | ResourceCommandFactsPort.lookup(ResourceSourceAuthorityPort.Request) → Lookup(command?)；owner读原command+原owner父链，比较完整Binding含两个摘要/来源/修订/动作；访问须经营侧biz:order:view当前权限 | 真journal accept→查询→rejectAfterRollback→查询；ACCEPTED无终态hash；终态保留原resultPayload/rejectionCode及绑定版本摘要；缺失返回显式空，失配409、拒权403，不用currentHoldNo当历史结果 |
| 独立行业复用 | 新ResourceRecoveryInternalApi两个POST（/internal/merchant/recovery/operator-access、/internal/merchant/recovery/commands）；机器令牌先验；美业channel实际HTTP适配+条件接线，宿主本地owner唯一 | MVC错误令牌不调用owner，400/403/409/503不伪装空；真实TCP路径/请求/响应/服务发现/凭据及超时失败 |

**方法与文件：** shop-base/spi/user/MerchantOperatorAccessPort，spi/resource/ResourceCommandFactsPort、ResourceRecoveryInternalApi；merchant.port两个Impl，MerchantMappers.OperatorAccessMapper及ResourceCommandMapper原命令投影，merchant.dto.ResourceCommandFactRow；portal.internal.InternalResourceRecoveryController；beauty.port.RemoteMerchantOperatorAccessPort/RemoteResourceCommandFactsPort及BeautyMerchantClientConfiguration。服务只读，不提供命令写入、角色写入、占用/当前头写入或客户端入口。

**边界：** 成员事实是读取时的判断，不是可持续消费的授权凭证；USR/MCH需由可信已认证入口提供，机器令牌不能替代真实成员。原始结果仅传内部核对，不日志/公网输出；终态摘要是稳定证据摘要，不能凭自制摘要解冻。网络读取只允许无调用方事务，不在来源根锁或资源事务中反向访问。未来a2-2仍须生成真实业务计划、持久prepare/freeze/query决定、可信终态协调与全部相反写者协议。

- [x] a2-1a 写原生权限/原命令结果、协议鉴权、真实TCP失败反例；观察RED。
- [x] a2-1b 实现两个共用Port/owner精确查询及内部HTTP/美业远程接线；观察GREEN，权限守卫消融RED再恢复。
- [x] a2-1c 一次独立只读审查、一轮重要问题修正，受影响merchant/宿主/beauty回归；同步矩阵/对象/API/方法、保存本地检查点。a2真实来源provider及整个BM03仍不认定完成，不在此执行整模块部署。

### 2.17 BM03完整模块续做计划（2026-10-21）

沿§2.15、ADR-043及SHR02b批准设计，剩余工作连续执行至BM03-d，不再按核验读取切片宣布模块完成。
采用分任务实施与独立审查，沿既有隔离分支；每项先真实反例RED再GREEN，任务间不重复请求执行确认。

Ruling：共享容量实现可在来源业务实现之前开发和独立验证，但未注册真实来源时必须拒绝执行；生产美业链路须真实provider。
零售/餐饮通过同一个共享合同和禁用兼容验收，不为不存在的零售/餐饮预约聚合虚构授权提供者；其真实预约来源另由所属模块接入。
成本：本阶段不能声称已交付三个行业的预约业务，只交付共用内核与美业预约业务。

#### Task 50: BM03共用原子容量与生命周期

**文件责任：** shop-merchant/service/ResourceBookingService.java、service/impl/ResourceBookingServiceImpl.java；
容量SQL放MerchantMappers.ResourceBucketMapper/BookingHoldMapper/BookingHoldUnitMapper；必要独立DTO在merchant.dto。
共享ResourcePlanContract、V535与既有来源许可合同保持冻结；本任务不加公共用户HTTP、不复制美业规则。

**消费：** ResourceCommandService.accept/find/lockForExecution/completeSuccess/rejectAfterRollback；
ResourceSourceAuthorizationService.authorize/recover/requireAuthorized；ResourceAdmissionService.lockAndValidate。
**产出：** ResourceBookingService.execute(Scope scope,String commandNo) -> ResourceCommandService.Receipt；
对已接受命令执行原号恢复，可先authorize再短READ_COMMITTED事务，禁止资源事务内RPC。
Owner本地可增加必要的有界hold/occupancy查询；原命令Receipt保持不可变。

- [x] RED：新增真实MySQL ResourceBookingPersistenceIT及运行包装器；完整两资源成功、第二资源不足全回滚、两命令争同一STAFF跨店只成功一个。
- [x] 实现RESERVE：五分钟bucket初始化和canonical资源锁，容量从权威资源事实获取；原子写hold/unit/held计数、owner头和原命令成功终态。
- [x] 实现PROTECT/CONFIRM/RENEW/RELEASE/EXPIRE及整计划REPLACE；目标hold/version/leaseRevision/sourceRevision/ownerRevision均严格检查。
  固定临时租期15分钟，SOURCE_COORDINATED；续期从当前时刻延15分钟且leaseRevision+1；PROTECT同样延长并增加leaseRevision，不能绕来源许可。
  CONFIRM将held转confirmed且重复原号不重复扣量；到期HELD不得确认或续期，EXPIRE仅HELD且now>=expiresAt；CONFIRMED不得按TTL回收。
  RECONCILE明确为SOURCE_COORDINATED受保护HELD的恢复确认（可过原租期），仍须真实来源许可和重新准入；恢复释放走独立RELEASE，不能从终态复活。
  RELEASE可释放未开工HELD/CONFIRMED；executionStartedAt不空拒绝。所有动作保存原终态且再用同命令返回同Receipt。
  REPLACE新旧全资源统一排序锁，按净增量检查容量；先验证后原子释放旧/写新/链路关联，失败保留旧hold、units和owner头。
  新hold沿旧状态（初次HELD），旧hold的creationPlanHash不可重写，新hold自身的creationPlanHash/currentPlanHash均来自新计划；历史units不删除。
  完整资源变更、安全事件outbox、原命令终态与owner头同事务；不在事件中携带原冻结载荷或顾客资料。
- [x] GREEN：真实MySQL覆盖上述动作、同号重试、过期许可不写、故障回滚与未知结果原号find；异常不得把提交未知伪造成拒绝。
  只在能证明资源事务已回滚时才rejectAfterRollback，未知异常保留ACCEPTED供恢复。
  真实来源持久DENIED或原AUTHORIZED许可已过期时，可在owner/原命令锁内核实仍ACCEPTED后安全拒绝原号；并发已终态返回原结果，不直接解除来源栅栏。
  旧计划与未释放units必须完整一致，桶计数与全部活动units及合法hold父链一致；缺行、软删或悬空不能制造可用容量。
- [x] 自审、一次守卫消融RED→GREEN，运行受影响共享内核测试并提交；输出精确日志和接口供下一任务使用。

#### Task 51: BM03真实预约来源与业务服务

**文件责任：** beauty-api Req/VO与AppointmentService；beauty-core预约准备/匹配/来源授权/协调Service及Mapper；
必要追加V3/V4私有迁移、独立增量真源与严格validator清单。不得回写V1/V2，不依赖merchant实现。
**接口：** 与Task50通过shop-base资源Booking Port接线（由本任务补端口及owner薄适配），行业生成原计划，owner处理容量。

- [x] 先对齐原API038～045、147～153、160及AVL-01/APT-01～03实际字段；持久预览含真实政策/商品/定义版本、安排和修订，禁止客户端提供frozenPlan/holdNo当真相。
- [x] 用真实门店、服务商品、定义、人员资格/周班表、资源事实生成有界可约候选和完整计划；指定与自动匹配，包含缓冲、多人/多资源；匹配不等于容量承诺。
- [x] 持久准备→owner.accept→绑定真实原命令→预约根/fence/intent授权→owner.execute→原命令核验→本域终态落地/历史安排→释放fence。
  许可超时不自动解fence；网络未知返回operation并按原号恢复；改约失败保留旧安排。
  最小恢复扫描复用现有JobHandler/Outbox，持久操作号、当前命令、重试时点及版本/短租约CAS；进程重启可按原号继续，不靠内存Future或GET查询产生隐式新命令。
  创建的预留成功后用下一独立原命令确认，最终预约CONFIRMED须与真实CONFIRMED hold一致；中间未知保留操作进度，未确认HELD不宣称成约。
- [x] 当前经营权限逐次验证，消费者自约绑定本人，代约顾客与临客不伪造订单买家；创建/列表/详情/改约/取消/爽约与恢复全部Service联验。
**身份与来源实施裁决：** C端只认CONSUMER/USR当前认证本人，B端只认MERCHANT/MCH或USR当前经营授权。
内部协议必须原样携带realm/subjectKind并使用既有LoginUser恢复审计上下文，不把所有调用者转成MCH。
预约权限复用平台BizPerms管理机制，新增跨行业`biz:booking:view`（查看预约）与`biz:booking:manage`（创建、调整及取消预约）。
只读允许view或manage；来源freeze/query与写操作须manage，逐次查询真实成员权限；到货receive、核销verify、看订单order:view均不自动取得预约管理权。
BizPerms.LABELS及可分配码一起登记，不修改既有内置角色授权；OWNER既有裸*继续有效，其他角色由店主明确授权预约权限。
权限不增加新的认证/角色系统，不向既有员工静默加权限。
代约的已识别customerRef先经CustomerIdentityPort解析，持久引用规范userNo；无已识别顾客时生成本店受控guestRef，不伪造消费者/买家。
ResourceBookingPort是受信行业服务端合同：accept/query/execute/queryHold使用显式规范scope和真实操作身份，来源Service先验归属；
原回执由真实owner返回并持久绑定到来源准备记录，freeze只认该完整Binding和服务端生成计划；不得把公网传入的Binding写成已验证意图。
原ResourceCommandFactsPort的经营成员只读权限保持原样，不能放宽为任意消费者可读；C端协调经新的可信行业命令合同核验本来源原回执。
付款/权益的sourceRefs非空时，必须接真实来源合同，否则明确能力关闭；不在BM03创建消费者订单或宣称已收费。
完整资金/工单绑定仍按BM04/05原业务计划交付，不能用sourceRefs字符串替代证明。

- [x] 真MySQL验证来源不可伪造、持久幂等、并发同预约、旧版本拒绝、当前撤权、失败保旧、终态对账、原号恢复，提交。

#### Task 52: BM03端点与独立协议

**文件责任：** beauty-web预约/可约/operations Controller、beauty-channel远程Service/资源/来源协议、beauty-server内部Controller；
平台内部owner资源Controller及自动配置。共享机器令牌不替代真实操作者，外部不能提交主体/actor/裸资源许可。

- [x] 按Task51真实Service接入B/C端，校验trusted context和门店范围；真实TCP覆盖错误令牌、跨主体/跨店、UNKNOWN及断连，不返回伪成功。
- [x] embedded与standalone只注册唯一真实provider，禁用不注册；三端路由与API/Java清单增量对账，不生成未实现壳。
**端点落实：** 保持原BAPI010、038～045、146～153、160路径、DTO和真实Service签名，实际实现后再更新状态。
新增`POST /biz/x/beauty/operations/{operationNo}/recoveries`是明确增量，不改写原160项基线；GET只读。
B端可信BizContext、原始X-Store-No与当前MCH/USR成员；C私有预约虽在permitAll的/mp链上仍逐次要求CONSUMER/USR本人。
Availability公开入口保持匿名；不让请求体覆盖tenant/entity/actor/realm/subjectKind。

**协议落实：** channel仅依赖api和共用合同。传递完整可信LoginUser命名空间并在机器鉴权后恢复上下文，finally清理；
原MCH-only政策协议保留兼容，新预约协议不能将B-USR/C强转MCH。Booking/Candidates当前合同接真实merchant owner，
BEAUTY_APPOINTMENT反向freeze/query只认完整持久Binding。独立服务B端识别顾客经真实CustomerIdentityPort远程只读合同，
不降级成guest，不增加扣款许可。商品master revision经原事实协议完整透传，不以null/默认0补证据。
owner和source异常显式403/409/503，禁止HTTP200错误对象被当作空事实；远端比对完整返回身份及Binding。
真实TCP测试带全局异常处理、错误令牌无owner调用、跨店/主体、B-MCH/B-USR/C、UNKNOWN、断连、畸形返回和上下文复用。

**装配落实：** embedded唯一local source；standalone HOST只有web+channel而无core、使用remote source callback；
standalone SERVER有core和local source、通过remote owner访问平台。禁用或ops形态不注册公开预约路由/恢复Job；受机器令牌保护的内部协议沿已批准部署合同。
记录下一任务需要的JAR、配置、机器协议与provider类；三端API/方法增量对账，不提前打开BM04/06。

- [x] 测试并提交。

#### Task 53: BM03模块验收与部署

- [x] 联验实际创建→查询→确认/改约→取消/爽约→恢复；完整组合并发、失败保旧、过期许可与未知保护。
- [x] 构建实际JAR，临时MySQL真实迁移与embedded/standalone启动，经HTTP调用真实provider完成上述闭环；默认零售和餐饮回归。
- [x] 记录测试/迁移/配置/影响范围、消融和模块准出证据；独立全模块审查及必要修复；未完成项如实记载，不把基础readiness等同全部49任务交付。

**部署深化：** 生成独立BM03 harness，复用既有认证、原生fixture、迁移、构件哈希及finally清理机制；
保留BM01/02脚本与历史证据。实际构建embedded宿主、standalone宿主（无core）、beauty-server、默认零售构件，分别保留哈希。
独立形态用明确本地端口建立HOST→SERVER→HOST owner→SERVER source双向路径；禁止用带local来源的embedded进程作独立owner。
优先让SERVER使用单一私有schema，仅bty与必要sys/outbox/幂等/锁基础表，不复制商品/会员/订单/资源业务表。
源服务关闭后owner不能绕过来源许可继续执行。真实B/C登录验可约/预览/创建confirmed、列表详情、重叠改约、失败保旧、取消/爽约、
当前撤权、另一有权管理者原号恢复；实际丢响应/进程重启后按持久同键恢复，SQL只作事实核验，不代替业务动作。

**恢复调度：** JobHandler不是实际定时运行证明。沿现有JobDeclaration/JobHandlerRegistry/JobHttpPaths及共享令牌、ShedLock的目标协议，
补齐最小共用接线；优先抽取既有通用目标逻辑，不复制JobHandlerEndpoint或另建美业定时器。
独立服务不引入shop-app业务依赖；实际隔离job-worker及框架库可用于验证声明发现、真实触发、运行记录和重启恢复。
恢复只核验已知原终态，不以调度账号伪造用户授权；需要认证的步骤仍显式待处理。

**准出证据：** 当前正式V1～V4迁移/严格manifest及支持升级、真实provider类/构件内容、实际API/错误码/事件去重、
默认零售构件无美业入口与共用路径、餐饮受影响回归。原生并发/保护事实与部署HTTP证据分开，归档事件不声称通知实际投递。
记录完整结果JSON/日志/哈希；整体readiness可按未交付BM04～06保持503，不强改能力门控。


### 2.18 Task51预约真实来源/业务详细结构（服务检查点完成）

Task50已通过审查，当前基线ac76c2af8。原Service方法及DTO命名沿方法JSON的BAPI010、038～045、146～153、160；不提前实现052接待工单。

| 文件/组件 | 消费→产出 |
|---|---|
| base ResourceBookingPort＋merchant owner适配 | 显式Scope/Actor、Accept、原Receipt与Hold；accept/query/execute/queryHold，reconcile仅受信原Binding终态读取，不接受或执行新命令 |
| base ResourceCandidatesPort＋merchant owner适配 | 有界候选/区间容量；B/C可见人员边界，资格/班表事实和真实桶余量，不返回跨店他人业务引用 |
| api AppointmentService/AppointmentAvailabilityService/ServiceOperationQueryService | 原Req/Query/VO合同；公开入口不接受frozenPlan、可信actor、主体或hold作为授权 |
| core匹配/准备/协调/来源权威/查询 | 每个组件单一职责；事实RPC先于本域短事务，provider不急切依赖executor避免Bean循环 |
| core RecoveryJob | 已有JobHandler，短租约CAS，仅核验/协调原绑定终态；没有用户在场时不伪造LoginUser或新签许可 |

**V3追加四表：** preparation保存有期限预览及规范顾客/临客、原始计划与安排快照；operation保存幂等键/请求摘要、稳定接受载荷/步骤、已绑定原回执、进度/重试租约；arrangement保存不可变安排修订和快照；current明确当前安排指针。
V3已经临时库实际执行，保持其摘要冻结；追加V4的bty_appointment_resource_decision持久保存原intent/command/decision对应的fenceNo/fenceRevision，V2仍拥有原Binding/许可窗口。
独立增量真源/生成器和严格manifest/readiness4；V1/V2/V3不变，原41表目标清单保持历史基线，新增五表按本节增量对账。
BaseEntity.version是预约根修订，appointmentRevision是安排修订，状态更新不得导致历史安排丢失或读入所有历史项目。

**商品版本事实补齐：** ServiceProductFactsPort.Fact与ServiceCatalogFactsPort.Service追加可空goodsRevision/skuRevision，旧构造器明确为null保持既有调用兼容；
真实商品适配从PrdGoods/PrdSku.version读取并传播，美业预约必须取得真实非空非负修订并冻结，不能把缺字段伪造为0。
这些是商品/SKU主数据修订，**不是报价版本**；GoodsQueryPort仍拥有门店价/限时活动覆盖，预约只冻结观察到的实际报价/时长及修订，并在提交时重验。
不为取版本持锁调用商品/活动，不宣称跨域原子报价，不开启订单/付款。真实adapter测试覆盖DB版本含合法0、旧构造器null及既有读取兼容。

**匹配细则：** 项目按顺序执行，qty展开重复服务实例；缓冲占用按五分钟桶对齐，下一个项目在前项缓冲结束向上取整后再预留自己的前缓冲。
同资源的所有需求按规范resourceNo/桶跟踪使用，避免相邻非五分钟项目产生必然失败的假预览；STAFF数量须不同规范人员/资源。
root起止与逐项实际服务时间明确展示缓冲/间隔后的总时间线。候选扫描上限50，达到上限标记complete=false，不把未查全当作确定无可约资源。

**编排：** 预览先读取当前真实商品/政策/项目定义与所有要求，生成完整组合后持久化，无容量承诺。
创建先本域原子保存HELD根(version1)、永久空fence和持久operation；接受参数/键必须在owner.accept之前落库。
拿到owner实际Receipt后绑定intent/fence；RESERVE成功核验后，持久化下一独立CONFIRM接受载荷及键；仅真实CONFIRMED hold可把预约标记CONFIRMED。
任何丢响应都回查原号或重放同接受参数；不从变化后的当前owner重新猜预期版本。
改约REPLACE成功才原子切current安排，拒绝保旧；取消/爽约RELEASE有真实终态才改状态，历史不删除。
根修改、安排切换、intent终态协调与fence解除在同一个根锁短事务内，原始结果查询在事务外。

**许可与恢复裁决：** 原decisionNo/issuedAt/validUntil同Binding不可续写；过期保留原授权形状直到owner确定终态，随后协调解fence。
同Binding在phase3已协调后仍按当前真实actor权限返回原始决定（包括原fence修订），不把已知历史改成UNKNOWN；owner先核验原命令终态，旧决定不能复用到新命令。
原fence元数据必须持久化，不能编码在ID、从计数推导或依赖审计保留期限；丢失/损坏则失败关闭。
不能以“用户又点了一次”延长原许可。确实需要新动作时，仅在旧号已确定终态后，以新的已授权业务动作/明确持久步骤取得新命令。
RESERVE已成功而CONFIRM被拒绝时保持真实HELD和持久RELEASE_REQUIRED，不能伪造成约成功或已释放；允许经本人/有权经营人员明确取消完成释放。
后台只协调已经绑定的原终态；未取得接受回执、仍ACCEPTED或下一CONFIRM需要新授权时，保留操作及准确阻断原因，等待当前认证用户按原键重放。
为解决发起员工撤权后的在途操作，增加ServiceOperationRecoveryService.recoverOperation(entityNo,currentStoreNo,actorNo,operationNo)→OperationVO，
仅当前同店MERCHANT MCH/USR且BOOKING_MANAGE允许。它重推准确持久原键/原命令，不改原actor、幂等命名空间或initiatedBy；
Task52新增POST /biz/x/beauty/operations/{operationNo}/recoveries（BM03恢复增量接口，映射既有BTY-T03/T04），操作号本身绑定幂等恢复目标，不另造预约/资源来源。
必须验证撤权发起者→另一当前有权人员恢复同原命令；无权限者、跨店或非当前操作不能推进。
查询API只读进度，不在GET中隐式执行。原计划READ_MODEL预览的“无占位”语义保留，但持久预览需短元数据写事务，不使用数据库readOnly写入。

**验收归属：** Task51原生真实私表/服务测试可使用明确外部事实和owner合同夹具；不得把其当实际共享owner或独立反向协议证明。
Task52接真实HTTP和身份传输；Task53必须在独立平台宿主（仅web/channel）→美业server→平台owner→美业来源的真实双向路径验收，不能沿旧embedded owner本地授权形成假通过。

### 2.19 Task52预约端点与双向协议详细方案（协议检查点完成）

基线2ae608265，先写HTTP/身份/装配反例，再接真实Task51服务；不新增迁移。

| 文件责任 | 实施关系 |
|---|---|
| beauty-web api.biz/api.mp 五Controller | 原预约/可约/operation路径与显式恢复POST；薄复用BizContext、StoreRequestScopeValidator、SecurityUtils，不新增BeautyAuth体系 |
| beauty-api typed内部协议 | BeautyBookingInternalApi携带tenant/realm/subjectKind/actor可信委托；原DTO/Service沿用 |
| beauty-channel 四remote Service | 预约、可约、操作查询及恢复；机器调用不改变原身份，返回校验/失败关闭，无core依赖 |
| shop-base＋平台内部owner入口 | ResourceBookingInternalApi包含Booking/Candidates真实合同；CustomerIdentityInternalApi只读现有会员引用解析，保留票据范围/操作者/有效期 |
| beauty-server内部booking/source入口 | 机器令牌先验，按准确LoginUser恢复并finally还原；source只认BEAUTY_APPOINTMENT及持久完整Binding |
| channel-only remote source provider | standalone HOST无core，只能经反向HTTP；SERVER有core用local来源并远程owner；embedded仍唯一local来源 |

CapacityQuery的factAt与完整Replacement证明必须透传，不丢弃自占用排除范围；商品真实master修订原样传输。
IncompleteSearch在正确Service/API边界转三语明确业务错误，公开可约结果仍用complete=false，不等同确定冲突。
内部异常用403/409/503区分；空事实显式合同，HTTP200错误对象、缺必需字段或错Binding不能成为成功。
验证带实际全局Advice的TCP、B-MCH/B-USR/C、错机器令牌无owner调用、跨店/本人归属、UNKNOWN/断连/畸形响应、上下文清理；
条件装配覆盖embedded/standalone/disabled/ops及循环依赖。增量API/Java状态只登记实际实现入口，原160目标基线不改写。

### 2.20 Task53真实构件与恢复调度详细方案（已验收，见§31）

基线50eeaa5d9；独立BM03 harness保持BM01/02历史脚本。构建并复制四个实际业务构件及隔离job-worker；
embedded使用平台schema，standalone HOST只含web/channel、SERVER含core/channel且使用私有schema；
HOST与SERVER经观察代理双向互调，停止SERVER时owner不得绕过真实来源授权。

真实B/C密码登录、按当前时间生成可约日期，经人员/排班维护HTTP建事实；
目录按真实UNRESTRICTED模式新发布，不修改旧版本。验证创建CONFIRMED、列表详情、重叠改约、失败保旧、取消及爽约。
accept回执未知由另一当前有权管理者沿原键恢复；
CONFIRM已提交而响应丢失，由实际job-worker调度已绑定原终态协调，不新造用户授权。

共用任务目标沿现有JobHandlerEndpoint/JobHandlerRegistry/ShedLock协议抽取，保留旧平台薄兼容及JSON/令牌语义，
每个context仅一组JobHttpPaths，验证app/server无双重注册。恢复声明初始一分钟、timeout60秒、lock120秒并可配置；
私有schema只加必要基础表，不复制商品/会员/订单/资源。调度证据必须含声明发现、实际触发与运行记录，
不以JobHandler bean或测试替身计作自动执行。
模块文档对齐矩阵、领域对象、数据库字典、README及ADR；23项当前美业私表与原41目标基线分列，
基本预约时间线与BM06任务日历、归档outbox与实际通知分别记载。

### 2.21 BM03模块审查修复方案（2026-10-22，已修复及复审通过）

独立审查 `/tmp/review-bm03-module.md` 对Task53自身实现给出Approved，整体BM03仍有两项Important；修复基线c338e5722，统一处理后只复审修复范围，不提前启动BM04。

- 恢复公平性：持久查询在LIMIT前排除尚未到期及活租约行，以nextRetryAt、id稳定排序；保留CAS认领、租约、原命令号和待授权永久栅栏，不通过后台伪造身份推进。无需新增DDL。原生数据库覆盖超过50个等待操作及后续已绑定终态，按默认60秒节奏推进仍应恢复后者，另验活租约不挤占批次。
- 完整匹配：事实一次预取，构造需求槽和候选，以固定顺序进行有界迭代回溯；最多10000次候选尝试/槽位，owner容量展开及候选占用桶物化总预算100000次；任何预算耗尽均明确返回搜索不完整。STAFF按规范personnelNo、单位容量归一，物理资源保留实际容量，撤销精确还原占用。指定项为硬约束，不在搜索节点发RPC或持锁。预算耗尽或候选截断返回搜索不完整，只有完整穷举无解才作确定冲突。
- 行为反例：同项目15/60分钟双角色，P1整小时、P2仅前15分钟，自动及部分指定应找到可行解；固定短角色=P1应无解。覆盖预算耗尽、STAFF别名、物理容量和回溯撤销。
- 验收：先反例RED再GREEN，重跑受影响常规及原生业务测试；重新构建受影响构件，在新的隔离目录重跑实际部署，保留旧证据与新SHA。独立窄范围复审通过后，才能标记Task53/BM03完成。

### 2.22 BM04到店接待、工单与交易来源实施计划（2026-10-22）

目标沿REC-01/WO-01/SRC-01/SHR-03：真实顾客/临客→到店或直客→幂等工单及任务范围→普通服务订单来源→逐来源账单及原号恢复。延续原自动执行及SDD方式；本模块完成后双形态隔离部署与独立全模块审查。BM05执行/完工、BM06完整任务日历不提前交付。

已核对BM03准出和原数据原语、方法JSON；临客登记/认领尚无公共owner。普通OrderService.createFor可复用，目录revision不等于报价，权益服务单不替代普通订单。具体交易审计见[合同审计](../../.superpowers/sdd/beauty-backend/bm04-trade-contract-audit.md)。选择真实C接管checkout，复用STORE_VERIFY并在交易owner原子登记受管理履约及统一旧完成防线；禁止APPOINTMENT旧slot双扣和STORE_PICKUP回落。原V1–V4、平台V1–V536冻结。

| 需求/API | 子任务 | 验收焦点 |
|---|---|---|
| BTY-E02 / 031、032 | 54公共身份、55行业接待 | 无虚假买家，真实身份及持有凭证双验证、并发唯一认领 |
| G01/02/04/05 / 047、048、052、053、154 | 55业务、59入口 | 签到取消互斥、一次转单、整组合资源、异常协调不核销 |
| H01/H03 / 054、055、056、155 | 55/58业务、59入口 | 项目范围版本、快照与数量守恒，未就绪不得开工 |
| I01–05 / 068–073、必要来源确认 | 56交易owner、57资金owner、58编排 | 同次成交价格校验、当前买家确认、原号及真实金额 |
| 模块准出 | 60 | 双部署/私有库/当前构件、零售餐饮影响及独立审查 |

全局约束沿§1；原49任务/160API目标不改写，新增共用合同/机器协议单列。每个子任务启动前继续深化本节接口、字段、状态与AC，不按空Port计算交付。

#### Task 54: 共用临客登记认领与本人身份核验

**Files与归属：** shop-base/spi/member新增GuestRegistrationPort/GuestRegistrationInternalApi；CustomerIdentityPort/InternalApi增量本人核验；shop-core/member新增MbrGuestRegistration、MemberGuestRegistrationMapper、MemberGuestRegistrationService/Impl、MemberGuestCrypto及Port实现，扩展MemberCodeResolveService/Impl复用现有会员码；shop-app/portal/internal新增InternalGuestRegistrationController、扩展InternalCustomerIdentityController；beauty-channel新增RemoteGuestRegistrationPort及现有RemoteCustomerIdentityPort/owner接线。追加平台V537__shared_guest_registration.sql（已在本任务原生库执行，冻结字节），新增V538__member_reference_actor_namespace.sql给原mbr_identity_ticket追加可空actor_realm/actor_kind，按需同步当前schema-test夹具及生成字典；原迁移不动。测试/原生脚本按下述实际覆盖新增，配置/错误码/三语同步。

**Interfaces（唯一契约shop-base，不导入其他域实体）：**
- GuestRegistrationPort.Scope(String entityNo,String storeNo,DelegatedIdentity identity)。
- Registration register(Scope scope,RegisterCommand command)；RegisterCommand(String contact,String displayName,boolean consent,String idempotencyKey)；Registration(String guestNo,String claimToken,String state,long revision)。
- GuestFact get(Scope scope,String guestNo)；GuestFact verify(Scope scope,String guestNo,String claimToken)；GuestFact(String guestNo,String state,String userNo,long revision)。
- Claim claim(Scope scope,String guestNo,ClaimCommand command)；ClaimCommand(String verifiedIdentity,String claimToken,String idempotencyKey)；Claim(String guestNo,String userNo,long revision)。
- CustomerIdentityPort增加default ResolvedCustomer verifyConsumerProof(EntitlementContext context,String memberCode)，默认明确不支持，兼容原单抽象方法；真实member owner覆盖。MemberCodeResolveService增加ResolvedCustomer requireConsumerProof(String tenantNo,String userNo,String memberCode)。另增ResolvedCustomer requireGuestClaimReference(MemberCodeScope scope,String customerRef)，新guest认领必须验证票据的当前actor realm/kind；发行新cr1时从真实当前MERCHANT身份绑定两列，旧无namespace票据对新guest认领关闭，旧requireReference合同保持兼容。

**身份/数据：** 公共临客归member，不新增bty_guest。实体沿BaseEntity，含entity/store/guestNo、登记actorNo/Realm/Kind、原keyHash/requestHash、加密contact、displayName、claimTokenHash/密文、REGISTERED/CLAIMED状态和claimedUserNo/claimedAt；原键唯一索引包含身份命名空间与店，认领同guest根锁。含contact/displayName的登记requestHash采用配置密钥用途隔离的HMAC指纹，不以无密钥SHA暴露低熵联系资料猜测oracle；随机token/key摘要可用SHA。contact最长128且可空，displayName最长80，consent必须true；不按contact自动创建账号/会员/订单。claimToken服务端SecureRandom32字节base64url，哈希比对、密文用于同键重放，禁止原凭证出现在日志/outbox/幂等JSON，相关DTO.toString脱敏。
MemberGuestCrypto采用AES-GCM随机IV和tenant/entity/store/guestNo/purpose关联数据，配置shop.member.guest.credential-key为32字节base64，无默认密钥；未配时仅本能力关闭，不破坏默认零售启动。MEMBER_GUEST_UNAVAILABLE=40042 / err.member.guest_unavailable（加前核对唯一），三语和InternalServiceResponses的503映射一起补齐。

owner内部校验当前SecurityUtils完整身份等于Scope.identity，以及真实门店归属/当前当店BizPerms.CUSTOMER权限。机器令牌不赋权。原登记actor仅限定登记幂等重放，不永久占有guest；同entity/store另一当前有CUSTOMER权限的员工可get/verify/claim，使用自己的cr1与原guest凭证，禁止冒充原actor。claim同时校验原guest凭证与当前actor/store绑定cr1引用解析的活动真实用户；绑定后不能改为另一个人，重复原键回原结果、改参冲突。已提交认领的精确原key/hash/完整claim actor与原secret可在cr1自然过期后重放持久结果，仍先重核当前权限及已认领用户活动（外域事实锁外，锁内核相同user/revision）；新认领必须严格有效cr1，不能把重放改为新授权。get/verify不泄露contact/密文。本人proof只认原MEMBER_CODE短票据，同租户/当前CONSUMER-USR及活动用户，不接受登录令牌/手机号/任意字符串。身份核验不提供支付授权；现有BTG仅匿名引用，兼容在55详定，不凭格式伪造公共guest。

**协议：** register/get/verify/claim及verify-consumer-proof强类型完整identity/scope/请求回显核验；错token在owner之前拒绝，DelegatedIdentityContext finally恢复。沿真实400/401/403/404/409/503，HTTP200错误对象不能当事实；旧协议兼容。remote-owner形态唯一装配，embedded/default无重复provider。

- [x] RED：MemberGuestRegistrationIT及协议测试覆盖登记幂等/改参/跨realm、跨店/他人/撤权、真实mc1过期/他人/伪造拒绝；原生并发认领只有一个真实用户胜出，回滚不新增账号/订单；缺密钥明确不可用、重启后原键重放、存储不含原始contact/token、密文篡改拒绝。编译/环境错误不算行为RED。
- [x] 实现上述合同、owner、迁移、加密和远程协议；只用自建隔离缓存MySQL8.4、localhost端口、--pull never，finally清理，不并行启动多个native库。
- [x] GREEN：目标native、真实TCP及全局Advice/当前identity；原会员码流程、三语/架构/SQL方言和beauty装配按影响回归。消融身份或唯一认领约束使反例变红再还原。
- [x] 精确提交本任务文件及task-54-report.md（列H2/native/TCP/fixture边界），controller主TDD/progress不提交；独立spec/quality审查通过后进入55。54不是BM04模块准出。

**验收：** Task54已完成（e90e12aab..7b3142e3f）；独立审查唯一Important为API生成源不一致，统一修复后spec PASS / quality Approved。native13+独立JVM重放1、beauty109通过；平台相关45项中40通过、2按profile跳过、3已明确追踪守卫失败，不能声称全平台绿。报告与完整/复审记录见§32。

#### Task 55: 接待、临客引用与工单范围

依赖Task54实际共享身份及已审BM03；控制器已确认业务规则；正式实现先核对Task54最终签名并冻结方法/字段补充设计。原API方法名/Service归属以beauty-java-method-design.json为准，公开Controller和wire归59，业务实现归55。原BTG仅匿名，不倒填虚假客户。

**业务边界**

1. 身份登记/认领通过真实GuestRegistrationPort，B核验当前CUSTOMER，接待/转单额外BOOKING_MANAGE，普通查询BOOKING_VIEW或MANAGE；财务账单在58另验ORDER_VIEW。C只当前CONSUMER-USR与本人归属。认证账号不等于技师，任职/资格由既有资源owner匹配。
2. B签到CheckInReq(appointmentNo,identityProof,expectedRevision)，C MyCheckInReq(appointmentNo,checkInProof,expectedRevision)。USER预约B需当前actor的cr1身份等于预约规范客户，C的checkInProof明确为复合CheckInProof(memberCode,arrivalChallenge)，必须共享真实mc1核验且等于当前本人，并持当店未过期到店码；正式GUEST需guestNo/凭证证明，已认领者可通过已验证不可变认领回执解析。未有公共记录的匿名BTG不凭字符串充凭证，明确IDENTITY_REQUIRED并提示走显式异常登记；不能默认为临客已被本人认领。
3. 签到只是CONFIRMED→CHECKED_IN，不创建工单、不调用trade/pay核销、不释放hold。锁外身份/hold事实后本域短事务重锁预约→fence，核expectedRevision/current操作为空/安排与hold证据不变，markArrived CAS；取消/改约/爽约同根互斥。早到记录timing=EARLY且保留原计划时间，计划开始后但结束前记录LATE；预约结束后明确需要重新安排，不凭过去hold当当前资源。异常API记录后不自动绕过容量或放宽原单。签到时刻取server UTC。原键重放先验当前身份/权限，不能靠旧缓存复活已撤权操作。
4. 预约转工单必须已签到（052不隐式签到），expectedRevision准确；不同键并发同预约只一个工单。已成功创建的原键返回原工单，即使创建动作推进了原版本；同键改参冲突。打开工单冻结当前全部项目/定义及安排，任务PENDING、bindingRevision0、done/void0，canStart=false，startedAt不填。工单OPEN/scopeRevision1；任务每个原预约项目一行，数量来自原不可变快照。
5. 接待分配若与当前安排相同只确认；若不同，必须在创建任何工单/实际任务前完成真实owner整计划REPLACE。允许内部RECEPTION_CHANGE处理CHECKED_IN且无工单，保留checkedInAt/状态，成功才新安排；失败保持旧安排。已过原开始时间时不能在过去预留，需明确新的未来5分钟网格startAt（新增可选字段），不静默平移。完整预约范围重排/资源未知沿BM03原命令/fence协调，不能直接改assignment表。公共普通改约仍保留原权限/状态，不开放已开工修改。
6. 直客ReceiveWalkInReq包含storeNo、customerRef或guestNo二选一、items、assignments、startAt可空。空startAt采用含before buffer后最早未来5分钟网格并回显；指定必须明确未来且满足既有bookingAheadDays及资源owner约束，不额外发明30分钟业务上限。先持久接待operation及规范已核验客户引用，再通过原Matching/Preparation/Coordinator完整预留确认；无明确终态只返回PENDING及operationNo，不建任务。成功再按签到和转单内部合法流程提交，重启/丢响应沿固定原键查询，不能重新生成预约/hold。
7. 普通openWorkOrder可基于已签到预约复用052；无预约则创建未安排工单/任务（UNASSIGNED，canStart=false），不假称已占资源。需要即时资源的直客必须048完整流程。items只P1单步骤SERVICE，qty展开总量≤10，冻结真实已发布definitionVersion和规则，商品/门店可售事实锁外读取，短事务核已观察本域版本仍有效。
8. 提交/加项和真实报价/金额在58接56/57 owner；55只建立范围。工单内容不可被目录后来修改覆盖，任一任务无真实来源不得可开工。不以有appointmentNo或已付观测推断履约完成。

**数据与幂等（V5四表，编码前冻结JSON/DDL）**

9. BtyReceptionOperation：tenant/entity/store、operationNo、actorNo/Realm/Kind、endpoint/action、keyHash/requestHash、appointmentNo/workOrderNo、规范identityKind/identityRef、phase、关联原appointmentOperationNo/derivedKeyHash、冻结无凭证业务payload/result、nextRetryAt/leaseUntil、安全错误码。唯一tenant/entity/store/actorNamespace/endpoint/keyHash、operationNo；关键身份及原派生号在外调前保存。phase RECEIVED/RESOURCE_PENDING/ARRIVAL_PENDING/OPEN_PENDING/SUCCEEDED/REJECTED；UNKNOWN保留原号，不能按超时清除fence。不得把cr1/mc1/claimToken原文写此表。
10. BtyWorkOrderContext：一对一workOrderNo（entity/store父链）、identityKind(USER/GUEST)、identityRef、sourceReceptionOperationNo、appointmentArrangementRevision可空、冻结项目/安排/政策引用。bty_work_order.customer_ref保存带类型的可信引用，不能把GUEST号当真实userNo；消费/付款前必须解析已核验USER。独立上下文避免改冻结旧表；legacy无context仅只读未知，不假造身份/安排。
11. BtyGuestClaimReceipt：唯一tenant/entity/store/guestNo，保存不可变公共claimRevision/真实userNo/原owner回执摘要和记录时间，无contact/原凭证。它只是外部认领结果回执，不是公共顾客主档。shared认领成功而本域响应未知时，当前有权者沿原请求查owner事实后补写同一回执，不重认领/改绑。
12. 原匿名BTG不自动转公共guest。新增预约preview/create的可选guestNo显式字段（保留原构造器兼容，customerRef与guestNo互斥），仅接受owner当前事实；CLAIMED映射当前USER，REGISTERED保留真实guestNo。无提供方/陌生guest failclosed。旧null customerRef的BTG行为保留但不能伪claim。BAPI031新登记后可用于后续预约/直客。
13. C预约读/列表和工单归属同时支持直接USER或本域不可变claim receipt映射的guest；WHERE包含tenant/entity/store和deleted/父链，不先返回他人再过滤分页。Receipt按已验证owner事实写入后才可授权；不复制联系方式。共享owner已被其他行业认领但本域无回执时，显式原认领核验/补齐后再开放，不凭号码猜用户。
14. 幂等作用域含完整actor命名空间+店+endpoint，原hash只存摘要。登记/claim跨域原键由请求稳定派生，CURRENT有权限第二员工可恢复未完操作，但不能冒充旧actor发新授权。已完整本域提交只读恢复不需要过期原短票据再次有效；新身份变化需新证明。恢复例外必须明确：REGISTER已在共享owner成功但guestNo响应丢失时，只有原登记actor携同一原请求可重放；其他员工得到待原操作人恢复的明确状态，不伪造actor，不宣称全窗口跨员工恢复。已知guestNo且本域先持久已核验USER的claim未知，可由当前同店有权员工get核相同不可变CLAIMED事实后补receipt。任何RPC在本域事务外，纯本域成功状态/动作日志/安全outbox在同短事务提交。

到店证明增量：B当前BOOKING_MANAGE可POST /biz/x/beauty/arrival-challenges（原160基线之外单列）签发当前店到店码，默认90秒；ReceptionService.issueArrivalChallenge(entity,store,actor,key)无用户可调TTL。BtyArrivalChallenge保存guest/消费者无关的公开门店码及到期、签发actor/原键，码为SecureRandom32字节base64url，同键返回原码。它是门店可公开展示的短时挑战，不是登录/支付凭证；不写日志/outbox。C需真实本人身份和同店挑战两项，陌生店/过期/猜测拒绝；仅mc1或登录令牌不能证明到店。该码提供短时门店持有证明，不宣称GPS防转发认证。

**异常协调与读取**

15. ReceptionExceptionReq(appointmentNo,kind,proposal,reason,expectedRevision)；kind EARLY/LATE/UNAVAILABLE/CONFLICT/IDENTITY_REQUIRED，proposal REQUEST_RESCHEDULE/KEEP_WAITING/REQUEST_CANCEL。只记录待协调请求，不自动取消/替换/开工或发资金动作。没有已实现的公共审批/待办owner，采用本域append-only ActionLog及共用Outbox持久登记，VO清楚返回PENDING_REVIEW及原requestNo、受影响任务引用；不声称通用工作流/通知已投递。原flow的外部待办占位在方法JSON改为实际已复用Outbox、增写ActionLog，不能留空Port计交付。
16. WorkOrderDetailVO/MyWorkOrderVO给出原范围版本、逐任务计划/来源状态和允许动作；步骤/参与为空时诚实为空，actualStartedAt保持null，引用的definition/taskSnapshot冻结。所有操作列表以已实现能力门控，不返回尚无实现的start/complete。getBill/submitServices归58，55不先造假金额或已付。
17. 安全事件只含businessNo/entity/store/revision/state，reason/contact/credential/frozenPlan不发公共outbox；ARCHIVED不等于通知送达。

**文件与AC**

18. 原ReceptionService/Impl、GuestRegistrationService/Impl、WorkOrderService/Impl、AppointmentService#openWorkOrderFromAppointment及对应DTO；按职责拆ReceptionPreparation/Coordinator、WorkOrderPreparation/Query等实际helper，Impl直接本域Mapper，不建Repository。现有AppointmentAccess/Matching/Preparation/Coordinator只增加必要内部接待分支，旧正常预约回归必须仍绿。配置BeautyReceptionConfiguration，BeautyMappers增四表和必要scope查询，BeautyDataScopeRegistration及SchemaValidator/生成器/字典追加V5。
19. 测试ReceptionBusinessIT：真实owner票据与预约生命周期，签到取消并发、越权/跨店/C本人、过早/结束后、一次转单并发、冻结旧规则、改分配成功及冲突保旧、旧根/fence未知不能签到；原生外部资源owner fixture边界单列。
20. 测试WalkInRecoveryIT：真实完整组合成功→唯一预约/工单/任务，accept/confirm已提交响应丢失及服务重启恢复原号；resource未知不建单、无DB锁中RPC、第二当前授权员工恢复，不伪造旧用户。实际ownerHTTP与容量并发在60真实部署另验。
21. 测试GuestClaimReceiptIT：当前同店员工交接、guest→规范USER回执、消费/C分页本人归属；shared成功后本域回滚再原号补记、重复/异用户改绑拒绝、旧匿名BTG不能假认领。
22. 测试WorkOrderScopeIT：无预约未安排单不假占资源、已签到原项目完整快照、重复转单/多qty/不同定义、软删/陌生父链、异常记录幂等/outbox原子去重；移除fence检查或task父链条件做一次有效消融。
23. 先RED/GREEN，再beauty常规reactor及相关BM03原生回归/正式V1→V5升级严格漂移验证，目标架构/三语/生成物/SQL守卫。V5字符串状态与char_length约束需要时可按闭合语法扩展CHECK规范化，但不得忽略unsupported或扩大豁免；字面量大小写及未知函数拒绝须有反例。54共享实现未变时复用其已审专项，只在新调用触发具体风险时补测试。Controller/协议归59，55不得声称HTTP或BM04部署已过。

Bean图约束：AppointmentService.openWorkOrderFromAppointment与WorkOrderService.openWorkOrder共用同域WorkOrderCoordinator/Preparation辅助，后者只消费AppointmentPersistence/Matching/Preparation/Coordinator及本域Mapper，不反向依赖AppointmentService，避免Service循环。ReceptionCoordinator也调用该辅助，不复制一次转单逻辑。GuestRegistrationService独立只管共享调用及claim receipt。配置沿现有启用/形态条件，外部Port按调用取用；不得为装配绿写fake provider或缺表静默返回空结果。

接待重分配补充：不能因仅换人/床位而静默切到最新定义/时长；从原已成功arrangement冻结项目/definitionVersion及buffer，复用同一个有界Slot搜索引擎构造未来计划，实际资格/排班/容量取新时段事实。当前商品不再允许该服务时明确阻断/要求显式服务变更，不伪造新版本。新的matchFrozen只供本域已核验快照调用，不接受公开frozenPlan；不得复制整套匹配算法。RECEPTION_CHANGE保留CHECKED_IN，原来源/owner完整绑定仍须核实，任何已有工单/任务禁止此支路。

工单初始来源意图：创建工单/任务同事务创建bty_service_submission的OPEN/RECEIVED草稿，frozenCommand含版本、范围摘要、规范身份和taskRefs，不含cr1/mc1/secret，不据此执行外部建单。Task58由当前合法人明确提交后才进入真实source阶段；原request_key_hash应按actor完整命名空间+endpoint+key稳定派生，适配现有entity/store唯一索引，不让不同操作的同字面key误碰。初始VO返回submissionNo及未绑定状态。

**精确设计冻结决策：** 采用task-55-design.md所列方法/DTO/V5四表及锁序，按55A身份引用/到店码、55B签到/重排、55C工单范围、55D直客恢复四关依次实现和独立审查，仍属同一Task55。V5在55A执行后冻结，后续追加版本而不回改。052新增接口暂以fail-closed default兼容既有Remote，本地实现必须覆盖，不能计远程交付，59接线后移除default。OperationVO增workOrderNo/failureCode并保留原构造器；get/recover按可信scope分派接待原号。真实member身份消费链放shop-app原生bridge，beauty-core资源fixture边界明示，不添加跨域生产依赖。未安排task保持真实PENDING，另以assignmentState=UNASSIGNED表达。安全事件/Outbox随最早使用的55A实现，冻结规则读取辅助先在55B落地供55C复用。

登记031直接委托共享owner，使用完整命名空间派生原键，不在美业复制联系方式或其无密钥摘要、也不创建虚假的本域登记operation；未知operationNo可空，明确原actor同原请求重放。032认领继续先持久已核验USER/guest意图及原号、再外调、后原子回执。实际模块边界补充：GuestRegistrationPort新增仅表示通信结果未知的ResultUnknown异常，remote只转换明确TIMEOUT/UNREACHABLE及REMOTE_ERROR/status=0的无可核验owner结果（含响应无法解码）；不带原请求或敏感资料，core只捕获该类型，不依赖svc-client。403、owner明确能力不可用503和NOT_CONFIGURED仍失败，不能广捕RuntimeException当PENDING；channel分类测试覆盖。后台恢复只扫描实际无需用户新授权即可读取的资源终态；待原人或新proof项不轮占队首，必须覆盖超过50待用户项仍不饿死后续可恢复项。

**55A独立审查修复方案（fix round1，BASE de8409ea4）：** I1在任何新claim intent之前用共享member owner真实严格能力核验当前tenant/realm/kind/actor/entity/store及当前CUSTOMER，已CLAIMED分支同样执行；旧resolveReference兼容合同保持，不让美业读member表/解析私有票据。必要新增fail-closed strict-reference SPI/typed owner协议和local/remote真实实现，生成真源同步。精确合同：CustomerIdentityPort.default resolveClaimReference(EntitlementContext,ReferenceQuery)→ResolvedCustomer fail-closed；owner复用requireGuestClaimReference并实时CUSTOMER授权。新增POST /internal/customer-identity/resolve-claim-reference，ClaimReferenceRequest(identity,context,query)→ClaimReferenceResolution(request,customer)，完整echo校验，Remote不回落legacy。真实host覆盖same actorNo的MCH↔USR、REGISTERED/CLAIMED两状态，拒绝后无新intent/receipt/outbox；合法原intent当前员工恢复保持。I2先核当前身份/权限、preview归属和完整原请求摘要，再查原键已接受operation；存在则用原冻结规范身份/原号续行，新请求才核owner当前guest与preview一致性；覆盖成功后claim重放、未知后claim续行及创建前claim冲突。I3以V6__beauty_reception_identity_guard.sql新增ck_bty_reception_operation_5追加SQL NULL成对完整性约束，V5字节/SHA不变；同步manifest/ready=6、生成真源/字典/夹具，V1→V6有效历史升级及NULL四组合/非法kind/空ref真实MySQL反例，非法存量不得静默修复。I4词法层处理引用identifier，保留literal全部内容或拒绝不支持内容；不全局删反引号，补parser及原生literal反引号漂移反例。四项一次修复后独立窄范围复审，55B仍不得启动。隔离环境调整：首次V6 native所在自建MySQL被4GB Docker VM OOM终止（epoch1792664905），5项通过/47连接setup errors不计业务结果。后续仅自建UUID/--rm容器改用随删除清理的默认writable layer而非tmpfs，64MB buffer、performance_schema关闭及合理内存/连接限制；无host/shared volume、不改生产配置、不碰其他food/pricing容器，仍--pull never/随机localhost/TCP readiness/finally清自己。

**当前子步骤：** 55A完成（2bf892b98..59d49ed1b；统一修复I1–I4后Spec PASS/Quality Approved，历史U1/U2证据限制保留），55B已按[精确任务](../../.superpowers/sdd/beauty-backend/task-55b-brief.md)冻结启动。55B实现047/154/053业务及内部RECEPTION_CHANGE，DTO/签名沿精确设计§2/3，补ReceptionCoordinator与FrozenAppointmentScope及matchFrozen、currentArrangementEvidence；锁序/成功hold取证/资源REPLACE/原定义buffer与政策快照不变、合法状态与异常Outbox事务按本节执行。无新增表前提下沿冻结V6，不写签到/重排空壳、不创建工单/直客/资金，也不提前开放052或公开wire。新增反例先RED再改代码，至少一次有效fence双防线消融；真实member消费链放host、资源fixture边界明示。当前低内存native以BEAUTY_TEST_DISK=1单容器执行。55B完成独立审查后再C/D，整个BM04统一部署仍归60。

**55B独立审查修复增量（2026-10-23，BASE115d1cb74）：** I1接待无变更比较须按资源份数一对一消费，隐式沿用原安排可保留同一资源的多份容量，不放宽公开显式重复输入。真实新回归先发现更早的持久化冲突：已测BM03 physicalCapacityCanBeSharedAcrossDemandUnits允许同一ROOM需求qty=2分配同一capacity=2资源，而旧assignment唯一键uk_bty_appointment_assignment_2禁止这些逐份行落库。控制器裁决保留原容量语义，以V7追加迁移将该键改为同列普通索引，保留每份planned_assignment_no唯一和原根/current/fence/同事务一次提交；不改V1–V6、不合并丢份、不添加无必要ordinal或回填。真源/生成器/manifest/ready7及严格V1→V7升级/漂移负例同步；V7首次执行前由控制器独立留存准确SQL/SHA。当前已部署基线仍6，7待本轮验证。真实create先到达后才运行I1三场景RED，再修一对一比较与内部沿用/新时段匹配，回归原MatchingSearch和容量守恒。该增量覆盖上文55B沿V6的初始假设，C/D在B审查通过后消费新基线。仅fixture/DDL准备错误不算行为RED；M1日志警告分类在报告澄清，不扩无关生产修复。

**2026-10-23当前状态覆盖：** 55B实现115d1cb74、统一修复714542115已独立复审Spec PASS/Quality Approved；V7追加修正容量份数索引，当前ready7、27私表，V1–V7冻结。进入55C工单范围的精确方案整理；实施依据[55C任务](../../.superpowers/sdd/beauty-backend/task-55c-brief.md)。实际B的reassign在ReceptionCoordinator，C必须先明确其与WorkOrderCoordinator的单向共用归属，禁止后续形成双向Bean依赖或复制实现；精确设计核对后继续RED/编码。

**55C开发方案技术收口（2026-10-23，BASEf1d5daf80）：** 精确方案task-55c-design.md已核对，先真实业务RED再编码。把已审reassign移入单一ReceptionReassignment，接待/工单都向下消费，禁止双向Coordinator依赖。prepareReception重载在原root→fence→current→接待operation短事务原子绑定外层原号，不能包新REQUIRES_NEW。PreparedWorkOrderScope追加从真实事实得到的可信Scope，无预约不伪造hold；markWorkOrderOpened保持CHECKED_IN仅version+1。准备scopeHash使用语义项目索引/定义/规则/身份/数量单位/安排，排除最终随机task/workOrder/submission号；最终context保存完整冻结范围/hold引用和真实任务映射，submission草稿保存真实taskRefs与同一hash。先核当前权限/完整hash再成功重放，REPLACE未知不建单，成功以完整持久receipt绑定的确切revision续行。V1–7冻结/ready7，公开wire、直客恢复与资金仍后续任务。

**55C隔离验证环境增量（2026-10-23）：** 后续内核global_oom记录以精确本容器ID确认两次mysqld被VM全局OOM终止；先前仅events时原因未知的报告保留，不回填历史。Docker VM约3.8GiB、其他匿名聚合约3.2GiB、可用约188MiB，无已安装host MySQL8.4。仅本任务自建容器允许显式opt-in低内存模式：128MiB memory/swap硬上限、pool16M/log-buffer4M/redo16M/key-buffer1M、连接16/thread-cache0/table-open64/table-definition400、mysqlx及performance_schema关闭、仍磁盘datadir且不改事务持久性。默认64M测试路径不变，不改生产或其他容器/VM全局配置，不调OOM优先级。可用内存至少160MiB才校准单case，记录自身peak/variables/events；成功后相同单case配置完成剩余验证，若再次cgroup/globalOOM立即停止新容器并如实列未验证AC，不以H2替代。

**启动与验收：** 先据本节及方法JSON生成task-55-design.md，列确切DTO字段/Service方法、表约束、Bean图和AC→测试映射；任何未定事项在编码前由控制器裁决并同步本节。不得靠旧清单自动生成空方法计完成。目标031/032/047/048/052/053/054/055/154/155及到店码增量；P3候位049–051、关闭工单065–067和来源/账单056/071不在本任务。

- [x] 按55A–55D证据完成真实本域事务、原生并发/恢复和严格V1→V7升级；前置RED历史边界与后验有效消融分别记录，详见§32。
- [x] 精确提交产品文件、设计与报告；独立spec/quality审查通过再进入56，公开wire及模块部署归59/60。

#### Task 56: 普通服务报价建单与受管理履约owner

按[精确设计](../../.superpowers/sdd/beauty-backend/bm04-checkout-design.md)冻结CheckoutPort八方法/DTO（支付分支由57补齐）：prepare、readQuote、confirm、place、findOperation、source、initiatePayment、findPayment，confirm/place/initiatePayment只允许真实当前C。复用现有OrderService和同次writer定价，原子比对封版报价，优先采用原doCreate最终同次定价结果之后、库存/订单写入之前的ExpectedCheckoutQuote seal钩子及共享纯经济摘要，不强制整体拆零售定价算法；quote/source/美业任务统一COUNT，拒绝EA等隐式换算。持久过程事实/稳定来源行/原号映射，不复制订单/支付账。SERVICE商品必须真实支持STORE_VERIFY；成交时原子登记managed authority，通用完成写门及全部旧核销/收货/自动完成入口关闭提前完成，BM05执行继续门控。正式支付/线下receipt由Task57独立资金关口补齐；本任务不开放尚未实现的支付入口，不能把旧confirmOfflinePay输出包装成资金凭证。开工前详定签名/状态/SQL/配置/AC、追加平台最新迁移及ADR。


实施深化按档位2复用既有美业PRD与主TDD，补充[ADR-046](./ADR/ADR-046-共用购买确认与受管理服务订单归属.md)及[Task56精确设计](../../.superpowers/sdd/beauty-backend/task-56-design.md)。精确设计、真实实现及独立任务审查已完成，产品提交dbb635a7c；V539首次执行前独立固定并冻结。本地owner交付已验收，尚未部署BM04整体。方案保留现有零售普通writer，与已知本地餐饮分支实际writer一致；不整分支合并未接线定价引擎。首次成功确认/建单请求键永久绑定，异键冲突后查原操作；来源指纹仅标识真实一致观察，未知资金明确阻断。完整资金及offline receipt仍归57。

- [x] 按审计完成可执行设计；真实owner六方法已交付，历史receipt种子仅作只读组件夹具，正式资金归57。
- [x] RED/GREEN、恢复与消融验证及独立审查完成，产品dbb635a7c；具体分批证据见§32.5，支付及独立进程资金恢复归57。

#### Task 57: 资金原支付号恢复与正式线下实收

独立资金所有者内部关口，消费56已确认的普通订单，完成[精确设计](../../.superpowers/sdd/beauty-backend/bm04-checkout-design.md)§7/§8/§9相关部分；不是另一订单或支付账。PaymentOperationPort的prepare/initiate/query/confirmOffline在真实pay ledger上保存原operation→paymentNo/outTradeNo；交易先持久原requestKey映射，资金prepare外调前落意图，open与原号绑定同资金事务提交。所有旧/新open共用order guard，不以不同key绕出双attempt。发送前SENDING已提交，gateway调用锁外；timeout/未知不close/新号，仅query原号。买家新支付/取参数只真实HOST当前C有效会话，不开C身份自报的内部RPC。

正式OfflineReceiptPort仅pay:cash:receive当前当店操作员；C须先确认并建立OFFLINE订单，金额/币种取owner足额待收、CASH/BANK_TRANSFER按正式策略，transfer必须可信evidenceRef，实际actor不是主体号。复用ledger.open/settle产生真实资金receipt再投影订单；重复或已关闭订单收款进入原resolution，不伪造收款或再次收钱。旧managed confirmOfflinePay捷径拒绝。新pay:cash:receive/trade:checkout:prepare仅OWNER已有*、其他角色不自动加权；RECEIVE理货权限不授收款。

- [x] 开工前把精确设计八方法的payment分支、PaymentOperationPort/OfflineReceiptPort字段及owner/bridge文件逐项对账，按最新版本追加平台/独立pay迁移，不重写56已冻结DDL。
- [x] RED/GREEN真实MySQL ledger、不同key并发、open后崩溃/响应丢失/进程重启、未知不新号、回调与query一致、当前C及cash权限、原号receipt；外部仅隔离mockchannel，锁内无RPC。
- [x] 目标原资金/现金/支付竞争回归，提交并独立审查。报价/建单56与本任务均完成后才向美业来源提供完整金融事实。

#### Task 58: 来源提交、绑定与逐来源账单

消费55真实范围及56/57完整CheckoutPort与OfflineReceiptPort，实现056/071/072/073及P1必要来源确认/trace。外调前持久原意图/原号/摘要，来源buyer/store/行/量/版本核实后按guard→工单→任务的既有锁序CAS绑定；数量守恒，旧范围晚回执不能覆盖。UNKNOWN不新号建单/支付，账单金额读取owner且按币种，缺失明确不完整。开工前补完整签名与绑定/撤销边界。

- [x] 细化方案后TDD实现，native并发数量/重复绑定/原号恢复/owner异常/账单不完整验证。
- [x] 提交并独立审查，不提前开放BM05。

#### Task 59: BM04公开入口与双向协议

按原API方法清单落Controller、RemoteService、typed内部API和SERVER ingress。api-only公开入口，默认关闭；B-MCH/B-USR/C、当店/本人隔离、完整回显核验及finally清理。原160目标编号保留，增量合同单列；开工前逐API/Bean图冻结实际签名。

- [ ] RED/GREEN真实TCP+全局Advice/鉴权/错scope/错误200/断连及embedded/HOST/SERVER/disabled/ops装配。
- [ ] 提交并独立审查，留真实构件和配置/HTTP清单。

#### Task 60: BM04大模块部署及独立准出

部署前先补已定位的BM03基础设施包遗漏：c338e5722引入的shop-store-mybatis/ai.neargo.shop.store.config.JobLockConfiguration未纳入顶层包架构登记。优先移至已登记的ai.neargo.shop.job.config（同模块），更新ShedLockConfig与BeautyJobTargetConfiguration薄imports，保持唯一Bean/disabled无DataSource/锁和令牌行为并验证；不通过扩大业务豁免或改棘轮遮盖。该项是Task54基线已有，非新身份代码引入，但须在BM04模块准出前收口。

55C宿主守卫在-Pbeauty-embedded下进一步实测发现ArchitectureTest.topLevelPackagesMustBeRegistered的缺口为[beauty,store]：beauty是本分支BM01新增行业域尚未登记，不能归为仓库原有无关问题。Task60必须将beauty加入DOMAINS业务域并接受现有域间依赖守卫，不得加到infra豁免；若登记暴露真实跨域依赖，修实际越界而非放宽规则。与上文store迁job.config一起在正式部署前验证；普通beauty自身Architecture13通过不能替代宿主这项检查。

- [ ] 对账本节AC/矩阵/API/方法/表，全部真实owner可用。
- [ ] 当前JAR embedded、无core HOST、私有SERVER、默认零售及必要pay/job构件，真实认证完成身份→签到/直客→一次工单→quote/本人确认/真实普通订单/原支付→绑定/账单/恢复；故障丢响应和重启、撤权、旧核销不能提前完成。支付第三方仅隔离mockchannel，不发真实外部资金操作。
- [ ] 验证正式迁移/升级、零售餐饮影响，留当前SHA和HTTP/DB证据；finally仅清理本任务资源。
- [x] 全BM04代码对账、独立跨任务审查及I1必要修复复审通过（§32.24）；按§0.2进入BM05方案及编码。真实部署仍留60B，不以readiness或测试Bean代替。


### 2.23 Task60A-A1：锁配置与业务域架构收尾（2026-10-24）

依据Task60旧债及[60A设计A1](../../.superpowers/sdd/beauty-backend/task-60a-design.md)，先实施档位0有界片；其余A2～A11仍须到片前细化，不因总设计存在而一次授权所有文件。精确需求/命令/owned见[A1 brief](../../.superpowers/sdd/beauty-backend/task-60a-a1-brief.md)。

| AC | 文件与接口 | 验证 |
|---|---|---|
| A1-1 | shop-store-mybatis内JobLockConfiguration由store.config迁job.config；ShedLockConfig与BeautyJobTargetConfiguration更新import，原lockProvider/lockingTaskExecutor/usingDbTime不变 | HOST/SERVER唯一Bean，原DB锁及token路由，SERVER独立beauty/job关闭与non-api均无DataSource/target；不改POM/配置键 |
| A1-2 | ArchitectureTest登记beauty到DOMAINS，原规则/棘轮不变；新增条件启用的实际beauty类存在性断言（不静态依赖core） | -Pbeauty-embedded/-Dbeauty.arch.required=true；原16规则及新增presence实际执行。撤beauty登记定向消融应红，恢复同selector绿 |
| A1-3 | OpsPointsInspectionController正常PathVariable import | 原noFullyQualifiedReferences通过，不改端点/权限 |

- [x] 先仅测试断言，可信构件刷新后取得原架构失败RED；异常setup不算RED。
- [x] 最小迁包/两个import/DOMAINS/注解修复；只clean本工作区store、app、beauty-server三个target，按store install→HOST embedded→SERVER顺序验证，记录无旧class及实际classpath。
- [x] 仅DOMAINS beauty最小消融，精确topLevelPackagesMustBeRegistered RED；逐字恢复同selector GREEN，归档源/JAR/XML/SHA。
- [x] 独立spec+quality审查及必要修复复审后精确提交，补§5/§6真实证据；无部署、无其他片/数据库/角色授予变化。


#### 2.23.1 A1b：登记后暴露的内部准备辅助类命名

实际HOST34项仅serviceMustBeInterface仍失败，原因是AppointmentPreparationService具体类的内部协作者命名。根核定[A1b补充brief](../../.superpowers/sdd/beauty-backend/task-60a-a1b-brief.md)：重命名为AppointmentPreparation并更新六个生产调用/配置文件和两处方法设计真源、两份生成产物，保持Bean名及所有业务方法体。与A1联合准出，不弱化原规则或造空接口。增量10旧文件已核与实施基线一致，合计19路径/17逻辑文件。只新增本工作区beauty-core target清理/离线安装；HOST34全量、SERVER锁与core装配、原定向消融及生成校验构成验收，无native/部署。A1/A1b已联合代码验收，见§32.10。


### 2.24 Task60A-A2：原生付款测试选择与自有资源（2026-10-24）

A1/A1b产品`94aa791edb445b56bec9bcd348724ecff4ad803a`已通过独立审查；当前接续A2档位0脚本修复，精确需求见[A2 brief](../../.superpowers/sdd/beauty-backend/task-60a-a2-brief.md)。只修改verify-checkout-payment.mjs与新增checkout-payment-runner.test.mjs，原Java/SQL/迁移pin/Task59runner不改。

| AC | 实施 | 验证 |
|---|---|---|
| A2-1 | 14个payment类与1个beauty-source类的显式选择/方法白名单；仅unset默认，保留合法+多方法及payment逗号组合 | 非法选择在任何read/锁/工具/资源前失败；真实声明方法的正例和重复/空/未知反例 |
| A2-2 | 每选类本轮fresh、非零零失败/错误/skip XML；每个显式方法与正确class实际出现 | stale/零/skip/错类/缺方法及非零Maven退出均不能假绿，最小消融与恢复 |
| A2-3 | 密码env-only、同一串行锁、自有UUID创建前记attempt、丢创建响应仍finally清理 | fake effects验证argv不含秘密、只清本UUID及自己锁；安全unit通过后仅1个真实隔离MySQL9.7.2 source smoke |

- [x] 先安全CLI/受控effect RED，修复后Node GREEN，源方法来源复核。
- [x] 最小空值或XML防线消融应红，逐字恢复绿。
- [x] 一个既有实际native方法freshXML/归属cleanup验证；不重跑完整业务套件，不执行双进程部署。
- [x] 两文件精确差异、独立规格/质量审查与必要复审后提交；剩余60A片继续细化。

### 2.25 Task60A-A3：跨动作竞争与不可变回执（2026-10-24）

A2代码验收后，按[60A设计A3](../../.superpowers/sdd/beauty-backend/task-60a-design.md)细化档位0测试修复。精确需求、文件、方法、命令与失败边界见[A3 brief](../../.superpowers/sdd/beauty-backend/task-60a-a3-brief.md)。只修改ServiceSubmissionIT及aliases runner/对应Node单测三文件；不改业务Java、公共商品/订单/支付、SQL或权限合同。

| AC | 实施 | 验证 |
|---|---|---|
| A3-1 | 保存每个原请求与完整结果，按实际赢家核对SUBMIT_SOURCE或ADD_SOURCE唯一永久提交/参数摘要/原回执 | 原无偏向并发三场景保留；两种定向合法赢家均实际覆盖，输家冲突且无残留添加/来源 |
| A3-2 | 不可变接受回执/冻结事实不变，prepare恰好一次；完整重放VO对应当前查询投影 | 租约可合法推进revision，其余稳定字段与首次结果核对；不能只比较编号，非编号突变应失败 |
| A3-3 | aliases仅增加原三race方法和新参数化赢家方法 | Node白名单/非法选择提前拒绝；有限ServiceSubmissionIT原生类，不跑其它业务类/正式部署 |

- [x] 先定向ADD赢家取得旧固定SUBMIT_SOURCE断言的真实RED，不依赖随机调度。
- [x] 整类基线29/5失败/0错误/0跳过后，仅五个受影响race定向GREEN；BigDecimal数值比较不忽略qty，fresh非零零失败/错误/skip，不将分批说成单次整类全绿。
- [x] 旧固定赢家断言及回执字段突变覆盖检查，逐字恢复同selectorGREEN；新改Java按统一规范。
- [x] 三文件精确diff、Node安全检查、独立spec+quality及必要复审后提交；部署仍统一后置。

### 2.26 Task60A-A4：CHECK与列定义结构解析（2026-10-24）

A3代码验收后，档位0收尾工具与测试守卫。精确需求、五文件、接口和命令见[A4 brief](../../.superpowers/sdd/beauty-backend/task-60a-a4-brief.md)。新增test-only SchemaDdl给两个Java守卫共用，替代两份按缩进识别的正则；Java生产和既有迁移不改，Python生成器/test-schema-constraints只执行不编辑。

| AC | 实施 | 验证 |
|---|---|---|
| A4-1 | JS唯一DDL parser按引号/注释/括号层级拆CREATE定义，复用并完善现splitTopLevel判据，类型完整token | inline多列及多行CHECK不产生AND/CHAR_LENGTH假列，合法列/类型/索引/注释不丢 |
| A4-2 | SchemaDriftTest/SchemaGeneratorTest共用SchemaDdl.createColumns(String) | V506单空格/匿名CHECK和V517/V533续行CHECK均按结构识别；保留原ALTER重放语义 |
| A4-3 | 畸形闭合明确失败、table tail600及线性有界扫描保留 | 不吞下一表，不改冻结SQL/known/schema-test.sql；真实生成/列对账继续执行 |

- [x] 现有Node inline/CHECK及两个Java守卫路径取得真实行为RED，缺helper编译不算RED。
- [x] 五文件最小修复后Node双文件、SchemaDriftTest/SchemaGeneratorTest定向GREEN及freshXML；无native/部署。
- [x] entity-alignment/schema-lineage真实拆分记录，新增12个无ORM表登记仍归A5，不放宽守卫。
- [x] 逐行解析或约束防线最小消融RED，逐字恢复GREEN，独立规格/质量审查后精确提交。

A4实际共享校验22项19通过3失败，旧/新parser九个业务键对应表集合一致：12个非ORM登记归A5；另两项为九个未分类跨表键及gen-erd缺18键、voucher_no重复覆盖造成的归属分叉，列为A5b候选。A4不修这些登记，不把整个共享套件记绿。A5b须先按真实唯一键和读写者核验同义引用/同名异义，特别是积分与储值voucher，不能仅复制某份map消除差异；具体文件/AC/方法待前置验收后单独release。

### 2.27 Task60A-A5：精确非ORM表登记与未知表拒绝（2026-10-24）

A4代码验收后，档位0守卫收尾。唯一产品文件为 packages/shared/tests/entity-alignment.test.ts，精确要求见[A5 brief](../../.superpowers/sdd/beauty-backend/task-60a-a5-brief.md)。不动实体、DDL、持久化方案或known名单，不扩大IGNORE。

| AC | 实施 | 验证 |
|---|---|---|
| A5-1 | NO_ENTITY_YET只新增12个真实JDBC/SQL Mapper表及精确owner理由，保留原有全部键值 | 根对基线做新增键差集与理由核对 |
| A5-2 | 真实表守卫与负例共用unregisteredBusinessTables；仅own登记键有效 | 真实schema/实体/登记加入未知probe只返回probe；删除一个真实新登记同时返回两者 |
| A5-3 | 保留实体列、ghost与stale登记守卫及精确anchored IGNORE | entity-alignment单文件RED/GREEN及删登记消融；不重跑有生成副作用的schema-lineage |

- [x] 真实旧守卫取得12missing行为RED；不把缺helper/环境错误算RED。
- [x] 补12精确登记、抽共用分类函数及行为负例，已有points/logistics与新表同路分类。
- [x] 最小删登记消融RED，逐字恢复GREEN，记录实际命令/版本/计数和唯一文件SHA。
- [x] 独立规格/质量审查后精确提交；A5b血缘语义债另片，不声称整个共享套件通过。

### 2.28 Task60A-A5b：血缘语义与ER产物一致性（2026-10-24）

档位0。A4旧/新解析器均为363张表且九键成员相同，A5表登记已修复，剩余两项血缘失败须按实际所有者修复。源审计核对迁移唯一键、生产写者及消费者，精确需求和接口见[A5b brief](../../.superpowers/sdd/beauty-backend/task-60a-a5b-brief.md)，[审计](../../.superpowers/sdd/beauty-backend/task-60a-a5b-semantic-audit.md)。

选择一份纯schema-lineage模块供生成器和守卫共用；继续维护两份map会重复覆盖voucher且丢失角色目标列。不是照抄18缺项：plan/source_order/grant/execution等含独立命名空间或条件路径，tier/catalog还必须保留复合作用域。仅已证明关系出edge，条件路径说明保留；不改SQL/Java/业务契约。

| AC | 实施 | 验证 |
|---|---|---|
| A5b-1 | 纯registry/resolver/relations/classifier及badge，共用同一关系结果 | 实际表四条voucher、buyer别名、限定plan/actor/catalog/tier、11普通/3角色owner；target列/scope完整 |
| A5b-2 | 审计歧义键精确成员，拒绝未知新表；错误global不得生成edge/FK | 第七voucher表、跨族/根/self、receipt/sourceevent/entry/line/退款/历史grant/source负例 |
| A5b-3 | 生成器消费同一resolver且描述含目标列/作用域 | fixture缺目标/scope不出edge或FK；实际CLI生成并验证幂等，精确输出manifest |

Owned：新scripts/lib/schema-lineage.mjs、scripts/gen-erd.mjs、packages/shared/tests/schema-lineage.test.ts，及既有gen:erd实际改变的tracked db-*.svg和数据库-ER图.md。不是A4六文件的静态白名单；不得扩大DOMAINS/TOPICS或手改产物。测试不再import有生成副作用的CLI；不改A5文件、parser、SQL/known/package。现缺少sv/ent/pay详情域等旧限制保留，不宣称全库图完整。

- [x] 先记录旧lineage两个真实失败；旧逻辑纯提取后新增行为取得语义RED，不拿缺导出/模块当RED。
- [x] 最小实现与独立预期真实关系/字段标记检查；定向及combined entity+lineage GREEN。
- [x] voucher错误global、另一多态global、scope/target防线三类最小消融真实RED，逐字恢复最终GREEN。
- [x] source冻结后仅gen:erd生成/幂等和HTML检查；独立规格/质量审查及精确提交，无native/部署。

### 2.29 Task60A-A5c：报价命名空间血缘修复（2026-10-24）

档位0，针对A5b审查确认的继承Important与同处Minor单独实施，精确要求见[A5c brief](../../.superpowers/sdd/beauty-backend/task-60a-a5c-brief.md)。不以A5b scoped PASS遮盖真实错边。根核对四个quote_no实际表与GroupService/CheckoutService生产写者：营销BizKey.QUOTE与结算CQ属于独立体系。

| AC | 实施 | 验证 |
|---|---|---|
| A5c-1 | quote_no取消global，精确四成员/两family owner，未知第五表拒绝 | 实际两条family关系，无根/self/跨族边；toCol/tenant scope |
| A5c-2 | 精确operation.current_quote_no→checkout quote根 | 实际alias正例；不代替业务冻结revision/hash/caller校验 |
| A5c-3 | 生成器live54详情表数字移除或按实际派生 | 同一resolver/badge、生成文本/HTML实测、幂等及精确产物清单 |

Owned仅纯lineage模块、原lineage测试、生成器live说明行和其实际变化的既有ER产物。Java/SQL/parser/known/A5登记/域图定义不改。全registry审计不扩入本片。

- [x] 新实际报价关系/未知成员行为先RED，缺模块等不算。
- [x] 最小限定修复后entity+lineage联合GREEN，A5登记SHA不变。
- [x] quote_no旧global消融真实RED，逐字恢复最终GREEN，不重复A5b无关消融。
- [x] 生成/幂等、实际HTML/文本检查、独立规格质量审查后精确提交；关闭继承Important再进入A6。

### 2.30 Task60A-A6：统一SQL方言扫描覆盖（2026-10-24）

档位0，既有AC5的永久守卫。精确接口、两文件及验证见[A6 brief](../../.superpowers/sdd/beauty-backend/task-60a-a6-brief.md)。固定CLI同时扫描backend与backend-plugins/shop-industry-beauty，共用唯一RULES、SQLmask、路径/规则baseline判据，不增加baseline或跳过测试目录。

只读验证确认原整文件mask会删掉Java注解字符串与SQL textblock，单加目录仍漏Java。采用同checker内有界提取静态Java字符串/textblock后再复用原SQLmask；忽略Java注释/字符，保留原文件行号。不解析动态运行时SQL、不加AST依赖，不改SQL/Java/mask/known。额外实际违规须交根处理，不绕过。

| AC | 实施 | 验证 |
|---|---|---|
| A6-1 | 导出scanSqlPortability(root,scanRoots)；CLI固定两根且导入无副作用 | 相同真实坏SQL/Java在两根均命中同rule；实际复制CLI --check exit1 |
| A6-2 | 静态Java payload与.sql同规则，SQL/Java噪音不误报 | 普通注解/三引号、转义、文件行号和相邻真实代码反例 |
| A6-3 | 保留fresh/stale、精确baseline和旧跳过项 | tmp known/stale案例；真实--check，两个最小消融和逐字恢复 |

- [x] 必要只提接口后取得漏beauty/Java的真实行为RED，缺导出/环境错误不算。
- [x] Node专属测试GREEN及真实node scripts/check-sql-portability.mjs --check。
- [x] 移除beauty根及Java回退旧mask两类消融RED，精确恢复最终GREEN。
- [x] 独立规格/质量审查与精确提交，baseline/mask哈希保持；无native或部署。

### 2.31 Task60A-A7：已认领临客与真实资源替换组合（2026-10-24）

档位0，收尾既有业务的组合验证，不新增执行能力。精确三方法、DTO/服务、文件和安全环境见[A7 brief](../../.superpowers/sdd/beauty-backend/task-60a-a7-brief.md)。三产品仅新BeautyClaimedGuestResourceIT、旧checkout runner与其Node测试；默认retail反射编译边界保留，固定新class/三方法而非Beauty前缀通配。

| AC | 实施 | 验证 |
|---|---|---|
| A7-1 | 真实guest注册→GUEST预约/CONFIRMED→claim→当前mc1/cr1签到→原guestNo换人开工单 | USER身份/原guest与claim历史、真实REPLACE成功/新旧hold和容量/fence、原key重放无重做 |
| A7-2 | 真实非owner MERCHANT/USR当前当店角色撤BOOKING_MANAGE，保留查看/客户能力 | 精确FORBIDDEN，旧hold/current/安排不丢，无新增开单效果 |
| A7-3 | 排序人员A锁作测试门闩，真实REPLACE已许可后另一真实预约占B，再释放门闩 | owner实际CAPACITY_UNAVAILABLE/开单CONFLICT，原A和peerB持有都保留，无伪造结果 |
| A7-4 | 严格finite source selector/真实XML与默认编译 | 新selector Node RED/GREEN；native三例零跳过；一次实时权限SQL防线临时消融/恢复；默认test-compile |

资源/人员/班表/预约/身份/接待均用真实owner写链，不借ArrivalHoldFixture/FaultConfiguration/mock Port或替换Bean；SQL只作auth/goods前置、只读事实与独立人员行锁门闩。默认owner只配置前置，撤权主体必须非owner USR。保护登记/claim加密及原始凭据，摘要比较不在断言日志输出秘密。

唯一临时生产消融限MerchantMappers.OperatorAccessMapper的allowed表达式置真，观察撤权例真实RED后finally逐字恢复、重编译当前bytecode再GREEN；绝不进入产品提交。采用既有cached MySQL9.7.2/DISK64M/内存guard/共享串行锁/env凭据/ownUUID finally清理，仅必要隔离开发测试，正式部署仍后置。

- [x] 新selector真实行为RED，三产品最小接入/Node GREEN；组合已有业务正确可首次native GREEN，不制造假业务RED。
- [x] native三方法真实零fail/error/skip，身份/持久命令/新旧资源与历史共同断言。
- [x] 临时实时权限防线消融RED/精确恢复定向GREEN、默认retail test-compile与bytecode来源证明。
- [x] 独立规格/质量审查及精确提交，环境失败和警告单列，不宣称BM05或BM04部署完成。

### 2.32 Task60A-A8：预约权限词条与库存冲突提示（2026-10-25）

档位1；依据60A AC7与已有统一i18n合同。精确文件及命令见[A8 brief](../../.superpowers/sdd/beauty-backend/task-60a-a8-brief.md)。不改变权限码或授予关系，不新增接口、字段、配置或账本。复用BizPerms.LABELS及现有三语properties；A7及以前产品保持冻结。

| AC | 实施 | 验证 |
|---|---|---|
| A8-1 | 三语perm补预约查看/管理文案，中文逐字对应后端；英文/阿文补缺失common.delete；中文补既有mySpecs.delete三语缺项 | 真实权限标签守卫、键集及三语占位符对齐；重复权限词条不得静默覆盖 |
| A8-2 | 库存共用来源提示明确当前店{0}与冲突店{1}，保留独立库位整改说明 | 新stock-shared-source-label测试覆盖三语实际条目及两个真实Java参数顺序；旧文案已有{1}，不误称全吞参 |
| A8-3 | StockReconcile仅将邻近BizException/ErrorCode全限定名换正常import，保留storeNo/other | InventoryWriteback已有正确调用不改；不放宽message-placeholder或known棘轮 |
| A8-4 | 默认后端及B端受影响闸门 | 四个shared文件、BackendI18nParityTest实际XML、B端vue-tsc；缺{0}/错参数顺序消融及精确恢复 |

Owned为三份B端locale、三份后端properties、StockReconcileService必要import/相邻调用、原biz-perm-label与新stock-shared-source-label，共9文件；InventoryWriteback只读。现有root Vitest/vue-tsc可执行，使用既有依赖、不安装或改锁文件。无原生数据库或正式部署。

- [x] 先补针对真实缺词条/缺{0}的行为断言并取得RED，再做最小产品修改。
- [x] 四个shared守卫及后端三语守卫实际通过，B端类型检查记录真实结果。
- [x] 两类必要消融失败并finally恢复精确bytes；生成manifest及完整diff，问题按真实归属处理。
- [x] 独立规格/质量审查及精确提交；已按范围验收，B端类型关口必须经A8b关闭再继续A9，不宣布BM04或部署完成。

### 2.33 Task60A-A8b：统一会员码B端契约及真实适配补齐（2026-10-25）

档位1；A8实际B端vue-tsc暴露既有缺口：endpoints和marketing mock已登记mResolveMemberCode，但MerchantApi及真实httpApi缺失。该问题不由词条改动引入，A8范围审查通过不代表类型关口关闭。本片必须先于A9完成。依据既有共用会员C08合同及Java MemberResolveCommand/MemberCodeViews，精确范围见[A8b brief](../../.superpowers/sdd/beauty-backend/task-60a-a8b-brief.md)。

| AC | 实施 | 验证 |
|---|---|---|
| A8b-1 | MerchantApi补mResolveMemberCode，复用现有shared CustomerReference；本端requests补两字段入参 | 不复制响应、不扩顾客身份/资金字段，不以any/index signature规避检查 |
| A8b-2 | 原httpApi按既有endpoint发POST，仅投影memberCode/storeNo | 新真实适配单测验证路径/请求体、额外身份字段不转发、返回引用不改写、传输异常透传 |
| A8b-3 | 端上编译及契约对账 | 新适配方法缺失行为RED→GREEN；必要wire消融RED/精确恢复；完整B端vue-tsc零错误；受影响shared字段守卫 |

Owned仅b-app/src/api/contract.ts、requests.ts、http.ts及新tests/member-code-api.test.ts四文件。既有端点、拒绝签发身份的演示mock、共享CustomerReference类型与Java均只读。不新增页面、会话/凭据存储、日志、后端接口或权限。凭证只在POST body，不进URL/query；无外部真实身份或资金动作。

- [x] 读取现有owner合同/共享类型和API单测模式后，先复现实际适配方法缺失的行为RED。
- [x] 最小补齐四文件，定向测试、完整B端vue-tsc及必要字段守卫实际通过。
- [x] 最小wire消融必须失败并精确恢复，不重复无关后端/native套件。
- [x] 独立规格/质量审查及精确提交后，关闭A8继承的类型Important，继续A9。

### 2.34 Task60A-A9：权限登记与生成契约对账（2026-10-25）

档位1；60A AC7其余部分及A8b已有会员码请求的生成契约收口。原十一项及已核验六项派生输出、命令、隔离副本及异常生成物处置见[A9 brief](../../.superpowers/sdd/beauty-backend/task-60a-a9-brief.md)。不新增端点或权限授予；复用真实注解、角色、原REQUIRED、原OpenAPI生成器和已存在ResolveMemberCodeReq/CustomerReference。

| AC | 实施 | 验证 |
|---|---|---|
| A9-1 | REQUIRED补人员assignments/account-links/status-changes→STORE_ADMIN、store-sales-catalog/versions→GOODS四路径 | 原四个权限守卫及同方法注解前后顺序/邻方法不借权测试实际RED→GREEN；删一登记消融RED/恢复；不改PUBLIC/ANY_OF/ROLE_PERMS或runtimeauth |
| A9-2 | 真实角色/注解重生B功能/角色/三域矩阵及Ops JSON/md，当前技术索引同步 | 四个Ops GET×FINANCE/SUPER_ADMIN八行按源对账；人员账号关联/停用仍须真实ownerOnly，不由登记放开 |
| A9-3 | 现有B端生成器REQUEST_TYPES补mResolveMemberCode映射，原Req两字段补说明，重生OpenAPI | 新实际YAML测试验证必填两字段请求体、原response/auth；旧文档缺body真实RED，映射消融及恢复 |
| A9-4 | 生成幂等及全局文档守卫 | 两次真实生成SHA一致；全局check在自有/tmp源码副本执行，保留真实结果；额外产物先逐路径报根再批准 |

已确认权限守卫只向上找注解，四个真实Controller将PreAuthorize放在Mapping之后；允许同一测试文件提取供真实守卫复用的同方法注解块识别，并补前后顺序/多行/邻方法不借权反例，不移动生产注解或放开权限。Ops生成md中五角色退役elec码清理按当前未变Perms真源保留。

根另核对三个OpsEntitlement真实Controller及Perms：target registry遗漏四个points和十三个既有权益端点，共17项。增列原perm-endpoint-map.mjs及原ops-perm-matrix.test.ts两个源路径，只按真实method/path/权限码精确登记并补正反例；不扩大运行时授权。当前允许19路径，实际未变生成物只登记无差异。

隔离副本首次全局checker真实exit1，根逐份核对313行diff，额外允许API清单/API详情-B端、数据库表清单及词条/实体字典/glossary.json六份派生物：分别来自本次requestBody、已验收结构解析的两表列数、已验收三语/错误码/realm枚举。原生成器不改；17项允许路径中未变的三域矩阵只记实际无差异，不制造修改。snapshot中的ui-lib.page.html副产物不纳入产品。

本片产品：BizEndpointPermTest、一份Ops fixture、五份生成md（Ops/B功能/B角色/三域/技术README）、原B OpenAPI生成器、原requests字段注释、实际openapi-b.yaml及新member-code-spec.test，共十一文件。其它源/生成物不因全局checker运行自动获准提交。后端用JDK21离线缓存；不跑native/正式部署；A8b未变的B端完整编译/242项和shared8不重复为本片制造证据。

- [x] 真实四路径登记缺口和实际OpenAPI缺请求体均有行为RED，再最小修复。
- [x] 权限四方法与新增YAML测试实际通过，两个必要消融失败且精确恢复。
- [x] 真源生成、精确差异及幂等、隔离副本全局checker状态明确；非范围问题不静默批量收录。
- [x] 独立规格/质量审查及精确提交后继续A10，BM04正式部署保持后置。

### 2.35 Task60A-A10：有效确认请求与错误身份域断言（2026-10-25）

档位0，Task59 M1测试夹具收尾，不改变生产鉴权。精确两文件及测试见[A10 brief](../../.superpowers/sdd/beauty-backend/task-60a-a10-brief.md)：仅BeautyBm04EmbeddedHttpIT及新BeautyBm04ConfirmationFixtureTest。普通单测与编译先执行，真实embedded/standalone子进程拓扑及权限消融仍留60B最终统一部署。

| AC | 实施 | 验证 |
|---|---|---|
| A10-1 | 共用confirmationBody(quote)，完整quoteNo/quoteRevision/quoteHash | 普通validConfirmationUsesPreparedQuoteEvidence逐字段证明；两条真实身份请求使用同一有效body |
| A10-2 | 错MERCHANT realm必须HTTP401且10401，前后同商家会话仍可读合法B资源 | 普通wrongRealmAssertionRequiresExactAuthorityFailure及RejectsMalformedBodyFailure；拒绝过期10402/任意业务失败 |
| A10-3 | 错误请求前后确认/checkout指针与订单支付效果不变；随后同body真实C确认下单 | 实际SQL只读观测及现有业务fixture代码；此次仅编译，执行证据明确留60B |
| A10-4 | 保留独立无效body覆盖与有限消融 | 实际校验失败合同HTTP200/10400，不误当HTTP400；普通三方法RED/GREEN/精确恢复，embedded test-compile |

身份异常依据requireCurrentBuyer→SecurityUtils.requireUser→GlobalExceptionHandler.onUnauthorized，未被C过滤器接受的商家会话无身份，返回401/10401；onInvalid按现有信封返回200/10400。两类错误不能互相冒充。Helper在外层package-private static，普通测试不能构造依赖环境的Fixture或开启deployment属性。无后台进程/容器/数据库，不修改生产文件。

- [x] 先在真实共用helper路径补行为断言取得RED，不以缺类/编译失败记业务RED。
- [x] helper三方法实际零fail/error/skip，有限错误接受消融RED并逐字恢复GREEN。
- [x] 有效body、真实会话前后检查及零经济副作用断言代码编译通过；真实HTTP执行仍未验。
- [x] 独立规格/质量审查及精确提交，继续A11，不能据普通helper测试宣布M1部署验证通过。

### 2.36 Task60A-A11：恢复告警归因与SQL换行兼容（2026-10-25）

档位0，已确认质量缺口，精确五文件与完整约束见[A11 brief](../../.superpowers/sdd/beauty-backend/task-60a-a11-brief.md)。不改变业务契约、生产恢复逻辑或既有告警级别。

| AC | 实施 | 实际验证要求 |
|---|---|---|
| A11-W | WalkInRecoveryIT新增expectedRecoveryFailureEmitsAttributedWarningAndKeepsIntentRecoverable；复用丢失CONFIRM响应的受控所有者夹具及真实ReceptionRecovery，精确归因原operation与IllegalStateException | aliases有限选择器；单例正常GREEN、只移除既有LOG.warn消融断言RED、精确恢复源码及编译字节码后单例GREEN；持久意图/命令不丢失、租约清理、有界重试到ARRIVAL_PENDING，认证后恢复原操作 |
| A11-R | 原生runner仅增加有限类/方法及模式安全测试，保持凭据、部署后置和自有资源清理 | 两个受影响Node文件；非法选择器在provision前失败，不放宽通配/跨模式 |
| A11-N | SQL静态扫描器支持CR/LF/CRLF的Java行注释、原源行号与文本块续行；known/mask/rules不变 | 真实扫描fixture先RED、最小修复GREEN、lineOf消融RED及精确恢复GREEN；实际check fresh0/stale0 |

临时生产消融只限ReceptionRecovery现有两行LOG.warn，finally逐字恢复、重新编译，不提交生产变更。原生验证仅指定一个新增用例，使用已缓存MySQL9.7.2、本任务UUID和共享锁；不停止其他容器、不调用远程或共享数据库。保留完整告警来源和清理证据，不压制WARN，不因单例成功宣布正式部署通过。A3首个成功VO比较和A8重复守卫biz边界保留模块最终审查triage，不授权无关修复。

- [x] 两个Node文件实际有限验证、真实SQL扫描和换行消融精确恢复。
- [x] 单个原生告警用例正常/消融/恢复XML及源码、字节码和自有资源清理证据。
- [x] 独立规格/质量审查、精确提交，随后BM04模块级代码对账和必要编译；BM05及P2～P4继续，部署统一后置。

### 2.37 BM04模块代码收口与跨任务审查（2026-10-25）

档位0，执行§0.2既定模块关口，不新增业务契约。Task54–59及60A各片已分别完成编码与独立审查，仍须对接待→身份→工单→交易/资金→来源绑定/账单/恢复进行跨任务对账。精确[模块brief](../../.superpowers/sdd/beauty-backend/bm04-module-code-brief.md)约束本次只读独立审查；生产修复如需发生另发有界任务，不授权一揽子改动。

- [x] 以Task54前基线至A11的精确产品清单、当前源码hash及已接受审查建立模块映射；只将真实已完成行为列入对账，不把BM05开工/作业或BM06日历记为已交付。
- [x] 当前五个美业子模块联合test-compile -DskipTests成功，347个非target输入指纹留存；不安装、不启动构件。原host embedded编译、B类型检查等按实际任务/字节依赖复用，不重复无变化大套件。
- [x] 独立复核跨任务身份/原号/数量版本/双向协议/门控五类风险，给出实际检查范围、规格及质量结论；真实60B HTTP/数据库副作用和拓扑继续明确未验。
- [x] 对A3首个成功VO比较、A8 biz重复边界、环境/编译告警逐项裁定；更新当前README状态索引，模块代码关口完成后先细化BM05再编码。

### 2.38 BM04模块审查R1：建单最终事务前的本人授权（2026-10-25）

档位0，修复既有Task56 §3明确合同，不新增端点/字段/权限/配置。模块初审[报告](../../.superpowers/sdd/beauty-backend/review-bm04-module-code.md)规格与质量NEEDS_FIXES，0 Critical/1 Important/3 Minor；I1为place只在入口查当前C，商品/优惠锁外准备及place-key元数据事务后，最终writer事务前没有重核会话和活跃用户。root已核实际代码、policy、writer及原设计；当前仅静态定位，必须先真实时序RED，不能直接宣布已复现或修好。

[精确R1 brief](../../.superpowers/sdd/beauty-backend/bm04-module-r1-brief.md)仅四产品：CheckoutServiceImpl、新CheckoutPlaceAuthorityFlowTest、现有checkout-payment runner及其Node测试。保留入口/重放校验，在锁外准备完成、元数据事务之前以及该事务完全退出、最终writer之前各重核真实当前C与原buyer一致。不得把TokenStore/用户RPC放在事务锁内，授权失败不变成重新报价，也不丢已提交原place-key。第一检查确保预查询期间撤权不能新占永久key，第二覆盖元数据提交后的撤权窗口。

| AC | 新增实际用例 | 准出 |
|---|---|---|
| R1-1 | revokedDuringPlacePreflightCannotClaimOrCreateOrder | 原真实会话在真实锁外catalog.read后撤销，拒绝且原place keys空、确认保持，无订单/intent/库存消耗 |
| R1-2 | disabledDuringPlacePreflightCannotClaimOrCreateOrder | 同窗口禁用本fixture真实buyer，拒绝且同样不写claim或经济事实 |
| R1-3 | revokedAfterPlaceClaimCannotReachWriterAndOriginalKeyRecovers | 真实元数据提交afterCommit后撤销会话，拒绝writer；原key持久保留，新真实同buyer会话以原请求恢复恰好一单 |

- [x] 两受影响Java及有限runner测试先取得业务RED；只用真实owner/TokenStore，允许精确真实调用拦截注入时序，不增加生产hook。
- [x] 最小修复三例原生GREEN；分别移除首/末重核定向断言RED，finally逐字恢复源码与新编译class，最终三例GREEN。
- [x] 当前默认零售测试编译；实际XML/指纹/告警/环境凭据与自有UUID清理完整；不重跑不受影响大套件，不部署。
- [x] 同模块审查者只复审I1和本修复新增破坏；无Important后才关闭BM04代码关口并细化BM05。

M1其他首个成功VO比较、M2 biz-only权限词条重复边界、M3旧Flyway/编译/loader告警，经实际模块分诊均为非阻塞Minor，保留最终分支/依赖关口，不默默删除，也不扩本轮四文件。模块初审的未验真实拓扑、干净空缓存构建、原生批次边界和生成物范围均按原证据保留，正式60B继续全部P1～P4代码后执行。

### 2.39 Task61：BM05服务作业与履约设计深化（2026-10-25）

本任务只做方案与实际所有者审计，尚不授权BM05业务产品编码。模块代码关口已在§32.24关闭I1；全部P1～P4先代码、再统一部署的顺序不变。新增执行许可/实际占用涉及跨域合同，后续产品按档位2补既有PRD映射、ADR及本TDD/API/Java/schema真源，不凭目标清单创建空壳。

精确[Task61 brief](../../.superpowers/sdd/beauty-backend/task-61-brief.md)只允许写bm05-design.md、task-61-design-report.md及本任务审计证据。读取矩阵H/D05/O01、API057～066/105/155/159/027/028及对应方法、共享资源方案§6、真实订单/退款/人员/资源所有者，形成已有/缺失/须扩合同清单。

- [x] 明确真实开工许可的当前账号→业务人员、来源/任务/绑定版本、订单/退款围栏及原号恢复；复用resolveAuthorizedOperator，不把人员事实当动作授权。
- [x] 明确预约单位交接实际占用、开放占用、结束/清洁、超时阻断及活动调整与所有相反写者锁序；不二次扣量、不先释放旧位、不靠预计结束放行。
- [x] 明确任务状态/步骤/参与交接/完成量与Core履约ACK/关闭冻结、权限和双向协议；原canStart/旧完成门禁在真实合同之前保持关闭。
- [x] 模块切片含完整状态/接口/表与风险，首个可实施任务精确owned/签名/字段/AC→测试/有限命令/消融；新迁移先root实际文件hash关口，设计阶段不建表或执行。
- [x] 独立设计审查后root对账主TDD与既有真源再释放首片编码；本任务不能记作BM05业务交付或部署通过。

Task61设计关口已通过：初审I1长期任职空结束语义已修订并由同审查者限定复审通过；29个实际源码/迁移指纹一致。设计审计未执行测试或构建；当前编码领取Task62-S0，后续S1～S6按已审设计逐片细化。BM05业务尚未交付。

### 2.40 Task62：BM05-S0 当前操作人业务人员事实（2026-10-25）

档位1；依据功能矩阵BTY-H02/H04、既有共用人员账号关联与门店任职模型。完整BM05设计及审查材料见[设计](../../.superpowers/sdd/beauty-backend/bm05-design.md)、[初审](../../.superpowers/sdd/beauty-backend/review-task-61-design.md)、[I1限定复审](../../.superpowers/sdd/beauty-backend/review-task-61-design-r1.md)。本片是实际开工许可的人员前置，尚不交付开工、实际占用、完工或履约。

真实所有者审计：MerchantOperatorAccessPort.resolveAuthorizedOperator已提供当前精确权限的账号事实；人员账号关联和任职版本由V532维护。RemoteMerchantOperatorAccessPort仅实现allowed，不能假设远程账号解析已存在。新增PersonnelOperatorFactsPort由共用人员所有者本地解析账号并查询有效关联，账号号不出域；美业仅用同接口远程适配。经营账号权限、人员关联、任职、技能资格分别核验；OWNER无关联仍为UNLINKED，不自动成为技师。

精确路径、方法、字段、权限标签及AC→测试见[Task62 brief](../../.superpowers/sdd/beauty-backend/task-62-brief.md)与设计§10。内部POST /internal/merchant/personnel-operator/current沿用绑定信封、精确MERCHANT MCH/USR命名空间与当前权限；不新增公开接口。五个service:task权限无默认角色种子授权。LINKED任职有效区间为validFrom<=observedAt且(validTo为空或observedAt<validTo)；空结束时间表示长期有效，JSON必须显式保留null，遗漏字段视为依赖畸形。授权缺失与依赖失败不可伪装成UNLINKED。

数据库结构：本片无DDL、无新表、不改冻结V532/V533或美业V1～V8。API/Java方法设计JSON增加明确PLANNED的S0合同并重新生成清单；不改变原160目标编号或49任务覆盖。后续Core/Pay执行保护与资源占用的迁移仍是候选，实际文件/hash及ADR关口在各片开工前执行。

S0实际协议接线补充：共享InternalReplyDeserializer与InternalBindings.tree原来不支持枚举标量，不能承载已冻结LinkState。精确扩展这两个现有辅助类与BoundInternalProtocolTest：只允许枚举精确name字符串、拒绝未知或数字，canonical用name且既有固定摘要不变。组合reactor明确增加该实际测试报告，由四类扩为五类；不放宽nullable字段必需性或其他标量。详见brief末尾root补充。

S0临时组合reactor偏差：实际Maven3.9.16将绝对module文本拼接到basedir导致模型校验失败；改用从自有临时reactor到当前两个真实工程的标准相对路径，并断言canonical目标和class来源一致。不改仓库POM、不install、不复制旧缓存产物；模型失败不算行为RED或原生测试。

- [x] 先写真实owner/SQL、协议与远程HTTP测试；取得行为RED，构建/环境故障不当业务红灯。
- [x] 仅实现设计§10的精确产品路径；有效账号在事务外及快照内核验，RR事实读取不成为持久授权；新实现按统一Java规范。
- [x] 原生MySQL9.7.2自有UUID环境验证身份、权限撤销、长期/过期/歧义任职；实际绑定HTTP验证null、范围/摘要/状态及上下文恢复。
- [x] 消融有效期上界取得对应断言RED；恢复精确源码并重新编译，恢复GREEN且源码/class指纹一致。
- [x] 当前共享与美业组合reactor验证五个实际测试报告和类来源；权限标签/生成物及相关零售/餐饮编译，保留存量告警。
- [x] 根核对实际证据与独立spec/quality审查；通过后提交本片，再细化S1，不以S0完成宣称BM05交付。

Ruling R1：采纳Pay所有者的订单行/单位受保护预算与永久managed来源标识方向，因为真实RefundPaymentStore与PaymentLedgerService的两条退款路径使用独立payTxManager，不受Core子订单锁覆盖。Core负责来源/数量/退款授权，Pay在同一原付款锁核验预算；不在美业记账，不永久冻结整笔付款。S1编码前仍须精确化并对账PRD/TDD/ADR/API/Java/schema和所有相反写者；本S0不建立任何Pay表或金融许可。已服务质量争议的资金返还须独立授权及数量口径，不得重用“未服务退款”不等式擦除历史履约或允许重复执行。代价是新增跨所有者恢复协议及保守阻断，不能用快照已付布尔值替代。

### 2.41 Task63：现金/线下美业核心闭环实施深化（2026-10-25）

档位2；用户§0.3优先级已确认，规格复用[功能矩阵](../requirements/美业系统-多端功能矩阵.md)、[领域边界](./design/美业系统-领域对象与聚合边界.md)，决策见[ADR050](./ADR/ADR-050-美业现金线下核心闭环与支付接入分期.md)。先按[Task63 brief](../../.superpowers/sdd/beauty-backend/task-63-brief.md)审计实际源代码并冻结无Pay依赖的现金/线下准入与完整作业方案；不直接沿用Task61付费许可阶段，也不删除已交付的正式线下收款。

实际审计并经Task63设计审查后的选择：Core专用人工确认可复用PAID且持久MANUAL来源，保留旧markPaid的真实回执门禁，明确所有PAID选择器/结算退款副作用的排除规则；不把“不接Pay”误解为另造未付状态机。已识别顾客沿真实购买确认首片，临客以Core显式USER/GUEST及条件非空约束下一片接入，均须在核心阶段闭环；不以guestNo充userNo。购买确认不等于线上支付，新模式不依赖Pay渠道/商户配置，但不能全局跳过原规则。精确DDL候选及相反读写者清单已由Task63审查冻结；实际SQL逐字门禁仍在首次native前执行。

- [x] 对齐现有现金/线下普通订单、真实买家/临客、商品/报价、checkout source及工单绑定实际调用链，逐项列明可复用/需扩展/不能调用；禁止伪造C会话、PAID、receipt或第二财务账。
- [x] 定义人工线下确认、Core订单/服务来源准入与数量仲裁、原号/版本/审计/撤销；处理原未支付自动关单、普通售后/取消与旧履约入口的竞争；人工方式不得成为已有在线/Pay来源的降级旁路。
- [x] 冻结以Task62-S0当前人员事实接入预约资源交接/实际占用、清洁、超时、暂停恢复/换人、步骤及完成量/关闭的设计；实现仍属M3～M6，真正有资格和容量才开工，不用人员快照代替资源授权。
- [x] 明确服务完成与共用订单履约的独立合同；未接资金状态如实展示，收款人工登记与正式回执不得相加，线下撤销/争议留事实而不自动调用资金退款。
- [x] 形成完整核心闭环切片，首片精确类/方法/DDL/权限/API/状态/AC与有限测试命令；只先细化必要片，不生成大量未消费接口。
- [x] 独立设计审查后root同步已有API/Java/schema真源并发布首片编码；设计关口完成。后续实施与编译见Task64及M2～M7，日历须包含任务和资源占用；正式部署仍后置。

### 2.42 Task64：M1共用人工线下服务准入（2026-10-25）

档位2；依据§0.3、功能矩阵BTY-I01/I03/I04/I05、ADR050及[Task63冻结设计](../../.superpowers/sdd/beauty-backend/bm05-offline-core-design.md)§1–4/8–10。本片只交付真实已识别顾客的人工报价→真实C购买确认→共用订单→人工实收声明/库存原操作结果→人工来源及恢复；不提前打开开工。临客M2、真实许可M3、实际占用M4、任务作业M5、履约关闭M6、任务资源日历M7仍须后续完成，不能以本片替代完整美业核心。

精确类/方法、4条B路由、4条绑定内部协议、DTO、迁移候选、所有相反金融读写者与测试方法按冻结设计§9及[Task64 brief](../../.superpowers/sdd/beauty-backend/task-64-brief.md)实施。接口/方法真源的manualServiceFoundation标记PLANNED；共用订单/库存候选增量另列，不修改原41项或既有迁移。库存命令接受/void返回不作完成证据：冻结原库存权威/生命周期/完整清单，读取实际原操作终态后才ADMITTED；缺失/已释放/迟到/未知均保持关闭，不伪造库存凭证。

- [ ] 实际普通商品/报价/门店/业务资格与真实C购买保持；不可变MANUAL_OFFLINE只由专用服务器路径创建，老来源不能降级。
- [ ] CASH_RECEIVE及新人工确认权限、原额/币种/时刻/幂等/版本/作用域校验；专用原子人工PAID及MANUAL来源，无Pay/普通金融已付副作用。
- [ ] 实收声明与库存结果分离，原号持久恢复；旧source无Pay IO且保持金融UNKNOWN，新人工观察及原来源行可查询；服务canStart=false。
- [ ] PAID自动结算LIMIT前过滤；支付/正式receipt/售后资金退款/修复/通用履约关闭人工入口，保留老资金及managed完工门禁。
- [ ] 按§9/10 AC真实测试、一次有意义消融及逐字恢复、当前源组合编译、标签/架构/i18n/生成物对账；首次native前root核实际SQL/实体/fixture/runner文件与SHA。
- [ ] 独立规格/质量审查、§5真实结果与§6真实提交统计；部署统一后置，原P2～P4全目标保留。

## §3 数据层之后的业务任务与启动深化

每部分开工前重新核对功能矩阵对应行、API输入输出、类/方法、表字段、权限与真实跨域提供方，提交“可实施 / 需先补合同 / 延后关闭”结论，再编码。上次形成的类清单不代替这次业务规则梳理。

| 部分 | 必须先深化的场景 | 开发任务与准出 |
|---|---|---|
| 共用人员/资源 | 可无账号人员、多店任职、岗位与角色、资格过期、停用在途交接；STAFF规范身份、班外/维修、缓冲、跨店冲突、hold过期和开放占用 | SHR-01/02；共用所有者可实际查询/原子占齐/替换/释放，旧slot不能同时独立扣量 |
| 项目/营业定义 | 同SKU门店价格与展示、固定时长/等级、版本生效、缓冲、技能和床位要求、先付政策 | POL-01/DEF-01；不给服务规则另存售价，高级规则仍受能力门控 |
| 预约 | 顾客自约/电话代约/临客、指定或不指定人、完整组合、重复提交、改时/换项目/换人、取消/爽约、来源未知与占位到期 | AVL-01/APT-01～03；原子成约、失败保留旧安排，不能靠已付自动复活被别人占用的时段 |
| 工作台 | 日/周、人员/工位/设备泳道、多任务多资源、未分派、计划/实际/缓冲/临时占位、匿名跨店busy、不完整与迟到响应 | CAL-01；含具体任务详情、来源阻断、快照同水位续页和动作后重载，不只日历空格 |
| 接待/工单 | 到店核验、无预约直客、一次签到转工单幂等、项目范围与真实买家、一个工单多任务及来源账单 | REC-01/WO-01；签到不核销、不伪造消费者订单 |
| 来源/执行 | 普通订单/资产消费来源区别、加项与本次确认、开工和退款/撤销的权威许可竞争、步骤数量、换人区间、超时及暂停占用、部分事实与关闭冻结 | SRC-01/TASK-01～03；真实来源就绪才开工，回调仅确认履约不再累加已做数量 |
| 逆向/异常/运行 | 未做撤项、普通售后、质量证据、支付/绑定UNKNOWN、丢事件、重复操作、通知打印未知回执 | AFTER-01/RECOVER-01/MSG-01/HIST-01/OPS-01；原号查询，事实保留，资金与服务/设备状态不互相冒充 |
| 后续经营与专项 | P2资产本店隔离/授权；P3护理与绩效政策；P4售卡、疗程、复杂收银、上门团体、逆向和离线 | 对照索引ASSET等任务；P4路线项先拆每类独立合同/用例/文件后再开工，不能一次提交全部 |

共用人员/资源的代码不在beauty-core复制；共享目录、Core/Pay/库存、媒体、通知/打印、审批/回访/导出等合同逐项核验真实签名/部署/恢复与能力验收。54个CONTRACT不是54个可以直接生成的Java方法。付费疗程销售与已持有权益消费分阶段，护理回访任务与护理内容也分别归其所有者。

## §5 验证命令、记录与发布界线

### 5.1 本轮仅计划检查

运行任务索引生成器验证49任务依赖无环、160个API与39项表首次主责唯一、118功能覆盖、阶段不被静默推迟；复验既有API/方法/数据库设计生成物。本轮不运行尚不存在的Maven工程、不执行DDL、不连接业务数据库。

### 5.2 实施时目标命令（当前不可当已执行）

在首次工程、共享构件及所需隔离环境准备后，从仓库根按当前任务运行：

```sh
# 工程结构/编译及当期完整reactor测试
mvn -f backend-plugins/shop-industry-beauty/pom.xml verify
# 当期定向测试；测试类创建后再运行，不能以不存在测试的跳过作为通过
mvn -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-core -am test -Dtest=PolicyDefinitionPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false
# 真实SQL及隔离MySQL/MariaDB测试的具体命令随DATA-02工具落地登记
# 任何Java/方法/字段/任务真源变动后重新生成相应清单
python3 scripts/gen-beauty-java-method-design.py --check
python3 scripts/gen-beauty-java-project-design.py --check
python3 scripts/gen-beauty-api-design.py --check
python3 scripts/gen-beauty-database-design.py --check
python3 scripts/gen-beauty-development-plan.py --check
```

定向命令的reactor汇总必须包含目标测试真实执行数且非0，相关模块的suite无跳过掩盖；完整verify不以单测试绿代替。改公共能力时增加其所有者、零售和架构回归；只改文档不运行资金/业务环境。

### 5.3 每任务完成记录

| 任务 | 实际文件/版本 | 红灯→绿灯/消融 | 真实环境与执行数 | 剩余阻塞/是否营业就绪 |
|---|---|---|---|---|
| 原49任务总体 | 基础工程与首批数据层见执行账本；本轮增量见§7 | 各切片记录实际红绿验证 | 不以计划检查代替业务测试 | 完整营业能力尚未完成 |
| Task62-S0 | 35e6f0f66，精确24文件；§33.2 | 枚举/父链范围RED→GREEN，有效期消融/精确恢复 | 最终33项，标签17、runner5、当前三行业编译 | 人员事实前置已实现；完整BM05及部署未交付 |

不在开始前承诺日历工期。先完成ENG-01/02和DATA-01～04测得真实构建/环境/合同成本，再估后续小切片；P2/P3/P4外部条件单列，不用任务数乘固定人日虚构排期。

## §6 下一次领取任务与本轮交付

**当前交付：BM01～BM03后端模块及历史隔离部署验收完成，分别见§22/25/31。BM04 Task54～59及60A-A1～A11与模块I1修复已完成代码验收，模块代码关口见§32.24；Task61设计与Task62-S0代码关口已通过（§33.2），Task63现金/线下设计已审查，下一片Task64人工准入编码（§0.3/2.42），原Pay S1暂缓；完整BM05开工/作业/履约未交付。BM05作业及BM06完整任务日历尚未交付；全部P1～P4代码完成后统一正式部署。** 后续按§21的BM-05、BM-06等业务模块继续推进。SHR02b来源冻结/容量/替换是BM-03内部开发步骤，实际占用/超时是BM-05内部步骤，不再各自作为对外“大模块交付”。ENG-01/02及已完成数据层不重复实施。

初始计划阶段产物（2026-10-20，非当前工程状态）：本TDD、开发任务JSON、生成任务对照索引和校验器；当时尚未编码。之后实际实现及验收按§7–20逐批记录，当前总览与后续领取以§21为准。

### Task62设计→实现对账（2026-10-25）

对应§2.40及§33.2：精确24路径及已记录的两个共享协议辅助类扩展，无新DDL/角色默认授权。实际提交统计：

```text
 b-app/src/i18n/locale/ar.ts                                                                                                   |   5 +
 b-app/src/i18n/locale/en.ts                                                                                                   |   5 +
 b-app/src/i18n/locale/zh-CN.ts                                                                                                |   5 +
 .../shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/config/BeautyMerchantClientConfiguration.java     |  17 ++
 .../shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemotePersonnelOperatorFactsPort.java        | 114 ++++++++++++
 .../shop-industry-beauty/beauty-channel/src/test/java/ai/neargo/shop/beauty/port/RemotePersonnelOperatorFactsPortTest.java    | 360 ++++++++++++++++++++++++++++++++++++
 backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalPersonnelOperatorController.java                        |  69 +++++++
 backend/shop-app/src/test/java/ai/neargo/shop/scenario/PersonnelOperatorFactsIT.java                                          | 667 ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 backend/shop-app/src/test/java/ai/neargo/shop/scenario/PersonnelOperatorProtocolTest.java                                     | 205 ++++++++++++++++++++
 backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java                                                        |  20 ++
 backend/shop-base/src/main/java/ai/neargo/shop/spi/personnel/PersonnelOperatorFactsPort.java                                  | 100 ++++++++++
 backend/shop-base/src/main/java/ai/neargo/shop/spi/personnel/PersonnelOperatorInternalApi.java                                |  17 ++
 backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalBindings.java                                                      |   3 +
 backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalReplyDeserializer.java                                             |  10 +
 backend/shop-base/src/test/java/ai/neargo/shop/svc/BoundInternalProtocolTest.java                                             |  48 +++++
 backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/OperatorPersonnelFactRow.java                                 |  17 ++
 backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java                                       |  34 ++++
 backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/PersonnelOperatorFactsPortImpl.java                          | 144 +++++++++++++++
 docs/technical/reference/B端功能点-权限码-页面.md                                                                             |   7 +-
 docs/technical/reference/B端功能矩阵-按角色.md                                                                                |   9 +-
 docs/technical/reference/三端权限矩阵-按业务域.md                                                                             |   9 +-
 packages/shared/tests/biz-perm-label.test.ts                                                                                  |   8 +
 scripts/tests/personnel-operator-runner.test.mjs                                                                              |  55 ++++++
 scripts/verify-personnel-operator.mjs                                                                                         | 216 ++++++++++++++++++++++
 24 files changed, 2139 insertions(+), 5 deletions(-)
```

### 本轮计划校验记录 · 2026-10-20

- 五组美业任务/Java方法/项目结构/API/数据库设计生成物校验通过；任务依赖无环，49任务覆盖全部160个API/合同、118功能与39项数据结构，首次归属唯一。
- 未发现早期阶段依赖后期能力；文档本地链接、状态行、README索引与定向空白检查通过。
- 未运行Maven、业务测试或DDL；未创建美业工程。以上结果只证明设计与计划一致，不证明业务可运行。

## §7 三行业公共写入基础实施（2026-10-20）

零售作为公共核心，餐饮最新已提交693ad5b77（NEXT-06实现ec5aba9a5）作为增强基线，美业接入。只导入经git status逐文件确认干净的15项已提交差异。原餐饮工作区的未提交NEXT-08等内容未导入。

| 任务 | 交付 | 验收 |
|---|---|---|
| S1 | auth/StoreRequestScopeValidator及餐饮薄封装 | 通用主体/门店比较；非法原门店头拒绝；零售旧过滤器不变 |
| S2 | idem/IdempotencyScope、PlatformOutboxPublisher、PlatformWritePrerequisites | 固定行业操作域、严格key、统一PLATFORM源和真实共享表检查；旧幂等接口兼容 |
| S3 | 两行业WriteGuardConfiguration及美业Web接线 | 复用原Mapper/Service；错事务/缺来源拒绝，schemaReady与可写前置分开 |
| S4 | BAPI-002原Controller/Service/Req/VO、ActionLog实体Mapper、spi/beauty事件合同 | 首个真实政策发布；不可变版本/头CAS/审计/幂等/outbox同主库事务；首建唯一键仲裁 |
| S5 | 零售/餐饮回归、美业真实HTTP和一次性MySQL测试 | 同键一次、异参冲突、并发首建、过期版本、跨店/伪造actor、原子回滚 |

每项先失败测试、后实现、绿灯及提交。公共代码放现有模块，不增加Maven子项目，不依赖food-core/web。原零售默认店回落和无key兼容保持；新入口采用严格校验。客户端key1～128字符；操作域BEAUTY:v1:<SHA256>，输入operation/entity/store逐段UTF-8长度编码。请求hash使用typed规范字段，包括expectedRevision/validFrom/policy，排除追踪字段。

首写沿既有方法签名；expectedRevision=0仅表示创建尚不存在的门店政策头，首版根version=1，后续必须带正数当前version。政策内容本批细化为bookingAheadDays、cancelRule（freeCancelBeforeMinutes/allowCustomerCancel）、allowedModes；P1仅PREPAID_SINGLE模式，不据配置开放后付。OffsetDateTime统一UTC毫秒持久化。事件合同位于shop-base/spi/beauty，正式路由未登记则发布失败、整体回滚，不以测试archive路由冒充投递就绪。政策本地发布不增加长流程ServiceSubmission。

### 7.1 实际实现边界

S1～S4 已落地；S5 以本节后续验证记录为准。共用能力所有者是零售已有核心包，
不是由美业反向承载零售。餐饮薄封装和美业入口使用共用门店校验；既有零售过滤器保持原行为。
BAPI-002 Controller 增加可选原始门店头 requestedStore，已同步 Java 方法真源；Service 签名保持不变。
业务审计沿系统员工身份；预约匹配所需技能、排班、资源及工作流留在后续共用人员/资源合同中。
已有疗程权益可复用共用权益合同；本次政策发布不实施售卡、核销或资金流程。

独立复核指出事务内元数据检查重复取连接可能耗尽连接池，已以真实 MySQL 单连接池复现，
改为 DataSourceUtils 统一复用/释放连接，不改变事务连接状态；复核确认原问题关闭。
本地政策、操作日志、幂等和事件原子写入不代表正式消费者和 standalone 远程调用提供方已完成。
readiness 保持503；未完成任务继续沿原49任务索引推进，不将本切片等同全量美业交付。

### 7.2 餐饮最新提交追平

收尾时餐饮已新增提交 `c634de551`（门店政策读取，NEXT-08），已原样 cherry-pick 为本分支 `74fdaf218`。
因此最终对齐餐饮基线为 c634de551，而不是停留在起始693ad5b77；未提交内容仍未导入。
新增餐饮政策 Controller 沿 FoodRequestScopeValidator 薄封装调用公共校验器；
本轮公共抽取及美业发布实现提交为 `f4f89ec3e`。在此最终组合上重新验证共享构件、餐饮 reactor、
临时 MySQL 及零售宿主同时嵌入餐饮/美业的构件形态。

### 7.3 本批最终验收记录

| 验证范围 | 实际结果 |
|---|---|
| 公共构件 reactor | 210项通过，0跳过 |
| 美业完整 reactor | 47项通过，0跳过 |
| 美业临时 MySQL | 31项通过，含单连接池、并发首建和真实事件写入后回滚 |
| 美业宿主 HTTP→SQL | 1项通过，真实 Controller/Service/事务/数据库；外部目录事实和归档路由为测试配置 |
| 最新餐饮完整 reactor | 91项普通测试通过；16项数据库专用例由独立脚本执行 |
| 最新餐饮临时 MySQL | 34项通过，0跳过（15主库＋18防护/政策读取＋1独立服务） |
| 零售宿主构件形态 | 默认零售；餐饮/美业同时embedded 11通过＋2不适用跳过；同时standalone13通过 |
| 设计与 SQL | 五组设计生成物、冻结迁移一致；Node6项；没有新增SQL方言依赖 |
| 服务启动 | 无数据库、关闭美业时真实JAR启动；liveness200/readiness503 |
| 门店头消融 | 删除原始门店头比较后4例中1个断言失败、0错误；恢复后公共构建通过 |

独立复核已关闭单连接池问题。全仓文档规范仍为5通过/3既有失败：Mermaid数量基线、
2处既有餐饮任务索引断链、5篇既有餐饮索引遗漏；没有新增美业断链。
本批S1～S5完成，不把完整49任务、正式事件消费或美业standalone业务能力标成已完成。

## §8 政策与项目定义实施批次（2026-10-20）

用户已批准上一轮列出的第1、2项；沿现有五模块实现，不提前开发预约。使用 executing-plans/TDD，原工作树不变。

### 8.1 规则与文件边界

- N1：补 StorePolicyQuery/StorePolicyVO、StorePolicyService#getPolicy 及原 BizStorePolicyController GET。
  读取本店 ACTIVE 头下 effective_at<=at 的最新有效版本（effective_at降序、revision_index降序）；
  返回配置头revision及有效版本revision，未来版本不提前生效，缺有效版本404，不能将配置当交易能力许可。
- N2：在 api/spi 放 BeautyPolicyInternalApi、BeautyDefinitionInternalApi；channel/client 实现两种远程Service；
  server/api 提供内部端点，沿现有服务令牌保护。actor仅由平台认证入口委托，内部请求显式验证entity/store/actor，
  自带query.storeNo不得偏离委托门店；审计通过受控内部请求建立短生命周期操作身份并finally还原。
  ServiceName新增BEAUTY；所有网络调用在本地主事务之外，不在写锁中请求公共商品服务。
- N3：商品权威域补 ServiceProductFactsPort/实现及内部只读合同，返回本店SERVICE SKU的商品号、基础时长和实时报价；
  门店/主体归属、上架及类型检查在商品所有者完成。美业只存基础时长快照，duration必须与商品事实一致；售价不写bty表。
  ServiceDefinitionService按既有listDefinitions/publishDefinitionVersion签名实现；duration细化为minutes/bufferBeforeMin/bufferAfterMin，
  requirements使用现有字段，资源类型STAFF/ROOM/SEAT/DEVICE，selectionRule=ANY_QUALIFIED，P1单步骤。
  首建expectedRevision0、根version1；后续CAS。定义版本与要求追加，跨主体和同主体跨店父链隔离；
  查询at使用版本created_at（现有定义表无effective_at），按创建时间/版本号选历史版本，禁止改冻结V1。
  分页增加page/size（默认1/20，上限100）并同步方法真源。步骤固定SERVICE/顺序0；复杂步骤留P4。
- N4：政策/定义发布事实采用明确的归档合同：SQL版本和action_log是权威，事件作为可追溯发布凭据，
  本批没有必须异步执行的业务副作用。提供显式部署配置 shop.outbox.archive-only 两个精确事件名，
  不默认修改公共路由、不注册空消费者、不据归档宣称通知已交付；没启用正式配置或消费者仍拒绝写入。
  后续如要求异步投影/通知，先迁移路由并验收消费者，再去掉对应归档合同。

### 8.2 独立验收任务

| 任务 | RED→GREEN步骤与准出 |
|---|---|
| N1 政策有效版本读取 | 先真实MySQL测试未来版本/时点等价/异店拒绝，再DTO、Service、Controller；缺有效版本404且无写副作用 |
| N2 远程提供方 | 先真实HTTP测试缺/错令牌、范围不符、原actor审计、业务400/403/404/409传递，再内部合同、客户端、配置；web/channel不得依赖core |
| N3 项目定义发布查询 | 先真实MySQL测试商品类型/时长不符、幂等、版本CAS、并发首建、不可变父链、回滚，再共享商品合同与本域业务；外部查询不占主事务 |
| N4 路由与回归 | 正式归档配置由测试读取并使用真实outbox；非法路由仍回滚；三行业共享回归、设计生成器、SQL冻结、消融及独立复核 |

每项运行目标测试确认失败原因，再实现并运行完整受影响reactor；各完成点提交。最终按实际测试回填§8.3。
Ruling：前两项既有接口实施属于1档；补商品SPI不是新商品域，也不新增表/账本。已有用户执行授权覆盖常规实现选择，不重复请求审批。

### 8.3 实际交付对账

N1/N3已实现原四项HTTP（BAPI-001/002/007/008），DTO和方法真源已重新生成；N2实现两组内部合同、
channel远程Service、server内部入口和商品反向合同。商品所属实现位于shop-core/product/port，
公共合同在shop-base/spi/product，PLATFORM内部端点在shop-app/portal/internal；美业没有导入商品Entity/Mapper。
N4配置为core资源beauty-publication-archive.yml，独立server沿原JdbcOutboxStore注册PLATFORM归档存储，
缺正式路由拒绝写入；standalone实际TCP到DB已确认两类事件ARCHIVED和原actor审计。

执行中反例：新增多市场SKU导致selectOne多行，现补CN条件，与已有GoodsQueryPort同口径；
外层事务调用定义发布导致外部事实查询落入事务，现显式拒绝ambient事务；两处均先失败后修复。
历史定义created_at沿现有AuditMetaObjectHandler的JVM本地时间，按稳定部署时区转换，不能把它当UTC裸时刻；
本轮没有修改零售全局审计或冻结V1。政策effective_at仍为独立明确的UTC值。

界线：DEF-01主HTTP切片完成情况以最终验证为准；POL-01的BAPI-144公开营业信息未在本批范围，
完整预约/资源匹配、任务工作台、通知及投递引擎仍待后续。readiness保持503。
全局架构检查发现既有OpsPointsInspectionController的全限定注解导致1项失败，先核验基线并记录，不冒充新代码违规。

### 8.4 本批验收与剩余边界

| 对账 | 实际证据 |
|---|---|
| N1 有效政策、N3定义发布及父链 | BeautyCatalogIT10项真实MySQL通过；含未来政策、历史选版、首版前空页、幂等重放、商品错范围/时长、并发首建、outbox实际flush后回滚与外层事务拒绝 |
| N2 双部署 | BeautyStandaloneEndToEndIT实际TCP→独立安全链→业务→MySQL通过；BeautyPolicyEndToEndIT实际宿主HTTP→SQL通过，包含政策GET时间参数及跨店查询拒绝 |
| N4 正式归档配置 | 独立服务测试显式导入实际YAML，SQL确认两类事件ARCHIVED、审计actor为原委托人；不据归档冒充业务投递 |
| 商品事实 | ServiceProductFactsPortTest6项真实H2 Mapper SQL通过；真实HTTP反向商品错误映射测试通过；外部商品依赖在独立端到端中提供受控事实测试替身 |
| 普通reactor | 美业54项通过；公共模块210项通过；餐饮91项普通测试通过（16项数据库专用未在本轮重复，上一批34项已执行，不算本批新证据） |
| 宿主组合 | 双standalone14项形态/自动装配＋6商品事实＋6i18n共26通过；双embedded11通过＋2个不适用standalone形态跳过 |
| 设计与迁移 | 五组设计生成器、冻结SQL、Node6项通过；没有新增SQL方言依赖或修改V1 |

独立复核发现2项P2并已补反例修复：分页前EXISTS过滤尚无历史版本的头，确保records/total一致；
查询record构造仅填默认值，Bean Validation处理HTTP非法分页，Service继续显式防御，内部query加@Valid。
两项均经历真实RED→GREEN，复核确认代码原因关闭。

全局检查保留真实结论：ArchitectureTest15/16，既有OpsPointsInspectionController的@PathVariable全限定名违规在基线9ae57e5e6已存在；
BackendI18nParityTest6/6；文档守卫5/8，既有Mermaid基线及餐饮链接/索引3项失败未扩大，不修改无关业务或抬升基线。
本批只交付政策/项目定义切片，readiness503不变，未连接营业库、未合并或推送。

最终补验：standalone宿主从实际AutoConfiguration注册两个Remote Service与两个公开Controller，
无beauty-core；关闭美业不创建远程提供方，8项美业形态/装配用例全部通过。
消融移除query的@Max(100)后，4项Web用例中1个断言失败/0错误；finally恢复，完整54项reactor通过。
实际关闭态JAR不连接数据库启动，liveness200/readiness503。最终源文件按120列及补丁空白检查收口。

## §9 DATA-05/06：预约与工单任务数据切片

本批接续183b04e8a，工作树干净；只实现已定义的7项Entity和15条自定义Mapper方法，不新增HTTP、人员资源主档或迁移。

### 9.1 实施规则和接口对账

- E1 预约：BtyAppointment/Item/Assignment继承BaseEntity，字段沿冻结V1；AppointmentMapper原4方法与AssignmentMapper原读取方法不改签名。
  安排revision为显式历史版本，不从状态更新后的根version推算。改约限HELD/CONFIRMED且版本/前态一致，必须有非空新hold与合法时段；旧安排行只追加不覆盖。
  changeStatus只允许HELD→CONFIRMED/CANCELLED/EXPIRED、CONFIRMED→CANCELLED/NO_SHOW；确认须hold。
  markArrived只允许CONFIRMED→CHECKED_IN、首次签到、非空时间；其他终态拒绝。
  assignment读取从安排→项目→预约联结tenant/entity/业务号/store，跨店/跨主体/软删父链不得读到；requestedPersonnel与安排人员分开保存。
- E2 工单任务：BtyWorkOrder/ServiceTask/ServiceStep/ServiceParticipation，Mapper方法按既有清单。
  所有标量CAS显式version+1及actor/updatedAt，错范围、错版本、非法前态返回0；后续业务Service必须将0映射冲突。
  工单范围版本、task绑定版本、BaseEntity版本分别校验；旧task的创建scopeRevision允许小于当前根scopeRevision。
  advanceScope仅OPEN/IN_SERVICE且未冻结；freezeScope接受OPEN/IN_SERVICE/SERVICE_FINISHED，关闭仅SERVICE_FINISHED+已冻结。
  closeFrozenScope重查真实任务数量/终态、步骤、参与段和当前绑定引用，不以计数快照关闭；空工单不关闭。
  recordCompletion仅IN_SERVICE，正增量且最多六位小数、不超过qty-voided、最终完成须至少一步且步骤齐备；只有全额完成设置DONE/finishedAt。
  changeStatus不提供任意target捷径：PENDING/PAUSED→IN_SERVICE、IN_SERVICE→PAUSED；VOIDED仅PENDING、从未开始且done0/voided=qty。
  启动/暂停恢复与绑定切换要求净数量qty-voided>0；全部作废的待执行任务只能进入VOIDED。
  switchBindingRevision只前进，须本域相同任务的新绑定行按uom完整覆盖qty-voided（来源外部许可/guard原子分配仍由DATA-07/SRC负责）。
  step完成仅IN_PROGRESS且task在服务中，开始结束合法、前序步骤已DONE/SKIPPED；resultSnapshot非空JSON。
  participation只结束未结束区间，时间不倒置、保留人员与原计划；允许在冻结待关闭期间收口参与段，但已关闭/取消根不可变。
- 锁序：后续业务事务先锁workOrder，再稳定排序锁task，再子行；冻结/完成并发验收遵循此序。
  本地SQL检查不代表Core/Pay许可或公共资源占用已确认，不在本批创建假服务、假资源或成功占位。

### 9.2 RED→GREEN任务及文件

| 任务 | 文件 | 验收 |
|---|---|---|
| E1 | beauty-core/entity/BtyAppointment*.java；BeautyMappers内3个Mapper；BeautyDataScopeRegistration | AppointmentPersistenceIT：字段/主库审计、安排历史、改约CAS及回滚、状态/签到、父链隔离、同版本并发单胜者 |
| E2 | entity/BtyWorkOrder/BtyServiceTask/BtyServiceStep/BtyServiceParticipation；BeautyMappers内4个Mapper；注册数据域 | ServiceTaskPersistenceIT：冻结/范围、数量上限、绑定单调、步骤顺序、参与结束、真实关闭条件、租户/门店隔离、事务回滚和根锁并发 |
| E3 | scripts/verify-beauty-persistence.mjs、README及本页 | 先缺类红灯再完整真实MySQL；普通reactor、冻结SQL/生成物、关键CAS消融、独立复核；本地提交不合并推送 |

Ruling：本批复用V1已存在的trade_binding表做只读局部守卫，不实现DATA-07 Mapper或任何外部资金/资源权威；多聚合业务编排仍待后续Service验收。

### 9.3 本批实际验收

- 数据层范围：7实体、7个Mapper接口（其中预约项目只用BaseMapper）、15条原始自定义方法；累计13实体数据域。
  DATA-05/06仅持久化层完成，预约/任务Service与Controller、DATA-07来源编排及公共资源匹配未交付；readiness仍为503。
- RED→GREEN：缺实体16项断言失败；数量精度/缺步骤/关闭早于任务完成3项反例失败；
  已开始任务误作废1项反例失败；零净数量启动/空绑定切换2项反例失败；全部修复后最终66项真实MySQL通过，0失败/错误/跳过。
  新增预约8项、工单任务17项；原有41项保持通过。解析器PRIOR别名改为earlier_step，不绕过平台拦截器。
- 完整美业reactor 54项通过。五组设计生成器、冻结V1和Node6项通过；SQL方言扫描无新增依赖；新增Java120列及补丁空白检查通过。
- 消融：仅移除数量六位精度守卫，23项用例中1断言失败、0错误；finally恢复，最终全量回归通过。
- 独立复核核对7实体字段、15原始签名及父链/CAS/时间/数量守卫；发现的零净数量P2已有真实反例与修复复核。
  基于原冻结结构实现，不修改全局审计和V1，不连接营业库，不改原餐饮工作区。

最终双部署补验：BeautyStandaloneEndToEndIT实际TCP→业务→MySQL1项通过；BeautyPolicyEndToEndIT宿主HTTP→MySQL1项通过。
实际可执行JAR关闭态无数据库启动，liveness200/readiness503；全部以最终源码构建后执行。

## §10 DATA-07：来源分配与提交意图数据层

基线a0965d62d；沿冻结V1实现ServiceSubmission、TradeBinding、SourceAllocationGuard三个新Entity，ActionLog已实现不重复创建。
新增3个嵌套Mapper、9条原始签名方法及数据域注册；没有新增表、公开API、资金/资源权限或恢复业务Service。

### 10.1 规则与先行取舍

- F1 提交：tenant/entity/store显式范围；有workOrderNo时还须同范围有效工单。requestKeyHash查回原payload/frozenCommand，不能修改原意图。
  原签名不带action，但数据库唯一键带action；同一摘要匹配多行时由MyBatis单行合同拒绝歧义，不LIMIT1选任意记录。
  后续Service必须将action纳入规范键摘要，并比较payloadHash；本批不假装单纯读取已完成异参业务错误映射。
  固定迁移：RECEIVED→SOURCE_PENDING/REJECTED；SOURCE_PENDING→BOUND/RECOVERING/REJECTED；
  RECOVERING→SOURCE_PENDING/BOUND/REJECTED。三个非终态允许自身CAS用于认领租约；BOUND/REJECTED不能重新激活。
  活动态nextRetryAt按DATETIME(3)实际持久化精度严格晚于updatedAt；终态清空retry，REJECTED须非空failureCode。未知结果必须RECOVERING，
  只有后续Service查到权威明确拒绝后才可REJECTED，Mapper状态CAS不代表外部证明。
  selectDueForUpdate只取三个非终态、nextRetryAt非空且到期的行，稳定排序、limit1..100、FOR UPDATE SKIP LOCKED；
  认领者必须同短事务advanceProgress延后retry并提交，外部调用不持锁。无retry的行不擅自视作可恢复。
- F2 来源guard：同主体规范sourceKind/sourceNo/sourceLineRef唯一，不按store/sourceRevision复制；缺行由唯一键仲裁，空SELECT不是锁。
  allocateQuantity只接受正六位数量、expectedVersion/sourceRevision一致、不超sourceQty；refresh只接受更高来源修订、
  新数量不少于已分配量和64位摘要，保持累计allocatedQty。来源权威/门店/uom由后续Service核验。
- F3 释放：正六位数量、不超已分配量、CAS与审计；保留原绑定历史。
  原签名没有目标binding或消费水位，因此采用保守整guard守卫：所有历史绑定（包括软删）都必须拥有可验证的本主体任务/工单/提交，
  来源三元组/uom一致、同门店父链、任务从未开始且全量VOIDED、提交仅BOUND/REJECTED，否则拒绝整次释放。
  无绑定的预留量可释放；已消费、在途、残缺父链不释放。代价：存在一项受保护分配时，暂不能局部释放该guard其他空闲量，
  待SRC业务有正式目标绑定/消费事实合同再扩展，不能靠估算消费量放行。冻结命令尚未形成绑定的在途引用必须由未来协调Service验证。
- F4 绑定批查：按taskNos/bindingRevision读取；空/null列表返回空，沿binding→task→workOrder与binding→submission双父链确认tenant/entity/store，
  支持不同任务按版本分组批查；不覆盖旧绑定。
- 多根锁序：涉及来源时先按规范身份排序锁guard，再排序工单根→任务→绑定/提交；纯任务仍沿§9根→任务→子行。
  恢复扫描先在独立短事务认领并提交，不带扫描所得submission锁进入guard业务事务，以免与上述顺序反转。

### 10.2 任务与验证

| 任务 | 文件范围 | 行为证据 |
|---|---|---|
| F1 | BtyServiceSubmission、ServiceSubmissionMapper | 原请求/冻结命令恢复、歧义拒绝、状态CAS/审计/租约、到期范围与SKIP LOCKED、终态禁止重启 |
| F2/F3 | BtySourceAllocationGuard、SourceAllocationGuardMapper | 共享guard跨任务并发上限、版本跨修订唯一、精度/陈旧修订拒绝、刷新兼容、释放消费/在途/异常父链拒绝、回滚 |
| F4 | BtyTradeBinding、TradeBindingMapper及数据域注册 | 双父链、批查空参/指定版本、历史保留、真实Mapper主库审计和主体域 |
| F5 | verify-beauty-persistence.mjs、README、TDD/ledger | 先缺类RED、实际MySQL GREEN，完整reactor与原66项回归、双部署端到端、消融、一次独立审查及本地提交 |

数据层冲突仍以影响行0返回，后续Service必须转为明确业务冲突。完整跨域恢复、源行权限/消费证明、调度Job与公开API尚未交付。

### 10.3 实际交付与验证对账

| AC | 实际测试方法 | 结果 |
|---|---|---|
| F1 原请求/参数/冻结命令保留、歧义拒绝 | ServiceSubmissionPersistenceIT#sameRequestReturnsOriginalPayloadAndFrozenSourceCommand、#rejectsAmbiguousRequestHashAcrossActionsInsteadOfPickingOne | 实际Mapper插入同action同键异参被唯一约束拒绝，原payload保持；跨action摘要歧义拒绝 |
| F1 固定状态与审计 | #progressCasRetainsOriginalIntentAndRejectsTerminalRestart、#activeLeaseNeedsFutureRetryAndRejectedNeedsFailureCode | 陈旧版本/非法迁移/终态重启均0，成功显式审计，原号/命令不替换 |
| F1 恢复认领 | #dueRowsAreBoundedScopedAndLeasedInSameTransaction、#concurrentRecoverySkipsLockedIntent | 实际双连接SKIP LOCKED、限额/范围、同事务租约已验证 |
| F1 持久化租约精度 | #leaseMustRemainFutureAtPersistedMillisecondPrecision | 亚毫秒先RED：expected0/actual1；按DATETIME(3)比较后GREEN |
| F2 共享来源行上限 | SourceAllocationConcurrencyIT#concurrentTasksShareOneGuardAndCannotExceedSourceQuantity、#sourceRevisionsCannotCreateAnotherGuardForSameCanonicalLine、#refreshPreservesAllocatedQuantityAndRequiresNewerCompatibleSnapshot | 并发仅一个1.5分配成功，来源2数量不超限；修订不另建guard，刷新保留已分配量 |
| F3 释放保护 | #doesNotReleaseConsumedQuantityEvenWhenHistoricalBindingWasSoftDeleted、#doesNotReleaseAllocationReferencedByRecoveryInFlight、#malformedParentChainCannotAuthorizeRelease、#releasesFullyVoidedNeverStartedTaskWithoutDeletingBindingHistory | 已消费/在途/异常父链拒绝，从未开始全量作废可收口且历史保留 |
| F4 批读与主库数据域 | #batchReadsOnlyRequestedBindingRevisionAndAuthorizedDoubleParent、#entityMapperInsertUsesPrimaryAuditAndSubjectDataScope；ServiceSubmissionPersistenceIT#actualEntityInsertUsesPrimaryAudit | 指定版本/空列表/错门店/错主体、实际审计与数据域通过 |

首轮缺类20项断言失败、0错误；实现后20/20通过。独立审查指出持久化租约精度，按合同影响升级为P2，补反例RED→GREEN。
最终普通reactor54/54、真实MySQL87/87（新增21＋原66），全部0失败/错误/跳过；不以H2或mock冒充实际约束。
消融仅移除来源修订单调守卫，21项中1断言失败/0错误；finally恢复后完整回归通过。
五组设计生成器、冻结V1、Node6项及SQL方言无新增检查通过；方法JSON及生成清单已同步实施边界，9条原签名不变。

文件对账：beauty-core新增3Entity、2个IT；改Mapper集合和数据域注册；改MySQL验证脚本；README、TDD、方法JSON/对应生成方法清单及ledger同步。
ActionLog复用已有类，未新建Repository/转发Service、未加公共接口/表/错误码/配置，不修改零售或餐饮代码。
首批16项P1持久化合同完成；49任务整体、SHR共用人员/资源及跨域恢复/预约工作台仍待后续，readiness503不变。

最终双部署与可执行验证：BeautyStandaloneEndToEndIT实际TCP→业务→MySQL1项通过；BeautyPolicyEndToEndIT宿主HTTP→MySQL1项通过，全部0失败/错误/跳过。
实际最终JAR关闭态无数据库启动，liveness200/readiness503。本地检查点保留在美业隔离工作树，不合并/推送。

## §11 SHR-01a：共用人员基础数据与批量事实查询

### 11.0 核验与本批边界

基线aaa7ff243；只读核验最新本地餐饮分支codex/food-next01=07f6b2347。其shop-merchant没有新增人员/资源实现，
账号与预约槽实现沿现有共同基线；不合并其无关餐饮/前端工作。原目录仍有用户未提交变更，不写入该目录。
复用MchAccount作为可选登录身份、MchStoreRole作为权限，不把权限角色当业务岗位。现有AppointmentSlotPort只支持单店总名额，
不具备跨店STAFF、技能、组合hold/原子替换；本批保留原接口及行为。

发现依赖：原SHR-01包含按resourceNo排班，而SHR-02依赖SHR-01。按既定所有权拆为SHR-01a人员5表→SHR-02a规范资源→SHR-01b班表→SHR-02b组合仲裁。
本批只交付01a基础数据和内部批量事实查询，不实现人员维护HTTP、账号绑定/任职写Service、排班、资源占用或美业远程接入；SHR-01/02整体不标完成。

### 11.1 设计与合同

- shop-base新增spi.personnel.PersonnelFactsPort，getBatch(Query)：tenant/entity/store、1..200个明确personnelNos、UTC时点（截到毫秒）；
  返回稳定personnelNo与assignmentNo、业务姓名/本店等级、公开标记、生效区间、业务岗位和已核验有效资格。
  不返回账号号、电话、联系资料或权限；有无/停用登录账号不决定业务任职。无结果是没有有效事实，数据库/范围查询错误不得伪装空闲或匹配成功。
- shop-merchant新增5个Entity，原MerchantMappers增加对应5接口；实际任职、岗位、资格投影PersonnelAssignmentFactRow/PersonnelPositionFactRow/PersonnelQualificationFactRow归merchant.dto。
  查询按tenant/entity/store与有效主体/门店/人员/任职父链，区间[validFrom,validTo)，岗位只读ACTIVE，资格只读VERIFIED且有效、门店为当前店或主体通用。
  返回全部符合条件的资格及其scope，匹配策略/开工许可由后续实现；不在本查询中授予收款、隐私或服务权限。
- PersonnelFactsPortImpl直接查询本域Mapper。四处上限：最多200个人、最多200条任职（SQL取201作溢出哨兵）、岗位/资格各最多2000条（取2001）；
  超限或同人员同店同一时点存在多个有效任职，明确拒绝，不能LIMIT后静默丢记录或任取一条。
  三次批量SQL在独立只读REPEATABLE_READ快照事务内执行；拒绝外层事务，调用方在自己的写事务外读取事实。
  数据域绕过仅封装内部可信查询，全部SQL仍有显式范围；通用BaseMapper的5表主体域仍注册。
- shop-app正式平台迁移V532__shared_personnel_foundation.sql，5表沿既有设计真源字段、业务唯一键带tenant/entity；
  CHECK保护状态、有效区间和公共version/deleted；不加跨域外键、不修改已应用迁移。账号主体/任职不重叠的写业务守卫与审计由后续维护Service完成，当前读取拒绝歧义。
  新表随数据库默认字符集/排序规则，避免引入MariaDB/MySQL专有排序规则。只在一次性MySQL执行，不连接营业库。

### 11.2 RED→GREEN计划与准出

| 任务 | 精确文件/职责 | 测试与预期 |
|---|---|---|
| P1 | backend/shop-base/.../spi/personnel/PersonnelFactsPort.java | Query拒绝缺范围、空/超200名单，毫秒规范化与不可变集合 |
| P2 | shop-merchant/entity/MchPersonnel{,Account,Store,Position,Skill}.java；mapper/MerchantMappers；dto/Personnel{Assignment,Position,Qualification}FactRow；port/PersonnelFactsPortImpl；config/PersonnelDataScopeRegistration | PersonnelFactsPersistenceIT：无账号、多店、资格过期/未核验/跨主体、权限角色不授技能、时间端点、父链停用、歧义、超限、事务边界、真实审计和主体数据域 |
| P3 | shop-app/resources/db/migration/V532__shared_personnel_foundation.sql；scripts/verify-personnel-persistence.mjs | 直接执行正式迁移；状态/区间/账号可空唯一键/业务唯一键真实MySQL验证；不以H2约束替代 |
| P4 | README、TDD本节、ADR-040及ledger | 全量merchant依赖测试、既有账号/slot回归、美业reactor与DB回归、SQL/设计/静态检查、消融及一次独立审查；只本地提交 |

先写真实IT并确认缺类红灯，再实现；新增功能的每项验收按实际方法回填§11.3。
架构决定沿既有多行业方案落在merchant，不新建行业人员表；ADR仅记录这次实施和阶段拆分，不重新定义账号权限。

审查P2：不区分大小写的数据库可能将子引用P/p、A/a关联到同一父行；Java不得按子引用原字符串分组。
实际MySQL三反例19项/3断言失败/0错误后，SQL投影统一取父行规范业务号，任职歧义与子事实分组使用规范号；不修改部署排序规则。
快照验收增加真实第二连接在第一读后更改岗位/资格，当前快照仍读原值，下次查询读新值；消融切为READ_COMMITTED须触发断言失败。

派生文档范围：新增merchant表/Entity/Port触发表清单、词汇、分层及ER生成；按当前分支真源同步派生物，不修改原餐饮工作区。全局ER同时收敛既有源与派生物漂移，不据此声称新增无关业务。

新增表配套血缘：personnel_no/assignment_no登记到共用主档/任职，link_no和position_no按不同业务登记冲突而非错误关联；正式V532收尾采用InnoDB，不指定字符集/排序规则。
H2派生schema随平台迁移重生成，仍以MySQL断言证明约束。新增业务键断言先失败再修复。
基线aaa7ff243在/tmp干净归档只读重现schema-lineage两个既有失败：跨表同名业务键未分类、gen-erd键表不一致；不修无关资金/营销语义，单独记录实际结果。


### 11.3 实际交付与验收

实际新增5个Entity、5个嵌套Mapper、3个本域事实投影、1个共用PersonnelFactsPort及其merchant实现、数据域注册，
以及正式V532和可重复的一次性MySQL验证脚本。人员归属merchant，零售、餐饮、美业共用同一事实合同。

| AC | PersonnelFactsPersistenceIT实际方法 | 验收结果 |
|---|---|---|
| 可选登录、业务身份与权限分离 | personnelWithoutLoginAccountHasStableBusinessIdentityAndNoContactLeak、loginPermissionsDoNotCreateBusinessPositionOrQualification | 无账号仍有业务身份，不泄露联系资料，权限角色不授岗位/资格 |
| 多店与有效区间 | samePersonnelCanHaveIndependentStoreAssignments、effectiveIntervalsAreInclusiveAtStartAndExclusiveAtEnd | 任职按店独立，[from,to)端点通过 |
| 有效岗位、资格及父链 | businessPositionIsReadFromActiveAssignmentRowsOnly、onlyVerifiedCurrentStoreOrEntityWideQualificationsAreReturned，以及foreignSubject/inactivePersonnel/inactiveOrForeignStore/archivedEntity四项 | 只返回有效本域事实，错误父链不返回 |
| 歧义与规模上限 | overlappingEffectiveAssignmentsFailInsteadOfPickingAnArbitraryRow、tooManyChildFactsFailInsteadOfTruncatingSilently、queryRequiresBoundedExplicitNamesAndNormalizesToMilliseconds | 重叠任职、超限、非法请求明确拒绝 |
| 数据库规范身份 | databaseEquivalentPersonnelRefsCannotBypassAmbiguity、databaseEquivalentAssignmentRefKeepsPositionFact、databaseEquivalentPersonnelRefKeepsQualificationFact | 实际CI排序规则三反例先RED再GREEN，不按子引用字符串误分组 |
| 快照与事务边界 | factsMustBeReadOutsideCallerWriteTransactions、laterFactQueriesUseSameSnapshotDespiteAnotherConnectionChangingRows | 拒绝外层事务；第二连接更新不撕裂当前事实快照，下次读取可见新值 |
| 正式DDL、主库审计及主体域 | formalMigrationEnforcesIntervalsStatusesAndNullableAccountUniqueness、actualMapperInsertUsesAuditAndDataScopeRestoresAfterTrustedLookup | 正式V532真实执行、CHECK/唯一键、实际Mapper审计和主体域通过 |

最终人员MySQL20/20，共用merchant及依赖253/253，原账号/预约槽/商品事实兼容37/37，均0失败、0错误、0跳过。
美业普通reactor54/54、实际MySQL87/87、独立部署与宿主HTTP→MySQL各1项通过；最终关闭态JAR无数据库启动，liveness200/readiness503。
RR消融切换为READ_COMMITTED时20项中1断言失败、0错误；finally恢复，最终20项全绿。独立审查一次，P2规范身份问题已修复，无遗留审查项。

全局派生与边界：五组美业设计生成器、冻结V1、Node6项、SQL无新增方言依赖检查通过。新人员血缘断言1项通过，
完整schema-lineage为14通过/2失败；干净基线aaa7ff243为13通过/同2项失败，分别为“新出现的跨表同名业务键必须被分类”与
“与gen-erd.mjs的键归属表一致”。遗留source_event_no/execution_no/entry_no/line_no及旧资金键歧义不在本批修改范围，不能声称全局闸门全绿。
新5表已进入生成H2及ER/表清单，H2与迁移表集合一致；约束证明仍以实际MySQL为准。

本批仅SHR-01a基础切片完成。SHR-01/02整体、人员写维护/账号绑定守卫、美业远程消费、排班、资源占用和预约日历均未完成。
接续顺序：SHR-02a规范资源身份→SHR-01b排班→SHR-02b组合占用仲裁，再接美业预约任务与工作元占用日历；保持零售既有账号和预约槽兼容。
只在美业隔离工作树本地提交，不合并、不推送、不执行营业库迁移。


## §12 SHR-02a：共用规范资源身份

### 12.0 需求与范围

架构决定：[ADR-041](./ADR/ADR-041-共用规范资源身份与跨店人员唯一.md)。

基线5c360212c；只读核对最新本地餐饮分支8e732eeb7（新增桌台配置，共用merchant/base与平台迁移未变）。
延续已批准的多行业架构及§11.3接续顺序；需求为功能矩阵D01与共用服务人员结论，设计真源为数据库JSON的mch_resource。
目标是为零售服务人员、餐饮服务员与房间/座位、美业技师和设备提供一致资源身份，供后续班表和仲裁引用。
本批仅规范资源主档和内部批量事实，不交付维护HTTP/写Service、排班、容量桶、hold、现场占用、迁移旧slot或独立美业远程入口。
既有STAFF人员模型已存在；不引入第二份技师、桌台或工作流对象。餐饮床位等命名仍由行业展示，本期物理类型遵循ROOM/SEAT/DEVICE。

### 12.1 合同与实现设计

- shop-merchant拥有mch_resource及MchResource、ResourceMapper。V533只增该表，复用BaseEntity并显式entityNo；冻结V532及旧slot不改。
  STAFF的storeNo为空、personnelNo非空且capacity=1；物理资源必须有storeNo且personnelNo为空，capacity>0。
  tenant/entity/resourceNo与tenant/entity/personnelNo唯一；保留软删行的唯一身份，不能按门店或状态再复制同一STAFF。
  CHECK显式验证NULL结构，避免SQL UNKNOWN放过非法组合；状态仅ACTIVE/SUSPENDED，继承数据库排序规则，无跨域FK。
- shop-base/spi/resource/ResourceFactsPort.getBatch(Query)：Query(tenantNo,entityNo,storeNo,Set<String> resourceNos,Instant at)，
  明确1..200名单，时间截到UTC毫秒；at筛选任职有效期，主档姓名/状态/容量读取当前值，不提供历史主档重建。ResourceFact(resourceNo,Type resourceType,storeNo,personnelNo,name,capacity,boolean openOccupied)。
  STAFF返回同一规范号和规范personnelNo，storeNo仍为空。物理门店号来自有效store父行；不输出物理id、账号、联系资料或开放占用凭证号。
- ResourceFactsPortImpl查询本域ResourceMapper，单条批量SELECT关联主体/门店及人员任职。STAFF必须有该店该时点ACTIVE任职和有效人员；
  所有路径显式tenant/entity/store和未删/启用/未归档父链。单SQL已形成一致语句快照，无多步读取事务；不新增空转Service。
  SQL返回父行规范人员号和本表规范资源号；类型用数据库相同比较语义投影成固定枚举值，避免合法CI/AI类别导致Java转换错误；同一规范资源出现多个有效任职或超过200行明确拒绝，LIMIT201作哨兵。
  openOccupied只代表主档存在阻断指针，不证明占用可释放或没有未来hold；所有事实都不代表整个服务时段可约、资格许可或开工许可。
- 新表登记merchant主体数据域；内部可信查询暂绕过通用数据域但完整显式范围，并恢复原上下文。调用方不得直接把不可信HTTP主体传入Port。

### 12.2 实施计划（本会话直接执行）

Spec：既有资源数据库结构/字段字典及本节。Java21/Boot4/MyBatis-Plus，零新依赖；沿用隔离工作树。
Review Focus：SQL NULL约束绕过、CI引用身份、软删后重复人员资源、跨主体/店父链、重叠任职不静默折叠；各在真实MySQL验证。

| 任务 | 文件与接口 | 验证 |
|---|---|---|
| R1 | shop-app/.../scenario/ResourceFactsPersistenceIT.java；scripts/verify-resource-persistence.mjs | 正式MySQL反例先RED，缺类而非编译失败；资源查询/DDL/审计与数据域 |
| R2 | shop-base/.../spi/resource/ResourceFactsPort.java；merchant/entity/MchResource.java、mapper/MerchantMappers.ResourceMapper、dto/ResourceFactRow.java、port/ResourceFactsPortImpl.java；config/PersonnelDataScopeRegistration增资源注册 | 同人跨店同一resourceNo、物理范围/多类型、时间端点、停用/错误父链、NULL/唯一键、CI规范号、重叠拒绝及上限 |
| R3 | shop-app/.../V533__shared_resource_identity.sql | 正式迁移CHECK/唯一键，不修改旧迁移；实际审计与主体隔离 |
| R4 | 本TDD、ADR-041、README、ledger、派生schema/ER/表/词汇/分层 | 原人员20项与旧账号/slot/商品37项、merchant依赖、SQL/生成检查、消融、独立审查后本地提交 |

- [x] 写R1并运行缺类RED；字段来自现有设计，不新建行业私表。
- [x] 实现R2/R3使实际MySQL GREEN；逐条核对上述Review Focus。
- [x] 消融人员资源不按店复制的守卫（唯一键）应触发原反例，再恢复GREEN。
- [x] 完成R4并回填AC与真实方法/结果，只完成SHR-02a，不标SHR-02整体完成。


### 12.3 AC与真实验证方法

| AC | ResourceFactsPersistenceIT实际方法 | 验证行为 |
|---|---|---|
| 同主体同人跨店只有一个规范资源 | staffHasOneCanonicalResourceAcrossEffectiveStoreAssignmentsWithoutLogin、onePersonnelResourceSurvivesSuspensionAndSoftDeletionAndUsesScopedUniqueness | 跨店同resourceNo、无账号可用，停用/软删不释放唯一号，跨主体/租户各自隔离 |
| 物理资源店归属与结构 | physicalResourcesUseTheirStoreAndDoNotInventPersonnel、formalMigrationRejectsInvalidResourceShapesIncludingNullCombinations | ROOM/SEAT/DEVICE按店返回，NULL非法组合、STAFF容量及类型/状态/version/deleted拒绝 |
| 任职端点与有效父链 | missingAssignmentAndHalfOpenValidityCannotMatchStaff、inactivePersonnelAndAssignmentBlockStaffFacts、inactiveForeignOrDeletedStoreBlocksPhysicalAndStaffResources、archivedOrInactiveEntityCannotReturnAnyResource、personnelAndAssignmentParentChainsCannotCrossEntityOrTenant | [from,to)端点、未删/启用/同域父链 |
| 范围及失败语义 | explicitScopeAndExplicitNamesDoNotReturnOtherResources、suspendedOrDeletedResourcesAreOmitted、queryFailurePropagatesInsteadOfBeingReportedAsNoMatchingResource | 只返回明确名单及有效同域事实，数据库错误传播 |
| 数据库等价身份/类别 | caseEquivalentReferencesKeepCanonicalPersonnelResourceAndStoreIdentity、databaseEquivalentTypeValuesProduceCanonicalSharedCategories | P/p、S/s、R/r父链规范号和CI/AI类别均按数据库语义投影 |
| 脏数据与边界 | overlappingAssignmentsCannotBeSilentlyCollapsedIntoAnEligibleResource、oversizedJoinResultsFailInsteadOfTruncatingOrDeduplicating、queryIsExplicitBoundedImmutableAndNormalizedToPersistedMilliseconds | 重叠/超200行拒绝，显式1..200名单、毫秒规范化、不可变输入/输出 |
| 隐私与阻断事实 | openOccupationIsOnlyABlockingFactAndItsPrivateReferenceIsNotExported | 仅布尔阻断标记，不外传占用凭证或联系人/账号 |
| 主库审计与主体域 | actualMapperInsertUsesAuditAndSubjectScopeRestoresAfterTrustedLookup | 实际Entity插入审计ACTOR、BaseMapper主体隔离、可信查询恢复外层范围 |

首次缺类RED为17断言失败/0错误；初次实现18项中17通过，唯一失败为测试预设CHECK报告顺序。
非法类别同时违反type/shape，测试改为接受任一实际约束拒绝，不削弱DDL。补CI/AI类别反例19项中1断言失败/0错误；
SQL按同一比较语义投影类别后19/19通过。唯一人员资源约束消融19项中1断言失败/0错误，finally原样恢复正式V533。


最终验证：资源实际MySQL19/19（/tmp/resource-final-native.log）；原人员实际MySQL20/20；
merchant及依赖253/253；原账号/slot/商品事实兼容37/37；美业普通reactor54/54，均0失败/0错误/0跳过。
一次独立只读审查未发现需修复项；没有遗留Minor，不请求重复审查。测试未连接营业库。
五组设计生成器、冻结V1、Node6项、SQL无新增方言检查、词汇生成检查及git diff --check通过。
完整schema-lineage14通过/2失败，与§11已复现的基线一致：旧跨表业务键分类、旧ER键归属不一致；未声称全局闸门全绿。
新增表已进入H2派生schema（共346平台表）、表清单、ER、词汇及后端分层；resource_no在幂等/对账中仍有多态含义，登记冲突解释，不错误指定全局唯一归属。

文件对账：新增1个平台迁移、1个Entity、1个共用SPI、1个本域事实投影、1个Port实现、1个IT和1个一次性MySQL脚本；
修改既有MerchantMappers增加1接口、既有主体域注册增加1表；README、ADR041、本TDD、ledger及上述派生物同步。
未修改旧迁移、旧slot/账号/餐饮桌台实现、已冻结美业V1或设计任务JSON状态；无新增公开接口、权限、配置或通用依赖。

阶段边界：SHR-02a基础切片完成，SHR-01/02整体仍未完成。接下来实施SHR-01b排班，再完成组合资源占用仲裁与美业远程接入/预约日历。
资源创建/重分配/恢复及其维护鉴权仍为后续写业务；可信内部Port不等于公开或远程授权入口。
at只选择有效任职，不能重建历史姓名/状态/容量；本批真实DDL验证使用最小父表fixture，未重放全部平台历史迁移或验证所有部署排序规则。
未来部署仍须按目标环境验收；本批没有合并、推送或营业库发布。保留当前隔离分支与ledger用于后续49任务实施。


## §13 SHR-01b：共用周班表与排班窗口

### 13.0 需求与边界

基线94ff55cf7，隔离工作树codex/beauty-foundation干净；最新本地food8e732eeb7，共用merchant/base及迁移无新增。
依据功能矩阵D02、既有mch_schedule_rule/change_log设计及§12接续顺序；架构决定见[ADR-042](./ADR/ADR-042-共用排班窗口与跨店冲突.md)。
交付周规则、变更历史2表及可信内部窗口读取，供零售服务、餐饮人员/物理资源、美业资源日历和后续可约计算共用。
P3的请假/加班/停约例外审批、维护HTTP/写Service、生成容量桶、变更影响协调、远程消费及预约容量仲裁未交付，SHR-01/02整体仍不完成。
本批周排班窗口不考虑尚未交付的例外或容量，不代表整个项目可约/资格许可；不从班表直接扣旧slot或新建STAFF资源。

### 13.1 合同与语义

- shop-merchant新增MchScheduleRule、MchScheduleChangeLog，MerchantMappers增ScheduleRuleMapper/ScheduleChangeLogMapper；
  平台V534新增对应2表，字段沿既有设计与BaseEntity；业务唯一号带tenant/entity，不改旧迁移。历史仅作为后续追加证据，维护写入并发/权限仍待业务Service。
- shop-base/spi/schedule/ScheduleWindowsPort.getBatch(Query)：Query(tenantNo,entityNo,storeNo,Set<String> resourceNos,Instant startAt,Instant endAt)。
  明确1..50资源，UTC毫秒半开窗口、最长31天；范围须在1000-01-03至9999-12-29 UTC内，保留时区及跨日展开余量。
  Window(resourceNo,storeNo,assignmentNo,startAt,endAt,List<String> ruleNos)；物理资源assignmentNo为空。输入/输出及规则列表不可变。
- weekdayMask按ISO星期一bit0至星期日bit6（1..127）；startMinute0..1439、endMinute0..1440、endDayOffset0/1，
  本地持续时间>0且<=1440分钟，validFrom/validTo是规则开始日的包含端点日期。午夜结束可用1440/offset0或0/offset1。
- ScheduleRuleMapper.selectWindowRules一次SQL取requested资源及全部相关门店的有效规则、规范资源/人员/门店父链及任职，
  保留重叠任职行；STAFF同resourceNo跨店检查，物理资源只本店；主动请求门店也须有效。SQL显式tenant/entity范围；最多2000连接行，LIMIT2001哨兵，拒绝超限。
  规则生效日期用UTC请求日期前后各2日保守预筛，再按规则ZoneId本地锚定日精确展开；有效任职按UTC半开区间裁剪，不能把上班时间扩展到任职之外。
- merchant.policy.ScheduleWindowAssembler以UTC前后各2日为保守候选开始日、按业务时区展开跨午夜，再精确排除无关时段并裁剪请求/任职边界；非法ZoneId拒绝，不回退UTC。
  ZoneId允许有效区域或固定偏移；需要季节变化的规则应使用区域时区。DST不存在或重复的本地端点在可能影响请求范围时明确拒绝，不自动移到下一小时或任取一个偏移；有效唯一端点按真实UTC时长返回。
  同资源同店同任职的重叠/相邻段合并并保留ruleNos，不重复增加容量；跨店重叠或同店不同任职重叠明确拒绝，邻接换店/换任职保持分段。
  最多10000原始窗口，超限拒绝而非部分返回；先检查全部相关店冲突再只输出请求店，不泄露其他店规则/任职。
- 一次SQL形成一致语句快照，可信内部查询封装数据域绕过且恢复上下文；新表仍登记merchant主体域。主档读当前姓名/状态，不重建历史资源状态。
  affectedRefs保存JSON引用快照，beforeRevision>=0、afterRevision>beforeRevision；不能靠历史插入证明影响处理已完成。

### 13.2 实施计划（本会话直接执行）

Java21/Boot4/MyBatis-Plus沿平台，零新增依赖。Spec为本节及既有字段字典；采用原隔离工作树和直接执行方式。
Review Focus：跨日锚定/日期端点、DST重复或缺失端点、跨店同人冲突及相邻交接、任职裁剪/重叠、范围/数据量/隐私；以下实际MySQL测试覆盖。

| 任务 | 文件与产出 | 验证 |
|---|---|---|
| S1 | shop-app/.../scenario/ScheduleWindowsPersistenceIT.java；scripts/verify-schedule-persistence.mjs | 缺类RED，正式V532/533/534实际MySQL，不以H2代替DDL |
| S2 | shop-base/.../spi/schedule/ScheduleWindowsPort.java；merchant/entity/MchScheduleRule、MchScheduleChangeLog；dto/ScheduleRuleFactRow；policy/ScheduleWindowAssembler；port/ScheduleWindowsPortImpl；原MerchantMappers新增2接口及数据域注册 | ISO周掩码、时区/跨日/日期端点、任职裁剪与合并、跨店冲突、DST、脏数据/父链/规模/不可变输入输出 |
| S3 | shop-app/.../V534__shared_schedule_rules.sql | 正式字段/唯一键/时间规则/CHECK/JSON/修订约束，真实Entity审计和主体域 |
| S4 | 本TDD、ADR042、README、ledger、派生H2/表/ER/词汇/分层 | 旧资源19项、人员20项、账号/slot/商品37项、merchant及美业回归、消融/静态检查/一次独立审查、本地提交 |

- [x] S1写反例、运行缺类RED。
- [x] S2/S3实现至实际MySQL GREEN；跨店冲突消融必须触发断言失败，恢复后全绿。
- [x] S4完成对账及独立审查，只标本切片完成；P3例外及完整预约/资源业务后续。


### 13.3 AC与实际测试对账

| AC | ScheduleWindowsPersistenceIT实际方法 | 行为证据 |
|---|---|---|
| ISO星期/包含日期/跨日锚定 | isoWeekdayAndInclusiveRuleDatesProduceRealUtcWindows、overnightUsesPreviousLocalAnchorIncludingItsLastValidDate、timezoneAndQueryClippingDoNotUseUtcWeekdayInsteadOfLocalWeekday、midnightEndCanUseMinute1440WithoutInventingAnExtraDay | 当地星期与规则开始日、末日跨夜、上海本地日/UTC前日、1440午夜结束 |
| 同店合并与有效任职裁剪 | sameAssignmentRulesMergeWithoutDuplicatingCapacityAndKeepProvenance、employmentClipsWindowsAndAdjacentAssignmentsRemainSeparate | 合并保留W/W2来源，任职裁剪至10–12/12–14并保留A/A2分段 |
| 跨店冲突/隐私/相邻交接 | crossStoreOverlapFailsEvenWhenCallerRequestsOnlyOneStore、adjacentStoreHandoffIsAllowedAndForeignRulesAreNotExported、overlappingAssignmentsFailInsteadOfBeingMergedAsOneShift | 跨店重叠拒绝，相邻17点换店允许，不输出外店规则/任职号，同店重叠任职拒绝 |
| 规范身份与物理资源 | caseEquivalentReferencesReturnCanonicalResourceStoreAndAssignment、physicalResourcesUseOnlyTheirOwnStoreWithoutPersonnelAssignment | r/p/s引用按规范R/P/S/A父链；物理房间无任职且不能跨归属店 |
| 父链/范围/错误传播 | inactiveAndArchivedParentChainsBlockWindows、foreignTenantAndSubjectReferencesCannotProduceWindows、invalidTimezoneAndDatabaseFailuresDoNotPretendNoShiftExists | 停用/软删/归档/错域不返回，坏时区/数据库失败不伪装无班次 |
| DST真实时间 | dstGapIsRejectedOnlyWhenItsPossibleWindowAffectsTheQuery、dstOverlapIsRejectedRatherThanChoosingAnOffset、midnightDstGapOnNextLocalDateStillChecksItsPossibleUtcRange、uniqueEndpointsAcrossDstReturnActualElapsedTime | 缺失/重复端点影响请求时拒绝，非相关日期不误报，唯一端点跨回拨正确返回04Z–09Z |
| 规模/不可变与毫秒 | oversizedJoinedRowsAreRejectedBeforeExpansion、excessiveExpandedWindowsFailInsteadOfReturningPartialCalendar、requestAndResultsAreBoundedImmutableAndUsePersistedMillisecondPrecision | 2001连接行/超过10000窗口拒绝，名单/范围/精度与不可变列表验证 |
| 正式DDL/审计/主体域 | formalDdlRejectsInvalidRuleShapesAndMalformedChangeEvidence、actualEntityInsertUsesAuditAndRuleAndHistorySubjectScopes | 正式V534范围/周掩码/分钟/日期/JSON/修订/唯一键拒绝，实际Entity审计ACTOR及规则/历史主体域 |

实际缺类RED：22断言失败、0错误（/tmp/schedule-missing-red.log），此前断言API编译错误已纠正，不计为功能RED。
实现后22/22、0失败/错误/跳过（/tmp/schedule-green.log）。跨店守卫消融22项中1断言失败、0错误，
具体失败为重叠查询未抛异常（/tmp/schedule-cross-store-ablation.log）；finally恢复守卫，最终回归另记。


独立审查P2：原候选末日只取请求结束的实际本地日期，漏过午夜跳时后次日规则的可能UTC范围。
新增midnightDstGapOnNextLocalDateStillChecksItsPossibleUtcRange：Santiago2026-09-06 00:30–02:00，
短查询03:15Z–03:45Z原本错误返回空；实际MySQL23项中1断言失败/0错误（/tmp/schedule-midnight-red.log）。
候选锚定日改为UTC前后各2日，与SQL预筛一致，再用原端点范围过滤，覆盖跳过的本地日期且不猜测偏移。
固定偏移ZoneId保留支持，使用固定偏移不自动随季节变化；需要DST应选区域时区。


最终验证：P2反例修复后实际MySQL23/23（/tmp/schedule-fixed-native.log），共用merchant及依赖253/253，
资源实际MySQL19/19、人员实际MySQL20/20、原账号/slot/商品事实兼容37/37、美业普通reactor54/54，全部0失败/错误/跳过。
一次独立只读审查，P2以实际RED→GREEN关闭，无遗留Minor，不请求重复审查。
五组设计生成器、冻结V1、Node6项、SQL无新增方言、词汇检查及git diff --check通过；完整schema-lineage14通过/2既有失败，
仍为旧跨表键分类及ER键归属不一致，未修改无关资金/来源业务含义，不声称全局闸门全绿。

文件对账：新增平台V534两表、2个Entity、1个共用SPI、1个SQL事实投影、1个窗口Assembler、1个Port实现、1个真实IT与1个一次性MySQL脚本；
修改原MerchantMappers增2接口、原数据域注册增2表；README、ADR042、本TDD及ledger同步。
派生H2、表清单、ER、词汇与分层已更新（348平台表）；rule_no与既有分账费率规则同名，登记碰撞而非错误关联。
未修改旧迁移/冻结美业V1、旧slot/账号/餐饮实现、公开接口、权限、配置、依赖或设计任务JSON状态；实际进度以上表与本节为准。

阶段：仅SHR-01b基础切片完成，SHR-01/02与49任务整体未完成。接下来是SHR-02b容量桶与组合占用仲裁，然后再接维护/远程入口和美业预约日历。
保留内部可信范围、当前主档过滤与明确规模上限；固定偏移可用但不自动跟随季节变化，需要DST时选区域时区。
请假/加班/停约审批、完整变更影响协调、写入鉴权/历史追加守卫、容量/资格许可和旧槽迁移仍待后续。
本批MySQL执行正式V532/533/534但采用最小父表fixture，未重放全部平台历史迁移或覆盖全部部署排序规则，不宣称生产迁移验收。
仅本地检查点，不合并/推送/营业库迁移；保留隔离工作树及ledger供后续实施。


## §14 SHR-02b：下一阶段详细需求与方案

以下保留编码前提案；后续实际进度以§15–21及SHR02b详细方案的验收记录为准。

完整入口：[共用资源容量与组合占用详细方案](./TDD-共用资源容量与组合占用-SHR02b.md)。
基于378a5f2f7人员/资源/周排班实际交付，明确34条需求、三行业边界、6个子阶段、状态机、时间桶/缓冲归一、根锁/净量替换、来源保护/冻结决策、现场交接/超时阻断及32项验收场景。
推荐原4张容量表补4类支撑数据：稳定归属头、持久命令日志、occupation-unit关系、多原因block，共8表；这是待评审模型，原39项数据库JSON尚未修改，不产生DDL。
5分钟UTC网格、10分钟TTL/建议30分钟普通续期上限等是候选值，正式编码前按三行业数据与压测冻结。
P1整单位交接、业务门店不通过replace迁移；正常开放占用限capacity1，真实超时阻断保留已存在的未来预约并协调，不能混为同一种状态。
来源冻结/保护协议及维护写守卫是接入门禁；既有只读人员/班表事实不代替锁内复核。旧零售slot写路径及原例外不自动切换。
上一批仅细化方案，编码前02b-1～6均未实施；现有V532–V534、设计任务JSON、Java代码及营业库未变更。


## §15 SHR-02b编码启动：需求归一

执行计划见[SHR-02b §2.2](./TDD-共用资源容量与组合占用-SHR02b.md)。
02b-1细分为1a归一算法→1b五表数据契约→1c归属/命令持久化，本轮实施可独立验证的1a。
1a不等于数据层或预留能力完成；未新增营业入口、迁移或消费方，后续仍须实际MySQL并发和来源恢复验收。

本轮实际交付ResourceDemandNormalizer及15项行为测试；共用merchant及依赖268项通过，消融1项真实失败后恢复全套通过。
五组设计检查、冻结V1、Node6项、SQL无新增方言、Java行宽与diff检查通过；独立审查无重要问题。
Minor待补：增强输入列表不被修改的断言；当前代码不修改输入。正式数据库与命令/归属并发仍未实现。
下一批为02b-1b五表字段/约束真源与正式MySQL数据层，实施前核对最新餐饮分支及迁移最大版本。


## §16 SHR-02b-1b五表数据契约

详细计划/实际证据见[SHR-02b §2.4、§5.5](./TDD-共用资源容量与组合占用-SHR02b.md)。
最新本地餐饮14f90a9ad核对后沿用merchant规范；V535新建归属头、命令、容量桶、hold及单位明细五表，
同步五Entity/五内嵌Mapper及主体数据域；真源39→41项，任务数量49不变，私有V1不改。
修订区分、合法JSON及状态/释放证据、UTC5分钟桶、容量溢出保护、历史唯一和精确组引用均有实际MySQL约束验收。
原生17/17，消融容量求和后2项真实失败再恢复17/17；merchant与依赖268/268，独立美业54/54，旧预约相关37项通过。
全量H2实际重放353表/2400种子成功；Node7、五组设计、冻结V1、SQL无新增方言及diff检查通过。
新增执行的SchemaDriftTest有1项既有支付异常表列解析失败，已在本批前7925cbf5e数据上调用同一方法复现；
schema-lineage仍为14通过/2既有失败，详见方案日志，不宣称全仓闸门全绿。
独立审查无重要问题，Minor为终态命令resultPayload/rejectionCode非空Mapper回读覆盖待补，排1c验收。
仅数据契约完成；命令不可变/结果恢复、owner根CAS、准入及原子预留尚未实现，接下来1c启动前细化方法再编码。


## §17 SHR-02b-1c持久命令与归属CAS

详细计划/实际证据见[SHR-02b §2.5、§5.6](./TDD-共用资源容量与组合占用-SHR02b.md)。
核对最新本地餐饮5a10a94e0，merchant/base/平台迁移无相关增量；按零售既有Service接口/impl与嵌套Mapper规范落地共享owner内部内核。
独立提交冻结命令，业务事务持执行凭证推进归属头和不可变成功结果；失败/未知结果按原号恢复，不猜拒绝。
同来源异键共享owner版本CAS，旧终态不可覆盖；引用按数据库规范身份，范围/登录租户/活动父链强校验。
31项命令原生测试、五表17项、merchant268项、美业54项及旧路径37项通过；ownerSQL CAS消融1项真实失败后恢复。
一次审查的两个Important已用三个原生反例RED→GREEN修复：占用证据缓存、保存点回滚凭证失效，无二次重审。
五组设计、冻结V1、Node7、SQL检查通过；旧全局结构/血缘失败不在本批修复，见详细方案§5.5/§5.6。
1b拒绝结果映射Minor关闭，1a输入列表断言增强仍待补。无新DDL、HTTP、SPI或营业库操作。
当前仅完成共用基础；下一步02b-2编码前继续细化准入、来源冻结许可、完整集合reserve/protect/confirm/renew/release/expire/reconcile与并发回滚验收。


## §18 SHR-02b-2a锁内准入基础

详细计划/实际证据见[SHR-02b §2.6、§5.7、§6.9](./TDD-共用资源容量与组合占用-SHR02b.md)。
核对最新本地餐饮eb6c686be，无相关merchant/平台迁移变化；按零售Service/impl/内嵌Mapper规范复用1a归一及周班表展开器。
新增ResourceAdmissionService，在原Execution事务内规范化引用并锁人员→资源根，复核身份绑定、资源有效性/归店及开放占用指针。
完整取整桶必须有无歧义任职/排班及所需资格覆盖；普通餐饮/零售员工可由可信行业策略显式声明无需项目资格，不能漏传或混用。
准入原生22项、命令31项、排班23项、merchant268项、美业54项、旧路径37项通过；开放占用消融1项真实失败后恢复22项通过。
五设计、冻结V1、Node7、SQL及Java行宽/diff通过，分层/索引同步。一次审查无重要问题，后续显式资格策略2项RED→GREEN；无重审。
待补Minor：多资源反向别名锁序及发现后人员绑定漂移的动态回归；历史1a输入断言增强仍保留。
本批不写容量/hold、不开放营业入口；下一步02b-2b/2c细化来源冻结许可、计划/资格策略绑定、服务端时间/提前量及完整原子预留生命周期。
现有全局结构/血缘基线失败不在本批修复，详见§5.7；保留隔离工作树，仅本地检查点。


## §19 SHR-02b-2b-1来源冻结合同与消费防线

详细计划/验收见[SHR-02b §2.7、§5.8、§6.10](./TDD-共用资源容量与组合占用-SHR02b.md)。
核对最新餐饮69b84d2ce，无merchant/平台迁移变化；不把订单数量分配guard或权益SourceDecision当资源来源冻结。
新增唯一共享ResourceSourceAuthorityPort及完整资源计划V1编码；原命令回执读出规范scope和实际payloadHash。
来源freeze/query仅在事务外调用，绑定原命令/完整计划和当前恢复actor；只有本service签发的私有对象可在原Execution中消费，提交前复核身份与期限。
合同/编码14项（纳入共享282项）、消费侧原生16项、命令31、准入22、美业54及旧路径37项通过；计划hash消融1项真实失败后恢复16通过。
一次审查无重要问题，Minor为非授权形状测试需隔离其它拒绝条件；历史2a/1a测试增强保留。五设计、冻结V1、Node7、SQL及Java行宽/diff通过。
现有生产来源适配器为零：无默认放行，未配置则拒绝，Port未实现缺口在分层清单可见。原生来源是合同double，绝不代表真实源持久冻结/权限/三行业接入。
下一批02b-2b-2先细化并实际实现美业来源适配及互斥/恢复，再按同合同覆盖零售/餐饮，最后接完整准入与原子容量预留。
无DDL、HTTP、配置或依赖变化，无营业库/合并/推送；全局结构/血缘基线问题未改，详见§5.8。


## §20 SHR-02b-2b-2a独立美业复用冻结计划

实施与验收见[SHR02b §2.8、§5.9、§6.11](./TDD-共用资源容量与组合占用-SHR02b.md)。
共用ResourcePlanContract下沉shop-base/spi/resource，保留merchant原调用接口和完整V1 JSON/摘要。
纯IdempotencyScope同包名原样移至base，clean构建证实旧模块无重复类；独立美业无需依赖merchant即可编码完整资源/资格计划。
共享289、美业56、原生消费16及美业预约/服务任务25通过；超限转换反例2项RED→GREEN，严格字段消融1项失败后恢复通过。
一次审查无重要问题，UTF8非ASCII摘要/字节上限反例待补；五组设计及冻结V1不变。
真实来源provider仍未实现，本次不是持久冻结或营业预约完成；下一步依§2.8落实来源意图/fence表、真实权限、预约写者互斥及原命令恢复。
无DDL/HTTP/配置/权限变化，无合并推送/营业库操作，整体49任务继续保留原worktree和ledger。


## §21 已完成任务与后续清单重排（2026-10-21）

核验代码基线`dc3b61dad`，分支`codex/beauty-foundation`；本节只整理实际状态和已有目标的实施顺序。
49任务JSON是首次API/表归属的设计索引，生成器明确禁止由索引推断完成状态；不修改其NOT_STARTED或据此计算完成率。
下表“基础完成”不能上卷为SHR-01/02、ENG-03等整任务完成；16项P1私有表已落地，41项为全阶段设计总数。

### 21.1 已交付范围

| 工作包/原任务 | 已完成成果 | 完成边界/证据 |
|---|---|---|
| ENG-01/02 | api/core/web/channel/server五模块，独立/嵌入条件装配，默认关闭及入口隔离 | 工程基础完成；实际部署形态见§7/8，不代表营业就绪 |
| DATA-01/02/03 | P1共16张私有表、冻结V1、独立历史与结构漂移检查、主库事务/审计/数据域 | P1数据基础完成，后续新表必须追加迁移；不推断生产发布完成 |
| DATA-04、POL-01/DEF-01切片 | 政策及服务执行定义查询、不可变版本发布，幂等/审计/outbox；商品事实接入 | 旧切片见§8；BAPI-144等BM-01后端补齐并通过§22；全量项目可约仍属于后续预约模块 |
| DATA-05/06 | 预约/项目/安排、工单/任务/步骤/参与等7项实体和Mapper | 数据层完成，无预约创建/接待/执行业务闭环；§9，a0965d62d |
| DATA-07 | 提交意图、来源绑定、数量分配guard及持久恢复查询 | 数据层完成，数量guard不是资源fence；§10，aaa7ff243 |
| SHR-01a | 人员、可选账号关联、门店任职、岗位/资格五表及内部事实查询 | 无账号人员可参与服务；维护权限/停用影响/远程接入未齐；5c360212c |
| SHR-02a | STAFF规范资源身份、物理资源主档及内部事实查询 | 同一业务人员跨店共用资源身份；容量事务未交付；94ff55cf7 |
| SHR-01b | 周规则、变更历史与时区/跨店排班窗口查询 | 班表读取基础完成；例外审批/维护闭环未完成；378a5f2f7 |
| SHR02b-1a/1b/1c | 5分钟UTC需求归一；owner/command/bucket/hold/unit五表；持久命令、原号恢复、owner CAS | 已有算法/数据/仲裁协议；尚无实际容量预留；7925cbf5e、88e49acd0、4f5e2cac6 |
| SHR02b-2a | 锁内复核任职、排班、资源、开放占用阻断及明确资格策略 | 内部准入基础完成，返回Plan不能视为可约许可；dcc9934ab |
| SHR02b-2b-1 | 唯一来源合同、原命令/完整计划绑定、私有许可及身份/提交时效守卫 | 消费侧完成，来源测试仍使用合同替身；6d0f7a005 |
| SHR02b-2b-2a | 计划证据合同与原摘要工具下沉base，merchant保持兼容，美业独立构建复用 | 消除跨域依赖，生产来源provider仍为零；dc3b61dad |

公共写入已有门店校验、幂等/审计/outbox等切片；ENG-03与SHR-03必须随预约、工单、来源动作逐项验收，不能因政策路径通过而整体勾选。

### 21.2 按业务模块交付及部署关口（替代上一版技术切片主线）

本节保留按业务模块的范围组织。**执行顺序以§0.2为准：P1～P4代码、测试、审查和编译先完成，正式部署最后统一验证。**
来源冻结、容量桶、Mapper、SPI、Outbox等是模块内部任务；每个内部任务仍做必要测试，不逐个技术切片安排正式模块部署。
原49任务及API/表首次归属保持不变，BM编号仅组织交付。同一共用能力可以支持多个模块，在首次需要的模块实现，后续模块扩展并回归，不重复建账。
现有已通过的工程/数据/接口切片不作废，但没有哪个大模块因这些切片自动取得“整体完成且部署通过”状态。

| 顺序/业务模块 | 当前状态及包含原任务 | 本模块完整交付范围 | 本模块部署验证的业务主线 |
|---|---|---|---|
| **BM-01 服务项目与门店政策** | **后端模块完成，隔离部署通过（§22）**；POL-01、DEF-01，所需ENG-03/SHR-03 | 共用商品/SKU及门店报价引用；服务执行定义与历史版本；门店服务政策/公开营业信息；相应权限、幂等、审计、事件及部署适配 | 使用真实商品提供方完成查询→发布→门店读取→历史版本回查；验证越权/重复发布/失败回滚，重启后结果保留 |
| **BM-02 服务人员、排班与服务资源** | BM-02 P1后端模块隔离部署通过（§25）；人员/任职/资格、物理资源、周规则及远程事实已实现；P3例外审批/完整任务影响仍后续 | 业务人员与可选账号、多店任职、岗位/资格、班表维护与例外/变更影响；床位/房间/设备等资源维护；同人STAFF规范身份和受权事实查询 | 建立无账号人员→任职/资格→排班→关联服务资源→查询匹配事实；验证跨店同人、停用/资格过期/班表变更与范围隔离 |
| **BM-03 预约管理** | 后端模块完成；Task50–53、全模块审查及修复复审、双形态隔离部署通过（§31）；AVL-01、APT-01/02/03及必要SHR/ENG能力 | 可约/预览、自约/代约、指定或自动匹配、预约列表详情、改约/取消/爽约；完整资源冻结/预留/确认/续期/释放/过期/替换及原号恢复，必要的占用查询 | 创建→查询→改约→取消完整链路；并发争抢不超售、改约失败保旧、响应丢失原号恢复、租期与未知结果正确保护；不要求先做实际服务执行 |
| **BM-04 到店接待、工单与交易来源** | Task54～59/60A及模块I1修复已代码验收，模块代码关口通过；待统一正式部署；REC-01、WO-01、SRC-01及所需SHR-03 | 临客认领、预约签到/直客接待、工单项目范围/加项、真实订单报价/来源提交/绑定/逐来源账单及恢复；权限/审计/事件、到店与取消互斥 | 预约到店或直客→幂等建工单→真实来源提交/绑定→查询账单；验证重复签到、越权、报价失效和来源结果未知；未就绪来源不能标为可开工 |
| **BM-05 服务作业与履约** | 任务数据基础，业务执行未完成；TASK-01/02/03，SHR02b实际占用及超时部分 | 接单、开工、工作流步骤、实际参与/交接、暂停/延长/提前结束、清洁、完工数量、履约确认和工单关闭；实际占用/开放占用/超时阻断及恢复 | 接单→开工→步骤→交接/暂停→完工→清洁释放→履约/关闭；验证不双扣、不双完工、未清洁不放开、超时与未来预约并存及冲突开工阻断 |
| **BM-06 预约与作业工作台** | 未完成；CAL-01及受控读模型/操作集成 | 日/周视图；人员/工位/设备泳道；具体预约及任务、计划/实际/准备清洁/临时占位/阻断、未分派；筛选/详情/操作后刷新、跨店匿名busy | 从真实预约与作业生成日历数据，任务与多资源占用可追溯；验证权限、分页同水位、不完整/延迟标记及动作后刷新。当前后端范围验收真实API/日历数据合同；可视页面另按端上任务交付 |
| **BM-07 服务异常与门店运营** | 基础设施部分复用，模块闭环未完成；AFTER-01、RECOVER-01、MSG-01、HIST-01、OPS-01 | 未做撤项/争议、异常待办与受控恢复、生命周期历史、通知/服务单打印、经营运行视图、完整对账/监控告警和旧径切换收口 | 撤项/争议→异常待办→原号恢复/复核→历史回查；真实通知与打印链路、重复/丢失/未知回执、容量对账与告警；设备真机未验不得记为打印完成 |
| **BM-08 既有储值与次数权益消费（P2）** | 未实施；ASSET-01/02 | 持有人授权、本店政策、既有储值与合格次数权益消费、未知恢复及争议财务待办；复用资产所有者 | 本人授权→消费→完成/撤销协调→原号恢复与账实核对，验证门店隔离及重复请求；不以此计作售卡/发行完成 |

BM-01→BM-02→BM-03→BM-04→BM-05→BM-06→BM-07为当前交付主线；BM-08及P3/P4继续按原业务阶段。
每个模块用到的日志、指标、幂等、权限、审计、事件、必要通知和失败恢复在本模块一起交付，不能全部推到BM-07。
BM-07负责跨模块运营入口和完整运行收口。共用商品、订单、资金、资产、通知和打印仍归原所有者，美业只持有自身服务事实。
BM-06当前属于后端计划，部署验证必须提供真实任务/占用数据和可用API，不能宣称已交付尚未实施的前端日历页面。

### 21.3 模块内部实施安排及当前下一步

**BM-01已执行清单（结果见§22；不重复领取）：**

1. 核对POL-01/DEF-01全部功能/API，列清已实现、缺项与既有外部依赖，补公开营业信息BAPI-144；核对共用SKU/报价引用，不在美业另存售价账。
2. 补齐本模块可信身份、门店范围、发布/查询权限、幂等/审计/事件投递和双部署正式提供方；测试替身不作为部署依赖。
3. 完成本模块查询/发布/历史选版的必要单元、真实MySQL、HTTP与权限/失败场景回归。
4. 打包实际构件，进入§21.4部署验证；形成记录并通过后，BM-01才标记完成，开始BM-02开发。

**BM-03内部技术顺序：** 真实美业来源意图/fence及终态协调→零售/餐饮来源合同兼容与真实提供方验收→全组合原子预留→租期/保护/确认/释放/过期→整计划替换→占用读模型→预约Controller/Service全链路联验。
已有代码不重做；从来源冻结开始的具体七项开发前检查保留在SHR02b §2.8，逐步写入可编码字段/方法与测试。
未配置来源拒绝；未知结果不凭TTL解冻；相反写者共用根锁；改约失败保旧，三行业不拥有不同的容量权威。
BM-03所需投影/恢复提前纳入，不能因为原编号为02b-6而拖到全部实际作业实现后。

**当前BM-04收尾与BM-05前置：** Task54～59代码阶段验收见§32，60A-A1～A11和模块I1修复已验收，代码关口见§32.24，Task61设计与Task62-S0代码关口已通过（§33.2），Task63现金/线下设计已审查，下一片Task64人工准入编码（§0.3/2.42），原Pay S1暂缓；实际部署统一后置（§0.2）。60A代码审查和编译通过后进入下述BM-05内部顺序，开工前仍须冻结真实许可、占用、数量及履约合同，不重做已验收的接待/来源内核。

**BM-05内部技术顺序（§0.3最新）：** 人工线下业务准入/真实开工许可（暂不接Pay）→预约单位交接实际占用→开放占用/结束清洁释放→超时阻断/活动调整→任务工作流/参与/数量→履约与关闭联验。
实际占用与超时不再作为BM-03基础预约完成前的统一阻塞；缺这些能力时，实际开工入口继续门控。
技术小任务做完可提交本地检查点，但不单独宣布大模块完成；跨模块必要合同在当前首次消费者模块补齐并回归。

### 21.4 每个大模块的正式部署验证

每个BM模块设置一个正式部署验收关口。一次关口可包含多种已承诺部署形态；失败先在本模块修复并复验，不能把失败或仅测试通过记作模块完成。
默认使用隔离验证环境及隔离数据库运行实际构件。BM-01本轮已完成实际构件验收（§22）；共享环境/营业库发布不在本次执行范围。

| 步骤 | 必须验证 | 留存证据 |
|---|---|---|
| 1 业务完成检查 | 对照功能矩阵/API/类方法/表，模块主流程、边界、权限、异常恢复齐备；所用共用能力有真实提供方 | 模块范围、原任务/API映射、缺项/门控清单及测试结果 |
| 2 构件与迁移 | clean打包可运行构件；本模块新迁移验证新库及已支持旧版本升级；正式配置、权限、事件路由和依赖接线正确 | 提交号、构件版本/摘要、配置项名称（不留密钥）、迁移版本/执行结果 |
| 3 实际部署启动 | 在隔离环境启动实际JAR/容器；验证该模块承诺的embedded/standalone形态，独立形态包含宿主与内部服务真实调用；默认零售关闭美业的启动回归 | 启动命令/环境、实际进程与路由、依赖连通和健康检查结果 |
| 4 部署后业务验证 | 通过真实HTTP/正式认证操作模块主流程；真实数据库/行业和跨域提供方；验证权限隔离、重复请求、并发及故障恢复，不以Test Bean替代业务依赖 | 请求与结果、关键持久事实/事件、失败场景及重启恢复记录 |
| 5 影响回归与关口结论 | 共用能力改动覆盖零售/餐饮受影响路径；接口错误/i18n、日志/指标/告警及必要打印设备验收；检查数据兼容和门控 | 回归清单、问题及复验、通过/不通过、允许的模块范围及下一模块 |

构件启动和liveness成功不等于模块业务通过；readiness按既有能力门控验证，阶段不具备完整营业能力时不能为验收强改200。
未接入模块保持关闭或明确拒绝，记录全局readiness与模块可验范围；只有整体必要条件就绪才开放对应营业入口。

模块完成记录统一格式：`BM编号｜范围/提交｜构件/环境/形态｜迁移｜真实业务用例｜回归/故障恢复｜遗留问题｜部署结论`。
状态按`待开发→开发中→业务测试通过→待部署验证→部署验证通过/模块完成`流转；不通过退回当前模块修复。
按§0.2调整：前置模块代码、必要测试、审查和编译通过后进入下一模块编码；各模块正式部署关口统一在全部规划代码完成后执行。

### 21.5 质量待办及当前验证口径

- 最近代码检查点：共享及依赖289通过、美业普通reactor56通过、真实MySQL来源消费16和美业预约/任务持久化25通过；来自dc3b61dad的历史验收，本次文档整理不重复运行Java或将其称为新结果。
- 来源消费16项采用合同替身，不能证明真实来源fence；美业25项证明持久化，不能证明业务Service/工作台闭环。
- 历史架构/SchemaDrift/血缘缺口已由60A对应片修复并独立验收，实际范围及保留边界见§32；不能将历史失败当当前未修，也不能将定向通过扩写为全仓/正式部署通过。
- 已记录测试增强：UTF8非ASCII摘要/字节上限；非授权决策逐项排除其它无效条件；多资源反向别名锁序及P→P2重绑定；输入集合非修改断言。它们不替代真实来源/容量并发验收。
- 不按历次测试数累计“已完成用例”，不把41表/160接口目标数量当完成量，不给49任务计算失真的百分比。

后续P2按BM-08推进ASSET-01/02既有储值与合格次数权益；P3按护理回访、业绩提成、营销、实物耗材、财务报表、终端设备等业务模块分别组织；P4按疗程计划、疗程销售、复杂收银、资产逆向、外部与离线等业务模块分别展开。每个后续大模块保留§21.4部署用例，执行时间统一后置至全范围代码完成后。既有权益消费复用权威资产合同，付费售卡和权益发行独立验收，按§0.2在前置代码完成并细化合同后依次编码。


## §22 BM-01模块部署关口（2026-10-21）

当前状态：**BM-01后端模块完成，隔离部署关口通过。** 下一项BM-02人员、排班与服务资源；本检查点尚未开始其编码。

本模块交付政策/服务定义发布与有效历史、顾客须知、公开门店/服务及受权商家详情、
实际商品/SKU/门店报价引用、共用商品目录版本/分组排序/显隐/销售周窗口，以及必要权限、幂等、审计和不可变发布证据。
商品和目录分别由原商品域与共用product目录拥有；没有新的美业价格/库存/人员身份账本。
美业政策/定义归私有库，目录V536归平台主库，不增加美业私有V1表。

| 验收单元 | 已运行结果及证据 |
|---|---|
| 新功能RED→GREEN | 公开查询5、notes11、HTTP/传输各2、真实Bean名1、宿主Mapper接线2、归档10分别复现原缺陷后通过；日志见ledger |
| 目录原生及升级 | 最终9项MySQL8.4通过：范围/引用、CAS、并发首建、幂等/失败回滚、生效历史/防篡改、旧版本增量迁移/重启及报价故障/下架后已提交重放；`/tmp/store-catalog-review-green.log` |
| 全量美业原生 | 95项MySQL8.4通过，包含已有数据/迁移、政策/定义、服务查询、原预约/任务与提交来源持久化；不是完整预约业务验收；`/tmp/beauty-bm01-native-final.log` |
| 独立美业普通构建 | 最终clean reactor 64项通过；`/tmp/beauty-bm01-final-build.log` |
| 商品真实拥有方与窗口 | 13项通过：实际directory/product/quote、实时价格、隐藏分组、周窗口/DST、旧服务合同及Bean名；H2不替代原生部署；`/tmp/store-catalog-visibility-green.log` |
| 餐饮影响 | 幂等/事件/条件装配21项通过；`/tmp/beauty-bm01-food-impact.log` |
| 零售与架构 | 29项中27通过、1不适用形态跳过、1已核对既有失败；门店价格3/幂等4通过；既有OpsPointsInspectionController全限定PathVariable导致ArchitectureTest失败，未修改该文件或放宽守卫；`/tmp/beauty-bm01-retail-impact.log` |
| 设计与派生物 | 五份美业设计生成器`--check`、冻结V1、平台H2生成、变更Java行宽及diff空白检查通过；49项任务定义不批量改状态 |

独立只读审查：1项Important已通过RED9/1断言失败→GREEN9修复，既有提交重放不再受商品下架/报价故障阻断，新请求仍失败回滚。
1项Minor延期：重复Window输入目前去重，和§2.11“拒绝重复”存在合同差异；不扩大有效时段，不将其写成已经一致。

部署脚本为`scripts/verify-beauty-bm01-deployment.mjs`。使用真实JAR、正式PASSWORD登录、真实主库/商品/目录提供方；
分别验证embedded、standalone宿主→beauty-server→PLATFORM反向拥有方、默认零售，以及重复发布、隐藏/时段、报价、历史/未来生效、
越权、整笔失败回滚、恢复发布、进程重启、依赖中断拒绝与readiness门控。
平台为显式当前结构夹具＋真实V536；另有V535基线夹具→V536的真实Flyway增量升级/validate/noop重入。
这不覆盖完整历史平台迁移重放/全部排序规则/生产滚动发布，不代表共享营业库部署；已知SchemaDrift/lineage欠账未在此修复。
独立服务整体readiness保留503；公共展示不赋予预约/服务开工/收费许可。

最终部署证据：`/tmp/beauty-bm01-accepted-deployment/result.json`、`/tmp/beauty-bm01-accepted-deployment-run.log`；运行状态BM01_RUNTIME_CASES_PASSED。
正式门店定价API写入12900门店报价，基础SKU保持9900，美业读取12900；商品下架后原发布请求仍重放成功，新请求拒绝并回滚。
宿主readiness实际200；独立服务readiness实际503，未强行开放整体美业营业能力。

| 实际构件 | SHA-256 |
|---|---|
| embedded | `a22225a998423aac5dc0b28059ec842fb74b64fc955e47deb996a7927f94d6d6` |
| standaloneHost | `7000d5cc554a106fe8d475deca02a8d6927f072cfbb6577c40a5754a44a6bdb0` |
| defaultRetail | `1c34799a276d46ff618096dce188a5ac58cbc964fa5c91a0aa86cef548de5200` |
| beautyServer | `238f3a4d9e52186a0456622c7fd342f392eeacf842aa815050769c36c6245c4c` |

模块结论：**BM-01｜从dc5a3b543的后端增量｜MySQL9.7.2，embedded/standalone/默认零售实际构件｜平台真实V536＋私有V1及增量迁移测试｜正式认证、拥有方、版本/报价、权限/重试/回滚/重启/依赖中断通过｜保留重复窗口归一化Minor与全局既有守卫欠账｜本模块隔离部署通过。**
不代表生产发布、全49任务完成、完整预约/工单/收费、端页面或预约工作台已交付。


## §23 BM02-a人员维护内部检查点（2026-10-21）

**状态：BM02-a已实现并测试通过；不是BM-02模块完成或部署通过。** 基线6d5f83fa5。
BAPI012～016落在共用shop-merchant；美业继续引用人员/任职/岗位/资格与STAFF稳定业务键。
既有V532～535和美业V1全部不变；没有新建人员/账号池、业务权限或另一套容量权威。

| 交付 | 真实行为 |
|---|---|
| 建档/名单 | 无登录人员＋当前店初始任职/岗位/确切SKU资格＋全主体唯一STAFF一次提交；名单SQL分页、status/keyword/有效资格goodsNo筛选，查询storeNo只能等于当前授权店 |
| 任职/资格 | 父revision和人员根→STAFF根锁；同店有效区间不可重叠；替换保留旧任职并仅撤销原店/原期资格；跨店继续同一personnelNo/resourceNo；岗位不生成登录授权 |
| 目录与历史 | INACTIVE/REVOKED/过期配置保留存储，当前及未来有效子集单独读取；写入检查200项活跃上限，历史累计不会造成成功维护后目录永久409 |
| 可选账号 | 仅同主体规范ACTIVE非owner成员；一对一当前关联靠账号根锁＋唯一索引；null显式解绑；主审计保留previousAccountNo/accountNo，身份/成员授权仍归原服务 |
| 停用/离职 | owner入口；活动占用/容量证据/未恢复命令时409且不提交变更；锁关联成员再调用真实MerchantStaffService.setStatus并回查DISABLED；业务恢复不自动重启登录；LEFT终态不复职 |
| 审计与恢复 | 强制幂等＋父revision；审计直接原子写mch_staff_log；500字符原因单独关联记录，避免截断或向事件传播自由文本；显式shop.personnel.publication.archive-only才归档不可变证据 |

| 验证 | 已运行结果 |
|---|---|
| 人员原生MySQL8.4 | 最终13项通过；`/tmp/personnel-maintenance-final-accepted.log`。包含实际人员事实Port、跨店同人/STAFF、任职保留/资格撤销、300历史资格后可读及未来活跃上限拒绝、原号恢复、账号唯一/解绑/前后审计、容量/开放占用阻断、原因500字、原子回滚与真实账号/会话停用 |
| 影响补验 | `--impact-only`单项通过；`/tmp/personnel-impact-final.log`。另一店存在未恢复ACCEPTED命令也保守阻断本店人员变更；有容量计数无单位明细仍阻断 |
| 共享商家及依赖 | 292项通过；`/tmp/personnel-merchant-regression.log`，其中shop-merchant77项；原生测试的产品边界为明确控制事实，不宣称整条商品部署验收 |
| 最终输入/HTTP会话回归 | 25项通过；`/tmp/personnel-final-regression.log`，含DTO3、人员HTTP2、原成员会话4及门店员工16；匿名/经理拒绝、raw门店头拒绝、操作者来自会话、强制请求键 |
| 独立审查 | 一次fresh只读审查：0Critical/2Important/0Minor；两项已在一轮修复：并发改名使停用落空、历史资格挤占目录；额外自查补齐旧/新账号关联审计。无二次审查或放宽测试 |
| 红灯证据 | 初始原生6断言失败缺实现→6通过；HTTP2缺控制器→2通过；岗位空值消融3/1断言失败→恢复3通过；目录/关联审计反例失败后修复；真实账号改名竞态1断言失败（期望DISABLED实际ACTIVE）→最终通过；技能筛选1断言失败→最终通过 |

原生实际成员停用/竞态使用真实MerchantStaffService、真实Mapper和内存会话池；未使用的OTP构造依赖为显式mock，不作为验证码登录或实际部署证明。
另一类人员维护测试的商品事实/部分成员协作者有显式替身；实际商品提供方/standalone事实接入和完整构件部署在BM-02模块关口继续联验。
测试数据库使用平台结构夹具，不是完整历史Flyway重放；本批未执行新迁移、共享营业库操作、merge或push。

取舍：对主体中任何未恢复资源命令先拒绝破坏性维护，代价是无关人员也可能暂时被阻断；不假装已经完成预约/任务自动交接。
继续复用原成员停用和撤销会话行为；若外层事务后续失败，数据库会回滚，已撤销的会话可能需要重新登录，权限状态不会因此放宽。
公开顾客档案/本人任务权限、P3协作/请假审批、实际预约来源与容量生命周期均未据本批宣称完成。

**下一项BM02-b：** 深化并实现BAPI020/021规范资源名单与维护，保留STAFF实体级唯一及ROOM/SEAT/DEVICE店级归属；身份不可改写，容量/停用尊重同根、版本及在途影响。
之后BM02-c周规则写入/查询及standalone只读事实接入；全部必要功能齐备后执行§21.4的BM-02大模块部署验收，失败留在本模块修复。


## §24 BM02-b物理资源维护内部检查点（2026-10-21）

本批对应§2.13及BAPI020/021；BM-02仍未通过完整部署关口，不宣称预约/日历/工作流完成。

| 需求/设计 → 实现 | 实际验收 |
|---|---|
| 共用当前店名单、物理维护、唯一STAFF只读 | ResourceService/Impl、BizResourceController、ResourceQuery/SaveResourceReq/ResourceVO；ResourceMaintenanceIT.createsPhysicalResourceWithRealFactsAndDurableReplay及paginatesCurrentStoreResourcesAndOneStaffIdentityWithoutCopyingEmployment真实ResourceFactsPort读取 |
| 身份/类型不可变、门店与大小写规范、revision/幂等 | scopeCanonicalIdentityAndTypeCannotBeChanged、revisionsProtectCapacityAndSuspensionAndReplayReturnsCommittedResult；真实SQL和幂等记录，不接受自报主体或actor |
| 容量/开放占用/未恢复命令保护 | usedCapacityIsFrozenAndOutstandingCountersOrOpenOccupationsBlockUpdates、otherStoreUnrecoveredCommandsConservativelyBlockAndLeaveNoPartialWrites；已有桶冻结容量，释放后可暂停/恢复，不改历史桶 |
| 原子审计及不可变发布证据 | MerchantMappers.ResourceMapper只增桶计数/物理CAS；ResourceChanged复用PlatformPublicationPublisher和严格mch_staff_log；strictPublicationFailureRollsBackResourceAuditAndKey证明新建回滚 |
| 可信HTTP/字段边界 | ResourceControllerTest两项真实安全过滤器证明匿名/非授权/外店头拒绝、强制key和可信actor；ResourceInputTest两项验证类型/人员引用/容量/名称/修订/分页 |

真实输出：
- `/tmp/resource-maintenance-red.log`：7 tests / 7 assertion failures / 0 errors（缺实现）；`/tmp/resource-controller-red.log`：2 tests / 2 assertion failures / 0 errors（缺入口）。首次GREEN运行的表名、命令枚举及Jackson异常包装夹具问题已按真实基线修正，不计作业务RED。
- `/tmp/resource-maintenance-native.log`：7 tests / 0 failures / 0 errors，BUILD SUCCESS。独立MySQL8.4结构夹具，非完整历史Flyway重放，无商品/资源事实替身。
- `/tmp/resource-input-ablation-red.log`：去掉物理资源personnelNo为空守卫后2 tests / 1 assertion failure / 0 errors；finally恢复源码。
- `/tmp/resource-merchant-regression.log`：依赖reactor合计294 tests / 0 failures / 0 errors，BUILD SUCCESS；`/tmp/resource-http-regression.log`：输入5项+资源/人员HTTP及成员/门店会话24项，合计29 tests / 0 failures / 0 errors，BUILD SUCCESS。
- 五份美业设计生成校验、冻结私有V1、SQL方言无新增命中、人员/资源新增Java120列及diff检查通过；没有修改旧迁移、既有slot、全局故障基线或私有库。原49项设计任务定义仍保持原始状态，实际交付归本TDD。

一次独立只读审查：0 Critical / 0 Important / 1 Minor；未启动第二轮审查。延期Minor为生成方法文档共用DTO表尾仅指§2.12，资源语义也应指§2.13；主API/本节/§2.13合同已明确，不影响实际端点。审查未代执行器认证后续回归；最终29项结果由执行器核验。

边界与取舍：STAFF只读、已有任意历史桶冻结容量，代价是人员变更走人员维护、已使用物理容量待未来版本化协议；主体存在未恢复命令可暂时阻断无关资源维护。并发维护/admission交错、同时同revision提交，真实未释放/孤儿单位及confirmed计数，审计故障/已有资源更新发布失败/真实消费者投递未在本批额外注入；代码复用既有根锁/CAS/影响SQL及事务发布器，仅主张上述实际测试范围。完整历史迁移、真实构件部署、班表及容量/任务生命周期仍是后续范围，不能由本检查点推断通过。已知全仓Architecture/SchemaDrift/lineage债务未修改或声称转绿。

**下一项BM02-c：** 先深化周规则读写、跨店人员冲突/有效任职裁剪/时区边界及standalone事实反向接入；完成后按§21.4部署验证整个BM-02。BAPI023/024的P3例外审批不冒充P1周班表维护。


## §25 BM-02人员、资源与周班表后端模块部署关口（2026-10-21）

**模块结论：BM-02 P1后端基础模块完成，隔离实际构件部署关口通过。** 本模块是共用人员/资源/工作窗口维护与事实运输，不包含前端、预约工作台、容量承诺、自动任务交接、P3请假/例外审批或生产发布。

### 25.1 需求 → 设计 → 实现对账

| 需求/所有者 | 实际文件与验收 |
|---|---|
| 当前店周规则查询与维护 | merchant的ScheduleService/Impl、BizScheduleController、ScheduleQuery/SaveScheduleRuleReq/ScheduleVO/ScheduleRuleVO；GET /biz/schedules，P1补充POST /biz/schedule-rules |
| 统一根修订、历史/冲突 | MerchantMappers.ResourceMapper.advanceScheduleRevision与countStoreScheduleRules；ScheduleMaintenanceIT的规则持久化/替换/停用、跨月冲突、任职裁剪、DST、同revision并发及回滚共10项 |
| 任职变化不绕过排班 | PersonnelServiceImpl.assignPersonnel在既有人→资源锁下拒绝本店任一ACTIVE周规则；employmentExtensionCannotReactivateClippedCrossStoreOverlap、newEmploymentInAnUnassignedGapCannotActivateStoredWeeklyConflict先RED后GREEN，并验证停用旧规则后可变更 |
| 原子变更证据 | 既有mch_schedule_change_log严格记录reason/actor/前后资源修订；ScheduleChanged复用平台发布器。无真实消费者归档仅显式开关；不新增表/独立流水/旧slot改写 |
| 独立事实运输 | shop-base MerchantFactsInternalApi；平台portal/internal.InternalMerchantFactsController；beauty-channel的三Remote*Port及BeautyMerchantClientConfiguration，独立server导入；原Personnel/Resource/ScheduleWindowsPort是唯一权威 |
| 凭证、范围与错误 | MerchantFactsControllerTest含真实GlobalExceptionHandler，三种拥有方歧义明确HTTP409；MerchantFactsTransportTest真实TCP；ScheduleControllerTest真实安全过滤器；ScheduleInputTest及有效期守卫消融 |
| 实际构件部署 | scripts/verify-beauty-bm02-deployment.mjs复用BM01框架并扩展lib/beauty-bm02-runtime.mjs；外部测试驱动fixtures/BeautyMerchantFactsProbe.java以实际提取server类路径启动原应用，不新增生产探针入口或提供方替身 |

人员revision和资源revision不同：既有人员建档生成STAFF时资源初始revision=0，人员为1；周规则写入必须从资源目录/班表响应读取实际resourceRevision，不凭人员revision猜测。

### 25.2 测试、消融与一次审查

| 证据 | 实际结果 |
|---|---|
| 排班初始反例 / 最终 | `/tmp/schedule-maintenance-red.log` 8 assertion failures / 0 errors；`/tmp/schedule-maintenance-final.log` 10 tests / 0 failures / 0 errors |
| 两项重要问题反例 | `/tmp/schedule-employment-red.log` 2 assertion failures / 0 errors；`/tmp/merchant-conflict-red.log` 2 tests / 1 assertion failure / 0 errors，实际409期望/200错误对象 |
| 有效期守卫消融 | `/tmp/schedule-input-ablation-red.log` 2 tests / 1 assertion failure / 0 errors；源码finally恢复后输入测试通过 |
| 改动人员路径的完整原生回归 | `/tmp/bm02-personnel-final.log` 13 tests / 0 failures / 0 errors |
| 最终共享merchant依赖reactor | `/tmp/bm02-merchant-final.log` 296 tests / 0 failures / 0 errors |
| 最终输入/HTTP/成员门店会话 | `/tmp/bm02-final-http.log` 35 tests / 0 failures / 0 errors |
| clean独立美业reactor | `/tmp/bm02-beauty-final-build.log` 65 tests / 0 failures / 0 errors，含三种反向事实传输及原目录/版本回归 |
| 餐饮影响 | `/tmp/bm02-food-impact.log` 21 tests / 0 failures / 0 errors，幂等/outbox/条件装配 |
| 全仓架构守卫 | `/tmp/bm02-architecture.log` 16 tests / 1 failure / 0 errors；唯一noFullyQualifiedReferences指向未修改的OpsPointsInspectionController，和基线相同。其余15项通过，不声称全仓绿色 |

一次独立审查0 Critical / 2 Important / 1 Minor；两重要问题均在一次修复中RED→GREEN并通过最终回归，没有二次审查。Minor延期：外部构件探针验证数量、资格存在和容量，未逐项断言返回身份/窗口端点；实际SQL原生身份断言和正式HTTP链已有覆盖，未将该探针当全字段证明。
生产规模性能、真实admission持续争用及BM03尚未实现的消费行为未作运行认证；原生与部署库均为显式结构夹具，不是平台全历史Flyway重放。SchemaDrift/lineage债务未重跑或修改。

### 25.3 实际隔离部署与构件

最终输出：`/tmp/beauty-bm02-accepted-deployment/result.json`，status=BM02_RUNTIME_CASES_PASSED。
数据库为自建MySQL9.7.2 tmpfs、回环随机端口，平台结构夹具＋真实V536；美业私有V1校验，历史0/1重启不变。无共享/生产库操作或外部发布。

- 实际embedded宿主：正式密码登录，实际商品拥有方SKU资格、无登录建档与幂等、物理资源维护、相同人员跨店任职且仅一个STAFF、跨店周规则冲突/相邻放行、容量计数/历史桶阻断、成员关联与暂停/恢复（恢复业务人员不恢复账号）、stale回滚与重启查询均通过。
- 普通standalone宿主与beauty-server实际JAR启动，原目录/政策链继续通过；共用维护仍在PLATFORM。测试驱动对提取且记录摘要的同一server构件启动原BeautyApplication，验证三个实际远程Bean→真实PLATFORM事实、故意注入跨店规则冲突后返回BizException(CONFLICT)/HTTP409、连接故障三种事实均拒绝；冲突注入SQL已恢复，未冒充普通写入口。
- 默认零售真实构件：无美业路由，共用商品/人员资源/周班表仍可读取；餐饮影响由上述21项回归核验，未声称餐饮生产部署。
- 宿主readiness实测200；独立服务整体readiness仍503，这是未开放预约/完整营业能力的既有关口；不因本模块通过就改成200。进程与自建容器均自动清理。

| 构件 | SHA-256 |
|---|---|
| embedded | `77dbdabf52a9e64abf46290a1da546a3c5b1db345c28cf89c648417ef2cdc30a` |
| standaloneHost | `3715eb6d16e747a2ebb19df072dd45990645057b1f773fb95de8c6108d1607a6` |
| defaultRetail | `4f5e80a03857a577d51535979619a1f289c394084db98597df3e2d63920e13d1` |
| beautyServer | `ec56e931abb66f1b65223244230b5d322cf7145099e024166720b76b59894964` |

### 25.4 当前取舍与下一业务模块

新规则需明确结束日且最多366日，长期班表续排；已有ACTIVE周规则时，须先显式停用再新增/替换本店任职/资格（包括历史日期规则，避免跨日筛漏），不自动重排。已使用物理资源容量仍冻结，等待未来版本化容量方案。
独立事实测试驱动只证明当前真实Bean/运输/拥有方链，不能替代尚未实现的预约消费端点；完整生产迁移、全仓守卫和最终发布仍有独立关口。

下一项BM-03：先按已批准矩阵/API深化预约创建/改期/取消、可信顾客与临客范围、单次先付/无交易来源、来源冻结、组合容量占用/替换和未知恢复，再编码；预约详情/查询与恢复随模块交付，完成后再做一次实际构件部署验收。

## §26 BM03-a1预约来源存储内部检查点（2026-10-21）

基线6d675af2f。仅完成预约来源意图与永久栅栏的数据前置，不是整个预约模块交付；a2真实来源Service、b共用容量事务、c预约业务API及工作台、d模块部署关口继续按§2.15推进，不提前转BM04。

### 26.1 需求、设计与代码对账

- 共用商品/订单/人员/资源所有权保持原边界；预约来源过程记录由美业保存。新增BtyAppointmentResourceIntent/BtyAppointmentResourceFence及两内嵌Mapper、数据域注册，无新Controller或可授权provider。
- 原41项设计/49任务与冻结V1不改，独立增量JSON及私有V2追加2表，实际18私表；唯一键保持tenant/entity前缀，命令号不可跨预约/店重绑，永久预约根CAS一个活动意图。
- LONGTEXT保留原始完整计划字节；JSON_VALID及UTF8字节≤65536保护。authorize/reconcile不改命令、计划或摘要；SQL更新作用域、阶段和修订必须匹配。
- 原AppointmentMapper改期、状态（含确认/取消）、签到均检查活动fence；异常软删的fence也不绕过，许可到期不解锁。先锁预约→永久fence→intent，核实可信终态后显式NULL释放；本批只保存对账记录，实际终态真实性仍由a2服务负责。
- 严格manifest分别核对V1/V2资源唯一归属、固定摘要及精确列/默认值/索引/强制CHECK。readiness版本2，未升级V1库validate拒绝，受控migrate升级后保留原预约。
- 拒绝存储对齐共用Decision：DENIED有decisionNo/reasonCode、没有许可窗口；对账后保留原拒绝证据，不能转成成功资源终态。此项只补兼容存储，拒绝业务编排尚未实现。

### 26.2 验证及一次审查

初始原生7项全部按缺失实体/版本升级断言失败；CHECK函数单测先失败再实现。实际MySQL会把OCTET_LENGTH规范化为LENGTH，检查实际CHECK_CLAUSE后仅加入同义白名单，不允许任意SQL函数。
首轮全原生104项、独立服务1项、恢复守卫后专项9项及真实宿主HTTP到数据库1项通过；临时移除改期NOT EXISTS守卫得到1项断言失败，finally恢复。
拒绝合同兼容修正新增真实Decision形状测试，先因reason_code缺失失败；最终全量原生105项、独立服务联验1项及干净reactor66项均通过，最终专项10项及真实宿主HTTP到数据库1项均通过。

一次新上下文只读审查无Critical/Important，1项Minor：有效补充Unicode原样往返及精确65536字节接受边界未单列，当前有ASCII+空白往返和超限UTF8拒绝证据；保留后续补充。
执行者对审查未裁定的拒绝存储形状作重要问题处理：修正尚未发布的V2候选并补reasonCode/拒绝无许可约束/拒绝不得对账为成功的SQL守卫；一轮RED→GREEN后不重复审查。未修改已发布或外部持久库中的迁移。

### 26.3 取舍与剩余边界

本批记录和Mapper参数均不构成来源权限或资源终态权威；未注册假的许可提供者。代价是预约仍不可营业，后续必须补真实来源权限、持久计划生成/比较和真实终态协调。
完整拒绝/准备意图放弃/未知恢复编排、所有实际写者遵守根锁、禁止通用Mapper更新破坏不可变绑定由a2承担；本批不声称这些流程已验收。BM-03尚未达到大模块部署关口，本次数据库/服务集成测试不替代实际构件部署。
已有全仓Architecture/SchemaDrift/生产历史债务不由本切片消除；不作全局发布通过声明。保留ongoing worktree与执行账本，只有本地检查点，不合并/推送或改共享生产库。

最终V1 SHA-256：`ac2a539664c42fdb7e4893f1359879fc5affbe4b24f95aae1809cd25f11061a4`；V2：`5b6768928018b68635c9c081b200a2cb6202d40645c5db62c91c558cc49d4114`。
最终证据：`/tmp/beauty-bm03-final-native.log`、`/tmp/beauty-bm03-final-clean-build.log`、`/tmp/beauty-bm03-final-host.log`；守卫消融：`/tmp/beauty-bm03-ablation-red.log`；拒绝形状反例：`/tmp/beauty-bm03-denial-red.log`。五项设计生成、V1/V2冻结生成检查、SQL方言无新增依赖及新Java120列检查已通过。

## §27 BM03-a2-1共用核验读取检查点（2026-10-21）

基线e2d89042f，按§2.16先补来源授权所需的实际所有者读取。当前没有美业来源可授权provider、业务计划生成或预约HTTP；整个BM-03及其部署关口尚未完成。

### 27.1 需求、设计与实现

- shop-base唯一维护MerchantOperatorAccessPort、ResourceCommandFactsPort与ResourceRecoveryInternalApi；shop-merchant两个PortImpl、OperatorAccessMapper及原命令投影查询实际经营成员/当店角色权限和原command/owner父链。共享合同供零售、餐饮、美业复用，未新增三套权限或容量模型。
- 主体ACTIVE/未归档、店ACTIVE、成员ACTIVE/未删除；MERCHANT/MCH只认成员号，MERCHANT/USR只认其关联user_no。无权限/停用/错店/大小写别名/歧义身份拒绝；当前角色定义优先本主体覆盖预置，精确权限或裸*，不回落静态角色。
- 原命令读取须当前biz:order:view，完整Binding逐字段匹配，包括命令号、来源、修订、动作、命令摘要和完整计划摘要。ACCEPTED不携终态；SUCCEEDED/REJECTED保存原载荷、拒绝原因、命令版本及原UTF8字节敏感摘要；新命令/移动当前头不能覆盖历史结果。Lookup(command=null)只表示受权查询未发现，不表示已回滚或可解除fence。
- shop-app新增InternalResourceRecoveryController两条机器POST；beauty-channel两个远程适配及条件接线，实际走PLATFORM。机器令牌先于所有者调用，403/409/服务失败不成为成功空值或授权；远程再次比对返回完整Binding。网络读禁止调用方事务内执行。
- 没有新增表、迁移、权限码、端页面；已提交V1/V2及原49任务/160目标API不变。API清单实施补充单列两个内部路径，类/方法、领域对象、矩阵、ADR043与README同步。

### 27.2 实际验证与一次审查

原生5项RED缺失合同、HTTP与TCP各1项RED缺失合同；原生初次夹具使用任意JSON而非实际V1计划，真实ResourcePlanContract拒绝后修正夹具，不放宽生产校验。
实际原生6项通过，包含当前权限、撤权/停用、MCH/USR、跨店/别名、拒绝/成功原结果与后续命令、完整绑定及事务边界。共用形状/UTF8摘要单测3项通过；实际GlobalExceptionHandler的HTTP3项通过。
真实TCP已验证机器凭据、成功/缺失/Unicode原结果回传及403/409/503传播；额外错误Binding响应反例先失败，再加远程守卫通过；独立reactor干净构建67项通过。
暂时去除ACTIVE成员条件得到2项原生断言失败，finally恢复。最终新旧命令原生38项（含生产MybatisPlusConfig扫描接线）、实际GlobalAdvice HTTP5项及形状/摘要4项，共47项通过；merchant及依赖300项通过，美业独立reactor干净构建70项通过。真实Spring条件接线验证远程只在本地owner缺失时注册；真实TCP证明协议，但不称部署真实业务提供方。

一次独立只读审查0Critical/1Important/0Minor：owner数据库异常经全局Advice返回200信封，Lookup缺失command可能被忽略为null。实际GlobalAdvice两路径先失败（预期503实际200），TCP的{}和200错误信封两项先失败；一轮修正为未知运行异常返回空503、Lookup.command必需且ALWAYS显式序列化null，保证全局忽略null配置下仍保留成功缺失。最终HTTP/TCP及全量回归通过，不进行重复审查。

同一修正轮执行者另发现生产扫描接线缺口：只读OperatorAccessMapper未继承BaseMapper，宽扫描测试能注册而宿主实际扫描需要@Mapper。新增真实生产MybatisPlusConfig启动测试先因NoSuchBeanDefinitionException失败，再仅添加@Mapper，不放宽扫描；最终实际接线及原生回归通过。

ArchitectureTest实际16项、1项失败，仍是未改动OpsPointsInspectionController的全限定@PathVariable，已与e2d89042f逐字节比较一致，其余15项通过；未标记全局发布绿灯。SchemaDrift/全生产历史未在本批重验。五项设计生成、SQL无新增依赖、V1/V2冻结、Java120列/无新增全限定跨域引用及git diff检查通过。

### 27.3 后续范围

两项读取是point-in-time事实，不签发可持续消费的来源许可；调用服务仍须保证输入来自已认证的真实actor。原结果是内部私有核实资料，摘要不是签名，不接受客户端自制摘要或terminalCode解冻。
下一步a2-2须根据持久预约/版本/需求建立真实计划及完整Binding、持久prepare/freeze/query决定，使用实际权威终态编排对账，并与所有相反写者共用根锁协议。完整a2、三行业容量事务、预约API/工作台和大模块实际部署均未完成。

本批最终证据：`/tmp/resource-recovery-final-native-protocol.log`（47）、`/tmp/resource-recovery-final-merchant.log`（300）、`/tmp/resource-recovery-final-beauty.log`（70）；一轮修正反例：`/tmp/resource-recovery-owner-error-red.log`、`/tmp/resource-recovery-envelope-red.log`、`/tmp/resource-recovery-host-wiring-red.log`；消融：`/tmp/resource-recovery-ablation-red.log`。工作保留在独立worktree，保存本地检查点，不合并/推送或部署共享生产库；大模块部署仍按§21.4在BM03全部必要单元完成后执行。


## §28 BM03共用原子容量内部检查点（2026-10-21）

按§2.17 Task50完成，代码8cb79f5f7、审查修复bfda5ba9c。ResourceBookingService.execute按原命令驱动RESERVE、PROTECT、CONFIRM、RENEW、RELEASE、EXPIRE、RECONCILE与整计划REPLACE。
短READ_COMMITTED主库事务内锁owner/原命令→规范人员/完整新旧资源集合→hold→资源时间桶；真实来源调用在事务外。
完整容量、单位历史、hold、owner头、原命令终态和安全outbox事件同事务。REPLACE按净增量，失败保旧；固定临时租期15分钟，受保护到期不隐式减量。
未注册来源时拒绝执行；本检查点原生验证使用显式测试来源合同，真实美业provider、预约业务及HTTP仍由Task51/52实施，整个BM03未完成，也未进行模块部署。

| 设计→实现 | 本次落点 |
|---|---|
| 共享执行 | merchant ResourceBookingService/Impl，BookingAllocationRow/CounterRow，MerchantMappers三个容量Mapper扩展 |
| 同事务安全事件 | base ResourceBookingChanged；沿既有PlatformPublicationPublisher和shop.resource.publication.archive-only配置，不含完整载荷/顾客资料 |
| 原生反例 | ResourceBookingExecutionIT 29项及旧foundation17项，verify-resource-booking-persistence.mjs运行两组 |
| 审查修复 | entity内悬空未释放单位拒绝新分配；PROTECT/RENEW也核验原桶/计数/父链，零增量不写桶；既有迁移不变 |

| 验收→证据 | 真实结果 |
|---|---|
| 初始RED | 25项/8失败/0错误，缺执行器；/tmp/task50-red.log |
| 整组/生命周期/失败保旧/提交未知 | 首轮28通过，补完整性后33通过；真实JDBC在commit之后抛异常，原号查询只得一个成功hold/event |
| 消融容量计数一致性守卫 | 1项/1预期失败；/tmp/task50-ablation-red.log，finally恢复 |
| 独立审查第一轮修复RED→GREEN | 新增13反例后46项/9预期失败→46项全部通过；/tmp/task50-r1-red.log、/tmp/task50-r1-green.log |
| 最终共享回归 | 215项/214通过/1既有Architecture FQN失败；/tmp/task50-r1-shared.log。全部预约原生46项与i18n6项通过 |
| 审查 | /tmp/review-bm03-capacity.md两Important已修复；/tmp/review-bm03-capacity-r1.md规格/质量通过。夹具Sequence警告已消除，既有重复Mapper/Mockito环境警告已在报告说明 |

Architecture剩余失败为未修改的OpsPointsInspectionController.java:84全限定PathVariable，基线已存在；不冒充全局绿灯。
SQL方言无新增、diff空白检查通过。无新增DDL、旧slot切写、实际作业占用、业务库发布或推送；主任务清单继续Task51→52→53。


## §29 BM03真实预约Service检查点（2026-10-21）

Task51实现1293e87e6、修复7724577f6，独立审查与增量复审通过。预约准备、真实来源授权、RESERVE→独立CONFIRM、创建/改约/取消/爽约、原号协调、当前管理者显式恢复已落地。
身份保留MERCHANT MCH/USR及CONSUMER USR，逐次校验当前预约权限与本人归属；商品/SKU真实修订及观察报价提交重验。
V3追加四表、V4追加原决定fence元数据，严格manifest最新4；V1～V4冻结。根修订与不可变安排修订分离。

审查六项修复：终态同事务安全预约outbox及重放去重；改约使用经来源和owner双重核验的原hold排除投影，其他占用仍计量；
容量factAt独立于查询回看窗口；恢复逐条隔离失败并回退60秒；历史无current指针预约只读且安排修订未知，不编造安排/hold；
V3/V4生成器共用确定性renderer，迁移字节不变。默认私有验证已含业务场景。

| 实际验证 | 证据 |
|---|---|
| 私有MySQL完整122项通过，含预约16项/真实V2→V4保留旧预约读取 | /tmp/task51-r1-full-beauty-final2.log |
| 实际资源owner完整50项通过，含原hold精确排除、防伪及新生效任职 | /tmp/task51-r1-full-owner.log |
| 常规独立五模块reactor72项通过；SQL renderer8项通过 | /tmp/task51-r1-reactor.log；/tmp/task51-r1-renderer-final.log |
| 缺陷RED与容量消融（精确1预期失败，恢复后绿灯） | /tmp/task51-r1-red.log；/tmp/task51-r1-owner-red.log；/tmp/task51-r1-owner-ablation.log |
| 独立审查六Important全部关闭，噪声Minor记录 | /tmp/review-bm03-business.md；/tmp/review-bm03-business-r1.md |

私有业务测试的外部事实/owner是明确合同夹具，实际owner原生另验；不作为完整部署链证明。
归档事件不是已投递通知，正常模式仍需平台消费者路由；后台不伪造用户授权，需新授权的步骤等待认证原键重放或有权人员恢复。
独立HTTP身份传递、真实来源回调、任务调度及实际JAR闭环归Task52/53；整个BM03尚未准出，BM04/05/06及订单/资金/疗程权益接入不提前宣称交付。


## §30 BM03端点与双向协议检查点（2026-10-22）

Task52实现c5b63aaea、修复0c5673e17，规格/质量审查及范围复审通过。
原18项预约/可约/操作查询入口实际接入，新增显式恢复POST；原160项目标基线保留。
30条机器路由分别为booking19、source2、owner8、customer1，不混记公网API数量。
B端MCH/USR、C端CONSUMER/USR本人，可信tenant/realm/kind/actor及原Binding、factAt/Replacement完整传递；
机器令牌先于owner调用，finally恢复线程身份，无HTTP锁内等待。显式Lookup区分缺失与错误信封，未知/断连不伪成功。
公开可约仍匿名，私有C预约不依赖permitAll链作授权；搜索未完整独立80104三语语义及内部422。

审查修复：remote容量必须满足与owner一致的半开区间相交，错时间窗事实拒绝，不落为0占用；
三个B入口共用BookingStoreScope薄校验函数，继续复用原BizContext/StoreRequestScopeValidator/SecurityUtils，权限仍由真实Service逐次校验。
实际TCP证实缺失primitive字段已被原解码器拒绝，无需误加默认值或重复机制。

| 验证 | 证据 |
|---|---|
| 最新五模块reactor93项通过，含真实TCP、三B入口特征及架构检查 | /tmp/task52-r1-reactor-green.log |
| 实际tmpfs MySQL私有122项通过，平台owner TCP/全局Advice/i18n7项通过 | /tmp/task52-native.log；/tmp/task52-owner-final.log |
| 错操作身份、商品修订及缺previewNo反例→守卫通过 | /tmp/task52-response-red.log；/tmp/task52-facts-red.log；/tmp/task52-preview-red.log |
| 错时间窗TCP RED→正确边界/部分重叠通过 | /tmp/task52-r1-capacity-red.log；/tmp/task52-r1-reactor-green.log |
| 搜索语义转换消融精确1预期失败，原字节恢复后绿灯 | /tmp/task52-incomplete-ablation-valid.log；/tmp/task52-incomplete-restored-green.log |
| 两Important已关闭，既有测试警告Minor记录 | /tmp/review-bm03-protocol.md；/tmp/review-bm03-protocol-r1.md |

配置图已验证embedded唯一local来源、standalone HOST无core唯一remote来源、SERVER core＋remote owner唯一local来源；
禁用/ops/noAPI无新增公网预约入口或恢复Job。SERVER默认api但构件不含beauty-web，仍只有内部入口。
这些是装配及协议证据，fixture不代表真实营业部署。独立private DB、实际双向owner回调/当前权限、任务声明/自动调度及整体模块准出仍由Task53完成。

## §31 BM-03预约后端模块准出（2026-10-22）

**结论：后端模块完成，独立审查与embedded/standalone隔离部署验收通过。** 实现及审查基线55d615a82，Task50–53与修复最终业务HEAD923a3149e。完整审查对Task53自身实现Approved，发现两项整体集成问题；统一修复后独立窄范围复审确认I1/I2全部关闭，无新增Critical/Important/Minor，spec/quality及整个BM03后端阶段Approved。[完整审查](../../.superpowers/sdd/beauty-backend/review-bm03-module.md)、[修复复审](../../.superpowers/sdd/beauty-backend/review-bm03-module-r1.md)、[修复报告](../../.superpowers/sdd/beauty-backend/task-53-module-fix-report.md)保留原始范围和证据限制。

### 31.1 业务范围与审查修复

已交付B/C可约查询、预览、自约/代约、指定或自动匹配、确认创建、列表详情、重叠改约、失败保旧、取消、爽约、当前权限及原操作恢复；沿真实共用资源所有者完成整组合预留/确认/保护/替换/释放。商品、人员、会员及资源容量账仍由原所有者维护；美业只保存自身来源/准备/操作/安排及冻结证据。共用容量内核可复用，不代表零售/餐饮业务预约流程已经实现。

I1恢复扫描改为先排除活租约，再按重试到期时间/id取50条，超过50条等待授权不再饿死后续可恢复终态；仍不伪造后台用户授权。I2自动匹配改为有限预算内回溯，支持原15/60分钟角色反例和部分指定；STAFF按规范人员身份占用，物理资源按原容量。候选尝试/槽位10000、占用桶物化100000，超限明确搜索不完整，完整穷举无解才返回确定冲突。

### 31.2 本轮实际验证

| 关口 | 实际结果 |
|---|---|
| 最终美业完整reactor | 106项执行、0失败/错误/跳过，含10项新增匹配用例；日志/tmp/task53-fix-beauty-final.log |
| 最终原生预约业务 | 18项执行、0失败/错误/跳过；超过50条跨默认60秒扫描、活租约、来源/栅栏/原终态；日志/tmp/task53-fix-business-final.log |
| 原生所有者/来源与迁移 | Task53已验收owner50、source16、严格迁移17；本轮未改实现/协议/DDL，复用已审证据，不声称重新执行 |
| 共享影响回归 | Task53平台27执行/3形态跳过、餐饮88执行/16条件或原生夹具跳过；修复仅改美业core，不重跑无关全平台套件 |
| 正式迁移与生成 | 冻结V1–V4未变，四个迁移生成检查与数据库10输出校验通过；私有业务表23，原41目标基线保留 |
| 独立审查 | 全模块审查+唯一修复范围复审通过；两个部署core嵌套字节一致，9个当前编译class与部署指令一致 |
| 默认零售 | 实际JAR无美业依赖/公开入口，美业路由404，共用资源/目录API可用 |

RED/GREEN记录与复现命令集中在修复报告。全局Architecture的OpsPointsInspectionController既有FQN债务未修改，其他全历史SchemaDrift/lineage未重验；不宣称全部后端全局检查通过。

### 31.3 最终实际构件与部署

最终证据/tmp/beauty-bm03-fix-final2/result.json，状态BM03_RUNTIME_CASES_PASSED；运行构件位于/tmp/beauty-bm03-fix-artifacts。独立复算五项SHA均匹配：

| 实际构件 | SHA256 |
|---|---|
| embedded.jar | 928b4e068d3c7acd78c185c38fac7e60a25ce451ff4f8f390b5b7dfb9822d17f |
| beauty-server.jar | 9b78f988fb8126ca417665eec15e7f25e84ea047291ccab9284f532ea4567f03 |
| standalone.jar（未改复用） | 411a2633a05fcc58d355c5e983bb219d7a6fca244054626132b09d42a876e3d2 |
| retail.jar（未改复用） | 8e3a4d0c3da1b812a425bc9ef05d814ddab4ba5f996ff68d8784be68eb4d83fc |
| job-worker.jar（未改复用） | db4a44076b3faa11d8cf14f0a6d6c17a50701d97a3c24869d39045015a5a2846 |

embedded停止后才启动真正无core的standalone HOST；独立SERVER单一私有schema为23业务表+行业history+6基础表，共30表，正式history0/1/2/3/4，不复制商品、会员、订单、人员或容量账。实际owner调用316、反向source调用19，B/C完整生命周期、响应提交后丢失、进程重启、撤权及另一当前管理者原号恢复通过。真实job-worker发现声明并cron执行，SUCCESS运行计数2且跨重启持久增长，result直接含两条CRON/SUCCESS/HTTP200运行记录。爽约等待真实预约开始后调用HTTP，未用SQL改时。停止SERVER后owner执行503、原命令仍ACCEPTED，不能绕过来源。

首个修复部署尝试因隔离MySQL容器OOM退出137而失败，Docker事件和失败日志保留；结束并行原生库测试后串行复验成功，没有降低业务断言或停止无关容器。所有本任务临时进程、代理及数据库由finally清理。平台使用原生schema夹具加正式增量，不是完整历史平台迁移重放；没有共享库/生产发布。

### 31.4 保留边界及下一模块

整体readiness仍503，按未交付能力门控；本关口不等同全部49任务或整个系统可营业。基础可约/预约安排时间线不是BM06完整任务日历/UI；事件ARCHIVED不是实际通知投递；BM04接待/工单/真实交易来源、BM05作业/履约和后续权益消费仍分别推进。

本轮裁决：有限预算匹配必须诚实报告搜索不完整，不能以贪心设计解释假无解；较大合法请求可能需要缩小查询或增加明确指定。ops/no-api禁止公开预约入口与Job，受机器令牌保护的内部协议按已批准合同保留。这些边界改变将需要明确的后续任务。下一模块按§21.3先深化BM04，不重复领取已完成BM03。

## §32 BM04进度：公开协议代码已验收，质量收尾与整体部署待完成

Task54实现6932ef5c4及文档源修复7b3142e3f已通过独立任务审查。[实现与验证报告](../../.superpowers/sdd/beauty-backend/task-54-report.md)、[完整审查](../../.superpowers/sdd/beauty-backend/review-task-54.md)、[修复复审](../../.superpowers/sdd/beauty-backend/review-task-54-r1.md)保留实际命令/日志、原生库/真实TCP/fixture边界。共用member owner提供临客登记/验证/不可变认领及本人mc1核验；行业不复制顾客主档，不授付款权。新增平台V537/V538已执行并冻结，旧迁移未改。

Task54 native13+独立JVM重放1及beauty109项通过；平台相关40通过、2profile跳过、3守卫失败分别是历史OpsPoints全限定注解、历史支付表解析差异及本分支BM03引入的JobLockConfiguration未登记包。第三项必须在Task60部署前迁入已登记job.config并回归，前两项继续如实披露。文档修复后五生成器及协议/160基线一致性检查通过，未重跑无关运行时套件。

Task55先按§2详细规则冻结方法/DTO、V5四表及事务恢复方案，再编码；Task56/57分别实现真实订单报价及资金原号，Task58来源绑定/账单，Task59协议，Task60统一部署及模块审查。BM04尚未完成，尚未有本模块真实部署准出，不据任务54通过声称后续业务已完成。

### 32.1 Task55A身份引用与到店码验收

实现de8409ea4、统一修复59d49ed1b。[报告](../../.superpowers/sdd/beauty-backend/task-55a-report.md)、[完整审查](../../.superpowers/sdd/beauty-backend/review-task-55a.md)、[修复复审](../../.superpowers/sdd/beauty-backend/review-task-55a-r1.md)记录4项Important的关闭及证据。最终115常规/52原生/8真实host/10owner与旧会员码测试通过，12生成/SQL检查通过。九个原缺陷反例及两个权限补强反例先实际RED后GREEN；原始claim/host实现前RED缺失及V5首次执行时独立hash时间链不足仍保留，不能倒补历史。

V1–V5未改，V6只加身份NULL成对guard，27私表，ready=6；[V6执行前独立SQL/hash/UTC记录](../../.superpowers/sdd/beauty-backend/v6-preexecution-root-record.json)与首次执行/最终内容一致。首轮V6测试5项通过后MySQL OOM、47setup errors单独披露；低内存隔离diskdatadir后52项全过，没有修改生产或其他容器。三项全局guard仍按Task60边界追踪，不称全部backend绿。55A只交付service、身份回执、归属查询、到店码与数据基础；接待公开wire/整个BM04部署尚未完成。


### 32.2 Task55B签到、异常协调与接待重排验收（2026-10-23）

实现115d1cb74，修复714542115。[报告](../../.superpowers/sdd/beauty-backend/task-55b-report.md)、[完整审查](../../.superpowers/sdd/beauty-backend/review-task-55b.md)、[修复复审](../../.superpowers/sdd/beauty-backend/review-task-55b-r1.md)记录一项Important与告警说明的关闭。交付B/C真实身份与同店到店证明的签到、本域异常登记及冻结原规则的内部整计划REPLACE；不释放hold、不建工单/任务、不授付款或开工。

初版115常规、48原生、真实HOST B1/C1及i18n6通过；双层fence消融1断言失败/0错误，恢复通过。后续容量修复的最终代码分别通过matching12、数量3、边界2、V7升级及五类漂移2、server10；不是同一轮全套结果。真实V7后、比较修改前的三场景RED为3断言失败/0错误。首次targeted轮4个连接错误与EOF原因未证实、夹具问题和helper专属前置RED缺失均按报告披露，不虚构历史。

既有matching允许同需求多份容量而旧assignment组合唯一键拒绝落库；V7只将该键改同列普通索引，保留每份planned_assignment_no唯一，不改V1–6或合并数量。当前ready7/27私表，[执行前独立SQL/hash记录](../../.superpowers/sdd/beauty-backend/v7-preexecution-root-record.json)与最终SHA一致。公开HTTP/remote接待、C/D工单与恢复及真实资源owner部署仍未交付；依次继续55C、55D、56–60，BM04尚未准出。


### Task55C 当前执行状态（2026-10-23，未完成）

55B已完成独立审查和V7原生验证；55C工单范围代码已有实现快照，但尚未提交产品commit或验收。独立审查发现5项Important：I5测试容器启动失败/超时清理已修复，经真实node子进程行为测试6/6通过并复审关闭；修复方案的RESOURCE_PENDING续行和REGISTERED临客状态描述亦已纠正并复审通过。

仍待完成4项生产修复：I1同原键成功竞态重放、I2成功phase不可回退、I3新052/省略身份054真实guest owner核验、I4已有孤立实际task防线。精确方案及未验证用例见[55C方案](../../.superpowers/sdd/beauty-backend/task-55c-design.md)和[55C报告](../../.superpowers/sdd/beauty-backend/task-55c-report.md)。这些方案不代表对应修复已实现或验证。

原生验证受Colima测试实例colima-hxkey-tests内存阻塞：已确认自建MySQL被全局OOM及128MiB校准cgroup OOM；最近只读复核MemAvailable236384KiB、无swap。停止新建测试容器，未操作其他任务容器。需恢复至少约512MiB可用内存或独立MySQL8.4后，取得真实业务RED，再实施上述修复/GREEN及剩余父链、B回归、最终wiring/host验证。不用环境错误充业务RED，不以历史118项reactor通过推断最终快照全绿。55D及56–60未启动，BM04尚未完成模块部署验收。


### Task55C 环境恢复与继续执行（2026-10-23）

用户已明确将本轮美业原生验证统一到项目现用的 MySQL 9.7.2。历史 MySQL 8.4 的结果仍按原版本保留，V1–V7迁移字节不变，不将旧结果改记为9.7证据。用户授权清理后，19个无挂载临时测试容器已删除，Colima可用内存恢复3653792KiB，环境阻塞解除；4个带数据库卷的已停止实例保留。

现在恢复55C第3轮修复和原生验证，使用普通BEAUTY_TEST_DISK=1/64MiB buffer与缓存mysql:9.7.2，停止沿用失败的128MiB校准。先复验受阻父链用例，再取得I1–I4真实业务RED、修复及GREEN，完成B定向回归和必要HOST/wiring后独立复审。当前仍未验收55C或BM04，未启动55D/结算/公开接线或模块部署。


### Task55C 验收完成（2026-10-23）

工单范围已提交22e1d8938a6ad2a45b2c44e40480d7a7d64668bf，41文件，commit tree与已审快照27a3c950c269fe1f817e50cea60e659f6e560ed1一致。独立复审SpecPASS/QualityApproved，I1–I4关闭，先前I5与方案纠正项维持关闭，无新增finding。最终普通美业118/118、真实宿主身份3/3、约定接待回归3/3及工单权限/原键/终态定向回归通过；本轮原生MySQL9.7.2，V1–V7不变。完整证据与历史失败见[55C报告](../../.superpowers/sdd/beauty-backend/task-55c-report.md)及[最终复审](../../.superpowers/sdd/beauty-backend/review-task-55c-r3.md)。历史SQL/Query专属前置RED缺口如实保留，本轮不补造历史。

CLAIMED→USER同时实际REPLACE已由静态链路核对兼容，但未独立执行此组合；作为Task60真实模块验证补充项，不把静态核对写成测试通过。下一项55D直客接待、原号恢复及后台有界对账，仍先精确设计后编码，统一MySQL9.7.2。55整体与BM04尚未完成，公开接线59、模块真实部署60，未向远程或生产发布。


Task60 验证闸门补充：现 scripts/check-sql-portability.mjs 的 targets()只扫描 backend，未覆盖 backend-plugins。完成模块部署前须让同一规则集定向覆盖当前美业插件SQL（不复制第二套规则、不放宽known-sql-dialect基线、不扩成无关插件治理），并验证真实坏SQL反例能触发；本轮旧gate通过只代表其实际扫描范围。D新Mapper资格查询另以真实MySQL9.7.2与针对性语法核对验证，不能把旧gate结果描述为插件SQL已被扫描。

**55D当前开发状态（2026-10-23）：** 精确方案[task-55d-design.md](../../.superpowers/sdd/beauty-backend/task-55d-design.md)已技术核对，实施BASE612b8c081。MySQL9.7.2首批两项直客测试均到达业务入口并因行为未实现断言失败（2 failures/0 errors），不是环境或编译错误。已有部分实现保留继续；全部验证与独立审查尚未完成，不标记55D或BM04完成。当前本机正常64MiBbuffer、自有实例串行验证，不再使用已失败的128MiB校准模式。

### 32.4 Task55D直客接待、原号恢复与后台对账验收（2026-10-23）

产品提交31479219d17da969104c413268c4ae7be7ac4f5c，35文件；commit tree 93eb6c32c37235bc52c87f48b935e3a7be6eae3b与独立审查快照完全一致。接待048复用既有预约资源协议和C唯一工单写体，原操作查询/恢复及api对账Job已实现；未知结果沿原号原键原命令，不冒用历史员工，不自动签到开单。无共用销售订单/资金、公开wire或部署交付混入本步。

首轮审查4项Important已在R1逐项关闭；R1引入的创建head锁等待跨资源start竞态在R2关闭，最终Scoped Spec PASS / Quality APPROVED，无未关闭Critical/Important。审查报告：[首轮](../../.superpowers/sdd/beauty-backend/review-task-55d.md)、[R1](../../.superpowers/sdd/beauty-backend/review-task-55d-r1.md)、[R2](../../.superpowers/sdd/beauty-backend/review-task-55d-r2.md)；[实施报告及逐AC证据](../../.superpowers/sdd/beauty-backend/task-55d-report.md)保留准确快照时序。

验证分批而非重复加总：首轮D整类21及后补11、真实HOST最新1（此前HOST回归5另记）、严格迁移24及相关5；R1原生D33+B2+C4=39、最终定向7、完整普通插件118；R2最新定向12和装配/架构23通过。有效SQL防线消融1断言失败后还原通过；R1各问题真实RED、R2锁等待真实业务RED均保留，前期夹具/观测失败不计业务证据。R2测试以真实独立行锁和线程阻塞点同步，依赖当前JDK21/JDBC栈，不把监控表观测写成最终GREEN。V1–V7字节不变，ready7/27私表，统一本机MySQL9.7.2，未推送或远程部署。

原Minor M1为故障注入WARN与既有Flyway/HOST环境警告分类，继续留在全分支审查，不称日志pristine；SQL gate现有扫描范围、未穷举的身份排列/交错和真实资源owner部署等边界详见报告，后续60按既定闸门验收。Task55本地业务完成，BM04整体未完成。接下来按[Task56任务](../../.superpowers/sdd/beauty-backend/task-56-brief.md)先精确设计，再交真实共用报价/普通建单/受管理履约；57资金、58来源账单、59协议、60大模块真实部署顺序保持。


### 32.5 Task56共用报价建单与受管理履约验收（2026-10-23）

产品提交dbb635a7cf9d36ddddfbd0138e8dfc40216d4e9b，87文件；commit tree 95a19a1b0fa62cca2e317086bb202b1a178ae463与最终独立复审快照完全一致，父提交f33bf12cf。共用owner交付prepare/readQuote/confirm/place/findOperation/source真实六方法及HOST C/内部B入口，复用零售与已知本地餐饮相同的普通writer、实际商品/活动/券和最终同次分摊；未合并未接线餐饮定价引擎。永久首键映射、稳定来源行、RR来源观察和受管理SERVICE_EXECUTION最低CAS已交付，支付方法留57，不以SQL历史receipt夹具充正式收款。

[首次独立审查](../../.superpowers/sdd/beauty-backend/review-task-56.md)为SPEC PASS/QUALITY NEEDS_FIXES，仅1项Important：native重启测试破坏默认Maven入口。R1同时按实际JSON/MySQL依赖隔离8个具体测试类，保留真实native断言，并让runner核新报告非零/零skip；[窄复审](../../.superpowers/sdd/beauty-backend/review-task-56-r1.md)确认I1 ADDRESSED、Scoped SPEC PASS/QUALITY APPROVED，新增Critical/Important/Minor均0。全量证据和失败历史见[实施报告](../../.superpowers/sdd/beauty-backend/task-56-report.md)。

验证按快照分批：防线消融A/B分别6项、2项真实业务断言失败，逐字节还原后合同4项、应用83项原生通过；owner分层修正后合同4项、原生/架构18项通过，后补配置2及合法取消/超时2通过。旧相关回归129项中3项架构失败、1项standalone条件跳过，其中本次优惠router→Mapper问题已修并定向通过，剩余OpsPoints FQN/store登记保留60。餐饮embedded实际5项通过、standalone专用1项跳过，不能称独立构件已验。

R1真实默认入口先1断言失败，另2项H2 JSON错误；抽象fixture条件不继承的中间态73项7失败/52错误/8跳过诚实保留。最终默认入口73项全部预期跳过，仅证明测试选择；真正native代表4项通过/0跳过，错误native配置1项断言失败且runner实际拒绝skip XML。没有累计历史批次冒充最终全套通过。

V539固定8444bytes/SHA256 5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b，首次执行前根独立固定，此后不变；beauty V1–V7/ready7/27不变。原生平台用V538结构夹具追加冻结V539，不代表全历史Flyway升级；库存独立库仍未原生验证。仅自有缓存mysql9.7.2串行/finally清理，未推送/远程部署。

共享静态当前及隔离HEAD基线均19项11通过/8失败：预约权限三语、B端common.delete、实体CHECK解析与12表清单、库存文案参数；属于本美业分支旧预约接线的亦为本项目部署前必修，不称无关历史。旧OpsPoints权限矩阵/文档索引生成漂移同样未放宽checker，不称全backend或全生成gate绿。Task60须连同JobLock迁已登记包、beauty域登记、插件SQL定向覆盖、真实认领+REPLACE和告警分类收口。

Task56本地owner完成；BM04整体尚未完成。按[Task57任务](../../.superpowers/sdd/beauty-backend/task-57-brief.md)先审计真实资金owner、全部open入口、原号/凭证/权限与新SQL，再技术核对、RED/编码/原生和独立审查。58来源绑定账单、59美业协议、60大模块真实JAR部署顺序保持，BM05仍关闭。

### 32.6 Task57已验收：原号支付与正式实收（BM04尚未整体部署）

本地产品提交741b38178f01a6f4159fc618d57a45d75a3601e0，父b34e31ba4，98文件树d25c4e58cb7b888bbb900b320c82d4a834183d47与最终独立审查完全一致。CheckoutPort补齐八方法，trade永久P0映射、pay原号canonical与单次发送、正式CASH/BANK_TRANSFER实收、实际操作账号/私有凭证owner及source真实收据引用均已交付；金额仍由stl_payment拥有。

独立全审发现3个重要问题，经两轮定点修复与窄复审全部关闭：NOT_SENT/CLOSED不再掩盖后续真实成功来源；canonical关系变化在当前锁读后整次回滚重试一次；未发送操作重查当前订单资格，且同一资金guard内任何已有SUCCESS阻止另一pending原号领取首次发送权。实际相反成功/迟收仍保留，竞争pending可保持原映射并明确CONFLICT/保守UNKNOWN，不伪装结清。审查见[全审](../../.superpowers/sdd/beauty-backend/review-task-57.md)、[R1](../../.superpowers/sdd/beauty-backend/review-task-57-r1.md)、[R2](../../.superpowers/sdd/beauty-backend/review-task-57-r2.md)。

有限证据：初始资金23项及相关边界38项通过；R1修复原生31项/普通14项，R2原生14项/普通4项均0失败0错误0跳过。批次相互重叠，不累计为独立总数。初始实现真实独立JVM发送前/通道接受后崩溃分别保持send0/1和原号，三防线消融命中3个真实断言、逐字恢复后4项通过；这两类专项未在R1/R2无关重复，不宣称最终全量测试通过。新增native类普通H2隔离41跳过只证明构建入口隔离。全部本任务临时资源已清，无生产资金操作、push或部署。

V540首次执行前独立pin并复核真实文件7477字节/SHA256 ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745，现已冻结；V539及beautyV1–V7逐字不变。[ADR-047](./ADR/ADR-047-资金原号操作与正式线下实收归属.md)保留全局identity、UTC实收下界与可信内部投影等边界。

完整普通门禁仍有已定位旧架构/四端点登记/标签/生成漂移；Task57 M1未知/空native selector校验作为Minor明确进入Task60最终门，不因本任务验收丢弃。58来源绑定/账单、59公开协议、60整BM04真实JAR部署和whole-branch审查仍按序，BM05保持关闭。完整证据与未验边界见[报告](../../.superpowers/sdd/beauty-backend/task-57-report.md)。

### 32.7 Task58已验收：来源提交、观察分配与逐来源账单

产品提交 ea2ead5c63f4830de566fdb3dcdda4b0a32424a9，基于 8cc2482ad；独立初审及两轮限定复审后 Important 全部关闭。覆盖功能矩阵 BTY-H01/H03/I04、BAPI056/071/072/073/137 的本地 Service 内核，公开 Controller/Remote/SERVER 归 Task59。真实 DTO、方法、Mapper、Bean 图、状态及锁序见[设计](../../.superpowers/sdd/beauty-backend/task-58-design.md)；命令、断言与边界见[报告](../../.superpowers/sdd/beauty-backend/task-58-report.md)、[初审](../../.superpowers/sdd/beauty-backend/review-task-58.md)、[R1复审](../../.superpowers/sdd/beauty-backend/review-task-58-r1.md)、[R2复审](../../.superpowers/sdd/beauty-backend/review-task-58-r2.md)。

已交付持久原意图及不可变端点回执、显式项目选择和加项、当前真实消费者映射、原号查询恢复、本域数量仲裁与账单/trace。B 仅准备和查询，C 真实本人确认/下单/支付；未知不换号。确定的 owner REJECTED/REQUOTE_REQUIRED 原子结束当前租约；EXISTING 恢复回执返回本域 submissionNo。原交易缺失使已知来源 epoch 失效，身份/授权失败仍传播，不能转成普通待恢复结果。

V8 已在首次执行前由根独立核对实际文件：4582 字节，SHA256 c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343；已实际执行并冻结，当前 ready8/28，V1–V7 与平台 V539/V540 字节不变。观察最高 pair 不下降，旧 epoch 的更高可信事实及可信 UNKNOWN 均阻断旧 ticket。按 observation→行 guard→工单→任务→submission 原子核净量并保留历史分配。绑定不授开工权，canStart=false、BM05 关闭；见[ADR-048](./ADR/ADR-048-美业来源观察水位与本域分配仲裁.md)。

账单读取 owner 同快照真实回执、净收、已退和未结，按币种汇总。来源因退款 BLOCKED 不等于资金不完整：当前财务事实完整且全部归属本工单时仍汇总，操作阻断单独保留。UNKNOWN、部分归属保持金额 null 或汇总不完整，不按任务数量分摊。提供方缺失及非 CREATED 仍展示历史来源与 lastVerified，不能推导新收款权。

初始原生内核 92/0/0/0、实际 HOST 4/0/0/0；R1 最终定向 7/0/0/0、R2 定向 4/0/0/0，分批重叠不相加。R1 RED7 和 R2 RED2 均为真实断言失败，修复后同风险场景通过；消融及逐字还原证据保留。平台 23 项仍有已登记的 2 项架构失败，不能声称全平台通过。HOST 的退款为受控 owner 事实而非完整退款 API；当前测试不代 Task59 公开协议或 Task60 实际构件部署。

原审查 M1 的跨动作竞争测试误定胜者留给 Task60 明确修复/分诊；其他历史门禁债继续保留。下一步 Task59 先逐 API/Bean 图核对及设计，再实施；整个 BM04 尚未部署准出。

### 32.8 Task59设计通过：公开入口与双向协议（实施中）

已核对[Task59设计](../../.superpowers/sdd/beauty-backend/task-59-design.md)及[设计审计报告](../../.superpowers/sdd/beauty-backend/task-59-report.md)：18 个公开入口、19 个美业内部路由、4 个反向 B 交易查询/准备入口，逐一对应实际 Service/DTO/权限及 Bean 条件。原 160 目标编号不变，到店码及既有通用恢复增量单列。设计通过并放行编码，尚未运行本步构建、RED/GREEN 或部署；不能按设计文件计功能已开放。

共用中立 InternalCall/InternalReply 使用完整身份、作用域及原请求的 HMAC 摘要绑定，复用现有机器令牌，不增加会话/nonce 系统。专用序列化保证嵌套 null 明确存在，严格解码拒绝缺失的数字或可空字段；安全业务码与请求摘要通过可选错误头传递，旧调用方及默认零售启动保持原合同。缺原号与路由故障区分，身份异常不转成来源未知；详见[ADR-049](./ADR/ADR-049-行业内部请求绑定与安全错误回传.md)。

052/054 预约开工单采用最小 canonical052 幂等修正，保留完整 054 入参摘要、schema1 历史记录及原派生资源键；跨入口只接受已证明等价的保守形式，不丢弃新增断言。无预约 054 不变。真实 C 确认/下单/支付及 B 实收继续留在 HOST 当前会话，SERVER 仅美业私有库和真实反向 owner 适配。原报价/到店码的合法历史回放不强加客户端未来有效期条件。

实施按六单元：中立合同及错误元数据→公开/内部适配→反向四方法→别名幂等→真实原生 embedded/双子进程 standalone 桥接→消融/有限回归/生成对账及独立任务审查。全部已执行 SQL 继续冻结，本步预计无 DDL；新增需要必须先另行核对。Task60 再做当前 JAR 的整体部署、全部遗留门禁和独立模块准出，BM05 仍关闭。


### 32.9 Task59代码验收：公开接待入口与双向协议（2026-10-24）

产品提交`c9d9518798ee74f93d7a1f899d356c4760d57b43`，94个精确审查文件：46个新增、48个修改。交付18个公开映射、19个typed美业内部方法和4个反向B交易owner方法，保持160目标编号；默认关闭、api-only及embedded/standalone接线，机器身份不代C确认/建单/付款。预约OPEN别名使用同一canonical052业务意图，保留原请求重放、schema1恢复和双旧意图冲突；无新DDL，8份私有迁移及平台537～540字节未变。BM05执行及canStart仍关闭。

独立R0规格/质量审查发现公开path校验及详情任务关联两项Important；R1已修复并独立复审通过，无新增阻塞。公开11路径在业务提供方选择前校验非空白/max32；B/C详情按真实owner顺序严格比对root.taskNos与嵌套任务。R1定向Ingress51、Responses3、Machine11共65项无失败/错误/跳过，插件及两形态HOST夹具编译通过。此前工单native30、核心native33、真实owner7和两项消融证据分别归档，不把重叠回归相加。证据与边界见[报告](../../.superpowers/sdd/beauty-backend/task-59-report.md)、[初审](../../.superpowers/sdd/beauty-backend/review-task-59.md)、[修复复审](../../.superpowers/sdd/beauty-backend/review-task-59-r1.md)。

这只是Task59代码阶段验收。HOST仍有已登记的全限定名/beauty-store包登记两项架构失败，进入60A；弱授权负例夹具和既有告警同样保留。实际AC15～18双子JVM、真实会话撤权/完整owner链及token-bypass消融待全部P1～P4代码后统一部署执行，未标通过。下一项为60A代码质量设计审计，之后继续BM05；不以Task59代码提交表示整个BM04部署准出。


### 32.10 60A-A1/A1b架构收尾代码验收（2026-10-24）

产品`94aa791edb445b56bec9bcd348724ecff4ad803a`，17逻辑文件/19路径。JobLock配置同模块迁job.config、两个薄import、Ops正常PathVariable；beauty纳入DOMAINS实际业务域并启用显式presence测试。登记暴露的内部准备辅助类改名AppointmentPreparation，Bean名和业务体逐字保留，仅类型及真实接线/方法设计产物对齐；没有放宽旧规则、造接口或新增业务能力。

HOST34/0失败/0错误/0跳过，含Architecture17、JobTarget7、Endpoint10；SERVER11/0/0/0，含独立关闭、唯一锁Bean/真实锁冲突及core装配。唯一beauty登记消融精确1项失败，逐字恢复同selector1项通过。store/core JAR目标与缓存一致、旧class为0，实际classpath已核；不是native/实际部署结论。独立spec+quality审查Approved，无阻塞，[报告](../../.superpowers/sdd/beauty-backend/task-60a-a1-report.md)/[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a1.md)保留警告与边界。下一片A2修复旧runner，60A整体、BM04统一部署和BM05仍未完成。


### 32.11 60A-A2原生付款测试脚本代码验收（2026-10-24）

产品`15b7143192da05eb6ae3242dffe25049cf685815`仅两文件。选择采用有限类/方法名单并保留合法多方法和付款多类语法，非法选择在任何资源effect前拒绝。XML只按真实结构统计用例，注释/CDATA和日志文字不能充当证据；每个选定方法、class、fresh水位与非零零skip均校验。密码经环境变量，串行锁及创建响应丢失后的清理只作用自有UUID。

独立初审两项Important（XML文本假用例、保留报告含临时凭据）经两轮定向修复复审关闭。最终安全单测19/0失败/0错误/0跳过，真实RED与原字节恢复证据保留。报告处理先从streams及所有本次可归属产物收齐凭据，再统一脱敏、全部复读核验后才写证明；失败不虚报安全，保留警告。

R1原生检查点仅指定BeautySourceCheckoutIT一个真实方法1/0/0/0，现场/归档XML及summary已脱敏、统计/用例不变，自有UUID/tmp/锁清理确认。R2只是纯凭据发现顺序修复，以生产main受控effects的19项测试验证，未重复native；不得把R1原生检查点说成最终R2源代码重跑。无Java/SQL/POM变化，也无正式部署。[报告](../../.superpowers/sdd/beauty-backend/task-60a-a2-report.md)、[初审](../../.superpowers/sdd/beauty-backend/review-task-60a-a2.md)、[R1复审](../../.superpowers/sdd/beauty-backend/review-task-60a-a2-r1.md)、[R2复审](../../.superpowers/sdd/beauty-backend/review-task-60a-a2-r2.md)记录边界；既有告警归A11，A3接续。60A整体、BM04部署、BM05及后续P2～P4尚未完成。


### 32.12 60A-A3跨动作竞争夹具代码验收（2026-10-24）

产品`ba07edc99c7b88a1d3de70c18c6e0586eae0073c`仅ServiceSubmissionIT、aliases精确名单和Node单测三文件。任一SUBMIT_SOURCE/ADD_SOURCE都可合法获胜；保留无偏向并发三场景，另定向覆盖两种赢家。独立按原请求核对永久namespace/payload、原SOURCE_SUBMITTED回执和冻结来源，失败方真实CONFLICT且无额外任务/来源，prepare一次。完整VO按当前投影核对，租约revision可推进，BigDecimal按数值比较；不可变回执与冻结命令仍逐字不变。

Node8项通过，最终受影响四selector共5例原生0失败/0错误/0跳过；三种动作/回执/数量突变分别真实RED，同一最终SHA逐字恢复后参数化2例通过。历史整类29/5比较失败仍保留，不把分批结果相加声称最终单次29全绿。测试查询错误、准备/沙箱失败单列透明记录。独立spec+quality审查通过，六处经核对的AOSP附带换行只改变格式；10个本任务容器和显式临时目录已清，SQL504与known5不变。详见[报告](../../.superpowers/sdd/beauty-backend/task-60a-a3-report.md)及[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a3.md)。

既有警告继续归A11，60A整体及BM04部署未准出；下片A4修复列/CHECK解析，全部P1～P4代码后才统一部署。没有商品、订单、支付、权限或迁移变更。

### 32.13 60A-A4结构DDL解析代码验收（2026-10-24）

产品`a018b4dc09956fe84f937543bd85d07512c3e0b2`仅五文件：JS唯一DDL解析器及真实测试、两个Java守卫及共用test-only SchemaDdl。CREATE按引号/注释/括号结构分列，完整类型token、CHECK整约束、下一表边界和既有600字符tail限制均保留。V506/V517/V533读取冻结源，Java守卫共用一份实现，Python与schema-test.sql未改。

初轮Node7项、Java6项均零失败/错误/跳过，最小分隔消融真实RED后五文件逐字恢复。独立审查I1发现合法ALTER注释分号被原正则截断；R1修复完整语句与下游子句边界，新增三项真实回归，最终Node10项全部通过。三Java源码与初轮验证时完全相同，保留六项XML证据，无重复Maven执行。独立复审I1已关闭、无新增重要问题。详见[报告](../../.superpowers/sdd/beauty-backend/task-60a-a4-report.md)及[R1复审](../../.superpowers/sdd/beauty-backend/review-task-60a-a4-r1.md)。

553冻结源保持不变；共享套件实际22项/19通过/3失败仍明确归A5和A5b，未改名单掩盖。编译诊断归A11；本片无native/部署，H2仅证明fixture可加载。60A整体、BM04和全部P1～P4尚未验收，正式部署继续统一后置。

### 32.14 60A-A5精确非ORM登记代码验收（2026-10-24）

产品`4a0fa71dda6b7fa522e486da1811a54ccb83b450`只修改entity-alignment.test.ts。精确补12个真实JDBC/SQL Mapper表理由，原57登记值和anchored IGNORE保持，真实守卫及负例共用own-key分类函数；未知probe和继承键仍拒绝，列/ghost/stale检查未减弱。

实际旧守卫6项/1失败恰报12缺项；行为测试10项/5失败后最小修复10项通过。仅删sys_outbox_stream消融10项/3失败，逐字恢复同SHA后10项全过。独立规格和质量审查均PASS、无发现，根核对源码/登记差集/恢复/提交内容。初次Vite受限临时文件EPERM另列，后续明确cwd授权重试已解决，不算业务RED。[报告](../../.superpowers/sdd/beauty-backend/task-60a-a5-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a5.md)。

本片不运行schema-lineage或生成器，两项血缘债按A5b精确方案继续，不声称shared整体已通过。无Java/数据库/部署，全部P1～P4后统一部署的顺序保持。

### 32.15 60A-A5b血缘语义与生成物代码验收（2026-10-24）

产品`44fa7f3ce3eed5e36ae2d80d9e7cc9dca345fc2d`抽取纯schema-lineage供真实守卫与ER生成器共用。凭证按积分/储值四条真实关系分开；计划、来源订单、grant、退款执行不再强设全局owner，catalog/tier及支付账号按实际作用域限定，buyer等角色保留真实目标列。审计歧义键的未知新表必须分类，graph/字段badge使用同一resolver，条件路径保留明确说明。

原lineage16项/2失败；旧逻辑提取后的语义RED、修复后定向57项和最终combined67项均有实际输出。voucher全局回退、plan错连、scope剥离分别真实失败，逐字恢复后67项通过。生成器末次仅修Markdown多行/pipe转义，纯resolver与测试字节不变，保留原67证明，不虚称新跑67；生成器本身实际执行及幂等检查覆盖该格式改动。独立审查及根manifest/源码/生成结果核验见[报告](../../.superpowers/sdd/beauty-backend/task-60a-a5b-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a5b.md)。

实际生成输出、精确计数/变更文件及边界以报告和root gate为准；未改DDL/业务Java/A5登记/known或扩大域图。既有DOMAINS缺sv/ent/pay详情等限制保留，关系集合与限定说明已受测试保护。本片不是数据库行完整性或实际部署验收；后续A6～A11、BM05及其余P1～P4继续，正式部署统一后置。

A5b审查另确认继承Important：quote_no使结算报价误连到营销。本范围验收不关闭该问题，已在§2.29单独发布A5c必须修复；同处live54文案Minor同片处理。整体关口仍未准出。

### 32.16 60A-A5c报价命名空间血缘验收（2026-10-24）

产品`811de0f35c67dd1319e087974cb92d7ace8ded0e`仅纯lineage、原测试、生成器live说明及两个实际生成物。quote_no不再全局指营销；营销修订→营销根、结算确认→结算根以及operation当前报价→结算根按精确源表限定并保留tenant scope，根/self/跨族/未知第五成员无edge/FK，UK优先和原业务冻结校验不改。live54数字从生成器源移除。

真实旧逻辑4失败/67通过，最小修复后71项通过，旧global消融3失败/68通过，逐字恢复后71项通过。两次实际gen:erd均成功；21项有首次/第二次直接SHA一致证明，三张专题图首次SHA漏采，最终与基线一致及首次未变状态有记录，明确不声称24项完整直接首次指纹。此历史证据限制经独立审查评估，无确认当前正确性缺陷，不为补历史证明重复运行。

独立规格/质量PASS，A5b继承Important与Minor均ADDRESSED，无新增发现。[报告](../../.superpowers/sdd/beauty-backend/task-60a-a5c-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a5c.md)。根核对五文件源码/生成物、71项及消融恢复、21直接指纹和三专题最终BASE、精确提交父树与index。A5登记SHA未变，无SQL/Java/数据库/部署，整体P1～P4尚未完成，下片A6。

### 32.17 60A-A6统一SQL方言扫描代码验收（2026-10-24）

产品`9136d67c98ff24a6448cd009511e3685370d2dfb`仅扫描器与新增Node测试。CLI固定扫描backend和美业插件，共用原十规则、SQL掩码及精确path/rule基线；Java普通字符串/textblock有界提取后扫描，保留原文件行号，导入不运行扫描，缺根明确失败。

真实RED9项/7失败、最小修复及最终9项通过；移除beauty目录与回退Java整文件mask两次消融真实1/3失败、逐字恢复。实际--check exit0：162处/51文件、54已知、fresh0/stale0，未扩baseline；SQLmask和known逐字不变。独立规格PASS/质量APPROVE，无重要问题。[报告](../../.superpowers/sdd/beauty-backend/task-60a-a6-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a6.md)。

审查Minor：Java仅bare-CR换行时//注释终止与原行计数仍有边界缺口，已带到最终分支审查triage，未声称所有合法Java格式全覆盖。无AST/Unicode/octal/跨字符串拼接或动态运行时SQL证明，报告明确静态边界。未执行Java编译/数据库/部署，下一项A7才运行必要隔离原生组合测试；全部P1～P4正式部署顺序保持。

### 32.18 60A-A7认领身份与真实资源替换代码验收（2026-10-25）

产品`7203aa3924f1527c3d2f74aa4c9a374cdf5ac4fa`仅新BeautyClaimedGuestResourceIT及原checkout runner/Node测试三文件。真实临客预约/认领/当前身份签到/换人开单验证USER归一、原历史不变、REPLACE成功与回放；非owner USR撤管理权限后精确FORBIDDEN；独立人员A行锁及真实peerB预约导致owner实际CAPACITY_UNAVAILABLE，原A和peerB占用均保留。无伪造预约/资源事实或业务provider替换。

Node真实19通过/1失败到最终20通过；正常原生三方法3/0失败/0错误/0跳过。唯一实时权限allowed表达式消融得到1项断言失败，finally恢复生产原SHA并实际重新编译后同定向1项通过；正常三绿与最终一绿为两批，不虚称再跑完整三例。最终默认零售733份测试源码test-compile BUILD SUCCESS，反射编译边界成立。生产源码/字节码及冻结迁移精确不变，七个自有UUID容器及串行锁无遗留。

前置缺publication归档配置、HELD与测试冻结时钟的失败历史保留：根批准单次原号诊断定位许可尚未生效，最终删除诊断，只保留测试局部systemUTC及既有archive-only配置。不是生产业务缺陷修复。实际环境/构建警告未压制，仍限隔离开发证据。独立规格PASS/质量PASS，无发现。[报告](../../.superpowers/sdd/beauty-backend/task-60a-a7-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a7.md)。根manifest/真实XML/源码与字节码/精确提交父树核验通过；下一片A8，全部P1～P4正式部署仍统一后置。

### 32.19 60A-A8词条与库存提示范围验收（2026-10-25）

产品`b31ebd4dd47c1170e30ab8121fc5ec151f24954f`精确九文件：三份B端locale、三份后端properties、StockReconcile必要import/相邻调用及两个TS守卫。补预约查看/管理词条、英阿common.delete、中文既有mySpecs.delete缺项；库存提示补当前门店{0}，原冲突门店{1}与两调用参数顺序保留。无权限授予变化、known弱化或无关格式重排。

真实初次12失败/12通过；补原中文缺项后最终四文件24项通过。移除{0}和反转真实Java调用参数均得到断言失败，finally精确恢复。后端实际XML六方法零失败/错误/跳过及BUILD SUCCESS；相关源码哈希与编译结果一致。独立范围规格PASS/质量APPROVED，[报告](../../.superpowers/sdd/beauty-backend/task-60a-a8-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a8.md)。

整体B端类型检查仍FAILED：两处BASE相同的mResolveMemberCode缺MerchantApi声明；根另确认真实httpApi亦缺实现。此继承Important保持OPEN，必须由§2.33 A8b真实编译通过关闭，不能以本片范围验收豁免。审查Minor：重复检测只覆盖既有解析器捕获的biz:项，pay/trade项未扩扫；描述保持该边界，最终分支审查triage。现有javac处理器/弃用/unchecked输出保留A11，不声称输出无警告。CannotVerify的既有授权运行时及Java MessageFormat不因本片静态词条守卫自动取得新结论；未改相应实现，完整运行验证仍后置。

### 32.20 60A-A8b会员码客户端及类型关口验收（2026-10-25）

产品`44f460cde2994cf3090cde9daeb47f5b15436606`仅B端contract/requests/http及新wire测试四文件。复用共用CustomerReference，真实POST仅投影memberCode/storeNo，不转发调用方身份、不写日志或持久化、不在mock签发凭证；不新增页面或后端协议。

真实缺方法RED三失败→三项GREEN；完整B端vue-tsc exit0无诊断，B端实际43文件242项通过，shared字段守卫8项通过。字段值对调消融得到一项断言失败，finally精确恢复后新三项再次通过；完整套件不为恢复再跑。初次shared配置后缀错误作为setup保留，不计行为RED。独立规格PASS/质量APPROVED，无发现，[报告](../../.superpowers/sdd/beauty-backend/task-60a-a8b-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a8b.md)。根核验四源码、只读owner/endpoint/mock/shared和A8九产品、实际日志、恢复及精确提交父树通过；A8继承类型Important关闭。

审查CannotVerify：真实HTTP权限/到期语义由未变所有者及后续统一部署验证；生成OpenAPI已有response但缺REQUEST_TYPES/body是确认缺口，已列A9必须收口，不能以客户端编译通过宣称文档完整。无后端/native/正式部署重复运行，A9后继续A10/A11及BM05～P4。

### 32.21 60A-A9权限与生成契约代码验收（2026-10-25）

产品`c07890c3fc9e691f5101d12cd5dc744709a84153`实际18文件（19允许路径，三域矩阵重生成无差异）。补四项REQUIRED，测试守卫改用JDK21方法AST正确识别注解前后/多行/FQ且不借邻方法/注释，损坏源码明确失败；新增有效反例和保留原四方法，最终Java10/0失败/0错误/0跳过。

已有会员码生成合同补required两字段body及说明，实际YAML3通过；Ops目标登记按真实注解精确补17项，完整37通过，删除一项消融失败、恢复后有限31通过（六旧项未选）。Ops角色JSON仅FINANCE/SUPER_ADMIN各四项新增，无其他增删；运行时权限未变。正常13份实际输出两代SHA一致；六项派生文档逐份核对来源后生成，root不是批量接纳未知diff。

根核验全部106证据SHA、19路径/18变更、25项只读源、六阶段JavaXML、真实恢复及两代输出；独立新鲜snapshot输入7792文件、checker后7793，唯一新增ui-lib.page.html副产物保留在/tmp不入产品。全局25生成器实际exit0。独立规格PASS/质量APPROVED，无重要问题，[报告](../../.superpowers/sdd/beauty-backend/task-60a-a9-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a9.md)。Java登记消融发生在AST扩展之前，后续最终10项已真实运行，不虚称最终源码重复消融。

Minor为既有glossary加载TS词条的MODULE_TYPELESS_PACKAGE_JSON输出，保留A11归因/最终分支审查，不改package元数据或静默压制。CannotVerify真源/快照/来源完整性由根实际hash、源码读取和106证据核验闭合；运行时ownerOnly及正式部署保持既有验证边界。无运行时授权/DDL/POM/known变更，未重复未变B242/typecheck/shared8或native。下一片A10，之后A11和BM05～P4继续。

### 32.22 60A-A10确认夹具代码验收（2026-10-25）

精确两测试文件提交`55e3d1d1fe0d170d06d68569414a6cd5f4821d60`，独立规格PASS/质量Approved，无Critical/Important/Minor。[报告](../../.superpowers/sdd/beauty-backend/task-60a-a10-report.md)、[审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a10.md)、[root gate](../../.superpowers/sdd/beauty-backend/task-60a-a10-code-acceptance-root-gate.json)对应§2.35四条AC。三个普通用例真实初始3断言失败、首次3通过、错误接受消融2断言失败、精确恢复3通过；embedded test-compile成功。root核验29个证据文件及22个当前/BASE所有者事实，产品源码和运行/编译指纹一致。

真实HTTP/SQL、经济副作用及embedded/standalone拓扑尚未执行，审查CannotVerify明确留最终60B，不能据普通helper测试宣布M1运行验收。有效quote body共用、401/10401严格身份拒绝及独立200/10400无效body分离已编码；所有生产鉴权和迁移未改。下一片§2.36 A11，随后BM04代码整体对账和必要编译，再继续BM05～P4；正式部署保持全部代码之后。

### 32.23 60A-A11告警与换行代码验收（2026-10-25）

精确五产品文件提交`817ec6c58478d62f48fa90dda509c01815bbc9b3`，[报告](../../.superpowers/sdd/beauty-backend/task-60a-a11-report.md)、[独立审查](../../.superpowers/sdd/beauty-backend/review-task-60a-a11.md)规格PASS/质量Approved，无Critical/Important。root核对五源码及38证据hash、原生真实XML正常1通过/删除两行LOG.warn后1断言失败/恢复1通过；生产源码恢复冻结f2f579…、恢复class9659c1…与初次一致，三个UUID容器及锁均清理。此受控故障夹具不是A7真实资源所有者证明，不是历史55D原用例，也不是正式部署。

Node两个受影响文件实际26项通过；换行lineOf消融得到真实源行号断言失败并恢复。真实SQL检查162命中/51文件/54known、fresh0/stale0，known/mask未改；最早手算fixture行号错误单独归因，未充业务RED。WARN精确归因原操作及IllegalStateException，原持久意图/命令不丢失，后台到ARRIVAL_PENDING后须真实认证才完成原操作。既有Flyway对MySQL9.7验证版本告警、编译处理器发现和旧测试弃用提示为Minor，原文保留模块及最终依赖关口，不压制、不宣称环境无警告。

root另完成全部五美业子模块当前快照测试编译BUILD SUCCESS，347非target输入指纹留存；不是再次执行用例。下一步§2.37模块跨任务代码对账/独立审查，仍不得据此宣布BM04运行部署或全部P1～P4完成。

### 32.24 BM04模块代码关口通过、正式部署后置（2026-10-25）

最终产品提交3ae7fd31b89eb06e01d9661b2be22e76a28910b7，[代码验收](../../.superpowers/sdd/beauty-backend/bm04-module-code-acceptance.md)与[root gate](../../.superpowers/sdd/beauty-backend/bm04-module-code-acceptance-root-gate.json)汇总Task54～59/60A及模块修复。初审0 Critical/1 Important/3 Minor；I1真实三时点撤权/禁用用例先3断言RED，再3GREEN，首/末校验各自消融单例RED，逐字恢复源码和实际class后最终3GREEN。21项Node与当前默认零售测试编译通过，64证据hash及六UUID清理核验；[同审查者复审](../../.superpowers/sdd/beauty-backend/review-bm04-module-r1.md)确认I1 ADDRESSED、规格PASS/质量APPROVED、无新问题。

代码现于锁外准备后及最终writer事务前重核真实当前C/活跃用户且buyer不变，事务内不新增TokenStore IO；已提交原key按真实新会话恢复。范围399产品路径（398存在/1删除），实际指纹匹配。原五子模块联合增量编译、当前修复默认零售编译及前片定向证据按真实快照保留，不宣称clean空缓存构建或将各批相加为完整营业通过。M1/M2/M3非阻塞覆盖/守卫边界/告警债保留最终分支和依赖关口。

BM04当前状态为代码阶段完成、待统一部署验证；60B真实构件双形态、HTTP/SQL经济副作用、生产token消融、重启/外部设备均未验。BM05开工、实际占用、执行完工/履约及BM06日历尚未交付，全部P2～P4也继续。Task61先按§2.39审计现有真实提供方并细化完整BM05方案，独立设计审查后再发布精确编码任务，不重复领取已验收片。

## §33 BM05服务作业与履约（代码阶段）

### 33.1 Task61设计关口（2026-10-25）

[完整设计](../../.superpowers/sdd/beauty-backend/bm05-design.md)完成实际所有者审计和S0～S6切片。初审长期任职空结束遗漏已修订并由同审查者限定复审通过，29个源码/迁移指纹一致；设计阶段未运行测试，不记为业务交付。顺序保持人员前置/真实开工许可→预约单位交接→开放占用/结束/清洁→超时/调整→作业/参与/数量→履约/关闭。

### 33.2 Task62-S0共用当前操作人业务人员事实验收（2026-10-25）

产品提交35e6f0f66dc67b6ca77d2ddcd22960b2e0f2de6f，精确24文件。[实现报告](../../.superpowers/sdd/beauty-backend/task-62-report.md)、[独立审查](../../.superpowers/sdd/beauty-backend/review-task-62.md)、[root证据核验](../../.superpowers/sdd/beauty-backend/task-62-root-evidence-gate.json)及[精确文件清单](../../.superpowers/sdd/beauty-backend/task-62-product-inventory.json)对应§2.40；规格PASS、质量Approved，无Critical/Important。

共用人员所有者复用当前精确权限账号解析，在同一RR快照核验账号关联/人员/有效任职与主体门店，快照前后重核权限；MCH/USR同号隔离，账号号不出域。新增查询全父链binary精确关联，空validTo长期有效，重复任职/畸形事实失败关闭。美业通过同接口绑定远程适配，拒绝/依赖失败不伪装成UNLINKED。共享协议最小补齐枚举name标量，旧固定摘要不变；五权限及三语标签可分配，无默认角色种子授权、新表、人员账或开工入口。

最终五类报告33项（owner13/controller3/remote7/shared7/roles3），失败/错误/跳过均0；标签17、Node runner5通过。AC→实际方法见brief及报告/最终方法清单，覆盖当前权限撤销、同号命名空间、长期/到期/歧义任职、显式null/畸形协议和范围。大小写父链用例先1断言RED/0error，修复后九变体通过。有效期消融目标过期用例自身断言失败；有限区间防线另1error单独归因。首轮漏class采集后按实际时点重采必要消融，95项仅Mapper源码/嵌套class两项变化，finally恢复编译后95项逐字一致、恢复32项通过。此消融明确属于binary父链修复前版本，上界条件未变；最终当前版本33项GREEN，不冒称再次消融。

root核对148证据hash、24产品、95当前测试源码/class、七批XML和自有资源清理；32条既有SQL、ROLE_PERMS及五个known-*逐字未变。当前默认零售宿主在无food profile最终组合reactor中编译并执行所选测试；另以当前backend+beauty+food临时reactor完成food-api/web/core、宿主和beauty-channel测试编译BUILD SUCCESS，未install或改仓库POM。七UUID容器、临时目录及锁已清理。

审查P3保留：profile命名的装配测试仅证明聚焦Bean注册/依赖关系，不能证明真实default/embedded/standalone/disabled/ops构件启动；统一部署须补核。原生使用MySQL9.7.2结构性认证夹具加冻结V532/V533，不是完整历史Flyway或正式部署。javac处理器/旧弃用/unchecked、Mockito agent/JVM CDS及已断言的缺传输/EOF告警原文保留；BM04 M1/M2/M3继续留最终分支关口。

### 33.3 当前下一步及未交付范围（用户新调整）

按§0.3/§2.41先完成现金/线下美业核心：人工确认与共用订单来源准入、真实资源占用、开工/暂停恢复/换人/步骤、完成量/履约关闭、实际任务日历。Task63已完成既有正式线下路径与无Pay要求的差异审计并冻结真实身份、来源、数量和相反写者合同，已发布Task64精确编码片（§2.42/33.4）。默认先收后做；人工确认、服务事实和正式支付状态独立。

此前S1的Core/Pay行单位保护/永久managed标识及原号恢复方案保留为后续支付接入，当前不先实现；相关root草案仍是历史候选，不是当前任务。S0已完成但canStart仍关闭，待人工准入和真实资源链接通后只按支持模式启用。BM05其余、BM06及后续能力均未假记完成；统一正式部署继续后置。


### 33.4 Task63现金线下设计关口

已完成实际共用订单/客户/资金读写者审计和M1～M7切片。独立设计审查I1指出StockPort.confirm返回不能证明库存完成；同一设计任务修复为实际原库存结果合同后接受。设计关口只核设计及源代码指纹，未执行产品测试、构建、迁移或部署。Task62人员事实保持已验收；旧Pay S1仍暂缓。下一编码片为§2.42 Task64，不是原支付保护片。
