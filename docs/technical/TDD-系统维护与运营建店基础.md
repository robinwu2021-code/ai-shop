# TDD 系统维护与运营建店基础

> 状态：本地实现与定向验证完成，待部署 · 档位：1 · 2026-11-11
> 需求：[运营端开店收款开发清单 S01–S07](./运营端开店收款闭环-开发功能清单-2026-11-11.md)；上游：[子行业与能力端验收](./TDD-子行业与能力端验收.md)。本轮先完成系统维护，再进入套餐维护。

## §0 需求到设计

| AC | 需求 | 实现与验收落点 |
|---|---|---|
| AC1 / S01 | 市场维护写权限与服务端一致，保留市场/币种/汇率规则 | SystemPage 的 market 写控件按 system:param:update；既有市场服务及 MarketMasterDataTest |
| AC2 / S02–S03 | 维护门店行业/子行业、多语言名称、经营类型、排序和启停；可查询停用项及恢复 | 新 StoreIndustryAdminService、OpsStoreIndustryController；系统“门店行业与子行业”页；复用 sys_store_industry |
| AC3 / S02–S03 | 父行业必须存在，经营类型必须存在；版本冲突不能覆盖；租户隔离 | 沿 CatalogFacade 查类型；父行业行锁、原 version CAS、原唯一键；框架校验 DTO |
| AC4 / S02–S03 | 停用行业/子行业不再可选，全部停用不恢复默认选项；旧店不回写 | MerchantQueryPort.IndustryRow 带 enabled；端口返回配置全量，建店查询和写入使用启停事实 |
| AC5 / S04–S05 | 支付行业、授权码、经营范围菜单和操作权限与实际端点相同 | 支付行业/范围使用 system:industry:read/update；授权码使用 product:category:read/update；不扩张角色权限 |
| AC6 / S06 | 业务端只来自可信部署；不能在运营表单伪造验收 | 复核既有部署选项及发布校验，复用已有相关测试，不新增可写部署目录 |
| AC7 / S07 | 支付目录/平台凭据/实例环境复用独立 near-pay 原实现，模拟与真实状态不得混淆 | 核查独立项目渠道、产品、凭据、环境能力及相关测试；渠道参数和真实验收单独记待办 |

## §1 现状与影响面

- sys_store_industry 已有业务唯一键、租户、version、审计列，无需新表。缺少维护 API 和 OPS 页面。
- 市场页面把 system:param:read 当写权限；支付行业/范围页面把 system:param:update 当写权限，实际后端为 system:industry:update；菜单亦存在错位。
- MerchantPortImpl.industries 只返回启用项；全部停用时 BizFoundationController 会回落默认映射，使维护结果失效。旧形状兼容保留，但已登记停用项不能被兼容分支重新放行。
- 新门店行业维护复用 commerce:catalog:read/edit（管理的是商城经营类型映射），与支付行业的 system:industry:* 分开；只给超管默认菜单，其他角色显式授权。
- 不变更历史门店、支付行业表、既有币种映射或角色业务权限。普通 OPS 代建属于 C 阶段，此处只准备基础数据和维护能力。

## §2 设计与执行计划

### 契约

- GET /ops/store-industries：配置全量，含停用项，稳定业务键 industryCode + subIndustryCode；subIndustryCode 空串代表父行业；响应包含业务字段、version，不含物理 id。
- POST /ops/store-industries：保存一行；expectedVersion=null 表示新增，非空为编辑 CAS；父/子业务键不可修改；body 包含简繁英名称、businessTypeCode、sort、enabled、reason。DTO 统一 Jakarta 校验，业务事实由服务处理。
- 维护接口复用 commerce:catalog:read/edit 和已有目录权限校验；commerce.enabled + commerce.catalog.enabled 时装配服务；维护 HTTP 同时要求既有 commerce.mall.http.enabled 运营上下文。
- sys_store_industry 复用已有表；新增 V655 菜单迁移，修正支付行业、范围和授权码菜单读码，不向既有角色新增业务写权限。
- 新运营 tab：/system?tab=storeIndustry；中文/英文 copy、前端类型/API/mock 及生成物同步。
- 内部 IndustryRow 新增 enabled，保留原构造参数重载；公开建店 JSON 不变。

### 批次

- [x] 1. 写服务、建店启停、权限和 API 回归用例；复用现有 S01/S04/S06/S07 测试。
- [x] 2. 服务端补维护/版本/父子约束及建店启停读取；前端补维护表单和权限修复；新增菜单迁移并生成相关产物。
- [x] 3. 本批完成后统一执行相关编译/测试及界面核验。共享 target 可能被其他会话使用，必要时在临时源码快照中构建，保留当前未提交源码，不清理共享输出。
- [x] 4. 逐项回填 S01–S07 状态和证据；只有本批验证通过后开始 P 阶段，外部真实渠道参数/验收如实列为部署事项。

### 文件责任

