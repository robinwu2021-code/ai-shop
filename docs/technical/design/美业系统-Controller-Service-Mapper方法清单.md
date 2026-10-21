# 美业系统 Controller、Service 与 Mapper 方法清单

> 状态：方法级目标与实施对照 · 2026-10-21 · 已实现范围和部署结果以主TDD为准，不能把整张清单当已完成。
> 由 `python3 scripts/gen-beauty-java-method-design.py` 生成，勿手改；真源：[Java方法设计JSON](./schema/beauty-java-method-design.json)。
> 依据：[统一Java规范§七](../../../.claude/skills/ai-shop-dev-standards/references/java.md) · [独立项目结构](../TDD-美业系统-独立项目与包依赖方案.md) · [API清单](./美业系统-API清单.md) · [数据库结构](./美业系统-数据库结构设计.md)。

## 一、方法及持久化规则

本清单逐项覆盖95条美业候选HTTP、11条共用候选HTTP，并保留54项共用CONTRACT对照。Mapper共25个，新增38个必要自定义锁/CAS/查询方法；其余使用继承CRUD。类总表、完整Service与Impl签名、DTO和辅助类见[Java类与方法清单](./美业系统-Java类与方法清单.md)。

- Controller仅绑定路径/Body/Query/协议头、鉴权与调用Service，返回业务VO/PageData；沿平台ApiResponseWrapper，不再包ApiResult。声明省略import、类级条件、Mapping和权限注解，HTTP方法/路径在标题；实施必须补齐其对应注解及“为什么”的公开API Javadoc。
- Service接口归beauty-api/service，Impl归beauty-core/service/impl且同名后缀Impl；Service方法无HTTP注解，Impl直接注入本域Mapper。禁止新增Repository、等义Application/DomainService或一个巨型BeautyManager。
- 经营主体用entityNo；BizContext.requireMerchantNo()是现有兼容来源，在Web接入处映射为该语义。currentStoreNo/actorNo来自可信上下文，actorNo是账号/用户/运营操作身份，实际执行人员由共用账号关联及任职核验取得，不能把无登录人员伪装成操作者。
- Service签名显式接收可信范围；租户沿平台当前MAIN及数据域规则，不另启租户体系。详情按对象解析归属后与获准范围相交，SQL仍限entity/门店/受控父链。技师本人范围由可信数据域/共用任职授权解析，不采信query.personnelNo；非受权跨店请求拒绝。
- 消费者本人方法接userNo，由SecurityUtils.requireUser()解析；publicStore/publicService/publicAvailability只由真实门店/目录确定主体，允许公开查询，不要求伪造商家身份。本人方法每次验证资源属主，不接收客户端自报userNo/entityNo/actorNo。
- ops的entityNo与authorizedStoreNos是平台授权目标，不是客户端任意主体；actorNo是当前运营身份，不代商家。技术恢复仍执行同一业务校验，不代顾客同意或代技师完工。跨店报表只读authorizedStoreNos与查询条件交集。
- Req/Query按统一Validation校验必填、长度、枚举、正数/精度与时间窗口；普通page>=1、size默认20且最大50，日历资源页/窗口沿工作台合同。错误沿统一ErrorCode/BizException及三语词条，不另造BeautyError体系。Req/Query只包含业务字段，VO不含物理id。body/query字段沿API输入合同，必须剔除路径字段及仅属身份的可信参数；预览编号绑定原操作者/主体/门店/窗口/版本，不能换人拿号提交。
- 版本请求expectedRevision映射到聚合BaseEntity.version；scopeRevision/bindingRevision/appointmentRevision/planRevision分别是范围、来源、安排、计划版本，不能混用。步骤/参与子行CAS版本取锁内真实行，根版本仍按用户期望检查。
- 业务写命令带Idempotency-Key，同键同载荷取原号、异参拒绝；预览与查询POST无业务副作用，不强制业务幂等头。长流程操作号/请求指纹由共用幂等编排与本域ServiceSubmission持久记录，不因超时换键。
- Mapper继承BaseMapper，常用CRUD不重写空壳；只对稳定行锁、CAS、数量上限等新增自定义方法。BaseMapper虽暴露删除/按id等方法，本域事实业务不使用delete/archive覆盖历史，也不把业务号传selectById。
- 普通分页以用例的真实业务根/组合投影分页，返回IPage后用MybatisPages.of转换PageData；数据量大的列表不用PageData.ofAll。CalendarSnapshotVO/CalendarPageVO是快照协议，不套普通分页，不将未加载/延迟当空闲。批量获取任务/来源/资源，禁止每卡片循环跨域N+1。
- API用带偏移时间并按门店IANA时区显示；本域Entity/自定义SQL采用与BaseEntity一致的LocalDateTime，边界明确转换成UTC本地值。区间[from,to)，数量BigDecimal，金额整数最小单位+币种；不引入浮点金额或全局时区重构。
- LOCAL_ATOMIC仅指本域短事务；ORCHESTRATED先保存意图再外部调用、后短事务确认/补偿。调用清单是候选分支，不是一次必须全执行的线性脚本；HTTP不在长锁事务中等待，UNKNOWN按原号恢复。
- 多行业同名短类通过受控扫描/必要Bean命名策略避免冲突；新增类正常import、构造器注入，不复制零售存量全限定注解/通配import等历史欠账。规范以统一Java文档为准，餐饮只参考模块隔离/薄适配和方法追溯。

固定锁顺序：先workOrder范围根（适用时），再按稳定业务号排序的task/appointment根，再子行。资源桶锁由共用资源所有者自己仲裁，不能在美业长事务中调远程持锁。首版/空行靠业务唯一键与持久化意图竞争，不能用空SELECT FOR UPDATE或MAX+1冒充互斥。

## 二、按模块直接输出Controller与Service方法

### A 门店与营业配置

#### BAPI-001 · GET /biz/x/beauty/store-policies

