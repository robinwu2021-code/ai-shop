# 平台基础库与基础能力库：review

状态：review 稿。创建：2026-11-26。上一层：[数据库设计](README.md)。
对象：平台基础库 `n_platform`（39 张）、基础能力库 `n_common`（21 张），共 60 张。
方法：把每张新表对着正式库里的原表（保留的表）和被并入的旧表，比对唯一索引、列、长度；再用只读查询取回被收短的 182 个列在正式库里的**实际最大长度**；最后看设计取舍。没有改任何设计文档，没有碰 61。

## 1 结论

- 60 张里有 **27 张**存在差异：唯一键没了或降级 53 处，唯一键用到的列在新表里没有 62 处，原表的列新表没有 8 处。
- 还有 **15 张表只有主键**（平台基础库 11、基础能力库 4），其中 `sys_dict_item`、`sys_sequence`、`mch_role_point`、`mch_pos_staff_pin` 是代码要靠唯一性的表。
- 被收短的 182 个列里，**13 个装不下正式库现有的数据**。这是第一次拿到“收短到底错没错”的实测，不再是推测。
- 其中有一批是**设计取舍**，不是缺陷：并入之后旧表的唯一约束不一定还成立，需要逐个决定唯一键该怎么写。

## 2 被收短的列：13 个装不下

长度是正式库里这一列的最大值。

| 表 | 列 | 原来 | 现在 | 实际最大 | 建议 |
|---|---|---:|---:|---:|---|
| `sys_outbox` | `event_no` | 64 | 32 | **64** | 恢复 64 |
| `sys_outbox` | `source_event_no` | 64 | 32 | **62** | 恢复 64 |
| `sys_outbox` | `event_type` | 64 | 32 | 43 | 放到 64 |
| `sys_outbox_delivery` | `event_no` | 64 | 32 | **64** | 恢复 64 |
| `sys_outbox_operation` | `event_no` | 64 | 32 | 63 | 恢复 64 |
| `sys_outbox_operation` | `request_id` | 64 | 32 | **64** | 恢复 64 |
| `mch_qualification` | `qual_no` | 64 | 32 | 44 | 放到 64 |
| `sys_api_resource_point` | `perm_code` | 64 | 32 | 36 | 放到 48 |
| `sys_audit_log` | `resource_no` | 64 | 32 | 38 | 放到 64 |
| `sys_audit_log` | `action` | 64 | 32 | 34 | 放到 48 |
| `sys_audit_log` | `created_by`、`updated_by` | 64 | 32 | 33 | 见下，作例外放宽到 64 |
| `sys_function_point` | `backend_status` | 16 | 10 | 15 | 取值 `NOT_IMPLEMENTED` 是 15 个字符，放宽到 16 或迁移时换成 `TODO` |

事件号是整个平台的事件主键，真实数据已经用满 64 位，收到 32 位会直接截断甚至写不进去。

**`created_by`、`updated_by` 32 位基本够用，更正（2026-11-26）。** 这份文档最初写的是“要改回 64”，依据只有审计表里的一个 33 位值，说得过头了。后来把正式库里全部 864 张表的 1674 个创建人、更新人列都查了一遍：1658 个不超过 32 位，只有 9 张表超过：

| 表 | 实际最大 |
|---|---:|
| `stl_payment` | 64 |
| `mall_order`、`mall_order_item`、`mall_order_component`、`mall_quote`、`mch_subscription_account`、`mch_subscription_reservation` | 47 |
| `pay_operation_audit`、`pay_merchant_profile_revision` | 33 |

超长的都是服务端写入的复合操作人标识，不是账号编号。公共字段保持 `varchar(32)`，这 9 张表单独放宽到 64，在字段文档里注明是例外。

另有 169 个被收短的列在这两个库里没有超长，可以保留。

## 3 唯一约束：分三种情况

### 3.1 保留的表上，原来唯一的现在不唯一了（要修）

这些表是原样保留的，唯一约束必须带过去。

