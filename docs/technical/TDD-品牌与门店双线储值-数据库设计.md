# 品牌与门店双线储值 · 数据库设计

状态：V2评审草案，未实现、未执行DDL。范围：34屏原型；逻辑模块及数据源分别归属Merchant/Core/Pay。现有迁移局部读至V543，仅作为本工作区基线；实际开发前必须重新盘点目标分支和环境的Flyway历史，本文不预占迁移号。

配套：[系统设计](TDD-品牌与门店双线储值-系统设计.md) · [API清单](TDD-品牌与门店双线储值-API清单.md) · [结构化模型](design/schema/brand-stored-value-v2-database.json) · [评审DDL](design/schema/brand-stored-value-v2-review.sql)。SQL明确标为DESIGN ONLY，不放backend迁移目录。

## 1. 实际基线与复用

| 现有对象 | 当前事实 | 处理 |
|---|---|---|
| mch_entity、mch_store、mch_account | 门店关联entity；账号是主体成员关系，不是统一品牌财务授权 | 复用主体旗下mch_payment_merchant渠道商户，不增加另一套同义商户组织表；新增品牌及管理授权 |
| mbr_member、mbr_member_store | 会员人档与经营主体关系；店维度是往来缓存 | 保留历史，增加品牌／单店运营关系及映射，不原地改变旧会员号语义 |
| sv_customer_identity | V539可信顾客与已验证账号一对一绑定 | 复用，先审计旧userNo/personNo别名及冲突，不按手机号合并 |
| pmt_activity、pmt_activity_version | Core活动与不可变版本；entity_no原NOT NULL | 扩展ownerScope；品牌不伪装成结算主体所有；发布协调状态独立 |
| pmt_recharge_tier | 旧Core活动档位含金额 | 保留原历史，不让V2在Core/Pay双写金额；V2金融真源在sv_release_tier |
| sv_recharge_plan、version | Pay单店单档方案，版本含金额 | 兼容原单和历史；V2发行配置支持多档，不修改已发布旧版本 |
| sv_account、lot、voucher、entry、hold_allocation | 已有资金和冻结分配内核 | 扩展issuer维度，保留余额及历史凭证，不新造第二本余额账 |
| sv_statement、sv_user_store_index | 门店展示投影 | 保留兼容读；新issuer目录与来源批次统计，不能依靠店索引复制品牌余额 |
| stl_bill | 按子订单／商家分账的现有结算单 | 不强行塞入储值发行方月度清分；新增sv_settlement_*，复用私有凭据和岗位能力 |
| pay_ent_command_dedup、pay_ent_outbox、sys_outbox | 防重和跨域事件 | 优先复用；金融事件消费去重必须与本地事务一致 |

## 2. 不变量与类型

- tenant边界不放宽；同品牌跨经营主体不代表跨租户。管理空间是授权集合，不是经营主体与品牌关系，也不是资金钱包。
- 品牌、门店各最多一个发行方，使用UNIQUE(tenant,scopeType,scopeNo)。停用不删除对象后重建第二个发行方绕开余额责任。
- 门店同一时点一个有效品牌绑定，由active_store_no生成列的唯一索引兜底；NULL仅用于非ACTIVE历史行。未来生效关系先PREPARING，生效工作流按锁校验，不用当前时间生成非确定索引。
- 顾客账户最终UNIQUE(tenant,issuer,canonicalCustomer,currency)。品牌与单店两个账户分别持有；一次预占／核销只允许一个account及issuer，不跨账户或币种组合。
- issuer指定settlementEntity是储值业务配置；不建立brand_entity归属表。交易冻结实际经营、收款及结算主体，不能用当前门店主体反推历史。
- 金额BIGINT minor，运算须用BigInteger或受检整数；比例bps。只在展示时格式化小数，浮点不能进入金融计算。
- UTC DATETIME(3)存业务时间，发行方版本保存IANA时区；账期[from,to)。首期一个发行方一个业务币种，账户键保留currency便于未来扩展，不做外汇换算。
- JSON仅保存快照／审计，不替代关键查询和唯一约束。内容摘要采用已修复的canonical JSON策略：对象键排序、数组顺序保留；不能hash原文字节后依赖MySQL JSON不格式化。
- 所有新表默认id、tenant_no、version、deleted、created_at、updated_at、created_by、updated_by；金额和历史事实不可物理删除，软删除不释放金融业务唯一性。版本表金融内容不可修改，仅状态/激活绑定允许受控变化。
- 只做逻辑关联，不跨Core/Pay建数据库外键或跨库JOIN。服务端在写入处验证同tenant、同issuer、原单及范围；后台对账扫描孤立引用。

## 3. 新表总览

