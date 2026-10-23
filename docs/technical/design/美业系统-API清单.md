# 美业系统 API 清单（按业务模块）

> 状态：目标接口契约与实施对照 · 2026-10-21 · 部分接口已编码，模块交付以主TDD部署关口为准。
> 由 `python3 scripts/gen-beauty-api-design.py` 生成，勿手改；真源：[API设计JSON](./schema/beauty-api-design.json)。
> 上游：[118项功能矩阵](../../requirements/美业系统-多端功能矩阵.md) · [领域对象](./美业系统-领域对象与聚合边界.md) · [数据库结构](./美业系统-数据库结构设计.md)。
> 关键详情：[预约工作台与日历契约](./美业系统-预约工作台与日历API设计.md)。

## 一、范围与阅读方法

20个业务模块，覆盖全部118项功能，共160个接口/接入项：106个候选HTTP接口、54个共用合同接入点。功能可以对应多个接口，一个共用合同也可能包含多条既有路由；本表不能作为已实现API数量。

优先采用行业编排＋共用商业核心：商品、报价、订单、支付、账户余额、权益余次、库存和财务分别由共用所有者维护。美业拥有预约、服务任务、实际参与、护理与疗程服务计划；疗程权益规则、发行、余次和使用账共用Pay能力。疗程计划不等于权益账户。

| 标记 | 含义 |
|---|---|
| 美业候选新接口 | `/biz/x/beauty`、`/mp/x/beauty`、`/ops/x/beauty`为设计路径；需实施前核对行业路由ADR与包边界 |
| 共用候选新接口 | `/biz/personnel`、`/biz/resources`、`/biz/schedules`等是多行业候选增量；不放进美业私有Controller |
| CONTRACT | 业务接入合同名称，没有新增HTTP承诺；复用真实共用路由或内部Service/SPI，不能照合同名称生成路由 |
| P1/P2/P3/P4 | 最低接线阶段；仍须门店能力、权限、来源和合同全部验收，不按日期自动打开 |

前=前台/POS；技=技师；管=商家Web；B=老板/店长App；平=平台。前/技/管/B复用商家认证及同一组业务API，服务端按门店、本人任务和字段权限裁剪。C端只使用`/mp`和已有共用消费者合同，不调用商家工作台。

## 二、统一请求、权限和结果约定

- 身份：tenantNo/entityNo/operator由可信登录上下文产生；storeNo是需验证的业务范围，不接受请求体自行提升主体、操作人或买家。C端customerNo由本人身份解析；代约可保留临客但付款时仍需真实买家及正式接管。平台诊断需目标范围授权与审计。
- 权限：表中端范围仅是入口。每个BAPI编号实施时绑定独立读/写权限与对象范围，例如`beauty.appointment.read/change`（候选权限名）；技师默认仅本人已分派任务及必要字段，管理操作按功能矩阵申/审/管分离。人员业务岗位与账号权限角色独立。
- 写请求：业务命令使用`Idempotency-Key`；同键同载荷返回原处理结果，同键不同载荷拒绝。预览、查询类POST不产生业务写入；长期异步命令返回操作号，超时/未知必须查原号。客户端自报成功不作为账或履约事实。
- 并发：修改携带`expectedRevision`；任务涉及来源另带`bindingRevision`。预览返回previewNo、expiresAt、目标修订及资源水位；提交由服务端重算并原子仲裁，预览不是资源锁。
- 时间：RFC3339带偏移，服务端按UTC保存、以门店IANA时区解释营业日；区间统一`[from,to)`。金额使用共用Money/币种合同，数量及精度用来源定义，不使用浮点金额。
- 返回：沿用项目统一响应封装，不另发明外层；普通列表数据为`PageData<T>{records,total,page,size}`。快照日历是`CalendarSnapshotVO`，不能套普通分页后将未加载区当空闲。Req/VO字段使用camelCase，业务编号为字符串，数据库id不对外。
- 状态：输出`revision,allowedActions,blockers,asOf`等适用字段；allowedActions仅辅助展示，每个命令仍重新鉴权和校验。异步以`operationNo,status=PENDING/UNKNOWN/SUCCEEDED/FAILED`区分，具体领域状态不混用。
- 错误：下文错误码均为提案，需接入统一ErrorCode/多语言；含`code,fieldErrors,conflicts,retryable,traceNo`等标准可容纳信息，禁止异常堆栈、护理正文、完整身份及余额出现在通用日志。
- 核心副作用：创建/取消预约不等于建单、扣次、退款或履约；ServiceSubmission跨域编排必须留原号与状态，资金/来源未知时阻断受影响开工、来源替换或关闭，不以一次重试生成新来源。

## 三、模块总表

| 模块 | 入口端 | 接入归属 | 条目 | 功能数 |
|---|---|---|---:|---:|
| A 门店与营业配置 | 前/管/B | 行业 | 5 | 5 |
| B 项目与目录 | C/前/技/管/B | 行业 | 9 | 6 |
| C 共用人员与权限 | 前/技/管/B | 共用 | 8 | 5 |
| D 资源与排班 | 前/技/管/B | 共用 | 10 | 6 |
| E 顾客与护理 | C/前/技/管/B | 行业 | 8 | 5 |
| F 预约与改约 | C/前/技/管/B | 行业 | 16 | 6 |
| G 到店与接待 | C/前/技/管/B | 行业 | 8 | 5 |
| H 工单与任务执行 | C/前/技/管/B | 行业 | 16 | 8 |
| I 共用交易与收银 | C/前/管/B | 核心 | 7 | 7 |
| J 储值 | C/前/管/B | 共用 | 6 | 7 |
| K 疗程与权益 | C/前/技/管/B | 共用 | 9 | 8 |
| L 营销 | C/前/管/B | 共用 | 5 | 5 |
| M 实物库存与耗材 | C/前/技/管/B | 共用 | 5 | 5 |
| N 售后与争议 | C/前/技/管/B | 行业 | 7 | 6 |
| O 业绩与提成 | 前/技/管/B | 行业 | 6 | 5 |
| P 班次财务 | C/前/管/B | 共用 | 5 | 5 |
| Q 查询与报表 | C/前/技/管/B | 行业 | 8 | 6 |
| R 消息待办回访 | C/前/技/管/B | 行业 | 7 | 5 |
| S 打印与设备 | 前/技/管/B | 共用 | 5 | 5 |
| T 治理与恢复 | 平/管/B | 支撑 | 10 | 8 |

## 四、逐模块接口

入参列列出业务字段；所有分页列表均加page/size，所有变更均适用身份、幂等和版本规则。`?`表示可选，`/`表示备选而非拼接路径；复杂领域校验见上游对象与数据库方案。共用合同的操作必须回到所有者执行，不能在美业重写。

### A 门店与营业配置

入口端：前/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-001<br>BTY-A01、BTY-A03、BTY-A04<br>P1 | `GET /biz/x/beauty/store-policies` | 查询营业与预约规则<br>storeNo,at | StorePolicyVO含版本和有效能力<br>已实现；部署与验收边界见后端开发计划§8 |
| BAPI-002<br>BTY-A03、BTY-A04<br>P1 | `POST /biz/x/beauty/store-policies/versions` | 提交营业/预约窗口与先付规则版本<br>expectedRevision,validFrom,policy | PolicyVersionVO<br>已实现；部署与验收边界见后端开发计划§8 |
| BAPI-003<br>BTY-A02<br>P1 | `CONTRACT merchant.store-context` | 列出并切换受权门店/岗位<br>当前账号,目标门店/岗位 | StoreContextVO与服务端重建权限<br>共用合同接入，路径待核对 |
| BAPI-004<br>BTY-A05<br>P4 | `POST /biz/x/beauty/capability-applications` | 申请高级经营能力<br>storeNo,capability,evidence | CapabilityApplicationVO不直接启用<br>美业候选新接口 |
| BAPI-144<br>BTY-A01、BTY-A03、BTY-A04<br>P1 | `GET /mp/x/beauty/stores/{storeNo}` | 读取公开门店营业与预约须知<br>storeNo | PublicStoreVO<br>代码已实现；BM-01后端模块隔离部署通过，详见主TDD§22 |

### B 项目与目录

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-005<br>BTY-B01、BTY-B02<br>P1 | `CONTRACT product.catalog-and-store-offer` | 共用商品SKU/媒体/上架与门店价维护<br>productNo,skuNo,storeNo,version | ProductVO/StoreOfferVO<br>共用合同接入，路径待核对 |
| BAPI-006<br>BTY-B03<br>P1 | `CONTRACT catalog.sales-calendar` | 维护分组排序与销售时段<br>StoreSalesCatalogPort.PublishReq(expectedRevision,validFrom,timezone,groups,entries)；当前范围/actor由可信上下文取得 | StoreSalesCatalogPort.Snapshot<br>已编码；product所有者GET /biz/store-sales-catalog与POST /biz/store-sales-catalog/versions；BM-01隔离部署关口已通过，详见主TDD§22 |
| BAPI-007<br>BTY-B04<br>P1 | `GET /biz/x/beauty/service-definitions` | 查项目作业定义<br>storeNo,skuNo,at,page?,size? | PageData<ServiceDefinitionVO><br>已实现；部署与验收边界见后端开发计划§8 |
| BAPI-008<br>BTY-B04<br>P1 | `POST /biz/x/beauty/service-definitions/versions` | 提交项目时长缓冲资格资源要求<br>skuNo,expectedRevision,duration,requirements,notes?(最多20条非空文本，每条最多500字符；默认空列表) | ServiceDefinitionVersionVO<br>已实现；部署与验收边界见后端开发计划§8 |
| BAPI-009<br>BTY-B05<br>P1 | `GET /biz/x/beauty/services/{skuNo}` | 查项目展示及适用须知<br>storeNo | ServiceDetailVO<br>代码已实现；BM-01后端模块隔离部署通过，详见主TDD§22 |
| BAPI-010<br>BTY-B05<br>P1 | `POST /biz/x/beauty/availability/queries` | 查询项目可约组合<br>storeNo,items,from,to,requestedPersonnelNo | AvailabilityVO含候选组合与过期时间<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-011<br>BTY-B06<br>P4 | `CONTRACT quote.configured-options` | 复杂选配与补差报价<br>正规SKU与选配版本,买家 | QuoteVO须正式合同开放<br>共用合同接入，路径待核对 |
| BAPI-145<br>BTY-B05<br>P1 | `GET /mp/x/beauty/services/{skuNo}` | 读取公开服务详情<br>storeNo,skuNo | PublicServiceVO<br>代码已实现；BM-01后端模块隔离部署通过，详见主TDD§22 |
| BAPI-146<br>BTY-B05<br>P1 | `POST /mp/x/beauty/availability/queries` | 查询公开可约时段及可选技师<br>storeNo,items,from,to | PublicAvailabilityVO<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |

### C 共用人员与权限

入口端：前/技/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-012<br>BTY-C01、BTY-C03<br>P1 | `GET /biz/personnel` | 查人员公开或内部档案<br>PersonnelQuery(page,size,status,keyword,storeNo?,skill?=goodsNo)；当前店、biz:store:admin | PageData<PersonnelVO>按用途脱敏<br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-013<br>BTY-C01<br>P1 | `POST /biz/personnel` | 建立可无登录的业务人员<br>CreatePersonnelReq(displayName,avatarRef?,assignment)；无登录建档，初始当前店任职/岗位/资格/STAFF原子写 | PersonnelVO<br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-014<br>BTY-C01、BTY-C05<br>P1 | `POST /biz/personnel/{personnelNo}/status-changes` | 人员停用与在途影响协调<br>ChangePersonnelStatusReq(expectedRevision,status,reason)；owner，未处理影响409，账号根锁与实际停用回查 | PersonnelChangeVO含受影响预约/任务<br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-015<br>BTY-C01<br>P1 | `POST /biz/personnel/{personnelNo}/account-links` | 绑定/解绑可选登录账号<br>LinkPersonnelAccountReq(expectedRevision,accountNo?)；owner；null显式解绑，保留旧/新关联审计 | PersonnelAccountLinkVO<br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-016<br>BTY-C01、BTY-C03<br>P1 | `POST /biz/personnel/{personnelNo}/assignments` | 维护任职岗位与资格有效期<br>AssignPersonnelReq(expectedRevision,assignmentNo?,storeNo,validFrom,validTo?,publicVisible,levelCode?,sortOrder,positions,skills[goodsNo,skuNo,levelCode?]) | PersonnelAssignmentVO<br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-017<br>BTY-C02<br>P1 | `CONTRACT merchant.roles-and-permissions` | 维护账号门店角色及操作范围<br>accountNo,storeNo,roleCode,permissionScope | RoleGrantVO不改变业务岗位<br>共用合同接入，路径待核对 |
| BAPI-018<br>BTY-C04<br>P3 | `POST /biz/personnel-cooperations` | 维护技师助理顾问与顾客负责范围<br>personnelNos,customerScope,validity | PersonnelCooperationVO<br>共用候选新接口 |
| BAPI-019<br>BTY-C05<br>P1 | `CONTRACT identity.session-revoke` | 撤销离职人员关联账号会话<br>accountNo,reason | SessionRevocationVO与审计<br>共用合同接入，路径待核对 |

### D 资源与排班

入口端：前/技/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-020<br>BTY-D01<br>P1 | `GET /biz/resources` | 查询规范人员与物理资源<br>ResourceQuery(page,size,storeNo?,type?,status?,keyword?)；当前店物理资源及有效/未来任职STAFF | PageData<ResourceVO><br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-021<br>BTY-D01<br>P1 | `POST /biz/resources` | 维护资源及容量<br>SaveResourceReq(resourceNo?,type,personnelNo=null,storeNo?,name,capacity,status,expectedRevision)；仅ROOM/SEAT/DEVICE，身份不可变，强制Idempotency-Key | ResourceVO<br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-022<br>BTY-D02<br>P1 | `GET /biz/schedules` | 查询班表与营业例外<br>ScheduleQuery(resourceNos,from,to)；1..50规范号、最多31日；周规则写入口见实施补充 | ScheduleVO（当前店ACTIVE规则、展开窗口、resourceRevisions）<br>BM-02 P1后端模块隔离部署通过；P3例外审批/预约/容量许可仍后续，见主TDD§25 |
| BAPI-023<br>BTY-D02、BTY-D04<br>P3 | `POST /biz/schedule-changes/previews` | 预览请假加班停约对预约任务影响<br>resourceNos,intervals,changes | ScheduleImpactVO<br>共用候选新接口 |
| BAPI-024<br>BTY-D02、BTY-D04<br>P3 | `POST /biz/schedule-changes` | 提交排班变更并生成协调待办<br>previewNo,expectedRevisions,reason | ScheduleChangeVO<br>共用候选新接口 |
| BAPI-025<br>BTY-D03<br>P1 | `GET /biz/x/beauty/workbench/calendar` | 查询日历统一快照<br>storeNo,from,to,view,resourceTypes,resourceCursor | CalendarSnapshotVO见工作台专篇<br>美业候选新接口 |
| BAPI-026<br>BTY-D03<br>P1 | `GET /biz/x/beauty/workbench/calendar/pages` | 续取同一快照资源泳道<br>snapshotToken,resourceCursor | CalendarPageVO过期要求重载<br>美业候选新接口 |
| BAPI-027<br>BTY-D05<br>P1 | `POST /biz/x/beauty/resource-changes/previews` | 预览资源替换或超时延长<br>targetRef,newAssignments,newEndAt,expectedRevision | ResourceChangePreviewVO<br>美业候选新接口 |
| BAPI-028<br>BTY-D05<br>P1 | `POST /biz/x/beauty/resource-changes` | 原子提交完整资源替换/延长<br>previewNo,expectedRevision,reason | ResourceChangeResultVO或冲突<br>美业候选新接口 |
| BAPI-029<br>BTY-D06<br>P4 | `POST /biz/x/beauty/group-scheduling/previews` | 团体并行及路途缓冲规划<br>participants,items,locations,requirements | GroupSchedulePreviewVO仅规划<br>美业候选新接口 |

### E 顾客与护理

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-030<br>BTY-E01<br>P2 | `CONTRACT member.identification` | 共用会员码识别/身份核验<br>memberToken,purpose,storeNo | CustomerIdentityVO非资产扣款授权<br>共用合同接入，路径待核对 |
| BAPI-031<br>BTY-E02<br>P1 | `POST /biz/x/beauty/guest-registrations` | 登记临客及后续本人认领<br>contact,displayName,consent | GuestRegistrationVO不伪造买家<br>美业候选新接口 |
| BAPI-032<br>BTY-E02<br>P1 | `POST /biz/x/beauty/guest-registrations/{guestNo}/claims` | 本人认领临客预约/服务<br>verifiedIdentity,claimToken | GuestClaimVO<br>美业候选新接口 |
| BAPI-033<br>BTY-E03<br>P1 | `CONTRACT member.profile-and-history` | 顾客档案会员关系和受权历史<br>customerNo,purpose,storeNo | CustomerProfileVO<br>共用合同接入，路径待核对 |
| BAPI-034<br>BTY-E04<br>P3 | `GET /biz/x/beauty/care-profiles/{customerNo}` | 读取最少必要护理提醒<br>purpose,taskNo? | CareProfileVO按字段授权<br>美业候选新接口 |
| BAPI-035<br>BTY-E04<br>P3 | `POST /biz/x/beauty/care-profiles/{customerNo}/notes` | 追加护理事实及更正引用<br>taskNo,content,consentRef,correctsNoteNo? | CareNoteVO<br>美业候选新接口 |
| BAPI-036<br>BTY-E05<br>P3 | `POST /biz/x/beauty/care-profiles/{customerNo}/media-authorizations` | 申请私有护理媒体上传/读取<br>taskNo,action,mediaNo?,purpose | 短期MediaAuthorizationVO<br>美业候选新接口 |
| BAPI-037<br>BTY-E05<br>P3 | `POST /biz/x/beauty/care-profiles/{customerNo}/privacy-requests` | 申请撤权留存或合法删除<br>requestType,scope,reason | PrivacyRequestVO<br>美业候选新接口 |