| 表 | 约束 | 现在 |
|---|---|---|
| `mch_entity` | `entity_no`、`store_code` | 普通索引 |
| `mch_store` | `store_no`、`store_code`、`slug` | 普通索引 |
| `mch_session` | `token_hash` | 普通索引 |
| `usr_session` | `token_hash` | 普通索引 |
| `mch_personnel` | `(tenant_no, entity_no, personnel_no)`、账号对应 | 普通索引或没有 |
| `mch_grant` | `(grant_no)`、`(management_no, actor_no, resource_type, resource_no, permission_code)` | 普通索引 |
| `mch_store_service_area` | `area_no`、`(entity_no, store_no, level, ref_code)` | 普通索引 |
| `sys_role` | `(end_code, role_code, entity_no)` | 唯一，保留 |
| `sys_grant` | `(end_code, subject_no, role_code, scope_no)` | 唯一，保留 |
| `sys_outbox` | `event_no`、`(stream_key, stream_sequence)`、`(source_system, source_event_no)` | 普通索引 |
| `sys_outbox_delivery` | `(event_no, handler_id)` | 普通索引 |
| `sys_setting` | `setting_key` | 普通索引 |
| `sys_document_number` | `document_no`、来源键 | 普通索引 |
| `sys_open_app` | `app_no` | 普通索引 |
| `sys_approval` | `(subject_type, subject_ref, revision)` | 普通索引 |
| `usr_guest_session` | `session_no`、`token_hash` | 普通索引 |

其中最要紧的是**事件表**：`event_no` 唯一、`(stream_key, stream_sequence)` 唯一是事件幂等和顺序的基础，降成普通索引等于去掉了防重。

### 3.2 并入后唯一约束要重新定义（要设计）

这些表合并了几张旧表，旧表各自的唯一约束一部分不再适用，一部分要改写成新表上的约束。

| 表 | 问题 | 建议的唯一键 |
|---|---|---|
| `sys_dict_item` | **只有主键，没有任何唯一键。** 三种字典合并后，同一字典类型里编码重复就会出现两条 | `(tenant_no, dict_type, item_code, market)` |
| `sys_sequence` | 只有主键；旧表按 `seq_key` 唯一 | `(seq_name, scope_key)` |
| `mch_store_end_grant` | 并进来的“端开通排期事件”有 3 个唯一约束（排期事件号、门店加权益加版本、操作来源加权益）在新表上没有索引 | 保留这三条，改写到新列上 |
| `mch_store_device` | 并入了线下写入凭证、打印代理、支付终端的设备部分，旧表的 8 个唯一键只有设备号一条还在 | `(entity_no, store_no, device_no)`、设备序列号、打印代理编号 |
| `mch_store_code` | 并入的桌码有 `token_no`、`token_hash` 两个唯一键，新表上没有 | `code_no`、`token_hash` |
| `auth_ticket` | 并入了登录票据、重置票据、到店码挑战，唯一键涉及的 `challenge_no`、`code`、`key_hash`、`reset_hash` 在新表上没有这些列 | 按票据类型分别定义，或补列 |
| `sys_open_app` | 旧表的 `app_key`、`credential_id` 不在新表 | 应用编号唯一、密钥编号引用（凭据在密钥库） |
| `sys_approval` | 并入了终端经理申请、人工调整，唯一键用的列没带过来 | 以 `(subject_type, subject_ref, revision)` 唯一为准，其余是审批的内容 |

### 3.3 代码在用、设计里删掉的防重键

这几个唯一约束的列被当成技术件删掉了，但代码在读写它们。

| 表 | 被删的列 | 代码 |
|---|---|---|
| `mch_brand` | `create_key`、`create_hash` | `BrandServiceImpl`、`ManagementRepository` |
| `mch_group` | `bootstrap_key`、`bootstrap_hash` | `ManagementScopeServiceImpl`、`ManagementRepository` |
| `mch_entity` | `create_key`（经营主体创建的防重） | `OperatingEntityServiceImpl` |