| 表 | 模块 | 用途 | 关键唯一性 |
|---|---|---|---|
| `mch_management_space` | shop-merchant | 经营管理授权空间，不是资金账户或品牌与主体归属关系 | `tenant_no,management_no`；`tenant_no,owner_actor_no,bootstrap_key` |
| `mch_management_grant` | shop-merchant | 独立授予品牌、门店及主体管理权限 | `tenant_no,grant_no`；`tenant_no,management_no,actor_no,resource_type,resource_no,permission_code` |
| `mch_brand` | shop-merchant | 经营品牌，不直接归属经营主体 | `tenant_no,brand_no`；`tenant_no,management_no,name_key`；`tenant_no,management_no,create_key` |
| `mch_brand_store_binding` | shop-merchant | 门店品牌有效期关系；品牌与主体无直接关系表 | `tenant_no,binding_no`；`tenant_no,active_store_no` |
| `mch_brand_change` | shop-merchant | 加入退出品牌及独立影响检查，不迁移资金 | `tenant_no,change_no`；`tenant_no,store_no,command_key`；`tenant_no,pending_store_no` |
| `mbr_scope_member` | shop-core/member | 品牌或独立门店会员关系，不替换平台人档 | `tenant_no,scope_member_no`；`tenant_no,scope_type,scope_no,customer_no` |
| `mbr_store_profile` | shop-core/member | 本店私有关系和资料，跨店不默认共享 | `tenant_no,store_profile_no`；`tenant_no,store_no,customer_no` |
| `mbr_scope_legacy_link` | shop-core/member | 历史主体会员到运营范围的审计映射，不能按手机号直接合并 | `tenant_no,link_no`；`tenant_no,legacy_member_no,scope_member_no` |
| `pmt_sv_release_link` | shop-core/promotion | Core活动版本到Pay唯一发行配置及发布状态 | `tenant_no,link_no`；`tenant_no,activity_version_no`；`tenant_no,release_no`；`tenant_no,publish_command_no` |
| `pmt_sv_approval` | shop-core/promotion | 仅门店发行审批，独立于已发布版本 | `tenant_no,approval_no`；`tenant_no,activity_no,draft_revision_no` |
| `sv_issuer` | pay/storedvalue | 品牌与单店发行方各自唯一 | `tenant_no,issuer_no`；`tenant_no,scope_type,scope_no` |
| `sv_issuer_version` | pay/storedvalue | 不可变收款与结算规则；不是品牌主体组织关系 | `tenant_no,issuer_version_no`；`tenant_no,issuer_no,version_index` |
| `sv_issuer_store_access` | pay/storedvalue | Core范围事实的Pay生效镜像，非第二个品牌管理系统 | `tenant_no,access_no`；`tenant_no,issuer_no,store_no` |
| `sv_issue_policy` | pay/storedvalue | 门店独立发行限制的作用范围 | `tenant_no,policy_no`；`tenant_no,management_no,target_type,target_no,currency` |
| `sv_issue_policy_version` | pay/storedvalue | 仅允许收紧的额度与审批规则 | `tenant_no,policy_version_no`；`tenant_no,policy_no,version_index` |
| `sv_release` | pay/storedvalue | 活动版本对应的不可变金融发行配置 | `tenant_no,release_no`；`tenant_no,activity_version_no`；`tenant_no,activity_no,draft_revision_no`；`tenant_no,issuer_no,activity_no,release_index`；`tenant_no,legacy_plan_version_no` |
| `sv_release_tier` | pay/storedvalue | V2档位金额唯一真源，不在Core再维护一份 | `tenant_no,tier_no`；`tenant_no,release_no,paid_amount_minor`；`tenant_no,release_no,ordinal` |
| `sv_issue_quota` | pay/storedvalue | 同事务额度计数器，按门店独立发行池及币种串行检查 | `tenant_no,quota_no`；`tenant_no,store_no,currency` |
| `sv_issue_quota_reservation` | pay/storedvalue | 原充值单额度占用及恢复栅栏 | `tenant_no,reservation_no`；`tenant_no,recharge_no` |
| `sv_customer_issuer_index` | pay/storedvalue | 顾客发行方目录，含零余额有历史账户 | `tenant_no,customer_no,issuer_no,currency`；`tenant_no,account_no` |
| `sv_clearing_entry` | pay/storedvalue | 从已过账核销分配生成不可变清分事实，不执行打款 | `tenant_no,clearing_no`；`tenant_no,usage_no,allocation_no,entry_type,decision_no`；`tenant_no,source_event_key` |
| `sv_settlement_bill` | pay/storedvalue | 发行方账期对手主体结算，与按订单stl_bill分离 | `tenant_no,bill_no`；`tenant_no,issuer_no,payer_entity_no,payee_entity_no,currency,period_from,period_to,bill_kind` |
| `sv_settlement_bill_item` | pay/storedvalue | 清分事实只进入一个正常结算账单 | `tenant_no,item_no`；`tenant_no,clearing_no` |
| `sv_settlement_payment` | pay/storedvalue | 付款登记和收款确认分别记录，无银行执行器 | `tenant_no,payment_no`；`tenant_no,payer_entity_no,receipt_digest` |
| `sv_settlement_event` | pay/storedvalue | 对账、争议、调整、付款确认的只追加审计事件 | `tenant_no,event_no`；`tenant_no,command_no` |
| `sv_activity_daily_summary` | pay/storedvalue/readmodel | 来源批次活动统计，不把共享账户余额复制到活动 | `tenant_no,issuer_no,release_no,occurrence_store_no,currency,business_date` |

共26张新增建议表。不是全部从零实现：防重、消息、付款凭据附件、订单核销和旧账本继续复用现有能力。

[P1组织与品牌详细设计](TDD-P1-经营组织与品牌管理-详细设计.md)补充授权来源、幂等键、待决变更唯一性及5表的完整业务规则；下列字段已同步。

## 4. 字段设计

公共列见第2节及DDL；下列列出各业务字段。

### mch_management_space

经营管理授权空间，不是资金账户或品牌与主体归属关系。模块：shop-merchant。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `management_no` | `VARCHAR(64) NOT NULL` | 管理空间号 |
| `name` | `VARCHAR(64) NOT NULL` | 空间名称 |
| `owner_actor_no` | `VARCHAR(64) NOT NULL` | 有效所有人，变更需审计 |
| `status` | `VARCHAR(24) NOT NULL` | ACTIVE/SUSPENDED |
| `bootstrap_source_account_no` | `VARCHAR(64) NOT NULL` | 创建时验证的现有所有人账号关系，不授予其余主体权限 |
| `bootstrap_key` | `VARCHAR(64) NOT NULL` | 创建幂等键，同一所有人相同键返回原空间 |
| `bootstrap_hash` | `CHAR(64) NOT NULL` | 创建请求规范化摘要 |
| `auth_revision` | `BIGINT NOT NULL DEFAULT 0` | 空间授权版本，授予及撤销递增 |

唯一键：`tenant_no,management_no`；`tenant_no,owner_actor_no,bootstrap_key`。

### mch_management_grant

独立授予品牌、门店及主体管理权限。模块：shop-merchant。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `grant_no` | `VARCHAR(64) NOT NULL` | 授权号 |
| `management_no` | `VARCHAR(64) NOT NULL` | 空间号 |
| `actor_no` | `VARCHAR(64) NOT NULL` | 现有认证主体标识 |
| `resource_type` | `VARCHAR(24) NOT NULL` | SPACE/BRAND/STORE/ENTITY/PAYMENT_MERCHANT，各自授权 |
| `resource_no` | `VARCHAR(64) NOT NULL` | 资源号 |
| `permission_code` | `VARCHAR(64) NOT NULL` | 能力码，复用权限注册机制 |
| `status` | `VARCHAR(24) NOT NULL` | ACTIVE/REVOKED |
| `valid_from` | `DATETIME(3) NOT NULL` | UTC生效 |
| `valid_to` | `DATETIME(3) NULL` | UTC失效 |
| `authority_source_type` | `VARCHAR(24) NOT NULL` | OWNER_ACCOUNT/PARENT_GRANT/SPACE_OWNER，实时复核授权来源 |
| `authority_source_no` | `VARCHAR(64) NOT NULL` | 原始账号关系号、父授权号或空间号 |
| `delegatable` | `TINYINT NOT NULL DEFAULT 0` | 可否继续委派本资源本能力 |
| `authority_snapshot` | `JSON NOT NULL` | 创建时来源摘要，仅审计不代替实时权限 |

唯一键：`tenant_no,grant_no`；`tenant_no,management_no,actor_no,resource_type,resource_no,permission_code`。

查询索引：`tenant_no,actor_no,status`；`tenant_no,resource_type,resource_no,status`。

### mch_brand

经营品牌，不直接归属经营主体。模块：shop-merchant。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `brand_no` | `VARCHAR(64) NOT NULL` | 品牌号 |
| `management_no` | `VARCHAR(64) NOT NULL` | 所属经营授权空间，非经营主体 |
| `name` | `VARCHAR(100) NOT NULL` | 品牌名 |
| `logo` | `VARCHAR(512) NULL` | 标识资源 |
| `status` | `VARCHAR(24) NOT NULL` | DRAFT/ACTIVE/INACTIVE |
| `scope_revision` | `BIGINT NOT NULL` | 门店范围版本 |
| `name_key` | `VARCHAR(100) NOT NULL` | 名称trim及Unicode NFC规范化，唯一性按目标库排序规则 |
| `create_key` | `VARCHAR(64) NOT NULL` | 同空间创建幂等键 |
| `create_hash` | `CHAR(64) NOT NULL` | 创建请求规范化摘要 |

唯一键：`tenant_no,brand_no`；`tenant_no,management_no,name_key`；`tenant_no,management_no,create_key`。

查询索引：`tenant_no,management_no,status,id`。

### mch_brand_store_binding

