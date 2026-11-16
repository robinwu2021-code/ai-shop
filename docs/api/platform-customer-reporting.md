# 平台客户只读查询

状态：2026-11-14 本地实现；未部署。菜单与测试映射见本批平台运营菜单 TDD。

所有路径只支持 GET，沿用公开 `ApiResult<T>` 包装。请求身份从已验证的 OPS MAIN 会话取得；调用方不能传操作者、租户或数据范围。返回物理主键始终不对外。

| 路径 | 必需权限 | 参数 | 返回 data |
|---|---|---|---|
| `/ops/customers/users` | `customer:user:read` | keyword（账号编号/昵称，≤64字）、page≥1、size=1..100 | `{page:PageData<Registration>,total,withoutStores,newLast30Days,trend:[{date,count}]}` |
| `/ops/customers/users/{userNo}/stores` | `customer:user:read` | userNo | `StoreLink[]` |
| `/ops/customers/members` | `member:member:read` | keyword（恰好4位号码尾号，或3..64位人档编号）、storeNo、brandNo、page、size | `PageData<Person>` |
| `/ops/customers/members/{personNo}` | `member:member:read` | personNo | `{person,memberships:Membership[],sources:Source[],stores:StoreLink[]}` |
| `/ops/customers/chains` | `merchant:merchant:read` | keyword（品牌编号/名称）、page、size | `PageData<Brand>` |
| `/ops/customers/operating-entities/{id}` | `merchant:merchant:read` | 独立经营主体号 | `{operatingEntityId,name,market,status,version,legalSubjectId,ownerUserNo,allowedActions}` |

分页默认 page=1、size=20。所有列表计数及页选取在授权范围内进行；同一响应的组合查询使用只读可重复读事务。无可用 MERCHANT 锚点且非全量范围的会话得到空列表与零计数。

## 字段与事实口径

- `Registration`：userNo、nickname、status、market、registeredAt（UTC毫秒）、entityCount、storeCount。来源是未删除 `usr_account` 注册资料，包含尚未建店账号；不是“纯SaaS经营者人数”。门店/主体关系来自有效 `mch_account`，不会把 `usr_account.entity_no` 常去店当成经营归属。地区为空时保持未知。total/withoutStores/30日趋势跟随同一 keyword 与范围，趋势按UTC自然日补全30天。
- `Person`：personNo、userNo（可空）、phoneTail（可空）、status、joinedAt、membershipCount、sourceType、sourceStoreNo、sourceStoreName。每个真实 `(tenant_no,person_no)` 只返回一行；不根据姓名/手机号尾号造合并。仅统计授权可见的 `mbr_member` 关系，首次来源按其最早 joined_at/id 原始记录的 first_store_no/source 读取，不被后续到访或筛选覆盖。未记录来源门店时保持空。
- `Membership`：memberNo、entityNo、entityName、status、joinedAt、sourceType、sourceStoreNo、sourceStoreName。`Source`：sourceNo、memberNo、sourceType、storeNo、storeName、occurredAt、first。来源与往来表必须同时匹配tenant/member/entity，详情保留全部授权关系及历史来源。
- `StoreLink`：storeNo、name、entityNo、brandNo、brandName。当前门店/品牌元资料仍受当前门店所属主体范围限制；历史来源门店已迁移到范围外时保留历史storeNo，隐藏当前店名/品牌；不能通过历史关系筛出范围外现有门店。
- `Brand`：brandNo、name、status、managementNo、managementName、stores。以真实 `mch_brand`、`mch_management_space` 与有效 `mch_brand_store_binding` 读取。有效指ACTIVE且已到effective_from、未到effective_to；不会使用商家经营漏斗替代连锁/品牌。受限会话仅可见有授权有效门店的品牌，门店计数仅包含可见门店；全量会话还可见零门店品牌。
- 独立经营主体详情只读取未删除、ENABLED的 `mch_operating_entity`，所有人必须对应未删除NORMAL真实 `usr_account`。允许ALL范围，或legal_subject_no匹配授权MERCHANT；未绑定法人时只可由实际owner的有效is_owner经营账号关系匹配授权主体。范围外返回FORBIDDEN，未知/停用/无有效用户返回NOT_FOUND。`allowedActions`为`["PAYMENT_MERCHANT_CREATE"]`，表示来源经营主体允许进入支付侧代建检查；支付侧仍验证自身实例市场、平台授权及创建流程。ownerUserNo仅来自来源权威记录，不将OPS STAFF冒充USER。

## 对应实现与验证

契约：`ops-web/lib/api/contracts/customer.ts`；入口：`ai.neargo.shop.portal.ops.OpsCustomerController`；查询：`ai.neargo.shop.portal.service.impl.CustomerReportingServiceImpl`；身份范围：`CustomerReadAccess`。

原生MySQL9测试 `CustomerReportingMysqlTest` 从冻结的生产迁移提取真实CREATE TABLE，保留原始字段、唯一键及原生生成列约束；历史MariaDB排序规则仅按项目MySQL9适配。覆盖未建店账号、范围内计数/分页、相同号码尾号不同人档、首次来源不可覆盖、品牌有效期及门店移出范围后的历史标识保留/元资料隐藏。
