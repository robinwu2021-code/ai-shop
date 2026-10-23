# P2 品牌会员共享实施设计

状态：编码与定向验收完成，最终审查及发布中。依赖P1经营组织；迁移V545。资金发行、余额合并和跨店核销不在本阶段。

## 模块与功能矩阵

| 功能 | Core/member | Merchant | BFF | B端 |
|---|---|---|---|---|
| 可信身份 | MemberIdentityFactsPort由User实现，平台人档唯一 | — | 不信任请求中的customerNo/userNo | 沿用现有会员新增入口 |
| 当前运营范围 | 通过SPI取得BRAND/STORE及revision | MemberOperatingScopePortImpl维护品牌→门店锁及当前事实 | 执行门店由现有权限验证 | 只在最外层切店 |
| 入会与旧映射 | MemberScopeService，按tenant/scope/customer唯一 | 品牌跨主体，独立店隔离 | MB03与原会员入口均接通 | 储值页没有重复新增会员入口 |
| 搜索 | MemberScopeQueryService，签名游标 | 只提供必要来源店名称 | MB01只读当前店账户 | 联想、搜索按钮、来源店 |
| 顾客选择 | 短时引用绑定actor/entity/store/scope及品牌revision | 品牌变化使旧引用失效 | MB02支持会员号、账户引用、统一会员码 | 详情底部储值，底部弹框选方案 |
| 隐私与状态 | 本店资料只读本店profile；历史归属不明资料不复制 | 不因同品牌扩大财务权限 | 无账务读权不查询Pay余额 | 无账户不显示余额或伪造零余额 |

旧会员、标签、营销受众及统计接口保留既有主体口径，未因P2扩张全品牌营销授权。品牌共享使用本阶段门店路径的MB接口；旧钱包仍为当前门店账户。

## 数据库

V545新增3张表，与已部署组织表相同使用utf8mb4_unicode_520_ci。所有新表有id内部键、tenant_no、version、deleted及created/updated_at/by；业务码沿用中央BizKey。

| 表 | 主要字段 | 唯一约束 |
|---|---|---|
| mbr_scope_member | scope_member_no、scope_type BRAND/STORE、scope_no、canonical customer_no、first_store_no、status LEAD/ACTIVE/BLOCKED、joined_at、shared_profile | tenant/scope_member_no；tenant/scope_type/scope_no/customer_no |
| mbr_store_profile | store_profile_no、customer_no、store_no、remark、advisor_actor_no、first_seen_at、last_seen_at | tenant/store_profile_no；tenant/store_no/customer_no |
| mbr_scope_legacy_link | link_no、legacy_member_no、scope_member_no、migration_no、identity_evidence、status | tenant/link_no；tenant/legacy_member_no/scope_member_no |

扩展mbr_identity_ticket：operator_scope_type、issuer_no（本阶段始终NULL）、brand_scope_revision、scope_member_no均为可空兼容列。旧票据字段为空时继续旧规则，新的运营范围票据必须重验范围、成员状态和revision；这只是身份识别，不是扣款授权。

shared_profile本阶段只写空对象；历史主体备注和标签留在原表，不推测备注由哪个门店撰写。只有新录入时明确当前店的备注写入本店profile。历史导入不更新现有profile，也不把查询行为记成服务到访。

## API清单与权限

统一ApiResult包装；以code=0判断成功。店员需要当前店CUSTOMER及SV_RECHARGE_CREATE；余额另需SV_ACCOUNT_READ。新增会员只需当前店CUSTOMER。逻辑权限映射既有BizPerms，不新增tenant级全资源角色。

| 编号 | 接口 | 请求 | 返回 |
|---|---|---|---|
| MB01 | GET /biz/stores/{storeNo}/stored-value/customer-candidates | keyword?、cursor?、size=20（1–100） | items、nextCursor、identityConflictCount |
| MB02 | POST /biz/stores/{storeNo}/stored-value/customer-selections | source严格三选一 | selection、sourceStoreNo、sourceStoreName |
| MB03 | POST /biz/stores/{storeNo}/members | phone、remark?、tagNos? | ScopeMemberSummary |