门店品牌有效期关系；品牌与主体无直接关系表。模块：shop-merchant。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `binding_no` | `VARCHAR(64) NOT NULL` | 绑定号 |
| `brand_no` | `VARCHAR(64) NOT NULL` | 品牌 |
| `store_no` | `VARCHAR(64) NOT NULL` | 门店 |
| `status` | `VARCHAR(24) NOT NULL` | PREPARING/ACTIVE/ENDED |
| `effective_from` | `DATETIME(3) NOT NULL` | UTC生效 |
| `effective_to` | `DATETIME(3) NULL` | UTC结束 |
| `scope_revision` | `BIGINT NOT NULL` | 品牌范围版本 |
| `change_no` | `VARCHAR(64) NOT NULL` | 关联变更单 |
| `active_store_no` | `VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN store_no ELSE NULL END) STORED` | 当前绑定唯一辅助键 |

唯一键：`tenant_no,binding_no`；`tenant_no,active_store_no`。

查询索引：`tenant_no,brand_no,status,store_no`；`tenant_no,store_no,effective_from`。

CHECK：`status IN ('PREPARING','ACTIVE','ENDED')`。应用层仍须校验。

### mch_brand_change

加入退出品牌及独立影响检查，不迁移资金。模块：shop-merchant。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `change_no` | `VARCHAR(64) NOT NULL` | 变更号 |
| `management_no` | `VARCHAR(64) NOT NULL` | 管理上下文 |
| `store_no` | `VARCHAR(64) NOT NULL` | 门店 |
| `from_brand_no` | `VARCHAR(64) NULL` | 原品牌 |
| `to_brand_no` | `VARCHAR(64) NULL` | 目标品牌，NULL退出 |
| `expected_scope_revision` | `BIGINT NOT NULL` | 提交时范围版本 |
| `status` | `VARCHAR(24) NOT NULL` | DRAFT/CHECKED/BLOCKED/PREPARING/COMPLETED/REJECTED |
| `check_snapshot` | `JSON NOT NULL` | 会员、预占、原发行方、账单检查结果 |
| `authorization_snapshot` | `JSON NOT NULL` | 品牌与门店双方授权依据 |
| `reason` | `VARCHAR(255) NULL` | 原因 |
| `expected_store_version` | `BIGINT NOT NULL` | 提交时门店乐观锁版本，不改变门店经营主体 |
| `expected_from_brand_revision` | `BIGINT NULL` | 原品牌范围版本，首次加入可空 |
| `expected_to_brand_revision` | `BIGINT NULL` | 目标品牌范围版本，退出可空 |
| `command_key` | `VARCHAR(64) NOT NULL` | 同店提交幂等键 |
| `command_hash` | `CHAR(64) NOT NULL` | 规范化提交摘要 |
| `check_expires_at` | `DATETIME(3) NOT NULL` | 检查时效，到期或数据变化必须重新检查 |
| `submitted_by` | `VARCHAR(64) NOT NULL` | 发起认证身份 |
| `decision` | `VARCHAR(16) NULL` | APPROVE/REJECT；批准不等于已生效 |
| `decided_by` | `VARCHAR(64) NULL` | 决定认证身份 |
| `decided_at` | `DATETIME(3) NULL` | UTC决定时间 |
| `decision_key` | `VARCHAR(64) NULL` | 决定命令幂等键 |
| `decision_hash` | `CHAR(64) NULL` | 决定命令摘要 |
| `pending_store_no` | `VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('CHECKED','PREPARING') THEN store_no ELSE NULL END) STORED` | 同店最多一笔待决或执行中变更 |

唯一键：`tenant_no,change_no`；`tenant_no,store_no,command_key`；`tenant_no,pending_store_no`。

查询索引：`tenant_no,store_no,status,id`。

### mbr_scope_member

品牌或独立门店会员关系，不替换平台人档。模块：shop-core/member。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `scope_member_no` | `VARCHAR(64) NOT NULL` | 新关系号 |
| `scope_type` | `VARCHAR(24) NOT NULL` | BRAND/STORE |
| `scope_no` | `VARCHAR(64) NOT NULL` | 品牌或独立门店号 |
| `customer_no` | `VARCHAR(64) NOT NULL` | 统一可信人档canonical identity |
| `first_store_no` | `VARCHAR(64) NOT NULL` | 首次来源店 |
| `status` | `VARCHAR(24) NOT NULL` | LEAD/ACTIVE/BLOCKED |
| `joined_at` | `DATETIME(3) NOT NULL` | UTC入会 |
| `shared_profile` | `JSON NOT NULL` | 仅允许共享的品牌资料 |

查询索引：`tenant_no,scope_type,scope_no,joined_at,scope_member_no`。

CHECK：`scope_type IN ('BRAND','STORE')`。业务层仍须显式校验。

### mbr_store_profile

本店私有关系和资料，跨店不默认共享。模块：shop-core/member。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `store_profile_no` | `VARCHAR(64) NOT NULL` | 关系号 |
| `customer_no` | `VARCHAR(64) NOT NULL` | 可信人档 |
| `store_no` | `VARCHAR(64) NOT NULL` | 本店 |
| `remark` | `VARCHAR(500) NULL` | 私有备注 |
| `advisor_actor_no` | `VARCHAR(64) NULL` | 顾问 |
| `first_seen_at` | `DATETIME(3) NOT NULL` | 首次服务 |
| `last_seen_at` | `DATETIME(3) NULL` | 最近服务 |

查询索引：`tenant_no,store_no,last_seen_at,customer_no`。

### mbr_scope_legacy_link

历史主体会员到运营范围的审计映射，不能按手机号直接合并。模块：shop-core/member。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `link_no` | `VARCHAR(64) NOT NULL` | 映射号 |
| `legacy_member_no` | `VARCHAR(64) NOT NULL` | 原mbr_member号 |
| `scope_member_no` | `VARCHAR(64) NOT NULL` | 新关系号 |
| `migration_no` | `VARCHAR(64) NOT NULL` | 迁移批次 |
| `identity_evidence` | `JSON NOT NULL` | 脱敏核实依据 |
| `status` | `VARCHAR(24) NOT NULL` | VERIFIED/CONFLICT |

### pmt_sv_release_link

Core活动版本到Pay唯一发行配置及发布状态。模块：shop-core/promotion。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `link_no` | `VARCHAR(64) NOT NULL` | 关联号 |
| `activity_no` | `VARCHAR(64) NOT NULL` | 现有pmt_activity |
| `activity_version_no` | `VARCHAR(64) NOT NULL` | 现有不可变发布版本 |
| `issuer_no` | `VARCHAR(64) NOT NULL` | Pay发行方引用 |
| `release_no` | `VARCHAR(64) NOT NULL` | Pay不可变发行配置引用 |
| `release_hash` | `CHAR(64) NOT NULL` | 只存内容摘要，金额真源在Pay |
| `publish_command_no` | `VARCHAR(64) NOT NULL` | 稳定跨域幂等命令 |
| `state` | `VARCHAR(24) NOT NULL` | PREPARING/PAY_READY/ACTIVE/STOP_REQUESTED/STOPPED/FAILED |
| `permission_snapshot` | `JSON NOT NULL` | 发布／审批依据 |
| `policy_versions` | `JSON NOT NULL` | Pay复核过的限制版本 |

查询索引：`tenant_no,state,updated_at`。

### pmt_sv_approval

