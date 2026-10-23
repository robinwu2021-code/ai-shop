# RECIPE-00A 物料与基础单位事实查询

> 档位1 · 承接增强方案P1配方前置合同 · 已实现

## §0 需求对账

依据[完整需求文档§12](../requirements/餐饮系统-完整需求文档.md)与[增强方案P1](TDD-餐饮系统-餐牌选配套餐与耗用增强方案.md)，提供配方定义所需的真实进销存物料及基础单位身份，先满足读取权威，不提前开放配方或扣料。当前inv_uom是预留字典，现有过账数量为int；inv_item无version列，不能填0冒充真实修订。物料可以没有前台销售SKU，物料档案和余额仍属于独立进销存数据源。

| AC | 实现/验收 |
|---|---|
| 真物料、真基础单位 | MaterialCatalogQueryPort +原InventoryAclService只读查询；真实独立库存Mapper、H2及原生MariaDB迁移验证 |
| 主体隔离，无查询创建副作用 | 只读外部主体找ACTIVE且externalRef精确匹配的owner，物料owner过滤；不调用会创建owner/item的旧方法，不访问库存余额/流水 |
| 不伪造单位版本或换算能力 | baseUom来自inv_item；unitSupport=BASE_ONLY，postingSupport=INTEGER_ONLY；definitionHash标识内容，不是CAS或时间序列 |
| 本地/独立餐饮一致 | 库存域Port实现、内部Token接口、food-channel远程适配；失败不重试、不变空集合 |

## §1 契约与边界

跨域唯一接口位于shop-base的ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort.getMaterials(entityNo,itemIds)。主体和物料业务键1～32，无首尾空白；请求主体/每个物料键及回执所有文本字段必须是原始JSON字符串Token，拒绝数字/布尔自动转换；1～256个且不重复。返回只含请求集合内的可用事实，未知/外主体/归档/退休/缺基础单位省略；没有精确externalRef匹配的owner返回空但不创建，数据库大小写/重音排序规则匹配不能替代Java精确键比对。基础单位是该物料的实际不透明代码，不从inv_uom或名称猜测换算，不把未知值补为PIECE。sourceOnSale=0不妨碍原料身份，原料可OWN或SYNCED且无销售SKU。

MaterialFact字段：itemId、entityNo、name、baseUom、weighed（0或1）、dataSource（OWN/SYNCED）、unitSupport=BASE_ONLY、postingSupport=INTEGER_ONLY、definitionHash。摘要为material-definition:v1及上述语义字段的长度前缀SHA256，不含物理id/ownerId/余额/成本/更新时间；内容相同摘要相同，不能作为单调修订、CAS或单位换算版本。

| 输出字段 | Java/JSON类型与约束 |
|---|---|
| itemId、entityNo | String，1～32；物料业务键及外部经营主体键，均无首尾空白 |
| name | String，非空白，最长128，保留原名称 |
| baseUom | String，1～16，无首尾空白，物料自身基础单位代码 |
| weighed | Integer，原始整数Token 0/1，拒绝小数/字符串/缺失值 |
| dataSource | String，OWN或SYNCED，不要求有销售SKU |
| unitSupport、postingSupport | String，固定BASE_ONLY和INTEGER_ONLY |
| definitionHash | String，64位小写十六进制SHA256 |

摘要规范顺序：`material-definition:v1, entityNo, itemId, name, baseUom, weighed十进制, dataSource, BASE_ONLY, INTEGER_ONLY`；每项先写4字节大端UTF-8字节长度，再写UTF-8内容，依次送入SHA256。摘要不证明数据可信，服务Token和主体隔离负责信任边界。

内部POST `/internal/inventory/catalog/materials/batch` 输入entityNo/itemIds，原服务Token优先校验、拒绝未知字段，输出裸MaterialFact[]。有效查询未找到返回200空数组；无Token401、参数400、禁止403、数据库/权威故障500；库存模块关闭时端点不装配，调用失败保留为基础设施失败，不把404路由缺失当物料不存在。所有用户/门店授权仍在后续B端边界，不暴露经营主体自报的用户写入口。

当前提供方是PLATFORM进程内的独立库存模块/独立inv数据源；仓库没有INVENTORY进程定义，不假造服务目标。food-server通过现有PLATFORM服务发现、内部Token及5秒单次调用获取；embedded直接使用原本地Port。未来库存实际拆进程时再迁移服务定位，不复制物料权威。

