# CAT-04C API019选项维护

## §0 需求对账

档位1，承接增强方案§2.3/2.9 P1及ADR040。API019：维护选择组工作头、真实选项、互斥/依赖及当前门店商品绑定；支持SPEC_VALUE与已有MODIFIER草稿引用。报价、配料创建/发布、不可变配置发布另行交付。

| AC | 模块/API/表 |
|---|---|
| 规格值64字符完整保存 | V3增量扩宽fnb_option_group_item.spec_value_no；原字典Port |
| 原真实规格和主体自建配料身份校验 | SpecValueCatalogQueryPort，fnb_modifier；SPEC须ENUM，MODIFIER须同主体有效工作头 |
| 完整选择约束与有序读回 | OptionSelectionPolicy，OptionGroupService，既有组选项读接口 |
| 当前门店绑定，不串主体/门店 | 原GoodsCatalogQueryPort + fnb_dish/fnb_dish_option_group |
| CAS/幂等/审计/Outbox原子写与回滚 | 原基础设施与实体乐观锁；一组一事务 |
| 原鉴权及双部署API | BizOptionGroupController、InternalOptionGroupController、RemoteOptionGroupService |

## §1 输入输出和范围

PUT `/biz/x/food/option-groups/{groupNo}/items`，原GOODS权限，当前门店从BizContext及门店目录校验，Idempotency-Key必填。输入version非负；items（≤100）、goodsNos（≤100）、selectionRules均必填，可传空集合清理草稿。输出configNo、version。未知字段/不合法编号/数量拒绝，不接受物理id、金额、库存或自报主体/门店。

items：optionItemNo（≤32，全局唯一，建议32字符UUID；调用者稳定业务引用，供同请求规则引用；不是物理主键）、sourceKind SPEC_VALUE/MODIFIER、二者引用严格互斥、minQty/maxQty/defaultQty DECIMAL(18,6)非负且min≤default≤max、sortNo非负。重复项号/重复来源拒绝；当前工作行中的既有项号不得移到别组或换身份，替换来源需用新项号；被移除工作行的历史解释以后续完整修订快照为准，不用当前表回填历史。SPEC引用≤64，须原字典当前主体可见的ENUM值；MODIFIER引用≤32，同主体DRAFT/ACTIVE的ADDON/COMBO。此处不声称配料已发布或可售。

selectionRules：mutexSets（选项编号集合数组，每组≥2）、requires（{optionItemNo,requiresItemNos[]}）。引用必须存在，不能重复、自依赖或成环，传递依赖不能与互斥矛盾；默认/必选集合不得互斥，默认正数量必须包含其依赖；默认/最小数量和不得超组上限。草稿可空或暂不满足组下限，最终可执行性由后续发布和交易校验。

goodsNos是**当前获准门店**的完整绑定集合：须原商品权威确认主体门店，且本地有餐饮菜品配置。既有绑定批量查询权威，只有明确归属当前门店的绑定可删除；外店、缺失/归档而无法确认归属的绑定保留，不能用空goodsNos绕过门店权限全主体清理。此取舍保留失效绑定以供后续获准清理，不猜归属。

写前读取远程规格/商品事实，不在本地事务内等待网络；拒绝加入已有外层事务。事务内锁组头，校验版本，重验本地引用，替换工作头子项/当前店绑定，版本递增，状态DRAFT，保留既有publishedRevisionNo；不可变发布修订不修改。物理删除仅限可变草稿子行/关联，既有审计/事实/修订禁止删除。使用当前完整快照及稳定规则归一化计算幂等指纹，不将重试变成重复事件。

## §2 实施计划

1. 失败测试：V3扩宽且V1/V2冻结；选择规则/数量/重复/循环/传递矛盾；服务真实Mapper写读、CAS/重放、跨主体/门店绑定保护、出箱失败回滚、64字符、来源身份；接口鉴权/未知字段、typed本地/远程一致。
2. 新增DTO OptionItemInput、SelectionRules、ReplaceOptionGroupItemsReq、OptionGroupMutationVO；OptionGroupService与内部typed API增加replaceOptionGroupItems，原组选项查询读回。
3. 新增OptionSelectionPolicy集中规范化；既有OptionGroupServiceImpl增加真实依赖、事务编排，沿FoodMappers嵌套Mapper；新增既有fnb_modifier实体及只读Mapper，不预建收费管理空接口。子行替换显式带entity/group，避免逻辑删除唯一键冲突。
4. V3扩宽并升级校验到版本3及物理长度验证；目标真源同步64，V1投影显式回退32并保持原摘要；主/旧设计、生成物和迁移资源检查同步。
5. 原Biz当前门店/目录/GOODS鉴权及内部token，补typed远程方法，不自动重试。真实一次性MySQL和三宿主、全模块/设计守卫；独立一次只读审阅；精确提交。