仅门店发行审批，独立于已发布版本。模块：shop-core/promotion。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `approval_no` | `VARCHAR(64) NOT NULL` | 审批单 |
| `activity_no` | `VARCHAR(64) NOT NULL` | 活动 |
| `draft_revision_no` | `VARCHAR(64) NOT NULL` | 精确草稿修订号 |
| `draft_hash` | `CHAR(64) NOT NULL` | 申请内容摘要 |
| `store_no` | `VARCHAR(64) NOT NULL` | 申请门店 |
| `management_no` | `VARCHAR(64) NOT NULL` | 有权审批空间 |
| `submitted_by` | `VARCHAR(64) NOT NULL` | 申请人 |
| `decided_by` | `VARCHAR(64) NULL` | 审批人 |
| `status` | `VARCHAR(24) NOT NULL` | PENDING/APPROVED/RETURNED/STALE |
| `policy_versions` | `JSON NOT NULL` | 提交时策略版本，仅审计，批准重新检查 |
| `decision_reason` | `VARCHAR(255) NULL` | 审批理由 |
| `decided_at` | `DATETIME(3) NULL` | 决定时间 |

查询索引：`tenant_no,management_no,status,created_at,approval_no`。

### sv_issuer

品牌与单店发行方各自唯一。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `issuer_no` | `VARCHAR(64) NOT NULL` | 发行方号 |
| `scope_type` | `VARCHAR(24) NOT NULL` | BRAND/STORE |
| `scope_no` | `VARCHAR(64) NOT NULL` | 品牌或门店 |
| `name` | `VARCHAR(64) NOT NULL` | 发行方名称 |
| `status` | `VARCHAR(24) NOT NULL` | DRAFT/ACTIVE/ISSUE_STOPPED |
| `current_issuer_version_no` | `VARCHAR(64) NOT NULL` | 当前结算和清分版本 |

CHECK：`scope_type IN ('BRAND','STORE')`。业务层仍须显式校验。

### sv_issuer_version

不可变收款与结算规则；不是品牌主体组织关系。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `issuer_version_no` | `VARCHAR(64) NOT NULL` | 配置版本 |
| `issuer_no` | `VARCHAR(64) NOT NULL` | 发行方 |
| `version_index` | `BIGINT NOT NULL` | 递增版本 |
| `settlement_entity_no` | `VARCHAR(64) NOT NULL` | 指定责任／结算主体 |
| `collection_mode` | `VARCHAR(24) NOT NULL` | CENTRALIZED/STORE_LOCAL |
| `clearing_mode` | `VARCHAR(24) NOT NULL` | PRINCIPAL_COMPONENT/INTERNAL_RECON |
| `debit_allocation_mode` | `VARCHAR(24) NOT NULL` | PRO_RATA/LEGACY_SNAPSHOT |
| `bonus_bearer` | `VARCHAR(24) NOT NULL` | ISSUER |
| `currency` | `VARCHAR(8) NOT NULL` | 一个规则版本一种币种 |
| `market_zone` | `VARCHAR(64) NOT NULL` | 账期时区 |
| `collection_config` | `JSON NOT NULL` | 授权收款配置引用，不存密钥 |
| `clearing_rule` | `JSON NOT NULL` | 舍入及承担规则版本 |
| `entity_acceptance` | `JSON NOT NULL` | 结算主体授权确认依据 |
| `content_hash` | `CHAR(64) NOT NULL` | canonical JSON摘要 |
| `effective_at` | `DATETIME(3) NOT NULL` | UTC生效 |

### sv_issuer_store_access

Core范围事实的Pay生效镜像，非第二个品牌管理系统。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `access_no` | `VARCHAR(64) NOT NULL` | 准入号 |
| `issuer_no` | `VARCHAR(64) NOT NULL` | 发行方 |
| `store_no` | `VARCHAR(64) NOT NULL` | 门店 |
| `operating_entity_no` | `VARCHAR(64) NOT NULL` | 当前经营主体事实 |
| `source_binding_no` | `VARCHAR(64) NOT NULL` | Core品牌绑定或单店事实 |
| `source_revision` | `BIGINT NOT NULL` | 源范围修订 |
| `can_issue` | `TINYINT NOT NULL` | 能否办理新充值 |
| `can_redeem` | `TINYINT NOT NULL` | 能否核销 |
| `status` | `VARCHAR(24) NOT NULL` | PREPARING/ACTIVE/SUSPENDED/ENDED |
| `effective_from` | `DATETIME(3) NOT NULL` | UTC生效 |
| `effective_to` | `DATETIME(3) NULL` | UTC结束 |

查询索引：`tenant_no,store_no,status,issuer_no`。

### sv_issue_policy

门店独立发行限制的作用范围。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `policy_no` | `VARCHAR(64) NOT NULL` | 策略号 |
| `management_no` | `VARCHAR(64) NOT NULL` | 经营管控空间 |
| `target_type` | `VARCHAR(24) NOT NULL` | SPACE/BRAND_STORES/STORE |
| `target_no` | `VARCHAR(64) NOT NULL` | 作用对象 |
| `currency` | `VARCHAR(8) NOT NULL` | 金额上限币种 |
| `current_version_no` | `VARCHAR(64) NOT NULL` | 当前不可变策略版本 |
| `status` | `VARCHAR(24) NOT NULL` | ACTIVE/INACTIVE |

CHECK：`target_type IN ('SPACE','BRAND_STORES','STORE')`。业务层仍须显式校验。

### sv_issue_policy_version

仅允许收紧的额度与审批规则。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `policy_version_no` | `VARCHAR(64) NOT NULL` | 策略版本 |
| `policy_no` | `VARCHAR(64) NOT NULL` | 策略 |
| `version_index` | `BIGINT NOT NULL` | 递增 |
| `allow_issue` | `TINYINT NOT NULL` | 允许单店新发行 |
| `require_approval` | `TINYINT NOT NULL` | 要求审批，父子OR |
| `max_bonus_bps` | `INT NOT NULL` | 比例万分比，20%=2000 |
| `max_paid_minor` | `BIGINT NOT NULL` | 单笔实收上限 |
| `max_bonus_minor` | `BIGINT NOT NULL` | 单笔赠送上限 |
| `max_active_days` | `INT NOT NULL` | 最大活动自然日数 |
| `max_outstanding_minor` | `BIGINT NOT NULL` | 未消费余额上限，含赠金和已收款占位 |
| `effect_mode` | `VARCHAR(24) NOT NULL` | NEW_ISSUANCE_ONLY |
| `parent_version_refs` | `JSON NOT NULL` | 继承规则版本 |
| `content_hash` | `CHAR(64) NOT NULL` | 摘要 |
| `effective_at` | `DATETIME(3) NOT NULL` | 生效时间 |

CHECK：`max_bonus_bps>=0 AND max_bonus_bps<=10000`；`max_paid_minor>0 AND max_bonus_minor>=0 AND max_outstanding_minor>0`；`max_active_days>0`。业务层仍须显式校验。

### sv_release