保留InventoryAclService为外部entity键映射与隔离查库的唯一位置：新增findMaterials只读方法返回库存内部MaterialIdentity；inventory.port.MaterialCatalogQueryPortImpl只适配事实与摘要，不碰原商品或库存命令。查询事务采用invTransactionManager、只读REPEATABLE_READ，外层平台事务不得改变库存数据源归属。控制器通过原InternalServiceResponses先验证Token，再用注入的JsonMapper解析原始请求字节；手工校验DTO保留鉴权顺序，控制器不引用Servlet或Web运行时类型。

## §2 实施计划

- [x] 1. 真实独立库存Mapper测试RED：OWN无SKU、下架SYNCED、外主体/归档/退休/缺单位过滤，查询零写入，内容摘要变化，不回退主库，实际只读事务；实现共享SPI、ACL只读方法、Port。
- [x] 2. 内部API和food-channel本地/远程接线，鉴权先于解析、坏输入与未知字段、故障500、坏回执/摘要/范围拒绝、单次调用及不重试，测试RED→GREEN。
- [x] 3. 库存原生临时MariaDB运行真实迁移及来源测试，餐饮/商品沿原MySQL验证；餐饮全模块、库存关闭与三宿主回归；消融隔离守卫RED后恢复GREEN，一次fresh审查，精确提交。

**模块类：** shop-base.spi.inventory.MaterialCatalogQueryPort/MaterialCatalogInternalApi/MaterialCatalogFacts；shop-inventory.service.InventoryAclService新增findMaterials及MaterialIdentity，impl.InventoryAclServiceImpl实现；shop-inventory.port.MaterialCatalogQueryPortImpl，api.internal.InternalMaterialCatalogController。food-channel.channel.port.CoreMaterialCatalogQueryPortImpl及FoodCatalogClientConfiguration。shop-app持有来源/HTTP测试，food-channel持有真实远程适配测试；新增scripts/verify-food-material-mariadb.py执行库存原生来源测试，原MySQL验证脚本保持不变。无新迁移或库存写业务改动。

**预检：** inv_item与InvEntity没有version/deleted/tenant字段，禁止沿餐饮BaseEntity复制；inv_uom无执行用途，不能称已支持换算；实际V1物料id上限32，不能沿H2夹具的40误设合同；ownerIdOf会创建owner，查询只能只读找existing owner；库存独立数据源明确绑定，不能复用primary；当前PLATFORM路由是实际现状，不代表库存已独立服务部署。

## §5 验收证据

| AC | 实际测试方法/结果 |
|---|---|
| 真物料与主体隔离 | MaterialCatalogQueryPortTest.returnsOwnMaterialsWithoutSalesSkuAndNeverLeaksAnotherOwnersSameItemKey；collationEquivalentOwnerAliasesNeverRelabelOrHashFactsForAnUnknownExactKey（H2大小写及MariaDB大小写/重音真实匹配后精确拒绝） |
| 查询零创建、数据源与事务 | missingOwnerAndMaterialReadsDoNotCreateOwnersItemsLocationsBalancesOrLedgers；ReadOnlyProbe断言实际inv事务、只读与REPEATABLE_READ，主库放同名诱饵表 |
| 不伪造单位或版本 | archivedRetiredAndMissingUnitsAreOmittedWithoutInventingPieceOrReadingDormantDictionary；contentHashTracksDefinitionsAndDoesNotPretendToBeAnInventoryRevisionOrBalanceVersion |
| 参数先拒、故障不变空 | badInputsFailBeforeSqlAndDatabaseFailureNeverBecomesEmptyOrReadsPrimary |
| 本地API鉴权及协议 | InternalMaterialCatalogApiTest共4项：鉴权优先、裸数组/500无隐私、真实typed HTTP往返、库存默认/显式关闭 |
| 独立餐饮一致性 | CoreMaterialCatalogQueryPortTest共7项：真实配置+HTTP/Token；坏范围/元数据/摘要拒绝；404及超时不变空/单次调用；weighed严格整数和所有文本原始Token；坏输入无调用/缺配置失败；餐饮关闭 |

测试先RED：来源测试缺少SPI/Port；HTTP测试缺少控制器/内部协议；远程测试缺少适配器。各自补齐后GREEN。初次H2主库夹具因缺DB_CLOSE_DELAY被关闭，仅修正测试生命周期。原架构守卫发现控制器不能引用Servlet，改为原始字节先鉴权再解析后，相关规则GREEN。