### F 预约与改约

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-038<br>BTY-F01、BTY-F02<br>P1 | `POST /biz/x/beauty/appointments/previews` | 校验预约要求并预览资源组合<br>storeNo,customerRef,items,requestedAssignments,startAt | AppointmentPreviewVO无占位承诺<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-039<br>BTY-F01、BTY-F02<br>P1 | `POST /biz/x/beauty/appointments` | 占齐完整资源并创建无交易或有来源预约<br>previewNo,customerRef,sourceRefs?,consentRef? | AppointmentVO与资源确认结果<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-040<br>BTY-F03<br>P1 | `GET /biz/x/beauty/appointments` | 查预约列表<br>storeNo,from,to,status,personnelNo,page,size,assignmentState | PageData<AppointmentSummaryVO><br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-041<br>BTY-F03<br>P1 | `GET /biz/x/beauty/appointments/{appointmentNo}` | 查预约任务资源时间线详情<br>appointmentNo | AppointmentDetailVO含版本与可用动作<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-042<br>BTY-F04<br>P1 | `POST /biz/x/beauty/appointments/{appointmentNo}/changes/previews` | 改时段/项目/指定人员预览<br>expectedRevision,items,startAt,assignments | AppointmentChangePreviewVO含报价影响<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-043<br>BTY-F04<br>P1 | `POST /biz/x/beauty/appointments/{appointmentNo}/changes` | 原子改约及资源替换<br>previewNo,expectedRevision,reason,consentRef? | AppointmentVO保留旧安排版本<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-044<br>BTY-F05<br>P1 | `POST /biz/x/beauty/appointments/{appointmentNo}/cancellations` | 取消预约释放资源并协调来源<br>expectedRevision,reason | CancellationVO含来源处理进度<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-045<br>BTY-F05<br>P1 | `POST /biz/x/beauty/appointments/{appointmentNo}/no-shows` | 记录爽约事实<br>expectedRevision,reason,evidenceRef? | NoShowVO不自动扣款扣次<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-046<br>BTY-F06<br>P4 | `CONTRACT booking.deposit-and-penalty-policy` | 定金尾款与违约合同<br>policyVersion,sourceRef,customerConsent | 合同待定默认关闭<br>共用合同接入，路径待核对 |
| BAPI-147<br>BTY-F01<br>P1 | `POST /mp/x/beauty/appointments/previews` | 本人预约预览<br>storeNo,items,startAt,requestedPersonnelNo | AppointmentPreviewVO<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-148<br>BTY-F01<br>P1 | `POST /mp/x/beauty/appointments` | 本人提交预约并占齐资源<br>previewNo,sourceRefs?,consentRef? | MyAppointmentVO<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-149<br>BTY-F03<br>P1 | `GET /mp/x/beauty/appointments` | 我的预约列表<br>from,to,status,page,size | PageData<MyAppointmentVO><br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-150<br>BTY-F03<br>P1 | `GET /mp/x/beauty/appointments/{appointmentNo}` | 本人预约详情<br>appointmentNo | MyAppointmentDetailVO<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-151<br>BTY-F04<br>P1 | `POST /mp/x/beauty/appointments/{appointmentNo}/changes/previews` | 本人改约预览<br>expectedRevision,startAt,items,requestedPersonnelNo | AppointmentChangePreviewVO<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-152<br>BTY-F04<br>P1 | `POST /mp/x/beauty/appointments/{appointmentNo}/changes` | 本人提交改约<br>previewNo,expectedRevision,consentRef? | MyAppointmentVO<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |
| BAPI-153<br>BTY-F05<br>P1 | `POST /mp/x/beauty/appointments/{appointmentNo}/cancellations` | 本人取消预约<br>expectedRevision,reason | CancellationVO<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |

### G 到店与接待

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-047<br>BTY-G01<br>P1 | `POST /biz/x/beauty/arrivals` | 核对身份并到店签到<br>appointmentNo,identityProof | ArrivalVO不履约核销<br>美业候选新接口 |
| BAPI-048<br>BTY-G02<br>P1 | `POST /biz/x/beauty/walk-ins` | 直客接待并安排即时资源<br>ReceiveWalkInReq(storeNo,customerRef,guestNo,items,assignments,startAt); exactly one customerRef/guestNo | WalkInVO(operationNo,operationState,appointmentNo,workOrder,startAt,recoveryAction); PENDING workOrder=null; frozen selectedStartAt UTC<br>Local Service Task55D review pending; HTTP/remote wire Task59 |
| BAPI-049<br>BTY-G03<br>P3 | `GET /biz/x/beauty/waiting-tickets` | 查询等候队列<br>storeNo,status,page,size | PageData<WaitingTicketVO><br>美业候选新接口 |
| BAPI-050<br>BTY-G03<br>P3 | `POST /biz/x/beauty/waiting-tickets` | 取号加入等候<br>customerRef,items | WaitingTicketVO<br>美业候选新接口 |
| BAPI-051<br>BTY-G03<br>P3 | `POST /biz/x/beauty/waiting-tickets/{ticketNo}/actions` | 叫号过号取消等候<br>action,expectedRevision | WaitingTicketVO<br>美业候选新接口 |
| BAPI-052<br>BTY-G04<br>P1 | `POST /biz/x/beauty/appointments/{appointmentNo}/work-orders` | 预约转工单并确认接待安排<br>expectedRevision,assignments,startAt? | WorkOrderVO幂等返回原工单<br>Task55C本地Service已实现；公开HTTP与remote接线待Task59 |
| BAPI-053<br>BTY-G05<br>P1 | `POST /biz/x/beauty/reception-exceptions` | 迟到早到及无法服务协调<br>appointmentNo,kind,proposal,reason | ReceptionExceptionVO含受影响待办<br>美业候选新接口 |
| BAPI-154<br>BTY-G01<br>P1 | `POST /mp/x/beauty/arrivals` | 本人到店签到<br>appointmentNo,checkInProof | MyArrivalVO<br>美业候选新接口 |