活动版本对应的不可变金融发行配置。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `release_no` | `VARCHAR(64) NOT NULL` | 发行配置号 |
| `issuer_no` | `VARCHAR(64) NOT NULL` | 发行方 |
| `issuer_version_no` | `VARCHAR(64) NOT NULL` | 冻结收款及清分版本 |
| `activity_no` | `VARCHAR(64) NULL` | V2的Core活动；旧PLAN历史为NULL，不伪造活动 |
| `activity_version_no` | `VARCHAR(64) NULL` | Core发布版本；草稿为NULL，发布只绑定一次 |
| `draft_revision_no` | `VARCHAR(64) NULL` | V2草稿修订；历史PLAN为NULL |
| `release_index` | `BIGINT NOT NULL` | 发行版本序号 |
| `source_kind` | `VARCHAR(24) NOT NULL` | V2_ACTIVITY/LEGACY_PLAN |
| `legacy_plan_version_no` | `VARCHAR(64) NULL` | 原单档方案版本 |
| `currency` | `VARCHAR(8) NOT NULL` | 币种 |
| `starts_at` | `DATETIME(3) NOT NULL` | UTC开始 |
| `ends_at` | `DATETIME(3) NULL` | UTC结束 |
| `state` | `VARCHAR(24) NOT NULL` | PREPARED/ACTIVE/ISSUE_STOPPED/ABORTED |
| `redemption_scope_mode` | `VARCHAR(24) NOT NULL` | BRAND_DYNAMIC/STORE_ONLY |
| `scope_snapshot` | `JSON NOT NULL` | 范围类型及资格规则；动态品牌不是固定门店名单 |
| `policy_versions` | `JSON NOT NULL` | 提交及批准检查版本 |
| `content_hash` | `CHAR(64) NOT NULL` | 规范化内容摘要 |

查询索引：`tenant_no,issuer_no,state,starts_at`。

CHECK：`(source_kind='V2_ACTIVITY' AND activity_no IS NOT NULL AND draft_revision_no IS NOT NULL) OR (source_kind='LEGACY_PLAN' AND legacy_plan_version_no IS NOT NULL)`。业务层仍须显式校验。

### sv_release_tier

V2档位金额唯一真源，不在Core再维护一份。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `tier_no` | `VARCHAR(64) NOT NULL` | 档位号 |
| `release_no` | `VARCHAR(64) NOT NULL` | 冻结发行版本 |
| `ordinal` | `INT NOT NULL` | 显示次序 |
| `paid_amount_minor` | `BIGINT NOT NULL` | 实收 |
| `bonus_amount_minor` | `BIGINT NOT NULL` | 赠送 |

CHECK：`paid_amount_minor>0 AND bonus_amount_minor>=0`；`paid_amount_minor<=9223372036854775807-bonus_amount_minor`。业务层仍须显式校验。

### sv_issue_quota

同事务额度计数器，按门店独立发行池及币种串行检查。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `quota_no` | `VARCHAR(64) NOT NULL` | 额度池 |
| `store_no` | `VARCHAR(64) NOT NULL` | 单店发行门店 |
| `currency` | `VARCHAR(8) NOT NULL` | 币种 |
| `outstanding_minor` | `BIGINT NOT NULL` | 已到账未消费合计，冻结部分已含其中 |
| `reserved_minor` | `BIGINT NOT NULL` | 未到账但已占用发行额度 |

CHECK：`outstanding_minor>=0 AND reserved_minor>=0`；`outstanding_minor<=9223372036854775807-reserved_minor`。业务层仍须显式校验。

### sv_issue_quota_reservation

原充值单额度占用及恢复栅栏。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `reservation_no` | `VARCHAR(64) NOT NULL` | 占用号 |
| `quota_no` | `VARCHAR(64) NOT NULL` | 额度池 |
| `recharge_no` | `VARCHAR(64) NOT NULL` | 原充值单 |
| `amount_minor` | `BIGINT NOT NULL` | 实收+赠送占额 |
| `status` | `VARCHAR(24) NOT NULL` | RESERVED/COMMITTED/RELEASED |
| `policy_versions` | `JSON NOT NULL` | 办理检查版本 |
| `expires_at` | `DATETIME(3) NULL` | 未收款可释放；已收款未知不得TTL释放 |

CHECK：`amount_minor>0`。业务层仍须显式校验。

### sv_customer_issuer_index

顾客发行方目录，含零余额有历史账户。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `customer_no` | `VARCHAR(64) NOT NULL` | canonical顾客 |
| `issuer_no` | `VARCHAR(64) NOT NULL` | 发行方 |
| `account_no` | `VARCHAR(64) NOT NULL` | 账户 |
| `currency` | `VARCHAR(8) NOT NULL` | 币种 |
| `has_recharge` | `TINYINT NOT NULL` | 成功到账资格 |
| `last_recharge_at` | `DATETIME(3) NULL` | B端默认排序 |
| `last_activity_at` | `DATETIME(3) NULL` | C端最近储值或核销 |
| `last_occurrence_store_no` | `VARCHAR(64) NULL` | 最近实际发生门店 |

查询索引：`tenant_no,issuer_no,has_recharge,last_recharge_at,account_no`；`tenant_no,customer_no,has_recharge,last_activity_at,issuer_no`。

### sv_clearing_entry

从已过账核销分配生成不可变清分事实，不执行打款。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `clearing_no` | `VARCHAR(64) NOT NULL` | 清分行 |
| `issuer_no` | `VARCHAR(64) NOT NULL` | 资金发行方 |
| `usage_no` | `VARCHAR(64) NOT NULL` | 原核销号 |
| `allocation_no` | `VARCHAR(64) NOT NULL` | 原批次分配引用 |
| `lot_no` | `VARCHAR(64) NOT NULL` | 资金批次 |
| `release_no` | `VARCHAR(64) NOT NULL` | 来源活动发行版本 |
| `issuer_version_no` | `VARCHAR(64) NOT NULL` | 原结算规则 |
| `recharge_store_no` | `VARCHAR(64) NOT NULL` | 原办理店 |
| `redemption_store_no` | `VARCHAR(64) NOT NULL` | 核销店 |
| `payer_entity_no` | `VARCHAR(64) NOT NULL` | 结算付款主体 |
| `payee_entity_no` | `VARCHAR(64) NOT NULL` | 服务收款主体 |
| `currency` | `VARCHAR(8) NOT NULL` | 币种 |
| `principal_used_minor` | `BIGINT NOT NULL` | 核销本金分量 |
| `bonus_used_minor` | `BIGINT NOT NULL` | 赠送分量 |
| `payable_minor` | `BIGINT NOT NULL` | 本规则应付金额，允许冲正为负 |
| `issuer_borne_minor` | `BIGINT NOT NULL` | 发行方承担分量 |
| `entry_type` | `VARCHAR(24) NOT NULL` | USE/REVERSAL/ADJUSTMENT |
| `original_clearing_no` | `VARCHAR(64) NULL` | 冲正或调整的原行 |
| `decision_no` | `VARCHAR(64) NULL` | 退款／调整的已确认决定 |
| `occurred_at` | `DATETIME(3) NOT NULL` | 原账务事实时间 |
| `source_event_key` | `VARCHAR(64) NOT NULL` | 非空稳定事件摘要键，USE/usage/allocation或REVERSAL/decision/allocation |

查询索引：`tenant_no,issuer_no,currency,occurred_at,clearing_no`；`tenant_no,payee_entity_no,occurred_at,clearing_no`。

### sv_settlement_bill