## §5 验收证据

| AC / 测试 | 真实结果 |
|---|---|
| SelectionRuleTest 6项 | 重复号/来源、引用XOR、数量界限、未知/自引用、循环、传递互斥、默认和必选合法性 |
| OptionGroupItemsTest 6项 | 64字符、绑定及CAS/重放、当前店清理/外店及未知保留、合法配料/跨主体禁用拒绝、改项身份拒绝、跨组编号碰撞回滚、出箱失败整组回滚 |
| 原OptionGroupServiceTest 5项 | 原创建/查询/归一化/回滚/事务边界回归 |
| OptionGroupApiTest 4项 + RemoteOptionGroupServiceTest 5项 | 原权限及可信当前店、新PUT、嵌套未知字段、typed命令和下一版本回执、原HTTP与错误不重试 |
| FoodPrimaryDatabaseTest 新增2项 | V2旧值升级V3且V1/V2 checksum不变；完整64写读；伪造当前历史但字段仍32时VALIDATE拒绝且不迁移 |
| FoodOptionGroupPrimaryDatabaseTest | 原token/无信封typed调用，真实规格权威HTTP、V3物理64、写入/重放/读回及冲突、原商品订单哨兵零写入 |
| FoodMigrationPackagingIT 3项 | V1摘要、food-core与standalone嵌套包仅一份V1/V3；无复制到宿主 |

JDK21及离线Maven缓存`/private/tmp/food-next08c-m2`。核心新类型/方法缺失、V3源仍32的测试先红；实现后业务17项、接口及远程9项通过。移除当前门店商品权限判断，外店绑定用例反例变红；恢复源码原字节后16项业务变绿，后补跨组编号碰撞用例也在最终全量执行通过。

最终餐饮全模块`clean install`：**287总计 / 250实际执行 / 37条件跳过，0失败0错误**，含打包3项。三宿主retail5实际+1条件跳过、embedded5+1跳过、standalone6无跳过，全部通过。设计43项（提交内原28项及工作区既有清单草案扩展）通过，V1/V2摘要原样。

最终一次性MySQL：19 + guards124 + standalone3 + 原规格4 =150实际、无跳过，包含跨组编号碰撞回滚。只使用一次性数据库，未对业务库应用V3。

一次独立只读审查：无Critical/Important。Minor延后：FoodSchemaValidator字段长度校验使用含下划线的JDBC模式且只取首行，若存在同模式匹配的额外表/列，可能误判真实字段宽度；后续应按精确TABLE_NAME/COLUMN_NAME过滤并补原生回归。本轮标准迁移结构及实际64字符写读已验证，不将此边界场景报告为已覆盖。

## §6 实现对账

| §2模块 | 实际交付 |
|---|---|
| 契约 | 4个DTO、OptionGroupService及OptionGroupInternalApi新增replace方法 |
| 业务规则 | OptionSelectionPolicy及SelectionRuleTest，规范化图和数量 |
| 主业务 | OptionGroupServiceImpl、FoodMappers工作行/关联删除、FnbModifier只读头及ModifierMapper，FoodDataScopeRegistration |
| 业务接线 | FoodAutoConfiguration懒原商品/规格Port；不在本地事务内等待网络 |
| API/双部署 | BizOptionGroupController、RemoteOptionGroupService、InternalOptionGroupController；测试同批 |
| 数据兼容 | V3、FoodSchemaValidator版本3及字段长度；主/旧真源、冻结投影、生成器/文档/测试 |
| 验证 | 原始主库迁移及独立typed用例、构建包验证、MySQL脚本及业务测试 |

收费配料创建/COMBO及不可变发布仍待后续，现有配料引用不宣称收费、库存或发布能力。选择定义变化事件沿FOOD_OPTION_GROUP_CHANGED用于配置变更，不引入事件溯源或新增消费者运行时。全局代码清单仍为独立草案，本实施清单是此批实际类方法回执，不吸收共享工作区其他修改。