MB01有keyword时返回范围会员，字段memberNo是新scope_member_no；displayName只使用本店明确备注，否则“顾客”，phoneMasked、memberStatus、canRecharge、unavailableReason、account、sourceStoreNo/Name。无keyword时返回当前店已储值账户，memberNo=null，按Pay现有最近储值倒序游标，不混入其他店余额。

MB02 source：`{type:MEMBER,memberNo}`、`{type:ACCOUNT,accountRef}`、`{type:MEMBER_CODE,code}`。只返回短时customerRef，不接受任意customerNo。selection复用旧选择字段：customerRef/expiresAt/memberNo/displayName/phoneMasked/account/pendingScope，外层附来源门店，B端适配器展平使用。ACCOUNT仍走旧单店授权；统一会员码只能识别当前范围已有成员，不借识别码隐式入会。

MB03和旧POST /biz/members均先使用现有人档/主体会员机制，再建立当前品牌或单店运营关系。旧入口返回原会员号；新入口返回运营关系号。备注与标签不扩张到其他主体。手机号录入不代表本人注册或营销同意，未绑定账号仍为LEAD。

## 主流程及事务

1. 门店权限校验→Merchant解析当前品牌/单店范围。
2. User解析可信平台人档，检查user绑定一致性；不能按同手机号、同尾号或同显示名合并不同人档。
3. 新增会员：既有主体会员与scope会员在同一Core事务更新；品牌→门店锁后用锁定读重验，scope自然唯一。外层事务中不自动重试已标记回滚的内层写事务。
4. 历史映射：只接受first_store_no或实际mbr_member_store往来证据，显式tenant/entity/store过滤。已存在VERIFIED映射按scope跳过。NULL来源不默认分配品牌；不一致身份返回冲突数量，不能生成钱包或改历史customer。
5. 会员搜索不发放资金。BFF获取当前店Pay账户摘要，不跨主体/门店查账；同步Pay调用不在Core持锁事务内。
6. 选择新运营成员→发放120秒哈希持久化引用→绑定当前actor/entity/store和品牌revision。切店、换人、品牌变化、当前主体拉黑均拒绝旧引用的新使用。统一会员码仍沿用平台码，不新增储值专码。
7. 后续储值沿用现有单店方案和原单恢复；已持久化充值命令不替换原customerRef/幂等键。新命令的过期识别引用按同一新入口刷新。
8. 本人后来正常绑定账号可令LEAD运营关系转ACTIVE，保留canonical customer和BLOCKED状态，不复制或合并任何余额。

旧主体拉黑仅约束该主体的新路径，不能由一个门店隐式拉黑其他主体的品牌会员；scope_member本身的BLOCKED是独立范围状态，账号认领不将其解除。

## 代码清单

| 模块 | 类/文件 |
|---|---|
| shop-base SPI | MemberOperatingScopePort、MemberIdentityFactsPort，新增中央BizKey前缀MSM/MSP/MSL |
| shop-merchant | MemberOperatingScopePortImpl |
| shop-core/member | MemberScopeModels、MemberScopeRepository、MemberScopeService、MemberScopeQueryService；MemberServiceImpl与MemberCodeResolveServiceImpl接缝；MbrIdentityTicket新增兼容列 |
| shop-core/user | MemberIdentityFactsPortImpl，不泄露完整号码或密文 |
| shop-app | ScopedStoredValueMemberService、StoredValueScopeViews、BizMemberScopeController |
| b-app | api/member-scopes.ts；CustomerAccountList、stored-value-account、StoredValueRechargeSheet；既有会员新增入口；三语词条 |

采用与P1一致的显式tenant/resource JDBC事务仓库；不添加未使用的ORM层。数据响应为类型化DTO，内部customerNo不作为公开请求凭据。

## 验收与上线

受影响模块编译，定向单测、真实Spring安全链HTTP、隔离MySQL演练、B端组件及类型检查。已通过且未受影响的用例不重复跑，不运行全量套件。Core与B H5/Android独立发布；Pay和C端无需升级，本阶段不新增品牌资金能力。

回退应用仍保留V545新表及兼容票据列、映射与审计；不删除关系、不回写旧会员号、不清零余额。部署和安装包版本见阶段交付记录。