发行方账期对手主体结算，与按订单stl_bill分离。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `bill_no` | `VARCHAR(64) NOT NULL` | 业务单号 |
| `issuer_no` | `VARCHAR(64) NOT NULL` | 发行方 |
| `payer_entity_no` | `VARCHAR(64) NOT NULL` | 付款主体 |
| `payee_entity_no` | `VARCHAR(64) NOT NULL` | 收款主体 |
| `currency` | `VARCHAR(8) NOT NULL` | 币种 |
| `period_from` | `DATETIME(3) NOT NULL` | UTC起含 |
| `period_to` | `DATETIME(3) NOT NULL` | UTC止不含 |
| `bill_kind` | `VARCHAR(24) NOT NULL` | CROSS_ENTITY/INTERNAL_RECON |
| `status` | `VARCHAR(24) NOT NULL` | DRAFT/PENDING_RECON/DISPUTED/PENDING_PAYMENT/PENDING_RECEIPT/SETTLED |
| `base_payable_minor` | `BIGINT NOT NULL` | 原清分合计 |
| `adjustment_minor` | `BIGINT NOT NULL` | 有据调整合计 |
| `registered_payment_minor` | `BIGINT NOT NULL` | 未撤销已登记付款合计 |
| `confirmed_receipt_minor` | `BIGINT NOT NULL` | 收款方确认金额合计 |
| `cutoff_at` | `DATETIME(3) NOT NULL` | 生成截止水位 |
| `confirmed_by` | `VARCHAR(64) NULL` | 对账确认人 |
| `confirmed_at` | `DATETIME(3) NULL` | 对账时间 |

查询索引：`tenant_no,payer_entity_no,status,period_from,bill_no`；`tenant_no,payee_entity_no,status,period_from,bill_no`。

CHECK：`period_from<period_to`；`registered_payment_minor>=0 AND confirmed_receipt_minor>=0`；`confirmed_receipt_minor<=registered_payment_minor`。业务层仍须显式校验。

### sv_settlement_bill_item

清分事实只进入一个正常结算账单。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `item_no` | `VARCHAR(64) NOT NULL` | 明细号 |
| `bill_no` | `VARCHAR(64) NOT NULL` | 账单 |
| `clearing_no` | `VARCHAR(64) NOT NULL` | 原清分行 |
| `amount_minor` | `BIGINT NOT NULL` | 原金额快照 |

查询索引：`tenant_no,bill_no,item_no`。

### sv_settlement_payment

付款登记和收款确认分别记录，无银行执行器。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `payment_no` | `VARCHAR(64) NOT NULL` | 付款登记单 |
| `bill_no` | `VARCHAR(64) NOT NULL` | 账单 |
| `payer_entity_no` | `VARCHAR(64) NOT NULL` | 支付方 |
| `payee_entity_no` | `VARCHAR(64) NOT NULL` | 收款方 |
| `currency` | `VARCHAR(8) NOT NULL` | 币种 |
| `amount_minor` | `BIGINT NOT NULL` | 本次付款金额 |
| `paid_at` | `DATETIME(3) NOT NULL` | 实际付款时间 |
| `receipt_ciphertext` | `VARBINARY(2048) NOT NULL` | 凭据编号加密，列表脱敏 |
| `receipt_digest` | `CHAR(64) NOT NULL` | 主体/通道/编号规范化摘要 |
| `evidence_refs` | `JSON NOT NULL` | 私有授权附件引用 |
| `status` | `VARCHAR(24) NOT NULL` | REGISTERED/RECEIPT_CONFIRMED/VOIDED |
| `registered_by` | `VARCHAR(64) NOT NULL` | 付款侧岗位 |
| `received_by` | `VARCHAR(64) NULL` | 收款侧岗位 |
| `received_at` | `DATETIME(3) NULL` | 实际到账确认 |
| `void_reason` | `VARCHAR(255) NULL` | 撤销原因；已确认不得直接撤销 |

查询索引：`tenant_no,bill_no,status,payment_no`。

CHECK：`amount_minor>0`。业务层仍须显式校验。

### sv_settlement_event

对账、争议、调整、付款确认的只追加审计事件。模块：pay/storedvalue。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `event_no` | `VARCHAR(64) NOT NULL` | 事件号 |
| `bill_no` | `VARCHAR(64) NOT NULL` | 账单 |
| `event_type` | `VARCHAR(24) NOT NULL` | RECON_CONFIRM/DISPUTE/DISPUTE_RESOLVE/ADJUST/PAYMENT/RECEIPT |
| `command_no` | `VARCHAR(64) NOT NULL` | 稳定幂等命令 |
| `event_snapshot` | `JSON NOT NULL` | 事件事实及引用，不存明文凭据 |
| `bill_revision` | `BIGINT NOT NULL` | 事件后的账单版本 |
| `reason` | `VARCHAR(255) NULL` | 争议或调整依据 |

查询索引：`tenant_no,bill_no,bill_revision`。

### sv_activity_daily_summary

来源批次活动统计，不把共享账户余额复制到活动。模块：pay/storedvalue/readmodel。

| 字段 | SQL类型与空值 | 含义 |
|---|---|---|
| `issuer_no` | `VARCHAR(64) NOT NULL` | 发行方 |
| `activity_no` | `VARCHAR(64) NULL` | V2的Core活动，历史PLAN为NULL |
| `source_kind` | `VARCHAR(24) NOT NULL` | V2_ACTIVITY/LEGACY_PLAN |
| `release_no` | `VARCHAR(64) NOT NULL` | 发行版本 |
| `occurrence_store_no` | `VARCHAR(64) NOT NULL` | 实际发生门店 |
| `currency` | `VARCHAR(8) NOT NULL` | 币种 |
| `business_date` | `DATE NOT NULL` | 发行方时区日期 |
| `credited_paid_minor` | `BIGINT NOT NULL` | 当日入账实收 |
| `credited_bonus_minor` | `BIGINT NOT NULL` | 入账赠送 |
| `used_principal_minor` | `BIGINT NOT NULL` | 按本活动批次核销本金 |
| `used_bonus_minor` | `BIGINT NOT NULL` | 按本活动批次核销赠金 |
| `reversed_minor` | `BIGINT NOT NULL` | 冲正额 |
| `clearing_minor` | `BIGINT NOT NULL` | 清分金额 |
| `recharge_count` | `BIGINT NOT NULL` | 笔数 |
| `redemption_count` | `BIGINT NOT NULL` | 笔数 |
| `projection_watermark` | `VARCHAR(64) NOT NULL` | 投影水位／对账批次 |

## 5. 现有表增量

现有字段不改历史语义，以下新字段初期可NULL以支持审计回填；新V2交易必须非空。