### H 工单与任务执行

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-054<br>BTY-H01<br>P1 | `POST /biz/x/beauty/work-orders` | 建立工单项目行及来源提交意图<br>customerRef?,storeNo,items,appointmentNo?,guestNo?,expectedRevision?,assignments,startAt? | WorkOrderVO<br>Task55C本地Service已实现；公开HTTP与remote接线待Task59 |
| BAPI-055<br>BTY-H01<br>P1 | `GET /biz/x/beauty/work-orders/{workOrderNo}` | 查工单任务来源与进度<br>workOrderNo | WorkOrderDetailVO<br>Task55C本地Service已实现；公开HTTP与remote接线待Task59 |
| BAPI-056<br>BTY-H01、BTY-H03<br>P1 | `POST /biz/x/beauty/work-orders/{workOrderNo}/service-submissions` | 提交新项目/加项及可信来源绑定流程<br>expectedRevision,expectedScopeRevision,tasks(taskNo,expectedRevision,expectedBindingRevision),additions(clientItemRef,skuNo,definitionVersionNo,quantity),source(PREPARE or EXISTING); no buyer/amount/customerConsent | ServiceSubmissionVO含恢复号<br>Task58 local service kernel implemented; independent review pending; HTTP/remote remains Task59; canStart=false |
| BAPI-057<br>BTY-H02<br>P1 | `GET /biz/x/beauty/tasks` | 查询我的或受权任务<br>storeNo,from,to,status,personnelNo,page,size,assignmentState | PageData<ServiceTaskVO><br>美业候选新接口 |
| BAPI-058<br>BTY-H02<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/acceptances` | 接单<br>expectedRevision | TaskActionResultVO<br>美业候选新接口 |
| BAPI-059<br>BTY-H02<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/starts` | 复核来源资格资源后开始服务<br>expectedRevision,expectedBindingRevision | TaskActionResultVO含实际开始时间<br>美业候选新接口 |
| BAPI-060<br>BTY-H02<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/steps/{stepNo}/completions` | 提交步骤进度<br>expectedRevision,completedQuantity,evidenceRef? | ServiceStepVO<br>美业候选新接口 |
| BAPI-061<br>BTY-H04<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/handovers/previews` | 预览换人交接与资格资源影响<br>expectedRevision,newPersonnelNo,role,effectiveAt | TaskHandoverPreviewVO<br>美业候选新接口 |
| BAPI-062<br>BTY-H04<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/handovers` | 提交交接并追加实际参与区间<br>previewNo,expectedRevision,reason | TaskHandoverVO<br>美业候选新接口 |
| BAPI-063<br>BTY-H05<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/interruptions` | 暂停/恢复/提前结束及事实记录<br>expectedRevision,action,reason,actualQuantity | TaskActionResultVO暂停不默认释放资源<br>美业候选新接口 |
| BAPI-064<br>BTY-H06<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/completions` | 提交真实完成数量与核心履约申请<br>expectedRevision,bindingRevision,quantity,consentRef? | TaskCompletionVO分开服务和履约状态<br>美业候选新接口 |
| BAPI-065<br>BTY-H07<br>P1 | `POST /biz/x/beauty/work-orders/{workOrderNo}/closure-previews` | 检查真实任务/来源版本和未结项<br>expectedRevision | ClosurePreviewVO含阻断项<br>美业候选新接口 |
| BAPI-066<br>BTY-H07<br>P1 | `POST /biz/x/beauty/work-orders/{workOrderNo}/closures` | 冻结范围后关闭并释放占用<br>previewNo,expectedRevision | WorkOrderClosureVO<br>美业候选新接口 |
| BAPI-067<br>BTY-H08<br>P4 | `POST /biz/x/beauty/work-orders/{workOrderNo}/stage-plans` | 跨日多阶段并行与上门作业计划<br>stageItems,resourceRequirements,expectedRevision | StagePlanVO仅能力开放后<br>美业候选新接口 |
| BAPI-155<br>BTY-H01、BTY-H02、BTY-H06<br>P1 | `GET /mp/x/beauty/work-orders/{workOrderNo}` | 本人服务任务及完成进度<br>workOrderNo | MyWorkOrderVO<br>Task55C本地Service已实现；公开HTTP与remote接线待Task59 |
| BAPI-159<br>BTY-H01、BTY-H02<br>P1 | `GET /biz/x/beauty/tasks/{taskNo}` | 查询具体任务步骤与安排/实际参与摘要<br>taskNo | ServiceTaskDetailVO含来源状态及允许动作<br>美业候选新接口 |

### I 共用交易与收银

入口端：C/前/管/B；接入归属：核心。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-068<br>BTY-I01<br>P1 | `CONTRACT trade.quote-and-create` | 核心报价建单及买家接管<br>storeNo,buyerRef,skuLines,quoteVersion | OrderVO美业仅记来源引用<br>共用合同接入，路径待核对 |
| BAPI-069<br>BTY-I02<br>P1 | `CONTRACT payment.initiate-and-query` | 顾客付款和原支付号查结果<br>orderNo,payMethod,customerConsent | PaymentVO由资金域判定结果<br>共用合同接入，路径待核对 |
| BAPI-070<br>BTY-I03<br>P1 | `CONTRACT payment.offline-receipt` | 正式合同允许的线下实收确认<br>orderNo,method,amount,evidence,operator | ReceiptVO禁止代消费者线上确认<br>共用合同接入，路径待核对 |
| BAPI-071<br>BTY-I04<br>P1 | `GET /biz/x/beauty/work-orders/{workOrderNo}/bill` | 汇总逐来源应收实收与未结<br>workOrderNo | WorkOrderBillVO: nullable per-source/per-currency due/gross received/refunded/net/outstanding, completeness/blockers; partial allocation WHOLE_SOURCE is not attributable aggregate<br>Task58 local service kernel implemented; independent review pending; HTTP/remote remains Task59; canStart=false |
| BAPI-072<br>BTY-I05<br>P1 | `GET /biz/x/beauty/service-submissions/{submissionNo}` | 查提交意图与来源绑定进度<br>submissionNo | ServiceSubmissionVO: original operation, immutable selected tasks and actual bindings, highest/last verified pair; GET observes only, never binds or pays<br>Task58 local service kernel implemented; independent review pending; HTTP/remote remains Task59; canStart=false |
| BAPI-073<br>BTY-I05<br>P1 | `POST /biz/x/beauty/service-submissions/{submissionNo}/recovery-requests` | 申请原号恢复查单及绑定<br>expectedRevision,reason | RecoveryRequestVO: immutable accepted request receipt; authenticated original recovery dispatch does not generate new purchase/payment identity<br>Task58 local service kernel implemented; independent review pending; HTTP/remote remains Task59; canStart=false |
| BAPI-074<br>BTY-I06、BTY-I07<br>P4 | `CONTRACT cashier.advanced-contracts` | 混合/跨单/后付/定金/免单/改价<br>已批准政策及原来源,本人授权 | 待正式合同不设执行路由<br>共用合同接入，路径待核对 |

### J 储值

入口端：C/前/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-075<br>BTY-J01<br>P2 | `CONTRACT stored-value.plans` | 共用独立充值方案版本启停<br>storeNo,planNo,version,scope | RechargePlanVO<br>共用合同接入，路径待核对 |
| BAPI-076<br>BTY-J02<br>P2 | `CONTRACT stored-value.recharges` | 创建提交实收确认充值及查原号<br>customerNo,storeNo,planVersion,receipt | RechargeVO仅正式渠道<br>共用合同接入，路径待核对 |
| BAPI-077<br>BTY-J03<br>P2 | `CONTRACT stored-value.accounts` | 本店账户目录及余额冻结<br>storeNo,customerNo,page,size | PageData<StoredValueAccountVO><br>共用合同接入，路径待核对 |
| BAPI-078<br>BTY-J04<br>P2 | `CONTRACT stored-value.consumptions` | 本次服务来源全额消费预览/本人确认<br>sourceRef,storeNo,memberAuthorization | ConsumptionVO不混合补现金<br>共用合同接入，路径待核对 |
| BAPI-079<br>BTY-J05、BTY-J06<br>P2 | `CONTRACT stored-value.history` | 本人门店消费记录及商家统一收支<br>storeNo,customerNo,from,to,page,size | PageData<StoredValueRecordVO><br>共用合同接入，路径待核对 |
| BAPI-080<br>BTY-J07<br>P2 | `CONTRACT stored-value.recovery` | 已收未入账/冻结/扣款未知原号恢复<br>rechargeNo/consumptionNo,reason | RecoveryVO不手调余额<br>共用合同接入，路径待核对 |

### K 疗程与权益

入口端：C/前/技/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-081<br>BTY-K01<br>P4 | `CONTRACT entitlement.rules` | 共用次数规则适用范围有效期<br>ruleNo,version,skuScope,quantity,validity | EntitlementRuleVO<br>共用合同接入，路径待核对 |
| BAPI-082<br>BTY-K02<br>P4 | `CONTRACT product.treatment-card-offers` | 疗程销售SKU及门店价格<br>ruleVersion,skuNo,storeNo | StoreOfferVO<br>共用合同接入，路径待核对 |
| BAPI-083<br>BTY-K03<br>P4 | `CONTRACT entitlement.sale-and-issuance` | 正式售卡来源及幂等发行进度<br>salesSourceRef,ruleVersion,customerNo | IssuanceVO发行和收款分别显示<br>共用合同接入，路径待核对 |
| BAPI-084<br>BTY-K04<br>P2 | `CONTRACT entitlement.eligible-assets` | 本人已持有合格权益和余次<br>customerNo,storeNo,skuNo | EligibleEntitlementVO<br>共用合同接入，路径待核对 |
| BAPI-085<br>BTY-K05<br>P2 | `CONTRACT entitlement.service-consumption` | 本次权益使用预览授权预占核销和恢复<br>sourceRef,entitlementNo,quantity,customerConsent | EntitlementConsumptionVO<br>共用合同接入，路径待核对 |
| BAPI-086<br>BTY-K06<br>P4 | `GET /biz/x/beauty/treatment-plans` | 查疗程计划与每次服务履历<br>customerNo,storeNo,page,size | PageData<TreatmentPlanVO><br>美业候选新接口 |
| BAPI-087<br>BTY-K06<br>P4 | `POST /biz/x/beauty/treatment-plans` | 创建分次服务计划<br>customerNo,entitlementRef?,sessions | TreatmentPlanVO计划不提前扣次<br>美业候选新接口 |
| BAPI-088<br>BTY-K07、BTY-K08<br>P4 | `CONTRACT entitlement.policy-cases` | 有效期次数申诉退转卡延期跨店政策<br>assetRef,caseType,evidence,approvedPolicy | CaseVO未定政策只登记不执行<br>共用合同接入，路径待核对 |
| BAPI-156<br>BTY-K06<br>P4 | `GET /mp/x/beauty/treatment-plans` | 本人疗程计划与每次履历<br>page,size | PageData<MyTreatmentPlanVO><br>美业候选新接口 |

### L 营销

入口端：C/前/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-089<br>BTY-L01<br>P1 | `CONTRACT promotion.eligible-and-quote` | 查询选择合格券并核心报价核验<br>buyerRef,skuLines,storeNo | EligiblePromotionVO/QuoteVO<br>共用合同接入，路径待核对 |
| BAPI-090<br>BTY-L02<br>P3 | `CONTRACT promotion.campaign-versions` | 活动草稿发布资格预算<br>campaignNo,version,scope,budget | CampaignVO<br>共用合同接入，路径待核对 |
| BAPI-091<br>BTY-L03<br>P3 | `CONTRACT member.tags-and-audiences` | 标签人群及受控经营名单<br>customerScope,purpose,tagChanges | AudienceVO<br>共用合同接入，路径待核对 |
| BAPI-092<br>BTY-L04<br>P3 | `CONTRACT entitlement.grant` | 受权赠券赠次发行查结果<br>grantSourceNo,ruleVersion,recipients | GrantVO不等同付费售卡<br>共用合同接入，路径待核对 |
| BAPI-093<br>BTY-L05<br>P3 | `CONTRACT analytics.campaign-conversion` | 活动预约回访转化统计<br>storeNo,period,campaignNo | CampaignReportVO<br>共用合同接入，路径待核对 |

### M 实物库存与耗材

入口端：C/前/技/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-094<br>BTY-M01<br>P1 | `CONTRACT trade.goods-inventory-delivery` | 实物报价库存承诺交付和售后<br>skuNo,storeNo,quantity,sourceRef | OrderVO/FulfillmentVO<br>共用合同接入，路径待核对 |
| BAPI-095<br>BTY-M02<br>P3 | `CONTRACT inventory.stock-operations` | 库存收货调拨盘点<br>storeNo,skuLines,operation,expectedRevision | StockDocumentVO<br>共用合同接入，路径待核对 |
| BAPI-096<br>BTY-M03<br>P3 | `CONTRACT inventory.service-material-use` | 按任务领料和实际耗用<br>taskNo,skuLines,operator,sourceNo | MaterialUsageVO<br>共用合同接入，路径待核对 |
| BAPI-097<br>BTY-M04<br>P3 | `CONTRACT inventory.return-and-scrap` | 退回报损质量处置<br>originalDocumentNo,lines,quality,reason | InventoryDispositionVO<br>共用合同接入，路径待核对 |
| BAPI-098<br>BTY-M05<br>P4 | `CONTRACT inventory.bom-and-cost` | 项目耗材BOM版本及成本差异<br>serviceSkuNo,bomVersion,period | BomVO/CostReportVO<br>共用合同接入，路径待核对 |

### N 售后与争议

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-099<br>BTY-N01<br>P1 | `CONTRACT aftersale.standard` | 普通交易售后材料审核进度<br>orderNo,lineNos,reason,evidence | AfterSaleVO不适用于任意资产退款<br>共用合同接入，路径待核对 |
| BAPI-100<br>BTY-N02<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/cancellations` | 未开始任务撤销及资源来源协调<br>expectedRevision,reason | TaskCancellationVO源取消另查进度<br>美业候选新接口 |
| BAPI-101<br>BTY-N03<br>P1 | `POST /biz/x/beauty/tasks/{taskNo}/service-evidence` | 追加完成数量提前结束质量事实<br>expectedRevision,kind,quantity,evidenceRefs | ServiceEvidenceVO不可覆盖原事实<br>美业候选新接口 |
| BAPI-102<br>BTY-N04<br>P4 | `POST /biz/x/beauty/service-remedy-cases` | 申请返工补做部分退款赔付方案<br>taskNo,sourceRef,proposal,evidence | RemedyCaseVO审批不等于退款成功<br>美业候选新接口 |
| BAPI-103<br>BTY-N05<br>P2 | `POST /biz/x/beauty/asset-disputes` | 登记储值次数争议形成财务待办<br>sourceRef,assetUseNo,reason,evidence | AssetDisputeVO保留原账<br>美业候选新接口 |
| BAPI-104<br>BTY-N06<br>P4 | `CONTRACT asset.reversal-contract` | 经正式合同执行资产逆向<br>originalUseNo,approvedCaseNo,policyVersion | ReversalVO默认关闭<br>共用合同接入，路径待核对 |
| BAPI-157<br>BTY-N03<br>P1 | `POST /mp/x/beauty/service-disputes` | 本人提交服务数量质量争议<br>taskNo,reason,evidenceRefs | ServiceDisputeVO<br>美业候选新接口 |