消融：只撤掉owner ACTIVE守卫，归档业主断言实际失败（1/1失败，返回MILK）；finally恢复实现，最终来源回归和原生MariaDB再验证。消融辅助脚本的断言文案曾匹配`to be empty`而实际为`Expecting empty`，已直接核对真实测试失败内容及恢复代码，不把辅助脚本文案当成业务错误。

修复前完整验证：餐饮`clean install`共362项，320实际通过，42条件跳过；`node --test scripts/tests/food-*.test.mjs`43/43；修复前来源5、内部API4、远程6、条件装配1均通过。修复前原生验证：餐饮MySQL191/191、商品MySQL4/4、库存MariaDB11.4的5/5，均无跳过；库存每个测试库原样执行V1～V13迁移。第一次组合脚本整体失败已如上记录，餐饮/商品各测试本身通过，之后独立库存脚本完整成功。

修复前来源/API/条件装配10/10；三宿主实际执行零售5、embedded5、standalone6，前两者各1项只适用于standalone的条件跳过。原系统ArchitectureTest最终15/16通过，唯一失败是其他会话OpsPointsInspectionController全限定注解；本批领域依赖、Controller位置、Port等相关规则通过，未声称全仓架构全绿。独立审查：Critical0、Important2、Minor0；两项重要问题已复现RED：数字请求键原先200而非400，远程文本可被数字/布尔转换；大小写等价的未知主体返回了重标事实。单次修复：合同范围TextDeserializer严格约束请求与回执，ACL核对owner.externalRef精确相等；未改数据库排序规则或其他调用方的旧findOwner行为。最终单次修复回归：

```text
餐饮 clean install: Tests363, Failures0, Errors0, Skipped42, Executed321
物料来源6/API4/条件装配1: 11/11实际通过
远程物料合同: 7/7实际通过，包含所有文本字段数字/布尔Token拒绝
原生库存MariaDB11.4: 6/6通过，无跳过；V1～V13原样执行，大小写/重音别名实际匹配后由ACL精确拒绝
原生餐饮MySQL191 + 商品MySQL4: 195实际通过，无跳过（与物料修复无关的来源未改动）
三宿主: retail5、embedded5、standalone6实际通过；前两者各1条件跳过
设计/冻结迁移合同: 43/43通过
原系统架构: 15/16；唯一失败仍为其他会话OpsPointsInspectionController的全限定注解
```

两项Important各有实际失败测试并已GREEN，无Critical/Minor，无第二次审查。代码和方案以本批15路径精确提交，其他会话索引与工作区保留。

## §6 实现对账与后续

§2模块逐行落地：共享3类，库存ACL接口/实现各1类及Port/Controller各1类，food-channel适配/装配各1类；3个行为测试类分别位于shop-app与food-channel。验证脚本增加真实库存Flyway来源测试；增强总方案仅添加前置切片链接。共15个精确路径（9个生产Java、3个测试Java、2份设计文档、1个验证脚本），未涉及商品/订单/支付/库存写业务、迁移或独立库存服务目标。

偏差说明：原生MySQL首跑已完成餐饮191项和商品4项，但库存V1因MariaDB专属utf8mb4_uca1400_ai_ci排序规则失败；未改冻结迁移，单独使用已缓存MariaDB11.4验证库存。原MySQL验证脚本恢复原样，新增MariaDB专用验证脚本。

原门户内部端点的Servlet异常处理不能直接搬入库存域；采用Token优先的显式JsonMapper解析，维持原协议及领域依赖规则。库存事实只覆盖基础单位，不修改增强库存合同的PENDING状态。完整架构守卫另有不属于本批的OpsPointsInspectionController全限定注解失败，未改该文件、棘轮或守卫。

本批不提供新物料维护、单位换算、配方工作头/发布、实际耗用、价格或库存命令。下一切片基于本事实合同做基础单位配方，换算与小数数量执行须在进销存扩展后验收。原料录入沿真实库存主数据能力，缺少的维护入口另行实现，不能靠查询自动创建。


本批决策与代价：基础单位/整数能力及内容摘要不等于换算、小数执行或CAS，完整配方/预占/耗用/报表仍后续实现；实际提供方为PLATFORM内库存模块，未来拆进程须改服务定位；显式JSON解析满足Token优先和无Servlet依赖，需由该控制器维护解析；冻结迁移保持，须分别验证MySQL与MariaDB。内部服务Token不替代未来B端用户/门店授权；Keeta和生产部署未启用，实际渠道同步及运行就绪未经验证。独立审查只读，整套测试由实施会话执行，未第二次独立重跑；其他会话改动不提交，其积分架构失败仍需所属会话处理。