| 表 | 新字段 | 用途 |
|---|---|---|
| `pmt_activity` | `management_no VARCHAR(64) NULL` | 经营授权范围 |
| `pmt_activity` | `owner_scope_type VARCHAR(24) NOT NULL DEFAULT 'LEGACY_ENTITY'` | LEGACY_ENTITY/BRAND/STORE |
| `pmt_activity` | `owner_scope_no VARCHAR(64) NULL` | 品牌或门店 |
| `pmt_activity` | `issuer_no VARCHAR(64) NULL` | Pay发行方 |
| `sv_account` | `issuer_no VARCHAR(64) NULL` | 迁移完成后非空 |
| `sv_account` | `issuer_version_no VARCHAR(64) NULL` | 开户时配置快照 |
| `sv_recharge` | `issuer_no VARCHAR(64) NULL` | 原发行方 |
| `sv_recharge` | `issuer_version_no VARCHAR(64) NULL` | 冻结收款/清分版本 |
| `sv_recharge` | `release_no VARCHAR(64) NULL` | 新发行版本 |
| `sv_recharge` | `tier_no VARCHAR(64) NULL` | 新档位 |
| `sv_recharge` | `collection_entity_no VARCHAR(64) NULL` | 实际收款主体 |
| `sv_recharge` | `settlement_entity_no VARCHAR(64) NULL` | 指定结算主体 |
| `sv_recharge` | `quota_reservation_no VARCHAR(64) NULL` | 单店额度占位 |
| `sv_lot` | `issuer_no VARCHAR(64) NULL` | 发行方 |
| `sv_lot` | `issuer_version_no VARCHAR(64) NULL` | 冻结结算规则 |
| `sv_lot` | `release_no VARCHAR(64) NULL` | 来源发行版本 |
| `sv_lot` | `tier_no VARCHAR(64) NULL` | 来源档位 |
| `sv_lot` | `collection_entity_no VARCHAR(64) NULL` | 原实际收款主体 |
| `sv_lot` | `settlement_entity_no VARCHAR(64) NULL` | 原结算主体 |
| `sv_statement` | `issuer_no VARCHAR(64) NULL` | 账务范围 |
| `sv_statement` | `scope_type VARCHAR(24) NULL` | BRAND/STORE |
| `sv_statement` | `scope_no VARCHAR(64) NULL` | 归属品牌或门店 |
| `sv_statement` | `release_no VARCHAR(64) NULL` | 充值来源，混合批次核销使用分配表归集 |
| `sv_statement` | `operating_entity_no VARCHAR(64) NULL` | 发生时经营主体 |
| `sv_statement` | `settlement_entity_no VARCHAR(64) NULL` | 原结算主体 |
| `sv_voucher` | `issuer_no VARCHAR(64) NULL` | 跟账户范围一致 |
| `sv_recharge_plan` | `issuer_no VARCHAR(64) NULL` | 旧单店方案到单店发行方映射 |
| `sv_recharge_plan` | `migrated_release_no VARCHAR(64) NULL` | 历史版本兼容发行配置 |
| `sv_hold_allocation` | `allocation_no VARCHAR(64) NULL` | 可追溯稳定分配号 |
| `sv_hold_allocation` | `issuer_no VARCHAR(64) NULL` | 明确单账户发行方 |
| `sv_hold_allocation` | `release_no VARCHAR(64) NULL` | 来源批次版本 |
| `ord_ent_service_order` | `selected_issuer_no VARCHAR(64) NULL` | 预览及授权绑定的发行方 |
| `ord_ent_service_order` | `selected_account_no VARCHAR(64) NULL` | 内部稳定账户引用；客户端仍用opaque引用 |
| `mbr_identity_ticket` | `operator_scope_type VARCHAR(24) NULL` | STORE/BRAND_VIEW |
| `mbr_identity_ticket` | `issuer_no VARCHAR(64) NULL` | 办理指定资金范围 |
| `mbr_identity_ticket` | `brand_scope_revision BIGINT NULL` | 识别时品牌版本 |

### 5.1 条件式结构切换（不在评审DDL中无条件执行）

1. sv_account先回填issuer_no及可信customer_no，检测同issuer同顾客币种重复；再建立唯一索引uk_sv_issuer_customer(tenant_no,issuer_no,customer_no,currency)，确认所有调用方已用issuer选账后才能删除旧uk_sv_owner和uk_sv_customer_owner。不能保留旧实体门店唯一键阻挡新模型，也不能先删旧键放开重复账户。
2. brand账户store_no为NULL，单店账户保留原store_no。entity_no仅保留原创建归因，不再作为品牌账户授权键；不能把不同结算规则下的历史账户entity_no批量改为新主体。
3. pmt_activity.entity_no需允许品牌owner为空；owner_scope_type=BRAND时owner_scope_no为品牌，STORE时为门店，LEGACY_ENTITY保留旧值。所有旧列表与操作显式限定其旧scope，不能因entity_no为NULL误当全平台活动。
4. sv_recharge来源CHECK扩展ISSUER_RELEASE：release_no、tier_no、issuer_no、issuer_version_no必填；旧ACTIVITY/PLAN约束保留，历史PLAN不伪造activityNo或参与reservationNo。
5. 发布后的V2金额只在sv_release_tier。Core pmt_activity_version.rule_snapshot仅存金融releaseRef/hash和运营规则；旧pmt_recharge_tier保留历史读，不参与V2金额决定。
6. sv_release草稿只有draft_revision_no，activity_version_no为空；经济规则变化创建新release，不原地改金额。Core生成不可变发布版本后，Pay仅允许一次绑定activity_version_no，匹配releaseHash后激活。旧PLAN映射可没有Core活动号，只保留legacy_plan_version_no；不得编造历史活动事实。
7. mbr_identity_ticket在当前店与actor绑定之外携带范围事实修订；顾客识别引用不授予消费扣款。ord_ent_service_order明确锁定所选issuer/account，预览与消费者授权hash包括同一选账；原快照仍canonical校验。
8. 需要新增的issuer读索引：sv_account(tenant,issuer,customer,currency)；sv_statement(tenant,issuer,occurred_at,record_no)与(tenant,issuer,store,type,occurred_at,record_no)；sv_recharge(tenant,issuer,status,created_at,recharge_no)；sv_lot(tenant,account,release_no,credited_at,lot_no)。以EXPLAIN评估覆盖，避免删除旧客户端仍依赖索引。

## 6. 额度、金融事务与状态约束

### 6.1 单店额度

outstanding=账户本金+赠金余额合计，**已冻结部分包含在余额里，不再加一次**。available=余额−held。reserved是已为待入账原单占用但尚未进余额的实收+赠送额度。

新办理在Pay事务内锁当前适用策略版本/作用域控制栅栏与sv_issue_quota，判断outstanding+reserved+newCredit<=effectiveMax，成功后创建原单及reservation。锁按scopeType/scopeNo/编号统一排序；不得在持锁时RPC Core。

未收款取消：RESERVED→RELEASED，reserved只减一次；到账：RESERVED→COMMITTED，reserved减少并outstanding增加，与账户、批次、凭证、收支、目录同事务。核销成功outstanding减去实际总扣减；预占不减，因为资金仍是未消费负债。冲正恢复outstanding；付款登记不改变顾客余额。

已收款未知结果不允许TTL释放reservation。后端worker以原单及稳定命令恢复，不能再次收款。过期清理只有未收款且有持久取消栅栏的原单才能释放。策略收紧不撤销已合法确认收款的原单，恢复按已收款冻结事实完成并记录当前超限风险，不能卡住到账或重复充值。

### 6.2 清分与账单

原始核销批次分配是唯一依据；principal_used与bonus_used在账户扣减时已确定，清分采用原issuer_version规则，不在结算时重新按当前赠送比例计算。混合活动消费分摊到多个来源release。

核销过账和产生清分事实同Pay事务，或以该事务outbox生成并消费去重；结算生成需等待水位完整。source_event_key非空且唯一，避免NULL决策字段导致普通USE重复插入。sv_settlement_bill_item对clearing_no唯一，账单生成并发不能重复领同一行。