### O 业绩与提成

入口端：前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-105<br>BTY-O01<br>P1 | `GET /biz/x/beauty/tasks/{taskNo}/participations` | 查询实际参与与产出事实<br>taskNo | ParticipationVO含历史交接区间<br>美业候选新接口 |
| BAPI-106<br>BTY-O02<br>P3 | `GET /biz/x/beauty/commission-policies` | 查规则版本及基数口径<br>storeNo,at,page,size | PageData<CommissionPolicyVO><br>美业候选新接口 |
| BAPI-107<br>BTY-O02<br>P3 | `POST /biz/x/beauty/commission-policy-versions` | 维护业绩分配与提成版本<br>scope,basis,shares,validity,expectedRevision | CommissionPolicyVersionVO<br>美业候选新接口 |
| BAPI-108<br>BTY-O03<br>P3 | `GET /biz/x/beauty/commission-entries` | 查本人或受权提成明细<br>storeNo,personnelNo,period,page,size | PageData<CommissionEntryVO><br>美业候选新接口 |
| BAPI-109<br>BTY-O03、BTY-O04<br>P3 | `POST /biz/x/beauty/performance-adjustment-cases` | 提交异议换人/退款后更正<br>entryNo,sourceRevision,evidence,reason | AdjustmentCaseVO追加不覆盖<br>美业候选新接口 |
| BAPI-110<br>BTY-O05<br>P3 | `POST /biz/x/beauty/commission-settlement-records` | 登记提成结算审核与已发放凭证<br>entryNos,approvalRef,paymentEvidence | SettlementRecordVO不新增银行代发<br>美业候选新接口 |

### P 班次财务

入口端：C/前/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-111<br>BTY-P01<br>P1 | `CONTRACT finance.receipt-history` | 读取普通订单/充值/资产消费凭条<br>sourceRef,customerNo,storeNo | ReceiptVO按阶段开放<br>共用合同接入，路径待核对 |
| BAPI-112<br>BTY-P02<br>P3 | `CONTRACT cashier.shifts` | 开关班钱箱实盘交接<br>storeNo,shiftNo,cashCount,expectedRevision | ShiftVO<br>共用合同接入，路径待核对 |
| BAPI-113<br>BTY-P03<br>P3 | `CONTRACT finance.business-day-close` | 营业日范围水位日结签核<br>storeNo,businessDate,watermark,approval | DayCloseVO<br>共用合同接入，路径待核对 |
| BAPI-114<br>BTY-P04<br>P3 | `CONTRACT finance.channel-reconciliation` | 逐口径渠道对账<br>storeNo,businessDate,channel | ReconciliationVO<br>共用合同接入，路径待核对 |
| BAPI-115<br>BTY-P05<br>P3 | `CONTRACT finance.settlements-and-cases` | 结算票据费用差异与原号恢复<br>settlementNo,caseNo,authorizedAction | SettlementVO<br>共用合同接入，路径待核对 |

### Q 查询与报表

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-116<br>BTY-Q01<br>P1 | `GET /biz/x/beauty/customer-timelines/{customerNo}` | 本人或受权顾客预约服务来源时间线<br>storeNo,from,to,page,size | PageData<CustomerTimelineVO><br>美业候选新接口 |
| BAPI-117<br>BTY-Q02<br>P1 | `GET /biz/x/beauty/workbench/summary` | 今日接待待服务未结异常概览<br>storeNo,businessDate | WorkbenchSummaryVO含asOf独立水位<br>美业候选新接口 |
| BAPI-118<br>BTY-Q03<br>P3 | `CONTRACT analytics.money-separate-measures` | 现金充值资产消耗余额分口径报表<br>storeNo,period,currency | MoneyReportVO不可叠加为营收<br>共用合同接入，路径待核对 |
| BAPI-119<br>BTY-Q04<br>P3 | `GET /biz/x/beauty/reports/scheduling` | 预约转化爽约资源利用率<br>storeNo,from,to,resourceType | SchedulingReportVO含计划/实际口径<br>美业候选新接口 |
| BAPI-120<br>BTY-Q05<br>P3 | `GET /biz/x/beauty/reports/services` | 项目服务产出成本复购业绩查询<br>storeNo,period,dimensions | ServiceReportVO各事实水位<br>美业候选新接口 |
| BAPI-121<br>BTY-Q06<br>P3 | `POST /biz/x/beauty/report-exports` | 创建受权跨店导出任务<br>storeNos,reportType,filters,purpose | ReportExportVO含水位与到期下载<br>美业候选新接口 |
| BAPI-122<br>BTY-Q06<br>P3 | `GET /biz/x/beauty/report-exports/{exportNo}` | 查导出结果及更正水位<br>exportNo | ReportExportVO下载二次鉴权<br>美业候选新接口 |
| BAPI-158<br>BTY-Q01<br>P1 | `GET /mp/x/beauty/timeline` | 本人预约服务及来源历史<br>from,to,page,size | PageData<MyTimelineVO><br>美业候选新接口 |

### R 消息待办回访

入口端：C/前/技/管/B；接入归属：行业。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-123<br>BTY-R01、BTY-R03<br>P1 | `CONTRACT notification.delivery-and-preferences` | 预约付款通知规则订阅与送达查询<br>eventRef,templateVersion,recipientScope | DeliveryVO失败不回滚交易<br>共用合同接入，路径待核对 |
| BAPI-124<br>BTY-R02<br>P1 | `GET /biz/x/beauty/workbench/exceptions` | 查询派工超时冲突来源异常待办<br>storeNo,from,to,kind,page,size | PageData<ExceptionWorkitemVO><br>美业候选新接口 |
| BAPI-125<br>BTY-R02<br>P1 | `POST /biz/x/beauty/exception-workitems/{workitemNo}/resolutions` | 认领/协调/解决业务异常<br>expectedRevision,action,targetRef,reason | ExceptionResolutionVO不绕过原聚合校验<br>美业候选新接口 |
| BAPI-126<br>BTY-R04<br>P3 | `GET /biz/x/beauty/follow-up-tasks` | 查询护理回访任务<br>storeNo,personnelNo,status,page,size | PageData<FollowUpTaskVO><br>美业候选新接口 |
| BAPI-127<br>BTY-R04<br>P3 | `POST /biz/x/beauty/follow-up-tasks` | 建回访计划及分配<br>customerNo,taskNo,dueAt,assignee | FollowUpTaskVO<br>美业候选新接口 |
| BAPI-128<br>BTY-R04<br>P3 | `POST /biz/x/beauty/follow-up-tasks/{followUpNo}/entries` | 追加跟进和顾客回复<br>content,replyConsent,expectedRevision | FollowUpEntryVO<br>美业候选新接口 |
| BAPI-129<br>BTY-R05<br>P3 | `CONTRACT notification.marketing-consent` | 营销订阅频控受众退订<br>customerNo,channel,purpose,consent | NotificationPreferenceVO<br>共用合同接入，路径待核对 |

### S 打印与设备

入口端：前/技/管/B；接入归属：共用。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-130<br>BTY-S01<br>P1 | `POST /biz/x/beauty/print-documents` | 按真实来源生成脱敏文档快照<br>documentType,workOrderNo/sourceRef,templateVersion | PrintDocumentVO<br>美业候选新接口 |
| BAPI-131<br>BTY-S02<br>P1 | `CONTRACT device.print-routes-and-templates` | 设备路由模板版本与自检<br>storeNo,deviceNo,templateVersion | PrintConfigVO<br>共用合同接入，路径待核对 |
| BAPI-132<br>BTY-S01、BTY-S03<br>P1 | `CONTRACT printing.jobs` | 提交打印查询原任务和受控补打<br>documentNo,deviceNo,originalJobNo?,reason | PrintJobVO未知先查原号<br>共用合同接入，路径待核对 |
| BAPI-133<br>BTY-S04<br>P3 | `CONTRACT device.terminals` | 终端绑定撤销心跳最小权限<br>deviceNo,storeNo,accountScope,action | TerminalVO<br>共用合同接入，路径待核对 |
| BAPI-134<br>BTY-S05<br>P4 | `CONTRACT device.edge-service` | 签到机上门边缘设备协作<br>deviceSession,identityProof,authorizedIntent | EdgeActionVO不能离线扣资产<br>共用合同接入，路径待核对 |

### T 治理与恢复

入口端：平/管/B；接入归属：支撑。写操作还须矩阵对应的操/申/审/管权限。

| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |
|---|---|---|---|
| BAPI-135<br>BTY-T01<br>P1 | `GET /biz/x/beauty/capability-status` | 查询门店接线兼容和验收能力<br>storeNo,clientVersion | CapabilityStatusVO缺省关闭<br>美业候选新接口 |
| BAPI-136<br>BTY-T02<br>P1 | `CONTRACT audit.scoped-events` | 受控操作审计及代客身份追踪<br>subjectRef,storeScope,from,to,page,size | PageData<AuditEventVO>不含护理正文<br>共用合同接入，路径待核对 |
| BAPI-137<br>BTY-T03<br>P1 | `GET /biz/x/beauty/traces/{businessNo}` | 关联预约工单任务来源资金状态<br>businessNo | BusinessTraceVO via actual WorkOrderBillService.getTrace local method; target HTTP class remains unimplemented<br>Task58 local service kernel implemented; independent review pending; HTTP/remote remains Task59; canStart=false |
| BAPI-138<br>BTY-T04<br>P1 | `GET /ops/x/beauty/recovery-workitems` | 平台查已付未绑定资源事件积压<br>entityNo,storeNo,kind,page,size | PageData<RecoveryWorkitemVO><br>美业候选新接口 |
| BAPI-139<br>BTY-T04<br>P1 | `POST /ops/x/beauty/recovery-requests` | 申请原号技术恢复<br>workitemNo,expectedRevision,reason,approvalRef? | RecoveryRequestVO不能代完工代授权<br>美业候选新接口 |
| BAPI-140<br>BTY-T05<br>P1 | `CONTRACT observability.health-and-alerts` | 健康慢请求错误率设备存活与告警<br>serviceScope,timeRange,traceNo | HealthVO/AlertVO接已有观测设施<br>共用合同接入，路径待核对 |
| BAPI-141<br>BTY-T06<br>P3 | `CONTRACT governance.privacy-config-backup` | 隐私配置发布备份恢复受控流程<br>scope,changeNo,purpose,approvalRef | GovernanceJobVO不提供任意表写入<br>共用合同接入，路径待核对 |
| BAPI-142<br>BTY-T07<br>P4 | `CONTRACT integration.external-booking` | 第三方预约来源映射签名去重冲突<br>provider,externalNo,revision,signedPayload | ExternalBindingVO需独立合同<br>共用合同接入，路径待核对 |
| BAPI-143<br>BTY-T08<br>P4 | `CONTRACT offline.claim-and-sync` | 断网事实认领去重同步<br>deviceNo,localIntentNo,evidence | SyncClaimVO未验收不开放营业<br>共用合同接入，路径待核对 |
| BAPI-160<br>BTY-T03、BTY-T04<br>P1 | `GET /biz/x/beauty/operations/{operationNo}` | 查询异步命令的原号进度和阻断原因<br>operationNo | OperationVO(operationNo,appointmentNo,state,action,recoveryAction,workOrderNo,failureCode); original appointment semantics retained<br>已实现Task52端点与独立双向机器协议；TCP/装配已测，BM03部署验收待Task53 |

## 五、既有路由证据与兼容边界