这三处是“请求防重”：同一个创建请求重复提交只生效一次。按“防重由应用层做，加业务表唯一键兜底”的约定，兜底的唯一键必须有，也就必须有这个键所在的列。我之前把它们当技术件去掉，是错的。

建议：这三个键保留，不当技术件处理。它们是“业务上的幂等号”，和订单号、外部单号一样是业务数据。

## 4 只有主键的 15 张表

| 库 | 表 | 需要的唯一键 |
|---|---|---|
| 平台基础库 | `mch_pos_staff_pin` | 门店加员工唯一 |
| | `mch_role_point` | 角色加权限码唯一 |
| | `mch_category_license` | 主体加授权码唯一 |
| | `mch_store_setting` | 主体、门店、设置域、设置项唯一 |
| | `mch_store_code`、`mch_trusted_device` | 码编号唯一；账号加设备指纹唯一 |
| | `mch_hq_release`、`mch_hq_release_store`、`mch_attendance` | 下发编号唯一；下发加门店唯一；打卡编号唯一 |
| | `agr_agreement_version`、`agr_agreement_consent` | 协议加版本唯一；协议加版本加人唯一 |
| 基础能力库 | `sys_dict_item`、`sys_sequence` | 见 3.2 |
| | `sys_outbox_stream`、`sys_outbox_attempt` | 流键唯一；投递加第几次唯一 |

这些在字段文档里写了“唯一键与索引”的文字说明，但建表语句没有带出来，因为新建的表我只给了主键。这是生成器的缺口，不是设计缺口。

## 5 设计取舍上的几点

**5.1 已核对、没有问题的**

- 平台基础库不存商品、订单、库存，边界清楚；B 端与运营端认证各一套、会话与登录日志各一张，与设计一致。
- 账号和人员保持两张：`mch_account` 的唯一键（主体加邮箱、手机、员工编码、用户号，账号编号）都带过来了。
- 角色与授权：`mch_role_point` 取代了角色上的权限列表，一对多落成表，是对的。
- 基础能力库的事件、字典、审批、发号、审计各一套，没有业务表混进来。

**5.2 要你定的**

| # | 问题 | 建议 |
|---|---|---|
| R1 | `created_by`、`updated_by` | 保持 32，仅 9 张有超长数据的表放宽到 64（见第 2 节更正） |
| R2 | `mch_brand`、`mch_group`、`mch_entity` 的创建防重键 | 保留，当业务幂等号 |
| R3 | `sys_dict_item` 的唯一键 | `(tenant_no, dict_type, item_code, market)` |
| R4 | 事件表的号码长度 | 恢复 64，不收短 |
| R5 | `mch_store_device` 合并了 4 张旧表（门店设备、线下写入凭证、打印代理、支付终端设备部分），旧表的唯一键只剩一条 | 先把线下写入凭证拆回独立表，它的“租约”是并发控制，和设备本身是两个对象 |
| R6 | `sys_function_point.backend_status` | 放宽到 16，不换值 |

R5 是这次 review 里唯一需要动表结构的一条：线下写入的租约、挑战、签名密钥是并发控制，和“这台设备是什么”不是一个对象，并在一张设备表里唯一键就写不出来。

## 6 要改的东西汇总

这些都是设计这边的修改，生成建表语句时一起做：

1. 保留的表上，把原来的唯一索引按原样带回（3.1）。
2. 并入的表按 3.2 重新定义唯一键。
3. 保留 3.3 的三个创建防重键。
4. 15 张只有主键的表补上业务唯一键。
5. 事件相关的号码恢复 64，其余超长的列放宽；`created_by`、`updated_by` 保持 32，只有 9 张有超长数据的表放宽到 64。
6. R5：线下写入凭证拆回独立表（平台基础库 39 → 40）。

改完要重新生成建表语句。61 上已有这两个库的表和一批测试账号的数据（主体、门店、字典 88 行等），**改表需要你的授权**。