金额核对：净应付=basePayable+adjustment；未登记付款=净应付−registered；待到账确认=registered−confirmed。付款新增时锁账单，限制累计registered不超净应付，确认单独锁payment及bill。部分付款可留在PENDING_PAYMENT，足额登记后PENDING_RECEIPT；只有confirmed达到净应付才SETTLED。收款方身份和岗位必须与payee匹配，付款登记岗位不能代替确认。争议、调整均只追加事件；已结算调整进入后续账期。

内部同主体billKind=INTERNAL_RECON，只能核对收支，不能生成payments或虚构向自己转账。负清分/冲正用原行关联，不能删除原账务；净额为负形成后续抵扣／应收处理，不能调用正向付款接口。

原型的赠送承担与实收清分只是示例。规则版本必须明确本金/赠金扣减顺序与舍入方式；分配过程中整数尾差只落到指定最后一项，保证sum(allocation)=usage，退款和冲正严格引用原分配。

### 6.3 会员和品牌变更

新会员先识别平台人档，再按当前店品牌建立BRAND关系或独立STORE关系，单一scope唯一。旧主体会员可能跨多个品牌，不整行复制标签或私有档案；依据来源/实际往来及已确认身份生成多条关系映射。独立门店加入品牌不删除原单店发行方；只变化品牌会员及接受品牌余额资格。

Core品牌绑定和Pay准入使用PREPARING+单调revision协调，品牌加入只在Pay确认准备好后对外可办理。退出／换品牌先阻断相关新执行并检查原单，Pay停止相应准入后才完成Core变更。重新加入使用更高revision，不按当前brandNo重解释旧资金。

## 7. 分阶段迁移与回滚

| 阶段 | 内容 | 必须阻断的风险 |
|---|---|---|
| M0盘点 | 目标环境版本、有效账户/批次/冻结/未到账/会员绑定/未结算清单及备份 | 不能沿用早期“账户为0”结论；当前已经发生过真实联调交易 |
| M1增量 | 新表和nullable字段，旧读写语义仍保持 | 不直接扩大权限，不删除旧索引 |
| M2映射 | 每个原门店创建单店发行方，映射旧方案版本、账户、批次与原单 | 无余额复制、清零、按手机号合并；NULL门店或身份冲突进入人工审计阻断 |
| M3双读核对 | 旧门店账与issuer账余额/冻结/批次/凭证笔数对账；会员scope来源核对 | 对不上不切写；projection不能冒充账本真源 |
| M4受控切换 | 写入栅栏、完成旧原单、unique键切换、issuer必填、旧写接口升级或拒绝 | 不能同时允许未改造旧服务和新服务写不同账户键 |
| M5品牌启用 | 创建品牌发行方及明确主体、激活跨店准入；旧门店余额仍是单店余额 | 不把旧店余额自动转品牌，不绕消费者授权 |
| M6恢复观察 | 幂等、收款未知、额度、通知与清分水位定向验证 | 已有新品牌资金后禁止直接回滚旧二进制；需要停新发行并兼容恢复 |

每次迁移保存old→new映射、数量、金额、摘要和审批记录。旧账只加可追溯引用；如需真正将单店余额转品牌，必须另做有据的转出/转入业务及责任确认，不属于本次普通数据回填。

## 8. 设计检查与未执行事项

本轮仅做结构化字段/索引引用、接口覆盖、DDL静态一致性及金额样例检查。未运行MySQL DDL、未编译后端、未执行全量测试，也未声称迁移可直接上线。新表量26、现有字段扩展10表是评审建议，落地前应结合目标分支中新增的品牌或通用审批能力复用合并，不能重复造表。


### 8.1 原始批次和示例扣减规则补充

新品牌样例明确debit_allocation_mode=PRO_RATA，按该批次原入账实收/赠送比例分配本金与赠送扣减，尾差按原分配规则处理，故2600元总扣减对应本金2000、赠送600。清分PRINCIPAL_COMPONENT使用已经落库的本金扣减分量，不在清分时重新拆一遍金额。

历史单店批次使用LEGACY_SNAPSHOT，继续执行该批次原冻结的本金／赠金使用规则；不能为了让新报表匹配示例而改旧余额分量。历史PLAN没有Core活动号，日报activity_no允许NULL，source_kind标明LEGACY_PLAN；按release与来源方案追溯，不伪造activityNo。


## 9. 主体与渠道商户字段修订

复用现有mch_payment_merchant：entity_no关联经营主体，pay_merchant_no为业务键，pay_channel为渠道；门店当前pay_merchant_no是默认收款配置。一个主体可以有多条不同渠道或门店进件商户，不新增(entity_no,pay_channel)唯一键把该能力收窄。

sv_issuer_version.collection_config保存经核验的版本化route引用数组：payChannel、payScene、payMerchantNo、merchantRevision。每个route必须归属对应实际收款主体；当前配置修改不更新历史版本。支付密钥、完整银行账户仍在既有安全配置或渠道，不复制进储值表。

| 现有表 | 追加字段 | 约束 |
|---|---|---|
| sv_recharge | collection_pay_merchant_no、collection_pay_channel、collection_payment_no | 实际渠道商户／渠道／可信支付内部流水引用；线下可空，不伪造 |
| sv_lot | collection_pay_merchant_no、collection_pay_channel | 跟随原充值事实冻结，历史追溯不查询当前默认商户推断 |

新增表数量仍26张，扩展表仍10张；新增的是现有交易表上的渠道追溯字段，没有创建第二套支付商户。品牌/单店账户键仍是tenant+issuer+customer+currency，不能按充值渠道切钱包。储值清分账单按主体结算，渠道向商户结算继续沿用原支付系统。

## P2迁移实施

V545只新增mbr_scope_member/mbr_store_profile/mbr_scope_legacy_link及mbr_identity_ticket的operator_scope_type、issuer_no、brand_scope_revision、scope_member_no兼容列；与P1同用utf8mb4_unicode_520_ci。历史主体备注不具备撰写门店证据，留原表，不推测复制；新profile只接受明确当前店写入，导入查询不更新last_seen。范围身份不改钱包客户键或余额。

### P3 账户读写适配落地补充（2026-10-23）

本轮没有新表、DDL或已执行迁移修改，旧索引全部保留。已存在的sv_account.issuer_no、issuer_version_no、customer_no等V549维度在新建发行账户时直接写入。发行账户技术标识按tenant/issuer/canonicalCustomer/currency确定；选中存量账户时复用其原accountNo，不重新编号、复制余额或重置账序。

选账在tenant/issuer/customer/currency精确匹配后，关联不可变sv_issuer_version验证账户冻结版本、币种和主体，并校验原门店。额外检查同主体/门店（含来源缺失）下的既有person/user别名账户，有第二个候选即阻断。新建不使用INSERT IGNORE吞掉约束冲突，依靠同Pay事务及发行方行锁与现有账务提交顺序执行；来源不明或不一致时回滚，保留原已收款业务的正常恢复机制。

账务锁顺序保持issuer→quota→account，核销仍先持原source/intent栅栏。新SQL与MyBatis使用同一payTxManager数据源；按accountNo读取/锁定的Mapper方法禁用缓存并刷新本地缓存。尚未切换新唯一键或移除旧唯一键；全写入节点就绪、冻结审计及条件式DDL仍是正式切换前置条件，不能将当前代码视为已完成切换。