| 已核查代码 | 已有路由举例 | 本方案如何使用 |
|---|---|---|
| [BizStaffController](../../../backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStaffController.java) | `GET/POST /biz/staff`；`POST /biz/staff/{mchAccountNo}/status`；`POST /biz/staff/{mchAccountNo}/store`；`GET /biz/roles` | 是账号与角色接口，不能当无登录业务人员、技能或排班接口；C模块人员候选接口另建，原账号协议保留 |
| [BizStoredValueController](../../../backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoredValueController.java) | `POST /biz/stored-value/recharges`；`POST /biz/stored-value/recharges/{rechargeNo}/submit`、`/receive`、`/cancel`；`GET /biz/stored-value/recharges/{rechargeNo}`；`GET /biz/stored-value/accounts`；`POST /biz/stored-value/consumptions/preview`、`/biz/stored-value/consumptions` | J模块候选接入，仍需验收门店隔离、真实来源、消费者授权和未知恢复。代码存在不等于所有门店已开通，不额外开放混付、线上退款或跨店 |
| [数据库方案中的现状核查](./美业系统-数据库结构设计.md#11-当前代码依据与旧稿修订) | 旧预约时段只含门店容量；`AppointmentSlotPort`不具备完整人员/资源匹配 | D/F共享资源仲裁需要新合同，不能仅包装旧tryBook就声明支持跨店人员防重约 |

其余CONTRACT项本轮按能力声明，不虚构已存在路径。实施拆分Controller应遵守[Java命名与包边界](../../../.claude/skills/ai-shop-dev-standards/references/java.md)，例如BizAppointmentController、BizServiceTaskController、BizPersonnelController；不要一个巨型BeautyApi承载20模块。

## 六、功能追溯与阶段闸门

| 功能 | 功能说明 | 阶段 | API/合同编号 |
|---|---|---|---|
| BTY-A01 | 门店门户、位置、营业时间和服务范围 | P1 | BAPI-001、BAPI-144 |
| BTY-A02 | 选择/切换受权门店及当前岗位 | P1 | BAPI-003 |
| BTY-A03 | 营业暂停、项目接待与预约窗口配置 | P1 | BAPI-001、BAPI-002、BAPI-144 |
| BTY-A04 | 先付单次服务规则及配置版本 | P1 | BAPI-001、BAPI-002、BAPI-144 |
| BTY-A05 | 上门、团体、后付、定金能力申请与控制 | P4 | BAPI-004 |
| BTY-B01 | 项目/护肤品建档、图片、SKU、上下架 | P1 | BAPI-005 |
| BTY-B02 | 门店价格及固定时长/等级规格维护 | P1 | BAPI-005 |
| BTY-B03 | 项目分组、展示顺序、销售时段与可见范围 | P1 | BAPI-006 |
| BTY-B04 | 项目时长、前后缓冲、技能与资源要求 | P1 | BAPI-007、BAPI-008 |
| BTY-B05 | 项目详情、适用说明、可约人员及今日可约查询 | P1 | BAPI-009、BAPI-010、BAPI-145、BAPI-146 |
| BTY-B06 | 项目附加项、复杂选配、补差和组合报价 | P4 | BAPI-011 |
| BTY-C01 | 共用人员建档/停用、可选账号关联与门店任职 | P1 | BAPI-012、BAPI-013、BAPI-014、BAPI-015、BAPI-016 |
| BTY-C02 | 岗位、操作范围、跨店和金额权限 | P1 | BAPI-017 |
| BTY-C03 | 业务岗位、技师公开档案、级别及有效服务资格 | P1 | BAPI-012、BAPI-016 |
| BTY-C04 | 服务助理/顾问协作关系和负责客户范围 | P3 | BAPI-018 |
| BTY-C05 | 离职/停用后的会话撤销、未来预约和任务交接 | P1 | BAPI-014、BAPI-019 |
| BTY-D01 | 技师、床位、座位、房间、设备资源维护 | P1 | BAPI-020、BAPI-021 |
| BTY-D02 | 营业日历、技师班表、休息与缓冲配置 | P1 | BAPI-022、BAPI-023、BAPI-024 |
| BTY-D03 | 今日排程、人员跨店统一空闲/占用及冲突查询 | P1 | BAPI-025、BAPI-026 |
| BTY-D04 | 请假、加班、临时停约及受影响预约处理 | P3 | BAPI-023、BAPI-024 |
| BTY-D05 | 改约/换人资源替换、超时延长与冲突待办 | P1 | BAPI-027、BAPI-028 |
| BTY-D06 | 团体同时占多资源、并行服务与上门路途缓冲 | P4 | BAPI-029 |
| BTY-E01 | 本人会员码展示与门店受控识别 | P2 | BAPI-030 |
| BTY-E02 | 临客登记、电话预约资料及到店认领 | P1 | BAPI-031、BAPI-032 |
| BTY-E03 | 顾客资料、会员关系和消费/服务历史 | P1 | BAPI-033 |
| BTY-E04 | 偏好、过敏/护理注意事项与资料访问控制 | P3 | BAPI-034、BAPI-035 |
| BTY-E05 | 护理照片、过程记录和资料撤权/留存管理 | P3 | BAPI-036、BAPI-037 |
| BTY-F01 | 按项目/任职/资格/排班/资源匹配技师、占位成约 | P1 | BAPI-038、BAPI-039、BAPI-147、BAPI-148 |
| BTY-F02 | 电话/微信代约及无交易预约 | P1 | BAPI-038、BAPI-039 |
| BTY-F03 | 我的预约、预约详情与门店预约日历 | P1 | BAPI-040、BAPI-041、BAPI-149、BAPI-150 |
| BTY-F04 | 改时间/项目/技师及原子资源替换 | P1 | BAPI-042、BAPI-043、BAPI-151、BAPI-152 |
| BTY-F05 | 取消、临时占位过期、爽约记录与资源释放 | P1 | BAPI-044、BAPI-045、BAPI-153 |
| BTY-F06 | 定金尾款、违约收费或爽约扣次政策 | P4 | BAPI-046 |
| BTY-G01 | 预约到店签到、身份核对和到店记录 | P1 | BAPI-047、BAPI-154 |
| BTY-G02 | 直客接待、即时资源安排和开工单 | P1 | BAPI-048 |
| BTY-G03 | 等候取号、过号、叫号与取消等候 | P3 | BAPI-049、BAPI-050、BAPI-051 |
| BTY-G04 | 分配接待技师、床位/房间和预约转工单 | P1 | BAPI-052 |
| BTY-G05 | 迟到、早到、无法服务与冲突协调 | P1 | BAPI-053 |
| BTY-H01 | 预约或直客开单、项目行和来源绑定 | P1 | BAPI-054、BAPI-055、BAPI-056、BAPI-155、BAPI-159 |
| BTY-H02 | 接单、开始服务、步骤进度与时间记录 | P1 | BAPI-057、BAPI-058、BAPI-059、BAPI-060、BAPI-155、BAPI-159 |
| BTY-H03 | 中途加项、新报价/来源和本人确认 | P1 | BAPI-056 |
| BTY-H04 | 资格资源复核、换人交接及实际参与区间留痕 | P1 | BAPI-061、BAPI-062 |
| BTY-H05 | 暂停、超时、提前结束与部分完成记录 | P1 | BAPI-063 |
| BTY-H06 | 单项完工、完成数量和核心履约申请 | P1 | BAPI-064、BAPI-155 |
| BTY-H07 | 工单结清检查、关闭和资源释放 | P1 | BAPI-065、BAPI-066 |
| BTY-H08 | 跨日、多阶段、多人并行及上门工单 | P4 | BAPI-067 |
| BTY-I01 | 基础报价、优惠校验、真实买家和门店落单 | P1 | BAPI-068 |
| BTY-I02 | 顾客线上支付/付款接管及结果查询 | P1 | BAPI-069 |
| BTY-I03 | 获准现金/转账等线下实收登记与确认 | P1 | BAPI-070 |
| BTY-I04 | 工单下来源汇总、逐笔付款和未结明细 | P1 | BAPI-071 |
| BTY-I05 | 支付处理中/未知、取消冲突与原号查单恢复 | P1 | BAPI-072、BAPI-073 |
| BTY-I06 | 多来源一次收款、混合支付、多人代付 | P4 | BAPI-074 |
| BTY-I07 | 定金/尾款、后付、免单与临时改价申请 | P4 | BAPI-074 |
| BTY-J01 | 独立充值方案创建、版本、启停和办理范围 | P2 | BAPI-075 |
| BTY-J02 | 识别顾客、选方案、核验实收、提交到账 | P2 | BAPI-076 |
| BTY-J03 | 本店顾客账户目录、余额与冻结查询 | P2 | BAPI-077 |
| BTY-J04 | 服务来源报价、全额合格储值消费本人确认 | P2 | BAPI-078 |
| BTY-J05 | 我的储值门店、核销记录及简明详情 | P2 | BAPI-079 |
| BTY-J06 | 本店充值/核销统一收支、筛选和单客详情 | P2 | BAPI-079 |
| BTY-J07 | 已收未入账、已扣待确认、冻结等异常恢复 | P2 | BAPI-080 |
| BTY-K01 | 疗程/次卡规则、适用项目、次数和有效期定义 | P4 | BAPI-081 |
| BTY-K02 | 疗程销售商品SKU、门店售价和上下架 | P4 | BAPI-082 |
| BTY-K03 | 售卡收款、按销售来源发行及发行进度 | P4 | BAPI-083 |
| BTY-K04 | 已持有合格次数权益、适用项目和剩余次数查询 | P2 | BAPI-084 |
| BTY-K05 | 单次使用预览、本人确认、预占/核销及原号恢复 | P2 | BAPI-085 |
| BTY-K06 | 疗程分次预约、服务计划与每次工单履历 | P4 | BAPI-086、BAPI-087、BAPI-156 |
| BTY-K07 | 有效期/次数异常申诉及受支持冲回处理 | P4 | BAPI-088 |
| BTY-K08 | 退卡/转卡/延期/跨店使用等政策与执行 | P4 | BAPI-088 |
| BTY-L01 | 已支持优惠/券查询、选择及核心报价校验 | P1 | BAPI-089 |
| BTY-L02 | 活动草稿、发布版本、资格与预算管理 | P3 | BAPI-090 |
| BTY-L03 | 会员标签、人群分组和顾客经营名单 | P3 | BAPI-091 |
| BTY-L04 | 赠券/赠次数权益的受权发行与结果 | P3 | BAPI-092 |
| BTY-L05 | 复购、预约转化、活动/回访效果统计 | P3 | BAPI-093 |
| BTY-M01 | 护肤品等实物选购、库存校验、交付和售后关联 | P1 | BAPI-094 |
| BTY-M02 | 库存查询、采购收货、调拨和盘点 | P3 | BAPI-095 |
| BTY-M03 | 按服务任务申请/登记耗材领用和实际耗用 | P3 | BAPI-096 |
| BTY-M04 | 退回、报损及可再售质量处置 | P3 | BAPI-097 |
| BTY-M05 | 项目耗材定额、BOM、成本和差异分析 | P4 | BAPI-098 |
| BTY-N01 | 普通交易售后申请、材料、审核和进度 | P1 | BAPI-099 |
| BTY-N02 | 未开始任务撤销、资源释放及原来源取消 | P1 | BAPI-100 |
| BTY-N03 | 已做/未做数量、提前结束及质量争议证据 | P1 | BAPI-101、BAPI-157 |
| BTY-N04 | 部分退款、返工补做及赔付方案 | P4 | BAPI-102 |
| BTY-N05 | 储值/次数使用争议登记和财务待办 | P2 | BAPI-103 |
| BTY-N06 | 资产冲回、退余额/退卡的后续正式合同执行 | P4 | BAPI-104 |
| BTY-O01 | 服务产出、参与技师/助理/顾问及归属事实 | P1 | BAPI-105 |
| BTY-O02 | 销售/服务业绩规则、分配比例和版本 | P3 | BAPI-106、BAPI-107 |
| BTY-O03 | 提成计算、明细、核对和异议申请 | P3 | BAPI-108、BAPI-109 |
| BTY-O04 | 换人、退款或资产冲正后的业绩/提成调整 | P3 | BAPI-109 |
| BTY-O05 | 提成结算、已发放留痕及后续调整 | P3 | BAPI-110 |
| BTY-P01 | 普通订单/充值/消费凭条与历史查询 | P1 | BAPI-111 |
| BTY-P02 | 开班、钱箱、现金实盘与交接差异 | P3 | BAPI-112 |
| BTY-P03 | 营业日切点、日结范围、水位及签核 | P3 | BAPI-113 |
| BTY-P04 | 收退款、充值、资产消费与渠道对账 | P3 | BAPI-114 |
| BTY-P05 | 结算单、费用票据、差异及受权恢复 | P3 | BAPI-115 |
| BTY-Q01 | 本人订单、预约、服务与消费记录 | P1 | BAPI-116、BAPI-158 |
| BTY-Q02 | 当日来客、待服务、进行中、完工与未结概览 | P1 | BAPI-117 |
| BTY-Q03 | 现金实收、充值、资产消耗及未消费余额分口径 | P3 | BAPI-118 |
| BTY-Q04 | 预约转化、爽约、技师/床位利用率与服务时长 | P3 | BAPI-119 |
| BTY-Q05 | 项目销售、成本损耗、复购、业绩与提成报表 | P3 | BAPI-120 |
| BTY-Q06 | 跨店受权汇总、导出、水位及迟到事实更正 | P3 | BAPI-121、BAPI-122 |
| BTY-R01 | 预约确认、改约取消及到店提醒 | P1 | BAPI-123 |
| BTY-R02 | 派工、换人、超时和资源冲突待办 | P1 | BAPI-124、BAPI-125 |
| BTY-R03 | 支付、到账、资产消费及异常结果通知 | P1 | BAPI-123 |
| BTY-R04 | 护理回访计划、分配、跟进和客户回复 | P3 | BAPI-126、BAPI-127、BAPI-128 |
| BTY-R05 | 营销订阅、频控、退订和受众配置 | P3 | BAPI-129 |
| BTY-S01 | 服务单、任务单与消费凭条生成 | P1 | BAPI-130、BAPI-132 |
| BTY-S02 | 打印设备、门店路由、模板及版本配置 | P1 | BAPI-131 |
| BTY-S03 | 打印失败/未知、查原任务、人工补打与审计 | P1 | BAPI-132 |
| BTY-S04 | 前台/技师终端绑定、撤销、心跳与最小权限 | P3 | BAPI-133 |
| BTY-S05 | 顾客自助签到机、上门终端及边缘设备协作 | P4 | BAPI-134 |
| BTY-T01 | 商家准入、门店能力及版本兼容检查 | P1 | BAPI-135 |
| BTY-T02 | 跨店越权、代客身份及关键动作审计 | P1 | BAPI-136 |
| BTY-T03 | 核心来源、预约、工单、资金结果关联追踪 | P1 | BAPI-137、BAPI-160 |
| BTY-T04 | 已付未绑定、资源/事件积压和来源恢复待办 | P1 | BAPI-138、BAPI-139、BAPI-160 |
| BTY-T05 | 慢请求、错误率、设备/服务存活、告警值班 | P1 | BAPI-140 |
| BTY-T06 | 备份恢复、配置发布、保留与隐私权限治理 | P3 | BAPI-141 |
| BTY-T07 | 第三方预约/订单接入、外部号映射与冲突处理 | P4 | BAPI-142 |
| BTY-T08 | 断网营业、同步认领及重复事实拦截 | P4 | BAPI-143 |

P1完成人员/资源统一、单次先付服务、无交易预约、接待工单、服务事实、逐来源账单与异常读模型。P2只接已验收储值/已持有次数权益；P3再开放护理、协作、营销、耗材和经营财务等；P4的付费售卡、团体/并行/上门、混付/后付/定金及资产逆向必须独立评审。

## 七、待决与验收边界

行业路由前缀、权限码、共用CONTRACT的真实入口与来源类型、异步响应适配，以及资源桶精度需在实施评审定稿。当前文档不注册路由，不更新后端已实现API清单，也不创建前端孤立页面。

预约工作台首期选“有水位的全量窗口快照＋短轮询”；可视化按人员/工位切换，并支持未分派任务与异常栏。暂不要求WebSocket或新增通用工作流引擎。具体快照一致性、分页、拖动预览/提交和验收场景见工作台专篇。

## 八、实施补充入口（独立于原160项设计编号）

以下补充P1周规则、BM03显式手动恢复及双向机器协议，不将P3例外审批冒充P1，也不改变原160项设计编号。Task52协议已实现；BM03实际部署验收待Task53。

| 方法 / 路径 | 所有者 / 用途 | 请求 | 返回 |
|---|---|---|---|
| `POST /biz/schedule-rules` | shop-merchant<br>BAPI022的P1周规则维护补充；不替代BAPI023/024 P3审批 | SaveScheduleRuleReq(ruleNo?,resourceNo,expectedRevision,timezone,weekdayMask,startMinute,endMinute,endDayOffset,validFrom,validTo,status,reason)；key必填，validTo必填且最多366日 | ScheduleRuleVO；资源根revision、历史保留、完整有效期冲突校验 |
| `POST /internal/merchant/facts/personnel` | PLATFORM portal/internal → 共用personnel所有者<br>受内部凭证及规范主体/店范围保护的只读事实 | PersonnelFactsPort.Query | List<PersonnelFact>；无账号/联系资料 |
| `POST /internal/merchant/facts/resources` | PLATFORM portal/internal → 共用resource所有者<br>受内部凭证保护；不授予可预约容量 | ResourceFactsPort.Query | List<ResourceFact> |
| `POST /internal/merchant/facts/schedules` | PLATFORM portal/internal → 共用schedule所有者<br>受内部凭证保护；跨店冲突不能隐藏为空闲 | ScheduleWindowsPort.Query | List<Window> |
| `POST /internal/merchant/recovery/operator-access` | PLATFORM portal/internal → merchant权限所有者，零售/餐饮/美业共用<br>机器令牌保护的当前成员/当店权限事实；无会话快照/业务岗位推导，不是持续许可 | MerchantOperatorAccessPort.Query(tenantNo,entityNo,storeNo,operatorNo,realm,subjectKind,permission) | Boolean；真实拒绝false，错误不伪装false |
| `POST /internal/merchant/recovery/commands` | PLATFORM portal/internal → merchant资源命令所有者，零售/餐饮/美业共用<br>当前成员biz:order:view核验后按完整Binding读取原命令；无写入或解冻能力 | ResourceSourceAuthorityPort.Request(binding,operatorNo,realm,subjectKind) | ResourceCommandFactsPort.Lookup(command?)；原状态/版本/结果及稳定终态摘要，ACCEPTED不携终态，缺失显式空，失败400/403/409/5xx；command字段必需，null显式序列化，{}和200错误信封拒绝；未知owner运行异常空503 |
| `POST /biz/x/beauty/operations/{operationNo}/recoveries` | beauty-web → ServiceOperationRecoveryService<br>BM03明确增量：当前MANAGE成员按原操作号手动恢复；GET仅只读；不改原160基线 | operationNo路径；可信BizContext与原始X-Store-No、当前MERCHANT MCH/USR | OperationVO；UNKNOWN/PENDING保留，不伪造终态 |
| `POST /internal/beauty/booking/queryAvailability` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.QueryAvailability | AvailabilityVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/queryPublicAvailability` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.QueryPublicAvailability | PublicAvailabilityVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.PreviewAppointment | AppointmentPreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/createAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.CreateAppointment | AppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/listAppointments` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.ListAppointments | PageData<AppointmentSummaryVO>；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/getAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.GetAppointment | AppointmentDetailVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewAppointmentChange` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.PreviewAppointmentChange | AppointmentChangePreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/changeAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.ChangeAppointment | AppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/cancelAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.CancelAppointment | CancellationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/recordNoShow` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.RecordNoShow | NoShowVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewMyAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.PreviewMyAppointment | AppointmentPreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/createMyAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.CreateMyAppointment | MyAppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/listMyAppointments` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.ListMyAppointments | PageData<MyAppointmentVO>；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/getMyAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.GetMyAppointment | MyAppointmentDetailVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/previewMyAppointmentChange` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.PreviewMyAppointmentChange | AppointmentChangePreviewVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/changeMyAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.ChangeMyAppointment | MyAppointmentVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/cancelMyAppointment` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.CancelMyAppointment | CancellationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/getOperation` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.GetOperation | OperationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/booking/recoverOperation` | beauty-server / InternalBeautyBookingController<br>机器令牌先验；完整当前tenant/realm/subjectKind/actor与真实Service授权（公开availability无伪身份） | BeautyBookingInternalApi.RecoverOperation | OperationVO；403/409/422/503显式失败；字段及返回对象号校验 |
| `POST /internal/beauty/appointment-source/freeze` | beauty-server / 实际AppointmentSourceAuthorityPortImpl<br>仅BEAUTY_APPOINTMENT；完整持久Binding与当前身份；HOST反向回调，无core旁路 | BeautySourceInternalApi.Delegation(identity,request) | Decision；UNKNOWN非许可；完整Binding比对 |
| `POST /internal/beauty/appointment-source/query` | beauty-server / 实际AppointmentSourceAuthorityPortImpl<br>仅BEAUTY_APPOINTMENT；完整持久Binding与当前身份；HOST反向回调，无core旁路 | BeautySourceInternalApi.Delegation(identity,request) | Decision；UNKNOWN非许可；完整Binding比对 |
| `POST /internal/resource-booking/accept` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | Acceptance | Receipt；错误不作为空事实 |
| `POST /internal/resource-booking/query` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | Command | Lookup(Boolean found,Receipt)；错误不作为空事实 |
| `POST /internal/resource-booking/execute` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | Command | Receipt；错误不作为空事实 |
| `POST /internal/resource-booking/hold` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | HoldQuery | BoundHold；错误不作为空事实 |
| `POST /internal/resource-booking/reconcile` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | Binding | Lookup(Boolean found,Receipt)；错误不作为空事实 |
| `POST /internal/resource-booking/reconcile-hold` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | BoundHoldQuery | BoundHold；错误不作为空事实 |
| `POST /internal/resource-booking/candidates` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | ResourceCandidatesPort.Query | List<ResourceFact>；错误不作为空事实 |
| `POST /internal/resource-booking/capacity` | PLATFORM / InternalResourceBookingController → 共用真实resource owner<br>中立机器协议，令牌不替代实际operator/来源校验；禁止RPC置于事务 | ResourceCandidatesPort.CapacityQuery（独立factAt、Replacement完整Binding+Target） | List<Capacity>；错误不作为空事实 |
| `POST /internal/customer-identity/resolve-reference` | PLATFORM / InternalCustomerIdentityController → MemberCustomerIdentityPortImpl<br>只读原actor绑定成员识别票据；保留EntitlementContext和委托身份；无guest/扣款许可降级 | CustomerIdentityInternalApi.Request(identity,context,query) | Resolution含完整身份/上下文/query与ResolvedCustomer；远端比对并验证userNo |
| `POST /internal/guest-registration/register` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member)<br>Task54实际增量；当前完整MERCHANT MCH/USR、真实entity/store和当店biz:customer；登记幂等命名空间含realm/kind/actor/店，同键改参409。安全、配置与验证边界见[Task54共用身份说明](../../../backend-plugins/shop-industry-beauty/README.md)。 | GuestRegistrationInternalApi.Register(scope,RegisterCommand(contact?,displayName?,consent,idempotencyKey)) | Registered(request,Registration(guestNo,claimToken,state,revision))；完整请求回显；服务端随机原凭证，加密持久原键重放 |
| `POST /internal/guest-registration/get` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member)<br>Task54实际增量；同店当前有权员工可交接读取；完整身份/门店权限重核，不冒充登记员工。安全、配置与验证边界见[Task54共用身份说明](../../../backend-plugins/shop-industry-beauty/README.md)。 | GuestRegistrationInternalApi.Get(scope,guestNo) | Found(request,GuestFact(guestNo,state,userNo?,revision))；完整请求回显，无contact/密文 |
| `POST /internal/guest-registration/verify` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member)<br>Task54实际增量；当前当店biz:customer，加原guest随机凭证；错误/未知凭证404。安全、配置与验证边界见[Task54共用身份说明](../../../backend-plugins/shop-industry-beauty/README.md)。 | GuestRegistrationInternalApi.Verify(scope,guestNo,claimToken) | Verified(request,GuestFact)；完整请求回显，不提供支付授权 |
| `POST /internal/guest-registration/claim` | PLATFORM / InternalGuestRegistrationController → MemberGuestRegistrationPortImpl (shop-core/member)<br>Task54实际增量；当前完整namespace/actor/store绑定的有效cr1加原guest凭证，同guest根锁唯一真实用户；旧无namespace票据拒绝。精确原key/hash/full claim actor/secret可在cr1过期后回原结果，仍重核当前权限及活动用户；新key/改参/异身份409。安全、配置与验证边界见[Task54共用身份说明](../../../backend-plugins/shop-industry-beauty/README.md)。 | GuestRegistrationInternalApi.ClaimRequest(scope,guestNo,ClaimCommand(verifiedIdentity,claimToken,idempotencyKey)) | Claimed(request,Claim(guestNo,userNo,revision))；完整请求回显，不改绑或创建账号/订单 |
| `POST /internal/customer-identity/verify-consumer-proof` | PLATFORM / InternalCustomerIdentityController → MemberCustomerIdentityPortImpl<br>Task54实际增量；当前CONSUMER/USR、同租户/本人活动账号及原MEMBER_CODE短票据；不接受登录token/手机号/任意字符串，识别不授予支付授权。安全、配置与验证边界见[Task54共用身份说明](../../../backend-plugins/shop-industry-beauty/README.md)。 | CustomerIdentityInternalApi.ProofRequest(identity,EntitlementContext,memberCode) | ProofResolution(request,ResolvedCustomer)；完整身份/上下文/原memberCode请求回显，必须解析为当前用户；过期/伪造mc1为400 |
| `POST /internal/customer-identity/resolve-claim-reference` | PLATFORM / InternalCustomerIdentityController → MemberCustomerIdentityPortImpl<br>Task55A fix1：当前完整realm/kind/actor/entity/store与CUSTOMER严格核验后建claim intent；旧resolveReference兼容不变 | CustomerIdentityInternalApi.ClaimReferenceRequest(identity,EntitlementContext,ReferenceQuery) | ClaimReferenceResolution(request,ResolvedCustomer)；全请求回显核对，不是资金授权 |