| 模块 | 文件或范围 | 责任 |
|---|---|---|
| 商户 | service/StoreIndustryAdminService、impl/StoreIndustryAdminServiceImpl | 字典业务、父子约束、目录关联、版本写入 |
| 宿主 | portal/ops/OpsStoreIndustryController | 真实运营上下文、框架校验、业务审计 |
| 读取 | MerchantQueryPort、MerchantPortImpl、BizFoundationController、BizMerchantController | 保留配置启停事实，读取与写入一致 |
| 运营 | system/store-industry-tab、system copy/page、system API/types/mock、nav/perm-map/point-codes | 可编辑维护页及真实读写权限 |
| 迁移/生成 | V655、OPS OpenAPI、UI/菜单与权限投影 | 新入口可达及契约登记 |

## §5 实现到需求验收

| AC | 测试 | 本轮结果 |
|---|---|---|
| AC1 | 市场页面真实写权限；MarketMasterDataTest / 既有市场服务规则 | 见下方本轮证据 |
| AC2/AC3 | StoreIndustryAdminServiceTest：真实表增改/恢复、父子/类型不存在、版本冲突、租户隔离；OPS HTTP 400/403 | 见下方本轮证据 |
| AC4 | BizStoreSetupTest：全部停用、父停用、子停用、旧形状与 OTHER；维护后实时读取 | 见下方本轮证据 |
| AC5 | 前端权限/菜单与服务端权限对账；后端拒绝写入；页面只读/可写行为 | 见下方本轮证据 |
| AC6 | commerce 部署/业务端发布相关测试 | 见下方本轮证据 |
| AC7 | 独立 near-pay 基础目录、凭据与环境相关测试 | 见下方本轮证据 |

## §6 交付与偏差

本轮证据（按实际范围复用，不因文档回填重跑）：

- ai-shop 隔离源码快照使用 JDK 21；IndustryPublicationTest 16 通过、CommerceCatalogWiringTest 4 通过、BizStoreSetupTest 19 通过、OpsStoreIndustryControllerTest 2 通过、MarketMasterDataTest 7 通过、OpsRoleCanDoItsJobTest 3 通过。日志：`/tmp/ai-shop-system-backend-main-passed.log`；行业发布初次有效结果在 `/tmp/ai-shop-system-backend-initial.log`。
- MySQL **9.7.4 / 127.0.0.1:3308** 独立临时 schema：StoreIndustryAdminServiceTest **5 通过、0 跳过**，包含真实表、CAS、父子编码、跨租户及 V655 权限迁移连续执行两次不扩权。临时 schema 已回收；日志 `/tmp/ai-shop-system-backend-tests.log`。
- OPS 初次定向 7 文件 168 项中，其余 5 文件通过；修复页面夹具和明确的菜单归属后，页面权限/维护交互及导航 **59 通过**。生成器冻结编码和开关识别 **6 通过**；新增/修改 mock 合同后 system/commerce **36 通过**；`npm run typecheck` 和定向 eslint 通过。分别见 `/tmp/ai-shop-system-ops-tests.log`、`/tmp/ai-shop-system-ops-final-fixes.log`、`/tmp/ai-shop-system-generator-tests.log`、`/tmp/ai-shop-system-mock-final.log`、`/tmp/ai-shop-system-types-final.log`、`/tmp/ai-shop-system-lint.log`。
- OPS 契约检查通过：既有未接 8 项未增长；孤儿检查通过：既有未分类 13 项未增长。已更新 UI、OpenAPI、权限种子文档及角色投影。
- 独立 near-pay 前端渠道目录、凭据、账户 **19 通过**。目录/凭据/执行环境服务端 **20 通过**：在临时源码快照完成生产模块编译，使用当前新增 SPI 契约补齐独立依赖缓存，再单独编译并运行这三组当前测试及 CatalogFixtures；未改独立仓库业务源码或共享缓存。日志 `/tmp/ai-shop-system-nearpay-scoped.log`。
- near-pay 全测试源码编译仍受四个非本批用例的旧构造函数调用阻塞（MerchantPaymentLocalStoreTest、MerchantPaymentDetailNoteTest、PayBizFacadeCollectStatusTest、CheckoutBizLocalStoreTest）；不能据此宣称独立项目全量回归通过。真实渠道参数/验收及部署仍为执行事项，不冒充已开通生产收款。
- 本地浏览器（独立临时 OPS 快照，明确 Mock 标记）已核验菜单、表单、稳定编码只读、原因必填、停用后版本递增和子项“父行业已停用”提示。服务端真库证据来自上面的独立 MySQL 用例，未把浏览器 mock 保存当作真库联调。

本轮偏差：新增 S05 直接相关的冻结编码生成器回归修复（`--emit-point-codes` 改用原 pointCodeOf 冻结映射）；复合装配开关的反向扫描补数组语法，正向覆盖边界未扩张。独立复核发现迁移编号与另一会话新增 V654 冲突，已将本批未部署迁移改为 V655；并修复 MySQL 大小写不敏感父项查询导致 Java 组树漏子项的问题。未提交或部署。

验证次数、输入校验及异常处理以 [Java 规范 §八](../../.claude/skills/ai-shop-dev-standards/references/java.md#八简洁实现框架校验与增量验证2026-10-30-用户确认)为准。