目的：查询营业与预约规则；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizStorePolicyController`

```java
StorePolicyVO getPolicy(@Valid @ModelAttribute StorePolicyQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.StorePolicyService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.StorePolicyServiceImpl`

```java
StorePolicyVO getPolicy(String entityNo, String currentStoreNo, String actorNo, StorePolicyQuery query);
```

本域读取：`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：门店能力查询。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-002 · POST /biz/x/beauty/store-policies/versions

目的：提交营业/预约窗口与先付规则版本；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizStorePolicyController`

```java
PolicyVersionVO publishPolicyVersion(@Valid @RequestBody PublishStorePolicyVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.StorePolicyService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.StorePolicyServiceImpl`

```java
PolicyVersionVO publishPolicyVersion(String entityNo, String currentStoreNo, String actorNo, PublishStorePolicyVersionReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`。

本域写入候选：`BeautyMappers.StorePolicyMapper.insert / 受控update分支`；`BeautyMappers.StorePolicyVersionMapper.insert`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.StorePolicyMapper.switchCurrentVersion`；`BeautyMappers.StorePolicyMapper.selectScopedForUpdate`。

跨域/基础设施：门店能力查询。

守卫：不可变版本追加，current指针按version切换。

#### BAPI-004 · POST /biz/x/beauty/capability-applications

目的：申请高级经营能力；阶段：P4；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCapabilityApplicationController`

```java
CapabilityApplicationVO applyCapability(@Valid @RequestBody ApplyCapabilityReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CapabilityService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CapabilityServiceImpl`

```java
CapabilityApplicationVO applyCapability(String entityNo, String currentStoreNo, String actorNo, ApplyCapabilityReq req, String idempotencyKey);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用准入/审批登记。

守卫：不在美业新建准入审批账，申请不直接启用能力。

#### BAPI-144 · GET /mp/x/beauty/stores/{storeNo}

目的：读取公开门店营业与预约须知；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpStoreController`

```java
PublicStoreVO getPublicStore(@PathVariable("storeNo") String storeNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceCatalogQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceCatalogQueryServiceImpl`

```java
PublicStoreVO getPublicStore(String storeNo);
```

本域读取：`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：StoreDirectoryPort；GoodsQueryPort；共用目录展示。

守卫：公开字段白名单；主体由真实门店解析，不要求伪造商家上下文。

### B 项目与目录

#### BAPI-007 · GET /biz/x/beauty/service-definitions

目的：查项目作业定义；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceDefinitionController`

```java
PageData<ServiceDefinitionVO> listDefinitions(@Valid @ModelAttribute ServiceDefinitionQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceDefinitionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceDefinitionServiceImpl`

```java
PageData<ServiceDefinitionVO> listDefinitions(String entityNo, String currentStoreNo, String actorNo, ServiceDefinitionQuery query);
```

本域读取：`BeautyMappers.ServiceDefinitionMapper.selectPage`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-008 · POST /biz/x/beauty/service-definitions/versions

目的：提交项目时长缓冲资格资源要求；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceDefinitionController`

```java
ServiceDefinitionVersionVO publishDefinitionVersion(@Valid @RequestBody PublishServiceDefinitionVersionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceDefinitionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceDefinitionServiceImpl`

```java
ServiceDefinitionVersionVO publishDefinitionVersion(String entityNo, String currentStoreNo, String actorNo, PublishServiceDefinitionVersionReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.ServiceDefinitionMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceDefinitionMapper.insert / 受控update分支`；`BeautyMappers.ServiceDefinitionVersionMapper.insert`；`BeautyMappers.ServiceRequirementMapper.insert`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.ServiceDefinitionMapper.selectScopedForUpdate`；`BeautyMappers.ServiceDefinitionMapper.switchCurrentVersion`。

跨域/基础设施：GoodsQueryPort。

守卫：定义版本与要求一同追加，校验商品时不自算售价。

#### BAPI-009 · GET /biz/x/beauty/services/{skuNo}

目的：查项目展示及适用须知；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceController`

```java
ServiceDetailVO getService(@PathVariable("skuNo") String skuNo, @Valid @ModelAttribute ServiceDetailQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceCatalogQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceCatalogQueryServiceImpl`

```java
ServiceDetailVO getService(String entityNo, String currentStoreNo, String actorNo, String skuNo, ServiceDetailQuery query);
```

本域读取：`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：无。

跨域/基础设施：GoodsQueryPort；共用目录展示。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-010 · POST /biz/x/beauty/availability/queries

目的：查询项目可约组合；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAvailabilityController`

```java
AvailabilityVO queryAvailability(@Valid @RequestBody AvailabilityQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentAvailabilityService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentAvailabilityServiceImpl`

```java
AvailabilityVO queryAvailability(String entityNo, String currentStoreNo, String actorNo, AvailabilityQuery query);
```

本域读取：`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`；`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用人员任职/有效技能查询；共用排班及资源可约查询。

守卫：候选结果不占位，完整组合提交仍重验。

#### BAPI-145 · GET /mp/x/beauty/services/{skuNo}

目的：读取公开服务详情；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpServiceController`

```java
PublicServiceVO getPublicService(@PathVariable("skuNo") String skuNo, @Valid @ModelAttribute ServiceDetailQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceCatalogQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceCatalogQueryServiceImpl`

```java
PublicServiceVO getPublicService(String skuNo, ServiceDetailQuery query);
```

本域读取：`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：StoreDirectoryPort；GoodsQueryPort；共用目录展示。

守卫：公开字段白名单；主体由真实门店解析，不要求伪造商家上下文。

#### BAPI-146 · POST /mp/x/beauty/availability/queries

目的：查询公开可约时段及可选技师；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAvailabilityController`

```java
PublicAvailabilityVO queryPublicAvailability(@Valid @RequestBody PublicAvailabilityQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentAvailabilityService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentAvailabilityServiceImpl`

```java
PublicAvailabilityVO queryPublicAvailability(PublicAvailabilityQuery query);
```

本域读取：`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`；`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用人员任职/有效技能查询；共用排班及资源可约查询。

守卫：候选结果不占位，完整组合提交仍重验。

### C 共用人员与权限

本模块为共用能力接入，不生成美业同义Controller/Service/Mapper；共用入口见§三及§五。

### D 资源与排班

#### BAPI-025 · GET /biz/x/beauty/workbench/calendar

目的：查询日历统一快照；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentWorkbenchController`

```java
CalendarSnapshotVO getCalendarSnapshot(@Valid @ModelAttribute CalendarQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentWorkbenchQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentWorkbenchQueryServiceImpl`

```java
CalendarSnapshotVO getCalendarSnapshot(String entityNo, String currentStoreNo, String actorNo, CalendarQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceStepMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用资源规范占用查询；共用人员/班表查询；共用异常投影。

守卫：不写容量桶；各域水位独立；按权限过滤并冻结短期快照。

#### BAPI-026 · GET /biz/x/beauty/workbench/calendar/pages

目的：续取同一快照资源泳道；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentWorkbenchController`

```java
CalendarPageVO getCalendarPage(@Valid @ModelAttribute CalendarPageQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentWorkbenchQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentWorkbenchQueryServiceImpl`

```java
CalendarPageVO getCalendarPage(String entityNo, String currentStoreNo, String actorNo, CalendarPageQuery query);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：已鉴权不可变快照缓存。

守卫：仅续取同一snapshot，不重新查询当前库拼页；过期重载。

#### BAPI-027 · POST /biz/x/beauty/resource-changes/previews

目的：预览资源替换或超时延长；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizResourceChangeController`

```java
ResourceChangePreviewVO previewResourceChange(@Valid @RequestBody PreviewResourceChangeReq req);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ResourceChangeService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ResourceChangeServiceImpl`

```java
ResourceChangePreviewVO previewResourceChange(String entityNo, String currentStoreNo, String actorNo, PreviewResourceChangeReq req);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用资源替换预览；共用人员任职/有效技能查询。

守卫：不持有资源，不写安排或实际参与。

#### BAPI-028 · POST /biz/x/beauty/resource-changes

目的：原子提交完整资源替换/延长；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizResourceChangeController`

```java
ResourceChangeResultVO changeResources(@Valid @RequestBody ChangeResourcesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ResourceChangeService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ResourceChangeServiceImpl`

```java
ResourceChangeResultVO changeResources(String entityNo, String currentStoreNo, String actorNo, ChangeResourcesReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.AppointmentAssignmentMapper.insert`；`BeautyMappers.ServiceParticipationMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：共用资源原子替换；共用人员/班表资格校验。

守卫：按预约/实际任务变更分支，先意图后仲裁，成功才追加安排/交接；不先释放旧组合。

#### BAPI-029 · POST /biz/x/beauty/group-scheduling/previews

目的：团体并行及路途缓冲规划；阶段：P4；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizGroupSchedulingController`

```java
GroupSchedulePreviewVO previewGroupSchedule(@Valid @RequestBody GroupScheduleQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.GroupSchedulingService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.GroupSchedulingServiceImpl`

```java
GroupSchedulePreviewVO previewGroupSchedule(String entityNo, String currentStoreNo, String actorNo, GroupScheduleQuery query);
```

本域读取：`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`；`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用人员任职/有效技能查询；共用排班及资源可约查询。

守卫：候选结果不占位，完整组合提交仍重验。

### E 顾客与护理

#### BAPI-031 · POST /biz/x/beauty/guest-registrations

目的：登记临客及后续本人认领；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController`

```java
GuestRegistrationVO registerGuest(@Valid @RequestBody RegisterGuestReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.GuestRegistrationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.GuestRegistrationServiceImpl`

```java
GuestRegistrationVO registerGuest(String entityNo, String currentStoreNo, String actorNo, RegisterGuestReq req, String idempotencyKey);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用顾客临客登记/认领。

守卫：使用共享身份提供方，不另造美业顾客表或虚假买家。

#### BAPI-032 · POST /biz/x/beauty/guest-registrations/{guestNo}/claims

目的：本人认领临客预约/服务；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizGuestRegistrationController`

```java
GuestClaimVO claimGuest(@PathVariable("guestNo") String guestNo, @Valid @RequestBody ClaimGuestReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.GuestRegistrationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.GuestRegistrationServiceImpl`

```java
GuestClaimVO claimGuest(String entityNo, String currentStoreNo, String actorNo, String guestNo, ClaimGuestReq req, String idempotencyKey);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用顾客临客登记/认领。

守卫：使用共享身份提供方，不另造美业顾客表或虚假买家。

#### BAPI-034 · GET /biz/x/beauty/care-profiles/{customerNo}

目的：读取最少必要护理提醒；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCareProfileController`

```java
CareProfileVO getCareProfile(@PathVariable("customerNo") String customerNo, @Valid @ModelAttribute CareProfileQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CareProfileService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CareProfileServiceImpl`

```java
CareProfileVO getCareProfile(String entityNo, String currentStoreNo, String actorNo, String customerNo, CareProfileQuery query);
```

本域读取：`BeautyMappers.CareProfileMapper.selectList`；`BeautyMappers.CareNoteMapper.selectList`；`BeautyMappers.CareMediaMapper.selectList`。

本域写入候选：无。

跨域/基础设施：隐私目的与授权核验；私有媒体读取授权。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-035 · POST /biz/x/beauty/care-profiles/{customerNo}/notes

目的：追加护理事实及更正引用；阶段：P3；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCareProfileController`

```java
CareNoteVO appendCareNote(@PathVariable("customerNo") String customerNo, @Valid @RequestBody AppendCareNoteReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CareProfileService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CareProfileServiceImpl`

```java
CareNoteVO appendCareNote(String entityNo, String currentStoreNo, String actorNo, String customerNo, AppendCareNoteReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.CareProfileMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：`BeautyMappers.CareProfileMapper.insert / 受控update分支`；`BeautyMappers.CareNoteMapper.insert`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.CareProfileMapper.selectScopedForUpdate`。

跨域/基础设施：隐私目的与授权核验。

守卫：锁档案版本，追加加密事实；更正保留supersedes引用。

#### BAPI-036 · POST /biz/x/beauty/care-profiles/{customerNo}/media-authorizations

目的：申请私有护理媒体上传/读取；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCareProfileController`

```java
MediaAuthorizationVO authorizeCareMedia(@PathVariable("customerNo") String customerNo, @Valid @RequestBody AuthorizeCareMediaReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CareProfileService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CareProfileServiceImpl`

```java
MediaAuthorizationVO authorizeCareMedia(String entityNo, String currentStoreNo, String actorNo, String customerNo, AuthorizeCareMediaReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.CareProfileMapper.selectList`；`BeautyMappers.CareNoteMapper.selectList`；`BeautyMappers.CareMediaMapper.selectList`。

本域写入候选：`BeautyMappers.CareMediaMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：私有媒体服务；隐私目的与授权核验。

守卫：只登记私有媒体引用，不把照片放普通日志。

#### BAPI-037 · POST /biz/x/beauty/care-profiles/{customerNo}/privacy-requests

目的：申请撤权留存或合法删除；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCareProfileController`

```java
PrivacyRequestVO requestCarePrivacy(@PathVariable("customerNo") String customerNo, @Valid @RequestBody CarePrivacyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CareProfileService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CareProfileServiceImpl`

```java
PrivacyRequestVO requestCarePrivacy(String entityNo, String currentStoreNo, String actorNo, String customerNo, CarePrivacyReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.CareProfileMapper.selectList`；`BeautyMappers.CareNoteMapper.selectList`；`BeautyMappers.CareMediaMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用隐私请求/留存治理。

守卫：共用隐私流程受理，不直接全表删档案。

### F 预约与改约

#### BAPI-038 · POST /biz/x/beauty/appointments/previews

目的：校验预约要求并预览资源组合；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
AppointmentPreviewVO previewAppointment(@Valid @RequestBody PreviewAppointmentReq req);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
AppointmentPreviewVO previewAppointment(String entityNo, String currentStoreNo, String actorNo, PreviewAppointmentReq req);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用顾客核验；共用资源组合可约/资格校验；合法来源/改价影响查询。

守卫：只产生有期限预览，不建单、不占位、不扣次。

#### BAPI-039 · POST /biz/x/beauty/appointments

目的：占齐完整资源并创建无交易或有来源预约；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
AppointmentVO createAppointment(@Valid @RequestBody CreateAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
AppointmentVO createAppointment(String entityNo, String currentStoreNo, String actorNo, CreateAppointmentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.AppointmentItemMapper.insert / 受控update分支`；`BeautyMappers.AppointmentAssignmentMapper.insert`；`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：共用资源原子占齐并确认；共用顾客核验。

守卫：未确认全部资源前只返回PENDING；补偿/重试按原号。

#### BAPI-040 · GET /biz/x/beauty/appointments

目的：查预约列表；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
PageData<AppointmentSummaryVO> listAppointments(@Valid @ModelAttribute AppointmentQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
PageData<AppointmentSummaryVO> listAppointments(String entityNo, String currentStoreNo, String actorNo, AppointmentQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectPage`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：先按预约根分页，再按当页appointmentNos批查项目/安排；顾客userNo解析为可信客户归属范围，不接受Query扩大身份。

#### BAPI-041 · GET /biz/x/beauty/appointments/{appointmentNo}

目的：查预约任务资源时间线详情；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
AppointmentDetailVO getAppointment(@PathVariable("appointmentNo") String appointmentNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
AppointmentDetailVO getAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

关键自定义调用：`BeautyMappers.AppointmentAssignmentMapper.selectByAppointmentRevision`。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-042 · POST /biz/x/beauty/appointments/{appointmentNo}/changes/previews

目的：改时段/项目/指定人员预览；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
AppointmentChangePreviewVO previewAppointmentChange(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody PreviewAppointmentChangeReq req);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
AppointmentChangePreviewVO previewAppointmentChange(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, PreviewAppointmentChangeReq req);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用顾客核验；共用资源组合可约/资格校验；合法来源/改价影响查询。

守卫：只产生有期限预览，不建单、不占位、不扣次。

#### BAPI-043 · POST /biz/x/beauty/appointments/{appointmentNo}/changes

目的：原子改约及资源替换；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
AppointmentVO changeAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody ChangeAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
AppointmentVO changeAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, ChangeAppointmentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.AppointmentItemMapper.insert / 受控update分支`；`BeautyMappers.AppointmentAssignmentMapper.insert`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.AppointmentMapper.selectScopedForUpdate`；`BeautyMappers.AppointmentMapper.changeSchedule`；`BeautyMappers.AppointmentAssignmentMapper.selectByAppointmentRevision`。

跨域/基础设施：共用资源原子替换；共用人员资格；正式报价/来源就绪查询。

守卫：旧安排版本保留，失败不丢原位；已开工禁止覆盖实际执行历史。

#### BAPI-044 · POST /biz/x/beauty/appointments/{appointmentNo}/cancellations

目的：取消预约释放资源并协调来源；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
CancellationVO cancelAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody CancelAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
CancellationVO cancelAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, CancelAppointmentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.AppointmentMapper.selectScopedForUpdate`；`BeautyMappers.AppointmentMapper.changeStatus`。

跨域/基础设施：共用资源释放；原来源取消合同；通知事实触发。

守卫：爽约只记事实，不自动扣款/扣次；取消与退款分开。

#### BAPI-045 · POST /biz/x/beauty/appointments/{appointmentNo}/no-shows

目的：记录爽约事实；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
NoShowVO recordNoShow(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody RecordNoShowReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
NoShowVO recordNoShow(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, RecordNoShowReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.AppointmentMapper.selectScopedForUpdate`；`BeautyMappers.AppointmentMapper.changeStatus`。

跨域/基础设施：共用资源释放；原来源取消合同；通知事实触发。

守卫：爽约只记事实，不自动扣款/扣次；取消与退款分开。

#### BAPI-147 · POST /mp/x/beauty/appointments/previews

目的：本人预约预览；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAppointmentController`

```java
AppointmentPreviewVO previewMyAppointment(@Valid @RequestBody PreviewMyAppointmentReq req);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
AppointmentPreviewVO previewMyAppointment(String userNo, PreviewMyAppointmentReq req);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用顾客核验；共用资源组合可约/资格校验；合法来源/改价影响查询。

守卫：只产生有期限预览，不建单、不占位、不扣次。

#### BAPI-148 · POST /mp/x/beauty/appointments

目的：本人提交预约并占齐资源；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAppointmentController`

```java
MyAppointmentVO createMyAppointment(@Valid @RequestBody CreateMyAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
MyAppointmentVO createMyAppointment(String userNo, CreateMyAppointmentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.AppointmentItemMapper.insert / 受控update分支`；`BeautyMappers.AppointmentAssignmentMapper.insert`；`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：共用资源原子占齐并确认；共用顾客核验。

守卫：未确认全部资源前只返回PENDING；补偿/重试按原号。

#### BAPI-149 · GET /mp/x/beauty/appointments

目的：我的预约列表；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAppointmentController`

```java
PageData<MyAppointmentVO> listMyAppointments(@Valid @ModelAttribute MyAppointmentQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
PageData<MyAppointmentVO> listMyAppointments(String userNo, MyAppointmentQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectPage`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：先按预约根分页，再按当页appointmentNos批查项目/安排；顾客userNo解析为可信客户归属范围，不接受Query扩大身份。

#### BAPI-150 · GET /mp/x/beauty/appointments/{appointmentNo}

目的：本人预约详情；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAppointmentController`

```java
MyAppointmentDetailVO getMyAppointment(@PathVariable("appointmentNo") String appointmentNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
MyAppointmentDetailVO getMyAppointment(String userNo, String appointmentNo);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

关键自定义调用：`BeautyMappers.AppointmentAssignmentMapper.selectByAppointmentRevision`。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-151 · POST /mp/x/beauty/appointments/{appointmentNo}/changes/previews

目的：本人改约预览；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAppointmentController`

```java
AppointmentChangePreviewVO previewMyAppointmentChange(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody PreviewMyAppointmentChangeReq req);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
AppointmentChangePreviewVO previewMyAppointmentChange(String userNo, String appointmentNo, PreviewMyAppointmentChangeReq req);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.StorePolicyMapper.selectList`；`BeautyMappers.StorePolicyVersionMapper.selectList`；`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceDefinitionVersionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用顾客核验；共用资源组合可约/资格校验；合法来源/改价影响查询。

守卫：只产生有期限预览，不建单、不占位、不扣次。

#### BAPI-152 · POST /mp/x/beauty/appointments/{appointmentNo}/changes

目的：本人提交改约；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAppointmentController`

```java
MyAppointmentVO changeMyAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody ChangeAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
MyAppointmentVO changeMyAppointment(String userNo, String appointmentNo, ChangeAppointmentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.AppointmentItemMapper.insert / 受控update分支`；`BeautyMappers.AppointmentAssignmentMapper.insert`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.AppointmentMapper.selectScopedForUpdate`；`BeautyMappers.AppointmentMapper.changeSchedule`；`BeautyMappers.AppointmentAssignmentMapper.selectByAppointmentRevision`。

跨域/基础设施：共用资源原子替换；共用人员资格；正式报价/来源就绪查询。

守卫：旧安排版本保留，失败不丢原位；已开工禁止覆盖实际执行历史。

#### BAPI-153 · POST /mp/x/beauty/appointments/{appointmentNo}/cancellations

目的：本人取消预约；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpAppointmentController`

```java
CancellationVO cancelMyAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody CancelAppointmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
CancellationVO cancelMyAppointment(String userNo, String appointmentNo, CancelAppointmentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.AppointmentMapper.selectScopedForUpdate`；`BeautyMappers.AppointmentMapper.changeStatus`。

跨域/基础设施：共用资源释放；原来源取消合同；通知事实触发。

守卫：爽约只记事实，不自动扣款/扣次；取消与退款分开。

### G 到店与接待

#### BAPI-047 · POST /biz/x/beauty/arrivals

目的：核对身份并到店签到；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizArrivalController`

```java
ArrivalVO checkIn(@Valid @RequestBody CheckInReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ReceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`

```java
ArrivalVO checkIn(String entityNo, String currentStoreNo, String actorNo, CheckInReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`。

本域写入候选：`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.AppointmentMapper.selectScopedForUpdate`；`BeautyMappers.AppointmentMapper.markArrived`。

跨域/基础设施：共用身份核验。

守卫：签到不调用会完成订单的履约核销。

#### BAPI-048 · POST /biz/x/beauty/walk-ins

目的：直客接待并安排即时资源；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWalkInController`

```java
WalkInVO receiveWalkIn(@Valid @RequestBody ReceiveWalkInReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ReceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`

```java
WalkInVO receiveWalkIn(String entityNo, String currentStoreNo, String actorNo, ReceiveWalkInReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.ServiceDefinitionMapper.selectList`；`BeautyMappers.ServiceRequirementMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.AppointmentItemMapper.insert / 受控update分支`；`BeautyMappers.AppointmentAssignmentMapper.insert`；`BeautyMappers.WorkOrderMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：共用资源即时完整组合；共用顾客核验。

守卫：先占资源确认，再幂等开工单；无来源任务不能开工。

#### BAPI-049 · GET /biz/x/beauty/waiting-tickets

目的：查询等候队列；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWaitingTicketController`

```java
PageData<WaitingTicketVO> listWaitingTickets(@Valid @ModelAttribute WaitingTicketQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ReceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`

```java
PageData<WaitingTicketVO> listWaitingTickets(String entityNo, String currentStoreNo, String actorNo, WaitingTicketQuery query);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用等候队列查询。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-050 · POST /biz/x/beauty/waiting-tickets

目的：取号加入等候；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWaitingTicketController`

```java
WaitingTicketVO joinWaitingQueue(@Valid @RequestBody JoinWaitingQueueReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ReceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`

```java
WaitingTicketVO joinWaitingQueue(String entityNo, String currentStoreNo, String actorNo, JoinWaitingQueueReq req, String idempotencyKey);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用等候号与队列动作。

守卫：不创建美业同义等候表。

#### BAPI-051 · POST /biz/x/beauty/waiting-tickets/{ticketNo}/actions

目的：叫号过号取消等候；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWaitingTicketController`

```java
WaitingTicketVO actOnWaitingTicket(@PathVariable("ticketNo") String ticketNo, @Valid @RequestBody WaitingTicketActionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ReceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`

```java
WaitingTicketVO actOnWaitingTicket(String entityNo, String currentStoreNo, String actorNo, String ticketNo, WaitingTicketActionReq req, String idempotencyKey);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用等候号与队列动作。

守卫：不创建美业同义等候表。

#### BAPI-052 · POST /biz/x/beauty/appointments/{appointmentNo}/work-orders

目的：预约转工单并确认接待安排；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentController`

```java
WorkOrderVO openWorkOrderFromAppointment(@PathVariable("appointmentNo") String appointmentNo, @Valid @RequestBody OpenAppointmentWorkOrderReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl`

```java
WorkOrderVO openWorkOrderFromAppointment(String entityNo, String currentStoreNo, String actorNo, String appointmentNo, OpenAppointmentWorkOrderReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`。

本域写入候选：`BeautyMappers.WorkOrderMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.ServiceSubmissionMapper.selectByRequestKey`。

跨域/基础设施：共用顾客核验；资源安排确认。

守卫：预约转工单幂等；工单范围与创建任务结果明确，不伪称已付。

#### BAPI-053 · POST /biz/x/beauty/reception-exceptions

目的：迟到早到及无法服务协调；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizReceptionExceptionController`

```java
ReceptionExceptionVO coordinateReceptionException(@Valid @RequestBody ReceptionExceptionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ReceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`

```java
ReceptionExceptionVO coordinateReceptionException(String entityNo, String currentStoreNo, String actorNo, ReceptionExceptionReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用业务待办/协调登记。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-154 · POST /mp/x/beauty/arrivals

目的：本人到店签到；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpArrivalController`

```java
MyArrivalVO checkInMyAppointment(@Valid @RequestBody MyCheckInReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ReceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl`

```java
MyArrivalVO checkInMyAppointment(String userNo, MyCheckInReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`。

本域写入候选：`BeautyMappers.AppointmentMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.AppointmentMapper.selectScopedForUpdate`；`BeautyMappers.AppointmentMapper.markArrived`。

跨域/基础设施：共用身份核验。

守卫：签到不调用会完成订单的履约核销。

### H 工单与任务执行

#### BAPI-054 · POST /biz/x/beauty/work-orders

目的：建立工单项目行及来源提交意图；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWorkOrderController`

```java
WorkOrderVO openWorkOrder(@Valid @RequestBody OpenWorkOrderReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
WorkOrderVO openWorkOrder(String entityNo, String currentStoreNo, String actorNo, OpenWorkOrderReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentItemMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`。

本域写入候选：`BeautyMappers.WorkOrderMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.ServiceSubmissionMapper.selectByRequestKey`。

跨域/基础设施：共用顾客核验；资源安排确认。

守卫：预约转工单幂等；工单范围与创建任务结果明确，不伪称已付。

#### BAPI-055 · GET /biz/x/beauty/work-orders/{workOrderNo}

目的：查工单任务来源与进度；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWorkOrderController`

```java
WorkOrderDetailVO getWorkOrder(@PathVariable("workOrderNo") String workOrderNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
WorkOrderDetailVO getWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceStepMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-056 · POST /biz/x/beauty/work-orders/{workOrderNo}/service-submissions

目的：提交新项目/加项及可信来源绑定流程；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWorkOrderController`

```java
ServiceSubmissionVO submitServices(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody SubmitServicesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
ServiceSubmissionVO submitServices(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, SubmitServicesReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.SourceAllocationGuardMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.TradeBindingMapper.insert`；`BeautyMappers.SourceAllocationGuardMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.WorkOrderMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.WorkOrderMapper.advanceScope`；`BeautyMappers.ServiceSubmissionMapper.selectByRequestKey`；`BeautyMappers.SourceAllocationGuardMapper.selectSourceForUpdate`；`BeautyMappers.SourceAllocationGuardMapper.allocateQuantity`；`BeautyMappers.ServiceTaskMapper.switchBindingRevision`。

跨域/基础设施：正式报价/成交或服务消费来源；本人确认与来源状态查询。

守卫：来源写者仍在Core/Pay；先保存意图和范围版本，再绑定原来源。

#### BAPI-057 · GET /biz/x/beauty/tasks

目的：查询我的或受权任务；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
PageData<ServiceTaskVO> listTasks(@Valid @ModelAttribute ServiceTaskQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
PageData<ServiceTaskVO> listTasks(String entityNo, String currentStoreNo, String actorNo, ServiceTaskQuery query);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectPage`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：先按service_task业务根做数据库分页，再用当页workOrderNos批查工单；技师本人范围由可信权限解析，不采信任意personnelNo过滤值。

#### BAPI-058 · POST /biz/x/beauty/tasks/{taskNo}/acceptances

目的：接单；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
TaskActionResultVO acceptTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody AcceptTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
TaskActionResultVO acceptTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, AcceptTaskReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceParticipationMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.changeStatus`。

跨域/基础设施：无新增跨域副作用。

守卫：接单/暂停/恢复/提前结束按动作分支；暂停不默认释放资源；接单不新建实际参与段，实际开始后才登记startedAt；早退只记录事实，实际完成数量经completion协议。

#### BAPI-059 · POST /biz/x/beauty/tasks/{taskNo}/starts

目的：复核来源资格资源后开始服务；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
TaskActionResultVO startTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody StartTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
TaskActionResultVO startTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, StartTaskReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceParticipationMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.changeStatus`。

跨域/基础设施：来源权威开工许可/授权确认（含绑定修订）；共用人员/资格/排班/资源开工复核。

守卫：绑定修订与资格/资源在开工再次校验，不用客户端自报已付；必须与并发撤销/退款有权威许可或写围栏合同，单次查询已付不能保证开工竞态安全，缺合同拒绝开通。

#### BAPI-060 · POST /biz/x/beauty/tasks/{taskNo}/steps/{stepNo}/completions

目的：提交步骤进度；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
ServiceStepVO completeStep(@PathVariable("taskNo") String taskNo, @PathVariable("stepNo") String stepNo, @Valid @RequestBody CompleteServiceStepReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
ServiceStepVO completeStep(String entityNo, String currentStoreNo, String actorNo, String taskNo, String stepNo, CompleteServiceStepReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceStepMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceStepMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`；`BeautyMappers.ServiceStepMapper.completeStep`。

跨域/基础设施：无新增跨域副作用。

守卫：锁任务并CAS步骤，不能绕过任务版本/顺序与状态；task根版本仍需CAS推进或同事务动作版本记录，以拒绝同一旧根版本的重复动作。

#### BAPI-061 · POST /biz/x/beauty/tasks/{taskNo}/handovers/previews

目的：预览换人交接与资格资源影响；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
TaskHandoverPreviewVO previewHandover(@PathVariable("taskNo") String taskNo, @Valid @RequestBody PreviewTaskHandoverReq req);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
TaskHandoverPreviewVO previewHandover(String entityNo, String currentStoreNo, String actorNo, String taskNo, PreviewTaskHandoverReq req);
```

本域读取：`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`。

本域写入候选：无。

跨域/基础设施：人员资格/资源交接预览。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-062 · POST /biz/x/beauty/tasks/{taskNo}/handovers

目的：提交交接并追加实际参与区间；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
TaskHandoverVO handoverTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody HandoverTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
TaskHandoverVO handoverTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, HandoverTaskReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceParticipationMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`；`BeautyMappers.ServiceParticipationMapper.endParticipation`。

跨域/基础设施：共用人员资格/排班；资源原子交接。

守卫：旧参与段收口，新参与段追加；保存实际人而非只覆盖安排人。

#### BAPI-063 · POST /biz/x/beauty/tasks/{taskNo}/interruptions

目的：暂停/恢复/提前结束及事实记录；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
TaskActionResultVO interruptTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody InterruptTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
TaskActionResultVO interruptTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, InterruptTaskReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceParticipationMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.changeStatus`。

跨域/基础设施：无新增跨域副作用。

守卫：接单/暂停/恢复/提前结束按动作分支；暂停不默认释放资源；接单不新建实际参与段，实际开始后才登记startedAt；早退只记录事实，实际完成数量经completion协议。

#### BAPI-064 · POST /biz/x/beauty/tasks/{taskNo}/completions

目的：提交真实完成数量与核心履约申请；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
TaskCompletionVO completeTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody CompleteTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
TaskCompletionVO completeTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, CompleteTaskReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.SourceAllocationGuardMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceParticipationMapper.insert / 受控update分支`；`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.recordCompletion`；`BeautyMappers.ServiceParticipationMapper.endParticipation`。

跨域/基础设施：可信来源完成数量/履约确认；共用资源结束/清洁缓冲。

守卫：真实服务结束与核心履约结果分开；完成量上限由锁/CAS保证。

#### BAPI-065 · POST /biz/x/beauty/work-orders/{workOrderNo}/closure-previews

目的：检查真实任务/来源版本和未结项；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWorkOrderController`

```java
ClosurePreviewVO previewClosure(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody PreviewWorkOrderClosureReq req);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
ClosurePreviewVO previewClosure(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, PreviewWorkOrderClosureReq req);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceStepMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-066 · POST /biz/x/beauty/work-orders/{workOrderNo}/closures

目的：冻结范围后关闭并释放占用；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWorkOrderController`

```java
WorkOrderClosureVO closeWorkOrder(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody CloseWorkOrderReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
WorkOrderClosureVO closeWorkOrder(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, CloseWorkOrderReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：`BeautyMappers.WorkOrderMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.WorkOrderMapper.freezeScope`；`BeautyMappers.WorkOrderMapper.closeFrozenScope`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`。

跨域/基础设施：权威来源最终查询；剩余资源释放。

守卫：先冻结scopeRevision，重读全部任务/来源绑定版本，未知不关单；按任务号排序锁相关任务，冻结只禁止范围增删，已开始任务收尾仍受检查版本约束。

#### BAPI-067 · POST /biz/x/beauty/work-orders/{workOrderNo}/stage-plans

目的：跨日多阶段并行与上门作业计划；阶段：P4；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWorkOrderController`

```java
StagePlanVO planStages(@PathVariable("workOrderNo") String workOrderNo, @Valid @RequestBody PlanWorkOrderStagesReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
StagePlanVO planStages(String entityNo, String currentStoreNo, String actorNo, String workOrderNo, PlanWorkOrderStagesReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：`BeautyMappers.WorkOrderMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：资源阶段规划。

守卫：P4开放后按范围版本规划，不提前承诺团体/并行资源。

#### BAPI-155 · GET /mp/x/beauty/work-orders/{workOrderNo}

目的：本人服务任务及完成进度；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpWorkOrderController`

```java
MyWorkOrderVO getMyWorkOrder(@PathVariable("workOrderNo") String workOrderNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
MyWorkOrderVO getMyWorkOrder(String userNo, String workOrderNo);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceStepMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-159 · GET /biz/x/beauty/tasks/{taskNo}

目的：查询具体任务步骤与安排/实际参与摘要；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
ServiceTaskDetailVO getTask(@PathVariable("taskNo") String taskNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
ServiceTaskDetailVO getTask(String entityNo, String currentStoreNo, String actorNo, String taskNo);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceStepMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

### I 共用交易与收银

#### BAPI-071 · GET /biz/x/beauty/work-orders/{workOrderNo}/bill

目的：汇总逐来源应收实收与未结；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizWorkOrderController`

```java
WorkOrderBillVO getBill(@PathVariable("workOrderNo") String workOrderNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.WorkOrderService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl`

```java
WorkOrderBillVO getBill(String entityNo, String currentStoreNo, String actorNo, String workOrderNo);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：逐类型来源应收实收/未结查询。

守卫：只汇总权威金额，不在本域造混合收款单。

#### BAPI-072 · GET /biz/x/beauty/service-submissions/{submissionNo}

目的：查提交意图与来源绑定进度；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController`

```java
ServiceSubmissionVO getSubmission(@PathVariable("submissionNo") String submissionNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceSubmissionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceSubmissionServiceImpl`

```java
ServiceSubmissionVO getSubmission(String entityNo, String currentStoreNo, String actorNo, String submissionNo);
```

本域读取：`BeautyMappers.ServiceSubmissionMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-073 · POST /biz/x/beauty/service-submissions/{submissionNo}/recovery-requests

目的：申请原号恢复查单及绑定；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceSubmissionController`

```java
RecoveryRequestVO requestSubmissionRecovery(@PathVariable("submissionNo") String submissionNo, @Valid @RequestBody RecoverSubmissionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceSubmissionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceSubmissionServiceImpl`

```java
RecoveryRequestVO requestSubmissionRecovery(String entityNo, String currentStoreNo, String actorNo, String submissionNo, RecoverSubmissionReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.ServiceSubmissionMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.SourceAllocationGuardMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.TradeBindingMapper.insert`；`BeautyMappers.SourceAllocationGuardMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`；`BeautyMappers.ServiceSubmissionMapper.selectScopedForUpdate`；`BeautyMappers.ServiceSubmissionMapper.advanceProgress`；`BeautyMappers.SourceAllocationGuardMapper.selectSourceForUpdate`；`BeautyMappers.ServiceSubmissionMapper.selectDueForUpdate`；`BeautyMappers.ServiceTaskMapper.switchBindingRevision`。

跨域/基础设施：原来源/资源操作号权威查单。

守卫：只按原键查询/幂等恢复，不新号支付或重复分配；涉及任务绑定才按workOrder→task→提交/guard锁序确认，冻结/绑定修订变化时拒绝旧结果覆盖；纯预约资源恢复分支不伪造任务。

### J 储值

本模块为共用能力接入，不生成美业同义Controller/Service/Mapper；共用入口见§三及§五。

### K 疗程与权益

#### BAPI-086 · GET /biz/x/beauty/treatment-plans

目的：查疗程计划与每次服务履历；阶段：P4；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizTreatmentPlanController`

```java
PageData<TreatmentPlanVO> listTreatmentPlans(@Valid @ModelAttribute TreatmentPlanQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.TreatmentPlanService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.TreatmentPlanServiceImpl`

```java
PageData<TreatmentPlanVO> listTreatmentPlans(String entityNo, String currentStoreNo, String actorNo, TreatmentPlanQuery query);
```

本域读取：`BeautyMappers.TreatmentPlanMapper.selectPage`；`BeautyMappers.TreatmentSessionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-087 · POST /biz/x/beauty/treatment-plans

目的：创建分次服务计划；阶段：P4；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizTreatmentPlanController`

```java
TreatmentPlanVO createTreatmentPlan(@Valid @RequestBody CreateTreatmentPlanReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.TreatmentPlanService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.TreatmentPlanServiceImpl`

```java
TreatmentPlanVO createTreatmentPlan(String entityNo, String currentStoreNo, String actorNo, CreateTreatmentPlanReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.TreatmentPlanMapper.selectList`。

本域写入候选：`BeautyMappers.TreatmentPlanMapper.insert / 受控update分支`；`BeautyMappers.TreatmentSessionMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：既有合格权益适用性查询。

守卫：只建服务计划，不提前扣未来次数。

#### BAPI-156 · GET /mp/x/beauty/treatment-plans

目的：本人疗程计划与每次履历；阶段：P4；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpTreatmentPlanController`

```java
PageData<MyTreatmentPlanVO> listMyTreatmentPlans(@Valid @ModelAttribute MyTreatmentPlanQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.TreatmentPlanService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.TreatmentPlanServiceImpl`

```java
PageData<MyTreatmentPlanVO> listMyTreatmentPlans(String userNo, MyTreatmentPlanQuery query);
```

本域读取：`BeautyMappers.TreatmentPlanMapper.selectPage`；`BeautyMappers.TreatmentSessionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

### L 营销

本模块为共用能力接入，不生成美业同义Controller/Service/Mapper；共用入口见§三及§五。

### M 实物库存与耗材

本模块为共用能力接入，不生成美业同义Controller/Service/Mapper；共用入口见§三及§五。

### N 售后与争议

#### BAPI-100 · POST /biz/x/beauty/tasks/{taskNo}/cancellations

目的：未开始任务撤销及资源来源协调；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
TaskCancellationVO cancelTask(@PathVariable("taskNo") String taskNo, @Valid @RequestBody CancelTaskReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
TaskCancellationVO cancelTask(String entityNo, String currentStoreNo, String actorNo, String taskNo, CancelTaskReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.WorkOrderMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.WorkOrderMapper.selectScopedForUpdate`；`BeautyMappers.ServiceTaskMapper.selectScopedForUpdate`。

跨域/基础设施：共用资源释放；原来源合法取消。

守卫：只撤未开始/允许撤销数量；已做事实不能归零。

#### BAPI-101 · POST /biz/x/beauty/tasks/{taskNo}/service-evidence

目的：追加完成数量提前结束质量事实；阶段：P1；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
ServiceEvidenceVO appendServiceEvidence(@PathVariable("taskNo") String taskNo, @Valid @RequestBody AppendServiceEvidenceReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
ServiceEvidenceVO appendServiceEvidence(String entityNo, String currentStoreNo, String actorNo, String taskNo, AppendServiceEvidenceReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：`BeautyMappers.ActionLogMapper.insert`。

跨域/基础设施：无新增跨域副作用。

守卫：数量/质量证据先作追加安全引用；实际doneQty只经任务完成协议变更。

#### BAPI-102 · POST /biz/x/beauty/service-remedy-cases

目的：申请返工补做部分退款赔付方案；阶段：P4；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceRemedyCaseController`

```java
RemedyCaseVO requestServiceRemedy(@Valid @RequestBody RequestServiceRemedyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceCaseCoordinationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceCaseCoordinationServiceImpl`

```java
RemedyCaseVO requestServiceRemedy(String entityNo, String currentStoreNo, String actorNo, RequestServiceRemedyReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用争议/补做/售后案件与审批。

守卫：案件登记不等于资产冲回；受权提供事实，不复制案件数据库。

#### BAPI-103 · POST /biz/x/beauty/asset-disputes

目的：登记储值次数争议形成财务待办；阶段：P2；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAssetDisputeController`

```java
AssetDisputeVO registerAssetDispute(@Valid @RequestBody RegisterAssetDisputeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceCaseCoordinationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceCaseCoordinationServiceImpl`

```java
AssetDisputeVO registerAssetDispute(String entityNo, String currentStoreNo, String actorNo, RegisterAssetDisputeReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用争议/补做/售后案件与审批。

守卫：案件登记不等于资产冲回；受权提供事实，不复制案件数据库。

#### BAPI-157 · POST /mp/x/beauty/service-disputes

目的：本人提交服务数量质量争议；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpServiceDisputeController`

```java
ServiceDisputeVO raiseMyServiceDispute(@Valid @RequestBody RaiseMyServiceDisputeReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceCaseCoordinationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceCaseCoordinationServiceImpl`

```java
ServiceDisputeVO raiseMyServiceDispute(String userNo, RaiseMyServiceDisputeReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用争议/补做/售后案件与审批。

守卫：案件登记不等于资产冲回；受权提供事实，不复制案件数据库。

### O 业绩与提成

#### BAPI-105 · GET /biz/x/beauty/tasks/{taskNo}/participations

目的：查询实际参与与产出事实；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTaskController`

```java
ParticipationVO listParticipations(@PathVariable("taskNo") String taskNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTaskService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTaskServiceImpl`

```java
ParticipationVO listParticipations(String entityNo, String currentStoreNo, String actorNo, String taskNo);
```

本域读取：`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-106 · GET /biz/x/beauty/commission-policies

目的：查规则版本及基数口径；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCommissionPolicyController`

```java
PageData<CommissionPolicyVO> listCommissionPolicies(@Valid @ModelAttribute CommissionPolicyQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CommissionPolicyService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CommissionPolicyServiceImpl`

```java
PageData<CommissionPolicyVO> listCommissionPolicies(String entityNo, String currentStoreNo, String actorNo, CommissionPolicyQuery query);
```

本域读取：`BeautyMappers.CommissionPolicyMapper.selectPage`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-107 · POST /biz/x/beauty/commission-policy-versions

目的：维护业绩分配与提成版本；阶段：P3；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCommissionPolicyController`

```java
CommissionPolicyVersionVO publishCommissionPolicy(@Valid @RequestBody PublishCommissionPolicyReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CommissionPolicyService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CommissionPolicyServiceImpl`

```java
CommissionPolicyVersionVO publishCommissionPolicy(String entityNo, String currentStoreNo, String actorNo, PublishCommissionPolicyReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.CommissionPolicyMapper.selectList`。

本域写入候选：`BeautyMappers.CommissionPolicyMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.CommissionPolicyMapper.lockRuleGroupAnchor`。

跨域/基础设施：无新增跨域副作用。

守卫：按稳定ruleGroup仲裁，新增版本，不覆盖历史计提规则。

#### BAPI-108 · GET /biz/x/beauty/commission-entries

目的：查本人或受权提成明细；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCommissionEntryController`

```java
PageData<CommissionEntryVO> listCommissionEntries(@Valid @ModelAttribute CommissionEntryQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CommissionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CommissionServiceImpl`

```java
PageData<CommissionEntryVO> listCommissionEntries(String entityNo, String currentStoreNo, String actorNo, CommissionEntryQuery query);
```

本域读取：`BeautyMappers.CommissionEntryMapper.selectPage`；`BeautyMappers.PerformanceShareMapper.selectList`；`BeautyMappers.PerformanceAllocationMapper.selectList`。

本域写入候选：无。

跨域/基础设施：无新增跨域副作用。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-109 · POST /biz/x/beauty/performance-adjustment-cases

目的：提交异议换人/退款后更正；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizPerformanceAdjustmentCaseController`

```java
AdjustmentCaseVO requestPerformanceAdjustment(@Valid @RequestBody PerformanceAdjustmentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.PerformanceService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.PerformanceServiceImpl`

```java
AdjustmentCaseVO requestPerformanceAdjustment(String entityNo, String currentStoreNo, String actorNo, PerformanceAdjustmentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.PerformanceAllocationMapper.selectList`；`BeautyMappers.PerformanceShareMapper.selectList`；`BeautyMappers.CommissionEntryMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用业绩异议审批。

守卫：审批后才由内部分配/追加冲回动作写业绩，不以申请直接改账。

#### BAPI-110 · POST /biz/x/beauty/commission-settlement-records

目的：登记提成结算审核与已发放凭证；阶段：P3；模式：**LOCAL_ATOMIC**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCommissionSettlementRecordController`

```java
SettlementRecordVO recordCommissionSettlement(@Valid @RequestBody RecordCommissionSettlementReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CommissionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CommissionServiceImpl`

```java
SettlementRecordVO recordCommissionSettlement(String entityNo, String currentStoreNo, String actorNo, RecordCommissionSettlementReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.CommissionEntryMapper.selectList`。

本域写入候选：`BeautyMappers.CommissionEntryMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.CommissionEntryMapper.recordPaymentProof`。

跨域/基础设施：共用审批/实际发放凭证核验。

守卫：只按版本追加凭证/状态，不发起银行代付。

### P 班次财务

本模块为共用能力接入，不生成美业同义Controller/Service/Mapper；共用入口见§三及§五。

### Q 查询与报表

#### BAPI-116 · GET /biz/x/beauty/customer-timelines/{customerNo}

目的：本人或受权顾客预约服务来源时间线；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCustomerTimelineController`

```java
PageData<CustomerTimelineVO> listCustomerTimeline(@PathVariable("customerNo") String customerNo, @Valid @ModelAttribute CustomerTimelineQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CustomerTimelineQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CustomerTimelineQueryServiceImpl`

```java
PageData<CustomerTimelineVO> listCustomerTimeline(String entityNo, String currentStoreNo, String actorNo, String customerNo, CustomerTimelineQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TreatmentPlanMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用订单/消费历史受权查询。

守卫：数据库分页，不先全表读出再ofAll；先在预约/工单生命周期读投影形成按occurredAt+kind+businessNo稳定排序的受权业务记录，再由该投影提供总数/分页，来源历史只经正式合同合并或装饰；禁止分别分页后拼接伪造total。

#### BAPI-117 · GET /biz/x/beauty/workbench/summary

目的：今日接待待服务未结异常概览；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentWorkbenchController`

```java
WorkbenchSummaryVO getWorkbenchSummary(@Valid @ModelAttribute WorkbenchSummaryQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentWorkbenchQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentWorkbenchQueryServiceImpl`

```java
WorkbenchSummaryVO getWorkbenchSummary(String entityNo, String currentStoreNo, String actorNo, WorkbenchSummaryQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用异常统计。

守卫：各业务号去重，和日历独立asOf。

#### BAPI-119 · GET /biz/x/beauty/reports/scheduling

目的：预约转化爽约资源利用率；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceReportController`

```java
SchedulingReportVO getSchedulingReport(@Valid @ModelAttribute SchedulingReportQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceReportQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceReportQueryServiceImpl`

```java
SchedulingReportVO getSchedulingReport(String entityNo, String currentStoreNo, String actorNo, Set<String> authorizedStoreNos, SchedulingReportQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`；`BeautyMappers.PerformanceAllocationMapper.selectList`；`BeautyMappers.PerformanceShareMapper.selectList`；`BeautyMappers.CommissionEntryMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用分析/库存成本事实。

守卫：读投影口径，不能当实时容量/资金事实。

#### BAPI-120 · GET /biz/x/beauty/reports/services

目的：项目服务产出成本复购业绩查询；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceReportController`

```java
ServiceReportVO getServiceReport(@Valid @ModelAttribute ServiceReportQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceReportQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceReportQueryServiceImpl`

```java
ServiceReportVO getServiceReport(String entityNo, String currentStoreNo, String actorNo, Set<String> authorizedStoreNos, ServiceReportQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.AppointmentAssignmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceParticipationMapper.selectList`；`BeautyMappers.PerformanceAllocationMapper.selectList`；`BeautyMappers.PerformanceShareMapper.selectList`；`BeautyMappers.CommissionEntryMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用分析/库存成本事实。

守卫：读投影口径，不能当实时容量/资金事实。

#### BAPI-121 · POST /biz/x/beauty/report-exports

目的：创建受权跨店导出任务；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizReportExportController`

```java
ReportExportVO requestReportExport(@Valid @RequestBody RequestReportExportReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceReportQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceReportQueryServiceImpl`

```java
ReportExportVO requestReportExport(String entityNo, String currentStoreNo, String actorNo, Set<String> authorizedStoreNos, RequestReportExportReq req, String idempotencyKey);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用导出任务受理。

守卫：记录受权范围与水位，不在本域再建一套下载任务表。

#### BAPI-122 · GET /biz/x/beauty/report-exports/{exportNo}

目的：查导出结果及更正水位；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizReportExportController`

```java
ReportExportVO getReportExport(@PathVariable("exportNo") String exportNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceReportQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceReportQueryServiceImpl`

```java
ReportExportVO getReportExport(String entityNo, String currentStoreNo, String actorNo, String exportNo);
```

本域读取：无直接本域表读取（使用共享提供方/快照缓存）。

本域写入候选：无。

跨域/基础设施：共用导出查询/二次下载鉴权。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-158 · GET /mp/x/beauty/timeline

目的：本人预约服务及来源历史；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.mp.MpCustomerTimelineController`

```java
PageData<MyTimelineVO> listMyTimeline(@Valid @ModelAttribute MyTimelineQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CustomerTimelineQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CustomerTimelineQueryServiceImpl`

```java
PageData<MyTimelineVO> listMyTimeline(String userNo, MyTimelineQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TreatmentPlanMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用订单/消费历史受权查询。

守卫：数据库分页，不先全表读出再ofAll；先在预约/工单生命周期读投影形成按occurredAt+kind+businessNo稳定排序的受权业务记录，再由该投影提供总数/分页，来源历史只经正式合同合并或装饰；禁止分别分页后拼接伪造total。

### R 消息待办回访

#### BAPI-124 · GET /biz/x/beauty/workbench/exceptions

目的：查询派工超时冲突来源异常待办；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizAppointmentWorkbenchController`

```java
PageData<ExceptionWorkitemVO> listWorkbenchExceptions(@Valid @ModelAttribute WorkbenchExceptionQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.AppointmentWorkbenchQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.AppointmentWorkbenchQueryServiceImpl`

```java
PageData<ExceptionWorkitemVO> listWorkbenchExceptions(String entityNo, String currentStoreNo, String actorNo, WorkbenchExceptionQuery query);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用待办投影。

守卫：使用统一待办投影分页与总数，本域表仅补充业务事实，不在第一张预约表分页代替待办列表。

#### BAPI-125 · POST /biz/x/beauty/exception-workitems/{workitemNo}/resolutions

目的：认领/协调/解决业务异常；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizExceptionWorkitemController`

```java
ExceptionResolutionVO resolveException(@PathVariable("workitemNo") String workitemNo, @Valid @RequestBody ResolveServiceExceptionReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceExceptionService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceExceptionServiceImpl`

```java
ExceptionResolutionVO resolveException(String entityNo, String currentStoreNo, String actorNo, String workitemNo, ResolveServiceExceptionReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用待办认领/解决；回到原聚合执行受权业务动作。

守卫：解决待办不能绕过原命令资格/来源/资源校验。

#### BAPI-126 · GET /biz/x/beauty/follow-up-tasks

目的：查询护理回访任务；阶段：P3；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizFollowUpTaskController`

```java
PageData<FollowUpTaskVO> listFollowUps(@Valid @ModelAttribute FollowUpQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.FollowUpCoordinationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.FollowUpCoordinationServiceImpl`

```java
PageData<FollowUpTaskVO> listFollowUps(String entityNo, String currentStoreNo, String actorNo, FollowUpQuery query);
```

本域读取：`BeautyMappers.CareProfileMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用回访任务查询。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-127 · POST /biz/x/beauty/follow-up-tasks

目的：建回访计划及分配；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizFollowUpTaskController`

```java
FollowUpTaskVO planFollowUp(@Valid @RequestBody PlanFollowUpReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.FollowUpCoordinationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.FollowUpCoordinationServiceImpl`

```java
FollowUpTaskVO planFollowUp(String entityNo, String currentStoreNo, String actorNo, PlanFollowUpReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.CareProfileMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用回访计划/记录/本人回复。

守卫：护理内容仍经护理接口授权，共用域管理回访任务状态。

#### BAPI-128 · POST /biz/x/beauty/follow-up-tasks/{followUpNo}/entries

目的：追加跟进和顾客回复；阶段：P3；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizFollowUpTaskController`

```java
FollowUpEntryVO appendFollowUpEntry(@PathVariable("followUpNo") String followUpNo, @Valid @RequestBody AppendFollowUpEntryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.FollowUpCoordinationService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.FollowUpCoordinationServiceImpl`

```java
FollowUpEntryVO appendFollowUpEntry(String entityNo, String currentStoreNo, String actorNo, String followUpNo, AppendFollowUpEntryReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.CareProfileMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用回访计划/记录/本人回复。

守卫：护理内容仍经护理接口授权，共用域管理回访任务状态。

### S 打印与设备

#### BAPI-130 · POST /biz/x/beauty/print-documents

目的：按真实来源生成脱敏文档快照；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServicePrintDocumentController`

```java
PrintDocumentVO createPrintDocument(@Valid @RequestBody CreateServicePrintDocumentReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServicePrintDocumentService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServicePrintDocumentServiceImpl`

```java
PrintDocumentVO createPrintDocument(String entityNo, String currentStoreNo, String actorNo, CreateServicePrintDocumentReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用文档快照/打印模板。

守卫：公共单不含护理正文照片及余额，打印任务/补打归共用。

### T 治理与恢复

#### BAPI-135 · GET /biz/x/beauty/capability-status

目的：查询门店接线兼容和验收能力；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizCapabilityController`

```java
CapabilityStatusVO getCapabilityStatus(@Valid @ModelAttribute CapabilityStatusQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.CapabilityService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.CapabilityServiceImpl`

```java
CapabilityStatusVO getCapabilityStatus(String entityNo, String currentStoreNo, String actorNo, CapabilityStatusQuery query);
```

本域读取：`BeautyMappers.StorePolicyMapper.selectList`。

本域写入候选：无。

跨域/基础设施：门店能力与协议兼容查询。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-137 · GET /biz/x/beauty/traces/{businessNo}

目的：关联预约工单任务来源资金状态；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceTraceController`

```java
BusinessTraceVO getBusinessTrace(@PathVariable("businessNo") String businessNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceTraceQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceTraceQueryServiceImpl`

```java
BusinessTraceVO getBusinessTrace(String entityNo, String currentStoreNo, String actorNo, String businessNo);
```

本域读取：`BeautyMappers.AppointmentMapper.selectList`；`BeautyMappers.WorkOrderMapper.selectList`；`BeautyMappers.ServiceTaskMapper.selectList`；`BeautyMappers.ServiceSubmissionMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`；`BeautyMappers.ActionLogMapper.selectList`。

本域写入候选：无。

跨域/基础设施：原来源状态关联查询。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-138 · GET /ops/x/beauty/recovery-workitems

目的：平台查已付未绑定资源事件积压；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.ops.OpsServiceRecoveryController`

```java
PageData<RecoveryWorkitemVO> listRecoveryWorkitems(@Valid @ModelAttribute RecoveryWorkitemQuery query);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceRecoveryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceRecoveryServiceImpl`

```java
PageData<RecoveryWorkitemVO> listRecoveryWorkitems(String entityNo, Set<String> authorizedStoreNos, String actorNo, RecoveryWorkitemQuery query);
```

本域读取：`BeautyMappers.ServiceSubmissionMapper.selectList`；`BeautyMappers.ActionLogMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用积压/恢复待办受控查询。

守卫：按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。

#### BAPI-139 · POST /ops/x/beauty/recovery-requests

目的：申请原号技术恢复；阶段：P1；模式：**ORCHESTRATED**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.ops.OpsServiceRecoveryController`

```java
RecoveryRequestVO requestRecovery(@Valid @RequestBody RequestServiceRecoveryReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceRecoveryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceRecoveryServiceImpl`

```java
RecoveryRequestVO requestRecovery(String entityNo, Set<String> authorizedStoreNos, String actorNo, RequestServiceRecoveryReq req, String idempotencyKey);
```

本域读取：`BeautyMappers.ServiceSubmissionMapper.selectList`；`BeautyMappers.TradeBindingMapper.selectList`。

本域写入候选：`BeautyMappers.ServiceSubmissionMapper.insert / 受控update分支`；`BeautyMappers.TradeBindingMapper.insert`；`BeautyMappers.SourceAllocationGuardMapper.insert / 受控update分支`；`BeautyMappers.ServiceTaskMapper.insert / 受控update分支`；`BeautyMappers.ActionLogMapper.insert`。

关键自定义调用：`BeautyMappers.ServiceSubmissionMapper.selectScopedForUpdate`；`BeautyMappers.ServiceSubmissionMapper.advanceProgress`。

跨域/基础设施：受控恢复审批；原来源/资源号查询。

守卫：技术恢复不能代授权或代完工，必须走相同Service不直接改表。

#### BAPI-160 · GET /biz/x/beauty/operations/{operationNo}

目的：查询异步命令的原号进度和阻断原因；阶段：P1；模式：**READ_MODEL**。

Controller（beauty-web）：`ai.neargo.shop.beauty.api.biz.BizServiceOperationController`

```java
OperationVO getOperation(@PathVariable("operationNo") String operationNo);
```

Service（beauty-api）：`ai.neargo.shop.beauty.service.ServiceOperationQueryService`；Impl（beauty-core）：`ai.neargo.shop.beauty.service.impl.ServiceOperationQueryServiceImpl`

```java
OperationVO getOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo);
```

本域读取：`BeautyMappers.ServiceSubmissionMapper.selectList`；`BeautyMappers.ActionLogMapper.selectList`。

本域写入候选：无。

跨域/基础设施：共用异步操作目录/受权目标映射。

守卫：仅美业操作原号；资金原号仍由其所有者查询。

## 三、11条共用HTTP目标与实施对照

以下为人员/资源所有者的目标及部分已实施签名；逐项状态见API清单与主TDD。人员、物理资源、周班表读写已编码，P3例外审批等仍待实施；这些类不在beauty-web/api/core中生成。归shop-merchant自身既有包边界，Service接口/Impl按原规范分包；Controller不得转而依赖人员Mapper。具体共享实现要同步共享契约评审与独立架构扫描。

### BAPI-012 · GET /biz/personnel

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizPersonnelController`；输入类型：`ai.neargo.shop.merchant.dto.PersonnelQuery`。返回VO沿同域merchant.dto；非美业DTO。

```java
PageData<PersonnelVO> listPersonnel(@Valid @ModelAttribute PersonnelQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.PersonnelService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelServiceImpl`。

```java
PageData<PersonnelVO> listPersonnel(String entityNo, String currentStoreNo, String actorNo, PersonnelQuery query);
```

### BAPI-013 · POST /biz/personnel

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizPersonnelController`；输入类型：`ai.neargo.shop.merchant.dto.CreatePersonnelReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
PersonnelVO createPersonnel(@Valid @RequestBody CreatePersonnelReq request, @RequestHeader("Idempotency-Key") String key, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.PersonnelService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelServiceImpl`。

```java
PersonnelVO createPersonnel(String entityNo, String currentStoreNo, String actorNo, CreatePersonnelReq request, String idempotencyKey);
```

### BAPI-014 · POST /biz/personnel/{personnelNo}/status-changes

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizPersonnelController`；输入类型：`ai.neargo.shop.merchant.dto.ChangePersonnelStatusReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
PersonnelChangeVO changePersonnelStatus(@PathVariable("personnelNo") String personnelNo, @Valid @RequestBody ChangePersonnelStatusReq request, @RequestHeader("Idempotency-Key") String key, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.PersonnelService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelServiceImpl`。

```java
PersonnelChangeVO changePersonnelStatus(String entityNo, String currentStoreNo, String actorNo, String personnelNo, ChangePersonnelStatusReq request, String idempotencyKey);
```

### BAPI-015 · POST /biz/personnel/{personnelNo}/account-links

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizPersonnelController`；输入类型：`ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
PersonnelAccountLinkVO linkPersonnelAccount(@PathVariable("personnelNo") String personnelNo, @Valid @RequestBody LinkPersonnelAccountReq request, @RequestHeader("Idempotency-Key") String key, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.PersonnelService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelServiceImpl`。

```java
PersonnelAccountLinkVO linkPersonnelAccount(String entityNo, String currentStoreNo, String actorNo, String personnelNo, LinkPersonnelAccountReq request, String idempotencyKey);
```

### BAPI-016 · POST /biz/personnel/{personnelNo}/assignments

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizPersonnelController`；输入类型：`ai.neargo.shop.merchant.dto.AssignPersonnelReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
PersonnelAssignmentVO assignPersonnel(@PathVariable("personnelNo") String personnelNo, @Valid @RequestBody AssignPersonnelReq request, @RequestHeader("Idempotency-Key") String key, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.PersonnelService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelServiceImpl`。

```java
PersonnelAssignmentVO assignPersonnel(String entityNo, String currentStoreNo, String actorNo, String personnelNo, AssignPersonnelReq request, String idempotencyKey);
```

### BAPI-018 · POST /biz/personnel-cooperations

状态：CANDIDATE_SHARED_OWNER_NOT_EXISTING_API。

Controller：`ai.neargo.shop.merchant.api.biz.BizPersonnelCooperationController`；输入类型：`ai.neargo.shop.merchant.dto.SetPersonnelCooperationReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
PersonnelCooperationVO setCooperation(@Valid @RequestBody SetPersonnelCooperationReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service：`ai.neargo.shop.merchant.service.PersonnelService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelServiceImpl`。

```java
PersonnelCooperationVO setCooperation(String entityNo, String currentStoreNo, String actorNo, SetPersonnelCooperationReq req, String idempotencyKey);
```

### BAPI-020 · GET /biz/resources

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizResourceController`；输入类型：`ai.neargo.shop.merchant.dto.ResourceQuery`。返回VO沿同域merchant.dto；非美业DTO。

```java
PageData<ResourceVO> listResources(@Valid @ModelAttribute ResourceQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.ResourceService`；Impl：`ai.neargo.shop.merchant.service.impl.ResourceServiceImpl`。

```java
PageData<ResourceVO> listResources(String entityNo, String currentStoreNo, String actorNo, ResourceQuery query);
```

### BAPI-021 · POST /biz/resources

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizResourceController`；输入类型：`ai.neargo.shop.merchant.dto.SaveResourceReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
ResourceVO saveResource(@Valid @RequestBody SaveResourceReq request, @RequestHeader("Idempotency-Key") String key, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.ResourceService`；Impl：`ai.neargo.shop.merchant.service.impl.ResourceServiceImpl`。

```java
ResourceVO saveResource(String entityNo, String currentStoreNo, String actorNo, SaveResourceReq request, String idempotencyKey);
```

### BAPI-022 · GET /biz/schedules

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizScheduleController`；输入类型：`ai.neargo.shop.merchant.dto.PersonnelScheduleQuery`。返回VO沿同域merchant.dto；非美业DTO。

```java
ScheduleVO getSchedules(@Valid @ModelAttribute PersonnelScheduleQuery query, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.PersonnelScheduleService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelScheduleServiceImpl`。

```java
ScheduleVO getSchedules(String entityNo, String currentStoreNo, String actorNo, PersonnelScheduleQuery query);
```

### BAPI-023 · POST /biz/schedule-changes/previews

状态：CANDIDATE_SHARED_OWNER_NOT_EXISTING_API。

Controller：`ai.neargo.shop.merchant.api.biz.BizScheduleChangeController`；输入类型：`ai.neargo.shop.merchant.dto.PreviewScheduleChangeReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
ScheduleImpactVO previewScheduleChange(@Valid @RequestBody PreviewScheduleChangeReq req);
```

Service：`ai.neargo.shop.merchant.service.PersonnelScheduleService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelScheduleServiceImpl`。

```java
ScheduleImpactVO previewScheduleChange(String entityNo, String currentStoreNo, String actorNo, PreviewScheduleChangeReq req);
```

### BAPI-024 · POST /biz/schedule-changes

状态：CANDIDATE_SHARED_OWNER_NOT_EXISTING_API。

Controller：`ai.neargo.shop.merchant.api.biz.BizScheduleChangeController`；输入类型：`ai.neargo.shop.merchant.dto.ChangeScheduleReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
ScheduleChangeVO changeSchedule(@Valid @RequestBody ChangeScheduleReq req, @RequestHeader("Idempotency-Key") String idempotencyKey);
```

Service：`ai.neargo.shop.merchant.service.PersonnelScheduleService`；Impl：`ai.neargo.shop.merchant.service.impl.PersonnelScheduleServiceImpl`。

```java
ScheduleChangeVO changeSchedule(String entityNo, String currentStoreNo, String actorNo, ChangeScheduleReq req, String idempotencyKey);
```

### BAPI-022补充 · POST /biz/schedule-rules

状态：IMPLEMENTED_BM02_ISOLATED_DEPLOYMENT_PASSED。

Controller：`ai.neargo.shop.merchant.api.biz.BizScheduleController`；输入类型：`ai.neargo.shop.merchant.dto.SaveScheduleRuleReq`。返回VO沿同域merchant.dto；非美业DTO。

```java
ScheduleRuleVO saveScheduleRule(@Valid @RequestBody SaveScheduleRuleReq request, @RequestHeader("Idempotency-Key") String key, @RequestHeader(value = "X-Store-No", required = false) String requestedStore);
```

Service：`ai.neargo.shop.merchant.service.ScheduleService`；Impl：`ai.neargo.shop.merchant.service.impl.ScheduleServiceImpl`。

```java
ScheduleRuleVO saveScheduleRule(String entityNo, String currentStoreNo, String actorNo, SaveScheduleRuleReq request, String idempotencyKey);
```

### 本批已冻结的共用维护DTO

| 类（shop-merchant） | 字段合同 |
|---|---|
| `ai.neargo.shop.merchant.dto.PersonnelQuery` | Integer page,size; String status,keyword,storeNo,skill（有效资格goodsNo） |
| `ai.neargo.shop.merchant.dto.CreatePersonnelReq` | String displayName,avatarRef; AssignPersonnelReq assignment（expectedRevision=0且assignmentNo=null） |
| `ai.neargo.shop.merchant.dto.AssignPersonnelReq` | Long expectedRevision; String assignmentNo,storeNo; OffsetDateTime validFrom,validTo; Boolean publicVisible; String levelCode; Integer sortOrder; List<String> positions; List<Skill> skills；Skill(goodsNo,skuNo,levelCode) |
| `ai.neargo.shop.merchant.dto.LinkPersonnelAccountReq` | Long expectedRevision; String accountNo（null为解绑） |
| `ai.neargo.shop.merchant.dto.ChangePersonnelStatusReq` | Long expectedRevision; String status,reason（1..500字符；完整审计，不广播到事件） |
| `ai.neargo.shop.merchant.dto.PersonnelVO` | personnelNo,displayName,avatarRef,status,revision,resourceNo,List<Assignment>,List<Qualification>；当前/未来有效配置，无账号/联系资料/物理id；历史存储保留 |
| `ai.neargo.shop.merchant.dto.PersonnelAssignmentVO` | personnelNo,revision,assignmentNo |
| `ai.neargo.shop.merchant.dto.PersonnelAccountLinkVO` | personnelNo,revision,accountNo |
| `ai.neargo.shop.merchant.dto.PersonnelChangeVO` | personnelNo,revision,status,accountDisabled（仅实际账号停用已回查后true） |
| `ai.neargo.shop.merchant.dto.ResourceQuery` | Integer page,size; String storeNo,type,status,keyword；默认1/20，上限100，storeNo仅当前店确认 |
| `ai.neargo.shop.merchant.dto.SaveResourceReq` | String resourceNo?,type,personnelNo=null,storeNo?,name,status; Integer capacity; Long expectedRevision；物理类型/归属不可变，STAFF由人员维护 |
| `ai.neargo.shop.merchant.dto.ResourceVO` | String resourceNo,type,storeNo,personnelNo,name,status; int capacity; long revision; boolean openOccupied；无物理id/占用凭证/剩余容量 |
| `ai.neargo.shop.merchant.dto.ScheduleQuery` | Set<String> resourceNos; OffsetDateTime from,to；1..50号、最多31日 |
| `ai.neargo.shop.merchant.dto.SaveScheduleRuleReq` | String ruleNo?,resourceNo,timezone,status,reason; Long expectedRevision（资源根）; Integer weekdayMask,startMinute,endMinute,endDayOffset; LocalDate validFrom,validTo（必填，最多366开始日） |
| `ai.neargo.shop.merchant.dto.ScheduleVO` | OffsetDateTime from,to; Map<String,Long> resourceRevisions; List<Rule> rules; List<ScheduleWindowsPort.Window> windows；非容量余量 |
| `ai.neargo.shop.merchant.dto.ScheduleRuleVO` | String ruleNo,resourceNo,status; long resourceRevision；替换新号，停用旧号 |

实际字段校验和修订语义以同域源码及主TDD§2.12（人员）、§2.13（资源）、§2.14（周班表）为准；不加入美业私有DTO/表统计。

共用MerchantMappers与mch表的具体增量不在美业BeautyMappers中生成；人员同主体稳定号、账号可选关联、跨店统一STAFF资源、任职资格有效期及完整资源原子仲裁，沿数据库/工作台方案验证。

## 四、25个美业Mapper直接方法

完整外层：`ai.neargo.shop.beauty.mapper.BeautyMappers`，源码归beauty-core。下列CRUD是继承方法的常用调用形状，**不需要重新声明/实现**；新增自定义方法才写SQL。Wrapper由Service构造，强制主体/门店或bty受控父链、合法状态/版本。返回Entity只在core内使用，不返Controller。

自定义SQL必须显式tenant/entity/业务键（子行经受权父链）范围；当前tenant沿MAIN，不新增客户端租户字段。CAS携带expectedVersion，成功version+1，并明确updatedBy/updatedAt；采用标量入参时不能假设MetaObjectHandler自动填审计。自定义int返回0必须转为明确冲突/越权/状态错误，不能静默成功。

### ai.neargo.shop.beauty.mapper.BeautyMappers.StorePolicyMapper

表：`bty_store_policy`；Entity：`ai.neargo.shop.beauty.entity.BtyStorePolicy`。

继承调用形状：

```java
int insert(BtyStorePolicy row);
BtyStorePolicy selectOne(Wrapper<BtyStorePolicy> queryWrapper);
List<BtyStorePolicy> selectList(Wrapper<BtyStorePolicy> queryWrapper);
<P extends IPage<BtyStorePolicy>> P selectPage(P page, Wrapper<BtyStorePolicy> queryWrapper);
int update(BtyStorePolicy row, Wrapper<BtyStorePolicy> updateWrapper);
```

```java
BtyStorePolicy selectScopedForUpdate(String entityNo, String storeNo, String policyNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
int switchCurrentVersion(String entityNo, String storeNo, String policyNo, long expectedVersion, String policyVersionNo, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：不可变政策版本已插入后切指针；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.StorePolicyVersionMapper

表：`bty_store_policy_version`；Entity：`ai.neargo.shop.beauty.entity.BtyStorePolicyVersion`。

继承调用形状：

```java
int insert(BtyStorePolicyVersion row);
BtyStorePolicyVersion selectOne(Wrapper<BtyStorePolicyVersion> queryWrapper);
List<BtyStorePolicyVersion> selectList(Wrapper<BtyStorePolicyVersion> queryWrapper);
<P extends IPage<BtyStorePolicyVersion>> P selectPage(P page, Wrapper<BtyStorePolicyVersion> queryWrapper);
int update(BtyStorePolicyVersion row, Wrapper<BtyStorePolicyVersion> updateWrapper);
```

无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceDefinitionMapper

表：`bty_service_definition`；Entity：`ai.neargo.shop.beauty.entity.BtyServiceDefinition`。

继承调用形状：

```java
int insert(BtyServiceDefinition row);
BtyServiceDefinition selectOne(Wrapper<BtyServiceDefinition> queryWrapper);
List<BtyServiceDefinition> selectList(Wrapper<BtyServiceDefinition> queryWrapper);
<P extends IPage<BtyServiceDefinition>> P selectPage(P page, Wrapper<BtyServiceDefinition> queryWrapper);
int update(BtyServiceDefinition row, Wrapper<BtyServiceDefinition> updateWrapper);
```

```java
BtyServiceDefinition selectScopedForUpdate(String entityNo, String storeNo, String definitionNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
int switchCurrentVersion(String entityNo, String storeNo, String definitionNo, long expectedVersion, String definitionVersionNo, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：定义版本/要求已完整插入后切指针；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceDefinitionVersionMapper

表：`bty_service_definition_version`；Entity：`ai.neargo.shop.beauty.entity.BtyServiceDefinitionVersion`。

继承调用形状：

```java
int insert(BtyServiceDefinitionVersion row);
BtyServiceDefinitionVersion selectOne(Wrapper<BtyServiceDefinitionVersion> queryWrapper);
List<BtyServiceDefinitionVersion> selectList(Wrapper<BtyServiceDefinitionVersion> queryWrapper);
<P extends IPage<BtyServiceDefinitionVersion>> P selectPage(P page, Wrapper<BtyServiceDefinitionVersion> queryWrapper);
int update(BtyServiceDefinitionVersion row, Wrapper<BtyServiceDefinitionVersion> updateWrapper);
```

无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceRequirementMapper

表：`bty_service_requirement`；Entity：`ai.neargo.shop.beauty.entity.BtyServiceRequirement`。

继承调用形状：

```java
int insert(BtyServiceRequirement row);
BtyServiceRequirement selectOne(Wrapper<BtyServiceRequirement> queryWrapper);
List<BtyServiceRequirement> selectList(Wrapper<BtyServiceRequirement> queryWrapper);
<P extends IPage<BtyServiceRequirement>> P selectPage(P page, Wrapper<BtyServiceRequirement> queryWrapper);
int update(BtyServiceRequirement row, Wrapper<BtyServiceRequirement> updateWrapper);
```

无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。

### ai.neargo.shop.beauty.mapper.BeautyMappers.AppointmentMapper

表：`bty_appointment`；Entity：`ai.neargo.shop.beauty.entity.BtyAppointment`。

继承调用形状：

```java
int insert(BtyAppointment row);
BtyAppointment selectOne(Wrapper<BtyAppointment> queryWrapper);
List<BtyAppointment> selectList(Wrapper<BtyAppointment> queryWrapper);
<P extends IPage<BtyAppointment>> P selectPage(P page, Wrapper<BtyAppointment> queryWrapper);
int update(BtyAppointment row, Wrapper<BtyAppointment> updateWrapper);
```

```java
BtyAppointment selectScopedForUpdate(String entityNo, String storeNo, String appointmentNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
int changeSchedule(String entityNo, String storeNo, String appointmentNo, long expectedVersion, String expectedStatus, LocalDateTime startAt, LocalDateTime endAt, String holdNo, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：资源新组合权威确认后变更时间与hold引用，历史assignment只追加；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

```java
int changeStatus(String entityNo, String storeNo, String appointmentNo, long expectedVersion, String expectedStatus, String targetStatus, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：取消/爽约/确认等分支由服务器状态机给目标态，不采信客户端随意目标；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

```java
int markArrived(String entityNo, String storeNo, String appointmentNo, long expectedVersion, String expectedStatus, LocalDateTime checkedInAt, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：幂等签到不代替订单履约；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.AppointmentItemMapper

表：`bty_appointment_item`；Entity：`ai.neargo.shop.beauty.entity.BtyAppointmentItem`。

继承调用形状：

```java
int insert(BtyAppointmentItem row);
BtyAppointmentItem selectOne(Wrapper<BtyAppointmentItem> queryWrapper);
List<BtyAppointmentItem> selectList(Wrapper<BtyAppointmentItem> queryWrapper);
<P extends IPage<BtyAppointmentItem>> P selectPage(P page, Wrapper<BtyAppointmentItem> queryWrapper);
int update(BtyAppointmentItem row, Wrapper<BtyAppointmentItem> updateWrapper);
```

无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。

### ai.neargo.shop.beauty.mapper.BeautyMappers.AppointmentAssignmentMapper

表：`bty_appointment_assignment`；Entity：`ai.neargo.shop.beauty.entity.BtyAppointmentAssignment`。

继承调用形状：

```java
int insert(BtyAppointmentAssignment row);
BtyAppointmentAssignment selectOne(Wrapper<BtyAppointmentAssignment> queryWrapper);
List<BtyAppointmentAssignment> selectList(Wrapper<BtyAppointmentAssignment> queryWrapper);
<P extends IPage<BtyAppointmentAssignment>> P selectPage(P page, Wrapper<BtyAppointmentAssignment> queryWrapper);
int update(BtyAppointmentAssignment row, Wrapper<BtyAppointmentAssignment> updateWrapper);
```

```java
List<BtyAppointmentAssignment> selectByAppointmentRevision(String entityNo, String storeNo, String appointmentNo, long appointmentRevision);
```

READ守卫：经appointment_item→appointment受权父链过滤；历史安排不按当前人物反推。

### ai.neargo.shop.beauty.mapper.BeautyMappers.WorkOrderMapper

表：`bty_work_order`；Entity：`ai.neargo.shop.beauty.entity.BtyWorkOrder`。

继承调用形状：

```java
int insert(BtyWorkOrder row);
BtyWorkOrder selectOne(Wrapper<BtyWorkOrder> queryWrapper);
List<BtyWorkOrder> selectList(Wrapper<BtyWorkOrder> queryWrapper);
<P extends IPage<BtyWorkOrder>> P selectPage(P page, Wrapper<BtyWorkOrder> queryWrapper);
int update(BtyWorkOrder row, Wrapper<BtyWorkOrder> updateWrapper);
```

```java
BtyWorkOrder selectScopedForUpdate(String entityNo, String storeNo, String workOrderNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
int freezeScope(String entityNo, String storeNo, String workOrderNo, long expectedVersion, long expectedScopeRevision, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：锁范围后冻结并形成关闭检查点；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

```java
int advanceScope(String entityNo, String storeNo, String workOrderNo, long expectedVersion, long expectedScopeRevision, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：仅未冻结范围可加项/撤项，revision单调增加；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

```java
int closeFrozenScope(String entityNo, String storeNo, String workOrderNo, long expectedVersion, long expectedScopeRevision, LocalDateTime closedAt, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：真实任务/绑定版本已重验且全部满足时关闭；不能只查陈旧完成计数；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceTaskMapper

表：`bty_service_task`；Entity：`ai.neargo.shop.beauty.entity.BtyServiceTask`。

继承调用形状：

```java
int insert(BtyServiceTask row);
BtyServiceTask selectOne(Wrapper<BtyServiceTask> queryWrapper);
List<BtyServiceTask> selectList(Wrapper<BtyServiceTask> queryWrapper);
<P extends IPage<BtyServiceTask>> P selectPage(P page, Wrapper<BtyServiceTask> queryWrapper);
int update(BtyServiceTask row, Wrapper<BtyServiceTask> updateWrapper);
```

```java
BtyServiceTask selectScopedForUpdate(String entityNo, String storeNo, String taskNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
int changeStatus(String entityNo, String storeNo, String taskNo, long expectedVersion, long expectedBindingRevision, String expectedStatus, String targetStatus, LocalDateTime occurredAt, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：动作合法且来源许可已确认；按动作设置相应时间；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

```java
int recordCompletion(String entityNo, String storeNo, String taskNo, long expectedVersion, long expectedBindingRevision, BigDecimal incrementQty, LocalDateTime finishedAt, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：incrementQty>0且done_qty+incrementQty<=qty-voided_qty；全部事实/提交检查点同一短事务；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

```java
int switchBindingRevision(String entityNo, String storeNo, String taskNo, long expectedVersion, long expectedBindingRevision, long newBindingRevision, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：新绑定和source guard已提交；新revision>旧revision，不覆盖旧绑定行；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceStepMapper

表：`bty_service_step`；Entity：`ai.neargo.shop.beauty.entity.BtyServiceStep`。

继承调用形状：

```java
int insert(BtyServiceStep row);
BtyServiceStep selectOne(Wrapper<BtyServiceStep> queryWrapper);
List<BtyServiceStep> selectList(Wrapper<BtyServiceStep> queryWrapper);
<P extends IPage<BtyServiceStep>> P selectPage(P page, Wrapper<BtyServiceStep> queryWrapper);
int update(BtyServiceStep row, Wrapper<BtyServiceStep> updateWrapper);
```

```java
int completeStep(String entityNo, String storeNo, String stepNo, String taskNo, long expectedVersion, LocalDateTime finishedAt, String resultSnapshot, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：先按固定顺序锁workOrder/task，再校验step状态与顺序，完成只追加合法结果；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceParticipationMapper

表：`bty_service_participation`；Entity：`ai.neargo.shop.beauty.entity.BtyServiceParticipation`。

继承调用形状：

```java
int insert(BtyServiceParticipation row);
BtyServiceParticipation selectOne(Wrapper<BtyServiceParticipation> queryWrapper);
List<BtyServiceParticipation> selectList(Wrapper<BtyServiceParticipation> queryWrapper);
<P extends IPage<BtyServiceParticipation>> P selectPage(P page, Wrapper<BtyServiceParticipation> queryWrapper);
int update(BtyServiceParticipation row, Wrapper<BtyServiceParticipation> updateWrapper);
```

```java
int endParticipation(String entityNo, String storeNo, String participationNo, long expectedVersion, LocalDateTime endedAt, String reason, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：只收口尚未结束区间；新参与段另insert，开始前后不得倒置；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ServiceSubmissionMapper

表：`bty_service_submission`；Entity：`ai.neargo.shop.beauty.entity.BtyServiceSubmission`。

继承调用形状：

```java
int insert(BtyServiceSubmission row);
BtyServiceSubmission selectOne(Wrapper<BtyServiceSubmission> queryWrapper);
List<BtyServiceSubmission> selectList(Wrapper<BtyServiceSubmission> queryWrapper);
<P extends IPage<BtyServiceSubmission>> P selectPage(P page, Wrapper<BtyServiceSubmission> queryWrapper);
int update(BtyServiceSubmission row, Wrapper<BtyServiceSubmission> updateWrapper);
```

```java
BtyServiceSubmission selectByRequestKey(String entityNo, String storeNo, String requestKeyHash);
```

READ守卫：请求唯一键冲突后比对payloadHash，异参拒绝；同参返回原意图。 原签名不含action，未来Service将action纳入规范键摘要；若库内跨action同摘要多行，单行读取拒绝歧义，禁止LIMIT1任取。

```java
BtyServiceSubmission selectScopedForUpdate(String entityNo, String storeNo, String submissionNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
List<BtyServiceSubmission> selectDueForUpdate(String entityNo, String storeNo, LocalDateTime now, int limit);
```

LOCK守卫：短事务有界FOR UPDATE SKIP LOCKED，取走时同事务推进nextRetryAt/version；外部调用不持锁，幂等原号抗重入。 limit只接受1..100，空retry不视为到期。

```java
int advanceProgress(String entityNo, String storeNo, String submissionNo, long expectedVersion, String expectedStatus, String targetStatus, LocalDateTime nextRetryAt, String failureCode, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：按固定状态迁移推进；未知不能写成失败后另起来源；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。 活动态租约按DATETIME(3)持久化精度严格晚于updatedAt；终态清空retry，REJECTED需安全错误码。

### ai.neargo.shop.beauty.mapper.BeautyMappers.TradeBindingMapper

表：`bty_trade_binding`；Entity：`ai.neargo.shop.beauty.entity.BtyTradeBinding`。

继承调用形状：

```java
int insert(BtyTradeBinding row);
BtyTradeBinding selectOne(Wrapper<BtyTradeBinding> queryWrapper);
List<BtyTradeBinding> selectList(Wrapper<BtyTradeBinding> queryWrapper);
<P extends IPage<BtyTradeBinding>> P selectPage(P page, Wrapper<BtyTradeBinding> queryWrapper);
int update(BtyTradeBinding row, Wrapper<BtyTradeBinding> updateWrapper);
```

```java
List<BtyTradeBinding> selectByTaskRevisions(String entityNo, String storeNo, List<String> taskNos, long bindingRevision);
```

READ守卫：从已鉴权任务父链读当前指定版本；不同任务版本按版本分组批查，避免逐卡片N+1。

### ai.neargo.shop.beauty.mapper.BeautyMappers.ActionLogMapper

表：`bty_action_log`；Entity：`ai.neargo.shop.beauty.entity.BtyActionLog`。

继承调用形状：

```java
int insert(BtyActionLog row);
BtyActionLog selectOne(Wrapper<BtyActionLog> queryWrapper);
List<BtyActionLog> selectList(Wrapper<BtyActionLog> queryWrapper);
<P extends IPage<BtyActionLog>> P selectPage(P page, Wrapper<BtyActionLog> queryWrapper);
int update(BtyActionLog row, Wrapper<BtyActionLog> updateWrapper);
```

无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。

### ai.neargo.shop.beauty.mapper.BeautyMappers.CareProfileMapper

表：`bty_care_profile`；Entity：`ai.neargo.shop.beauty.entity.BtyCareProfile`。

继承调用形状：

```java
int insert(BtyCareProfile row);
BtyCareProfile selectOne(Wrapper<BtyCareProfile> queryWrapper);
List<BtyCareProfile> selectList(Wrapper<BtyCareProfile> queryWrapper);
<P extends IPage<BtyCareProfile>> P selectPage(P page, Wrapper<BtyCareProfile> queryWrapper);
int update(BtyCareProfile row, Wrapper<BtyCareProfile> updateWrapper);
```

```java
BtyCareProfile selectScopedForUpdate(String entityNo, String storeNo, String profileNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
int changeAccessStatus(String entityNo, String storeNo, String profileNo, long expectedVersion, String targetStatus, String accessPolicyRef, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：只执行共用隐私流程已批准的受控状态动作，不物理抹除历史事实；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.CareNoteMapper

表：`bty_care_note`；Entity：`ai.neargo.shop.beauty.entity.BtyCareNote`。

继承调用形状：

```java
int insert(BtyCareNote row);
BtyCareNote selectOne(Wrapper<BtyCareNote> queryWrapper);
List<BtyCareNote> selectList(Wrapper<BtyCareNote> queryWrapper);
<P extends IPage<BtyCareNote>> P selectPage(P page, Wrapper<BtyCareNote> queryWrapper);
int update(BtyCareNote row, Wrapper<BtyCareNote> updateWrapper);
```

无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。

### ai.neargo.shop.beauty.mapper.BeautyMappers.CareMediaMapper

表：`bty_care_media`；Entity：`ai.neargo.shop.beauty.entity.BtyCareMedia`。

继承调用形状：

```java
int insert(BtyCareMedia row);
BtyCareMedia selectOne(Wrapper<BtyCareMedia> queryWrapper);
List<BtyCareMedia> selectList(Wrapper<BtyCareMedia> queryWrapper);
<P extends IPage<BtyCareMedia>> P selectPage(P page, Wrapper<BtyCareMedia> queryWrapper);
int update(BtyCareMedia row, Wrapper<BtyCareMedia> updateWrapper);
```

```java
int changeMediaStatus(String entityNo, String storeNo, String careMediaNo, long expectedVersion, String targetStatus, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：私有媒体撤权与本域引用状态分别确认，旧授权不因状态修改继续有效；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.TreatmentPlanMapper

表：`bty_treatment_plan`；Entity：`ai.neargo.shop.beauty.entity.BtyTreatmentPlan`。

继承调用形状：

```java
int insert(BtyTreatmentPlan row);
BtyTreatmentPlan selectOne(Wrapper<BtyTreatmentPlan> queryWrapper);
List<BtyTreatmentPlan> selectList(Wrapper<BtyTreatmentPlan> queryWrapper);
<P extends IPage<BtyTreatmentPlan>> P selectPage(P page, Wrapper<BtyTreatmentPlan> queryWrapper);
int update(BtyTreatmentPlan row, Wrapper<BtyTreatmentPlan> updateWrapper);
```

```java
BtyTreatmentPlan selectScopedForUpdate(String entityNo, String storeNo, String planNo);
```

LOCK守卫：只锁已存在根；未查到不等于建立互斥锁。创建靠业务唯一键＋幂等意图，禁止空查询充当锁。

```java
int advancePlanRevision(String entityNo, String storeNo, String planNo, long expectedVersion, long expectedPlanRevision, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：只调整计划版本，不修改权益账户余次；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.TreatmentSessionMapper

表：`bty_treatment_session`；Entity：`ai.neargo.shop.beauty.entity.BtyTreatmentSession`。

继承调用形状：

```java
int insert(BtyTreatmentSession row);
BtyTreatmentSession selectOne(Wrapper<BtyTreatmentSession> queryWrapper);
List<BtyTreatmentSession> selectList(Wrapper<BtyTreatmentSession> queryWrapper);
<P extends IPage<BtyTreatmentSession>> P selectPage(P page, Wrapper<BtyTreatmentSession> queryWrapper);
int update(BtyTreatmentSession row, Wrapper<BtyTreatmentSession> updateWrapper);
```

```java
int bindServiceReferences(String entityNo, String storeNo, String sessionNo, long expectedVersion, String appointmentNo, String taskNo, String assetUseRef, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：预约/任务均已确认归同顾客门店，实际assetUseRef只取共用使用结果；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.PerformanceAllocationMapper

表：`bty_performance_allocation`；Entity：`ai.neargo.shop.beauty.entity.BtyPerformanceAllocation`。

继承调用形状：

```java
int insert(BtyPerformanceAllocation row);
BtyPerformanceAllocation selectOne(Wrapper<BtyPerformanceAllocation> queryWrapper);
List<BtyPerformanceAllocation> selectList(Wrapper<BtyPerformanceAllocation> queryWrapper);
<P extends IPage<BtyPerformanceAllocation>> P selectPage(P page, Wrapper<BtyPerformanceAllocation> queryWrapper);
int update(BtyPerformanceAllocation row, Wrapper<BtyPerformanceAllocation> updateWrapper);
```

```java
BtyPerformanceAllocation lockAllocationAnchor(String entityNo, String storeNo, String sourceKind, String sourceNo, String basisCode);
```

LOCK守卫：锁同来源/basisCode的revision_index=1既存锚点；首版创建靠唯一键仲裁，未存在不空锁，后续版本/更正追加。

### ai.neargo.shop.beauty.mapper.BeautyMappers.PerformanceShareMapper

表：`bty_performance_share`；Entity：`ai.neargo.shop.beauty.entity.BtyPerformanceShare`。

继承调用形状：

```java
int insert(BtyPerformanceShare row);
BtyPerformanceShare selectOne(Wrapper<BtyPerformanceShare> queryWrapper);
List<BtyPerformanceShare> selectList(Wrapper<BtyPerformanceShare> queryWrapper);
<P extends IPage<BtyPerformanceShare>> P selectPage(P page, Wrapper<BtyPerformanceShare> queryWrapper);
int update(BtyPerformanceShare row, Wrapper<BtyPerformanceShare> updateWrapper);
```

无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。

### ai.neargo.shop.beauty.mapper.BeautyMappers.CommissionPolicyMapper

表：`bty_commission_policy`；Entity：`ai.neargo.shop.beauty.entity.BtyCommissionPolicy`。

继承调用形状：

```java
int insert(BtyCommissionPolicy row);
BtyCommissionPolicy selectOne(Wrapper<BtyCommissionPolicy> queryWrapper);
List<BtyCommissionPolicy> selectList(Wrapper<BtyCommissionPolicy> queryWrapper);
<P extends IPage<BtyCommissionPolicy>> P selectPage(P page, Wrapper<BtyCommissionPolicy> queryWrapper);
int update(BtyCommissionPolicy row, Wrapper<BtyCommissionPolicy> updateWrapper);
```

```java
BtyCommissionPolicy lockRuleGroupAnchor(String entityNo, String storeNo, String ruleGroupNo);
```

LOCK守卫：锁ruleGroupNo的revision_index=1稳定首版；首次并发插入靠唯一键拒重复，后续版本追加不覆盖历史。

### ai.neargo.shop.beauty.mapper.BeautyMappers.CommissionEntryMapper

表：`bty_commission_entry`；Entity：`ai.neargo.shop.beauty.entity.BtyCommissionEntry`。

继承调用形状：

```java
int insert(BtyCommissionEntry row);
BtyCommissionEntry selectOne(Wrapper<BtyCommissionEntry> queryWrapper);
List<BtyCommissionEntry> selectList(Wrapper<BtyCommissionEntry> queryWrapper);
<P extends IPage<BtyCommissionEntry>> P selectPage(P page, Wrapper<BtyCommissionEntry> queryWrapper);
int update(BtyCommissionEntry row, Wrapper<BtyCommissionEntry> updateWrapper);
```

```java
BtyCommissionEntry selectByBusinessKey(String entityNo, String storeNo, String businessKey);
```

READ守卫：同一计提/冲回业务键唯一；冲回必须引用originalEntryNo，不更新原金额。

```java
int recordPaymentProof(String entityNo, String storeNo, String entryNo, long expectedVersion, String expectedStatus, String paymentProofRef, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：只对获准待发状态记录已核验凭证及目标状态，不改amount_minor；WHERE限定可信范围、业务号、expectedVersion及合法前态，影响行必须为1。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。

### ai.neargo.shop.beauty.mapper.BeautyMappers.SourceAllocationGuardMapper

表：`bty_source_allocation_guard`；Entity：`ai.neargo.shop.beauty.entity.BtySourceAllocationGuard`。

继承调用形状：

```java
int insert(BtySourceAllocationGuard row);
BtySourceAllocationGuard selectOne(Wrapper<BtySourceAllocationGuard> queryWrapper);
List<BtySourceAllocationGuard> selectList(Wrapper<BtySourceAllocationGuard> queryWrapper);
<P extends IPage<BtySourceAllocationGuard>> P selectPage(P page, Wrapper<BtySourceAllocationGuard> queryWrapper);
int update(BtySourceAllocationGuard row, Wrapper<BtySourceAllocationGuard> updateWrapper);
```

```java
BtySourceAllocationGuard selectSourceForUpdate(String entityNo, String sourceKind, String sourceNo, String sourceLineRef);
```

LOCK守卫：主体内规范来源行共用一把锁，不按store/version复制guard；store从可信来源核验，首次创建靠唯一键。

```java
int allocateQuantity(String entityNo, String guardNo, long expectedVersion, long expectedSourceRevision, BigDecimal incrementQty, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：incrementQty>0且allocated_qty+incrementQty<=source_qty；唯一来源行、版本和可信快照匹配；受权来源/门店在Service验证。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。 数量须能以六位小数精确表示，不允许舍入成零仍报告成功。

```java
int releaseUnconsumedQuantity(String entityNo, String guardNo, long expectedVersion, BigDecimal releaseQty, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：只释放已确认未消费且不被在途提交引用的分配；0<releaseQty<=allocated_qty，不回退已履约数量。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。 现有签名无目标binding/消费水位，数据层保守要求全部历史绑定（含软删）均有合法双父链、从未开始且全量VOIDED任务和BOUND/REJECTED提交；否则整guard拒绝释放。局部空闲量释放及未形成绑定的在途引用由后续正式Service/合同解决。

```java
int refreshSourceSnapshot(String entityNo, String guardNo, long expectedVersion, long expectedSourceRevision, long newSourceRevision, BigDecimal newSourceQty, String sourceHash, String actorNo, LocalDateTime updatedAt);
```

CAS守卫：只接受权威新来源修订，newSourceQty>=allocated_qty且修订单调；未知状态不替换来源。 自定义SQL显式version+1并写updated_by/updated_at；不假定审计填充器替标量参数完成审计。 newSourceQty最多六位小数，sourceHash为64位摘要。

## 五、54个共用CONTRACT及既有方法证据

CONTRACT是一个能力接入点，可能覆盖多个既有接口，不能从名称机械生成一个Java Service方法。下表全部有归属说明；其真实签名以原源码为准，资源仲裁/高级收银等缺少完整合同的能力先在原域完成评审，不能临时写BeautyPayService/BeautyResourceMapper来凑数。

| API编号 | 共用合同 | 接入要求 |
|---|---|---|
| BAPI-003 | `merchant.store-context` | 列出并切换受权门店/岗位；P1；当前账号,目标门店/岗位 → StoreContextVO与服务端重建权限；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-005 | `product.catalog-and-store-offer` | 共用商品SKU/媒体/上架与门店价维护；P1；productNo,skuNo,storeNo,version → ProductVO/StoreOfferVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-006 | `catalog.sales-calendar` | 维护分组排序与销售时段；P1；StoreSalesCatalogPort.PublishReq(expectedRevision,validFrom,timezone,groups,entries)；当前范围/actor由可信上下文取得 → StoreSalesCatalogPort.Snapshot；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-011 | `quote.configured-options` | 复杂选配与补差报价；P4；正规SKU与选配版本,买家 → QuoteVO须正式合同开放；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-017 | `merchant.roles-and-permissions` | 维护账号门店角色及操作范围；P1；accountNo,storeNo,roleCode,permissionScope → RoleGrantVO不改变业务岗位；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-019 | `identity.session-revoke` | 撤销离职人员关联账号会话；P1；accountNo,reason → SessionRevocationVO与审计；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-030 | `member.identification` | 共用会员码识别/身份核验；P2；memberToken,purpose,storeNo → CustomerIdentityVO非资产扣款授权；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-033 | `member.profile-and-history` | 顾客档案会员关系和受权历史；P1；customerNo,purpose,storeNo → CustomerProfileVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-046 | `booking.deposit-and-penalty-policy` | 定金尾款与违约合同；P4；policyVersion,sourceRef,customerConsent → 合同待定默认关闭；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-068 | `trade.quote-and-create` | 核心报价建单及买家接管；P1；storeNo,buyerRef,skuLines,quoteVersion → OrderVO美业仅记来源引用；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-069 | `payment.initiate-and-query` | 顾客付款和原支付号查结果；P1；orderNo,payMethod,customerConsent → PaymentVO由资金域判定结果；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-070 | `payment.offline-receipt` | 正式合同允许的线下实收确认；P1；orderNo,method,amount,evidence,operator → ReceiptVO禁止代消费者线上确认；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-074 | `cashier.advanced-contracts` | 混合/跨单/后付/定金/免单/改价；P4；已批准政策及原来源,本人授权 → 待正式合同不设执行路由；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-075 | `stored-value.plans` | 共用独立充值方案版本启停；P2；storeNo,planNo,version,scope → RechargePlanVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-076 | `stored-value.recharges` | 创建提交实收确认充值及查原号；P2；customerNo,storeNo,planVersion,receipt → RechargeVO仅正式渠道；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-077 | `stored-value.accounts` | 本店账户目录及余额冻结；P2；storeNo,customerNo,page,size → PageData<StoredValueAccountVO>；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-078 | `stored-value.consumptions` | 本次服务来源全额消费预览/本人确认；P2；sourceRef,storeNo,memberAuthorization → ConsumptionVO不混合补现金；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-079 | `stored-value.history` | 本人门店消费记录及商家统一收支；P2；storeNo,customerNo,from,to,page,size → PageData<StoredValueRecordVO>；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-080 | `stored-value.recovery` | 已收未入账/冻结/扣款未知原号恢复；P2；rechargeNo/consumptionNo,reason → RecoveryVO不手调余额；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-081 | `entitlement.rules` | 共用次数规则适用范围有效期；P4；ruleNo,version,skuScope,quantity,validity → EntitlementRuleVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-082 | `product.treatment-card-offers` | 疗程销售SKU及门店价格；P4；ruleVersion,skuNo,storeNo → StoreOfferVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-083 | `entitlement.sale-and-issuance` | 正式售卡来源及幂等发行进度；P4；salesSourceRef,ruleVersion,customerNo → IssuanceVO发行和收款分别显示；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-084 | `entitlement.eligible-assets` | 本人已持有合格权益和余次；P2；customerNo,storeNo,skuNo → EligibleEntitlementVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-085 | `entitlement.service-consumption` | 本次权益使用预览授权预占核销和恢复；P2；sourceRef,entitlementNo,quantity,customerConsent → EntitlementConsumptionVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-088 | `entitlement.policy-cases` | 有效期次数申诉退转卡延期跨店政策；P4；assetRef,caseType,evidence,approvedPolicy → CaseVO未定政策只登记不执行；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-089 | `promotion.eligible-and-quote` | 查询选择合格券并核心报价核验；P1；buyerRef,skuLines,storeNo → EligiblePromotionVO/QuoteVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-090 | `promotion.campaign-versions` | 活动草稿发布资格预算；P3；campaignNo,version,scope,budget → CampaignVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-091 | `member.tags-and-audiences` | 标签人群及受控经营名单；P3；customerScope,purpose,tagChanges → AudienceVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-092 | `entitlement.grant` | 受权赠券赠次发行查结果；P3；grantSourceNo,ruleVersion,recipients → GrantVO不等同付费售卡；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-093 | `analytics.campaign-conversion` | 活动预约回访转化统计；P3；storeNo,period,campaignNo → CampaignReportVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-094 | `trade.goods-inventory-delivery` | 实物报价库存承诺交付和售后；P1；skuNo,storeNo,quantity,sourceRef → OrderVO/FulfillmentVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-095 | `inventory.stock-operations` | 库存收货调拨盘点；P3；storeNo,skuLines,operation,expectedRevision → StockDocumentVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-096 | `inventory.service-material-use` | 按任务领料和实际耗用；P3；taskNo,skuLines,operator,sourceNo → MaterialUsageVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-097 | `inventory.return-and-scrap` | 退回报损质量处置；P3；originalDocumentNo,lines,quality,reason → InventoryDispositionVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-098 | `inventory.bom-and-cost` | 项目耗材BOM版本及成本差异；P4；serviceSkuNo,bomVersion,period → BomVO/CostReportVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-099 | `aftersale.standard` | 普通交易售后材料审核进度；P1；orderNo,lineNos,reason,evidence → AfterSaleVO不适用于任意资产退款；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-104 | `asset.reversal-contract` | 经正式合同执行资产逆向；P4；originalUseNo,approvedCaseNo,policyVersion → ReversalVO默认关闭；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-111 | `finance.receipt-history` | 读取普通订单/充值/资产消费凭条；P1；sourceRef,customerNo,storeNo → ReceiptVO按阶段开放；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-112 | `cashier.shifts` | 开关班钱箱实盘交接；P3；storeNo,shiftNo,cashCount,expectedRevision → ShiftVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-113 | `finance.business-day-close` | 营业日范围水位日结签核；P3；storeNo,businessDate,watermark,approval → DayCloseVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-114 | `finance.channel-reconciliation` | 逐口径渠道对账；P3；storeNo,businessDate,channel → ReconciliationVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-115 | `finance.settlements-and-cases` | 结算票据费用差异与原号恢复；P3；settlementNo,caseNo,authorizedAction → SettlementVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-118 | `analytics.money-separate-measures` | 现金充值资产消耗余额分口径报表；P3；storeNo,period,currency → MoneyReportVO不可叠加为营收；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-123 | `notification.delivery-and-preferences` | 预约付款通知规则订阅与送达查询；P1；eventRef,templateVersion,recipientScope → DeliveryVO失败不回滚交易；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-129 | `notification.marketing-consent` | 营销订阅频控受众退订；P3；customerNo,channel,purpose,consent → NotificationPreferenceVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-131 | `device.print-routes-and-templates` | 设备路由模板版本与自检；P1；storeNo,deviceNo,templateVersion → PrintConfigVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-132 | `printing.jobs` | 提交打印查询原任务和受控补打；P1；documentNo,deviceNo,originalJobNo?,reason → PrintJobVO未知先查原号；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-133 | `device.terminals` | 终端绑定撤销心跳最小权限；P3；deviceNo,storeNo,accountScope,action → TerminalVO；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-134 | `device.edge-service` | 签到机上门边缘设备协作；P4；deviceSession,identityProof,authorizedIntent → EdgeActionVO不能离线扣资产；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-136 | `audit.scoped-events` | 受控操作审计及代客身份追踪；P1；subjectRef,storeScope,from,to,page,size → PageData<AuditEventVO>不含护理正文；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-140 | `observability.health-and-alerts` | 健康慢请求错误率设备存活与告警；P1；serviceScope,timeRange,traceNo → HealthVO/AlertVO接已有观测设施；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-141 | `governance.privacy-config-backup` | 隐私配置发布备份恢复受控流程；P3；scope,changeNo,purpose,approvalRef → GovernanceJobVO不提供任意表写入；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-142 | `integration.external-booking` | 第三方预约来源映射签名去重冲突；P4；provider,externalNo,revision,signedPayload → ExternalBindingVO需独立合同；按原所有者正式合同，不新增美业同义Java类 |
| BAPI-143 | `offline.claim-and-sync` | 断网事实认领去重同步；P4；deviceNo,localIntentNo,evidence → SyncClaimVO未验收不开放营业；按原所有者正式合同，不新增美业同义Java类 |

以下方法已逐条与当前源码比对，仅作为复用证据，不表示所有接入合同都已满足：

### ai.neargo.shop.spi.product.GoodsQueryPort

[源码](../../../backend/shop-base/src/main/java/ai/neargo/shop/spi/product/GoodsQueryPort.java)；商品快照复用，最终报价/落单不是此方法。

```java
Map<String, SkuSnapshot> snapshot(List<String> skuNos, Map<String, String> storeByEntity);
```

### ai.neargo.shop.spi.user.StoreDirectoryPort

[源码](../../../backend/shop-base/src/main/java/ai/neargo/shop/spi/user/StoreDirectoryPort.java)；公开门店解析，不能据此提升账号门店权限。

```java
Optional<StoreCard> resolve(String no);
```

### ai.neargo.shop.spi.member.CustomerIdentityPort

[源码](../../../backend/shop-base/src/main/java/ai/neargo/shop/spi/member/CustomerIdentityPort.java)；识别不是消费者扣款同意。

```java
ResolvedCustomer resolveReference(EntitlementContext context, ReferenceQuery query);
```

### ai.neargo.shop.spi.storedvalue.StoredValuePort

[源码](../../../backend/shop-base/src/main/java/ai/neargo/shop/spi/storedvalue/StoredValuePort.java)；资产持有者合同；美业经正式充值编排，不直接越过实收/授权。

```java
RechargeView createRecharge(EntitlementContext context, CreateRecharge command);
```

### ai.neargo.shop.spi.storedvalue.StoredValuePort

[源码](../../../backend/shop-base/src/main/java/ai/neargo/shop/spi/storedvalue/StoredValuePort.java)；未知按原号查单；不手调余额。

```java
RechargeView queryRecharge(EntitlementContext context, String entityNo, String rechargeNo);
```

### ai.neargo.shop.spi.product.ServiceProductFactsPort

[源码](../../../backend/shop-base/src/main/java/ai/neargo/shop/spi/product/ServiceProductFactsPort.java)；已实现MAIN/CN本店SERVICE商品事实；定价仍在GoodsQueryPort；基础时长来源prd_goods.duration_min；独立服务走公共内部HTTP合同。

```java
Fact get(String entityNo, String storeNo, String skuNo);
```

## 六、事务与验收重点

- 首次业务号/请求键、来源guard、业绩/提成首版：依唯一键创建，重复只复用同键同载荷；后续版本锁稳定首版或本域根。并发不以进程内synchronized/分布式缓存锁替代数据库约束。
- 开工许可：权威来源必须与并发撤销/退款建立许可/围栏；纯“查询已付后写任务开始”存在竞态，缺少正式合同就阻断能力。资源开工/交接也必须有权威结果及原号恢复。
- 关闭范围：freezeScope后复查任务/绑定版本并锁定对应完成事实；不能只靠工单缓存计数。所有范围变更和任务动作须遵守同一锁序与冻结规则。
- SourceAllocationGuard唯一键不含sourceRevision，当前门店经来源归属验证；CAS累计分配不超过sourceQty，未知来源不可替换，已消费数量不任意回退。
- 方法清单检查只证明接口/归属/表列引用一致，不证明SQL锁、HTTP鉴权、审计填充或并发正确。实施需架构扫描覆盖整个美业reactor、Bean/路由测试、隔离库事务并发和跨域故障恢复验证；不得把文档生成通过称为Java编译通过。

## BM03-a1增量实施清单（2026-10-21）

原目标JSON/49任务保持基线；本节是私有V2的内部存储增量，未注册来源provider、Service或预约Controller。完整状态见[主TDD§2.15/§26](../TDD-美业系统-后端开发计划.md)。

| 类 | 实际职责 |
|---|---|
| beauty.entity.BtyAppointmentResourceIntent | 原始计划LONGTEXT、命令/来源修订/摘要、决定与对账阶段；继承BaseEntity |
| beauty.entity.BtyAppointmentResourceFence | 预约永久根、单一活动意图指针及版本；继承BaseEntity |
| BeautyMappers.AppointmentResourceIntentMapper | scoped command锁读、authorize/reconcile CAS；调用参数不能证明权限或终态真实性 |
| BeautyMappers.AppointmentResourceFenceMapper | scoped root锁读、acquire/releaseReconciled CAS；不按许可TTL释放 |
| BeautyMappers.AppointmentMapper | changeSchedule/changeStatus/markArrived统一检查活动fence，含确认/取消 |

以下方法均在原beauty-core包；每个写事务先锁预约根→永久fence→intent，Mapper仅持久化。

```java
BtyAppointmentResourceFence selectScopedForUpdate(String entityNo, String storeNo, String appointmentNo);
int acquire(String entityNo, String storeNo, String appointmentNo, String intentNo,
        long expectedFenceVersion, String actorNo, LocalDateTime updatedAt);
int releaseReconciled(String entityNo, String storeNo, String appointmentNo, String intentNo,
        long expectedFenceVersion, String actorNo, LocalDateTime updatedAt);
BtyAppointmentResourceIntent selectByCommandForUpdate(String entityNo, String storeNo, String commandNo);
int authorize(String entityNo, String storeNo, String intentNo, long expectedVersion, String decisionNo,
        LocalDateTime issuedAt, LocalDateTime validUntil, String actorNo, LocalDateTime updatedAt);
int reconcile(String entityNo, String storeNo, String intentNo, long expectedVersion,
        int terminalCode, String terminalHash, String actorNo, LocalDateTime updatedAt);
```

a2仍须实现正式权限、持久预约生成计划、同Binding幂等及可信资源终态核实；本批不增加可调用预约HTTP。

## BM03-a2-1共用核验增量（2026-10-21）

本节为已编码的所有者只读前置，不改变原49任务或160编号；不是完整美业来源授权、预约或终态解冻。

| 模块 / 完整类 | 职责与实际方法 |
|---|---|
| shop-base / ai.neargo.shop.spi.user.MerchantOperatorAccessPort | boolean allowed(Query query)；显式租户/主体/店/操作者realm+subjectKind/权限 |
| shop-base / ai.neargo.shop.spi.resource.ResourceCommandFactsPort | Lookup lookup(ResourceSourceAuthorityPort.Request request)；完整Binding，原命令状态/结果/摘要；不以当前持有头替代历史结果 |
| shop-base / ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi | Boolean operatorAccess(Query query)；Lookup command(Request request)；PLATFORM内部协议 |
| shop-merchant / ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl | 实际有效成员、店授权、角色权限精确或裸*匹配；MCH/USR隔离，歧义拒绝，无静态角色兜底 |
| shop-merchant / ai.neargo.shop.merchant.port.ResourceCommandFactsPortImpl | 实际原command/owner父链，完整Binding比对，REJECTED/SUCCEEDED摘要及ACCEPTED非终态 |
| shop-merchant / ai.neargo.shop.merchant.mapper.MerchantMappers.OperatorAccessMapper | List<OperatorAccessRow> selectAccess(Query query)；最多2行用于拒绝歧义 |
| shop-merchant / ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper | ResourceCommandFactRow selectOriginalFact(ResourceCommandScopeRow scope, String commandNo)；只读投影 |
| shop-app / ai.neargo.shop.portal.internal.InternalResourceRecoveryController | ResponseEntity<?> operatorAccess(Query query,String given) / command(Request request,String given)；先机器令牌、保留错误HTTP状态 |
| beauty-channel / ai.neargo.shop.beauty.port.RemoteMerchantOperatorAccessPort | boolean allowed(Query query)；真实PLATFORM链路，不把失败当false |
| beauty-channel / ai.neargo.shop.beauty.port.RemoteResourceCommandFactsPort | Lookup lookup(Request request)；完整返回Binding比对，不把失败当缺失 |
| beauty-channel / ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration | 条件缺失时接远程两个Port，embedded仍使用本地owner |

内部Lookup必须显式command字段，允许显式null；缺字段/200错误信封拒绝，未知owner运行异常503；只读OperatorAccessMapper显式@Mapper由宿主实际注解扫描注册。

接口、owner及远程适配均在事务外读取；事实只说明读取时状态，不是来源许可。未新增可授权provider/预约Controller，待a2-2基于真实持久业务计划与来源根锁继续实现。
