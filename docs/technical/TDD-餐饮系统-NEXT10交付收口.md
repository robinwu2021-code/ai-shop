# 餐饮系统 NEXT10 交付收口

> 状态：桌区配置切片交付收口；正式营业就绪未放行。日期：2026-10-21。基线：ba72b2959，分支codex/food-next01。本轮只复验并对齐文档，不修改业务Java或冻结迁移。

## 0 交付结论

API001～003的门店策略和日历、API092～095的桌区配置已具备本地实现及typed独立调用。桌区可查询、创建、修改和安全启停；核心行为已通过真实数据库与实际入口验证。

本批达到继续开发非交易桌台配置的条件。平台能力授权、支付库存完整交易合同、正式订单接线及营业readiness仍需分别验收；不因本批测试通过而开放正式交易。

## 1 交付对账

| API | 业务方法 | 主数据 | 实际入口与适配 |
|---|---|---|---|
| 001 | StoreOperationService.getPolicy | fnb_store_policy读取 | BizStoreOperationPolicyController / RemoteStoreOperationService / 内部策略入口 |
| 002 | createPolicy | fnb_store_policy版本追加 | 同上，发布与原审计/幂等/Outbox同事务 |
| 003 | saveCalendar | fnb_service_calendar | BizServiceCalendarController / typed远程日历调用 / InternalServiceCalendarController |
| 092 | TableService.listTableAreas | fnb_table_area分页 | BizTableAreaController / RemoteTableService / InternalTableAreaController |
| 093 | createTableArea | fnb_table_area创建 | 同上，店内编码唯一，version0 |
| 094 | saveTableArea | fnb_table_area乐观锁更新 | 同上，仅改名称和排序 |
| 095 | setStatusTableArea | fnb_table_area及桌台/占用安全检查 | 同上，活动资源阻断停用；无变更不重复发事件 |

Controller、Service/Impl、DTO/VO、Mapper沿既有餐饮包规划；不在core零售域复制实现。实体复用FnbStorePolicy、FnbServiceCalendar、FnbTableArea、FnbActionLog。实际变化同时保存原审计指纹和现有Outbox业务前后快照。

需求与方法级AC对应见[M0 NEXT-09](./TDD-餐饮系统-M0基础工程与接入方案.md)及[桌区需求](../requirements/餐饮系统-NEXT09桌区管理需求细化.md)。141个API设计、79张候选表不代表均已实现；本批只有上述7个B端业务端点及对应内部合同交付。

## 2 本轮复验

环境沿JDK21和任务私有Maven缓存 `/private/tmp/food-next08c-m2`；Maven离线执行。MySQL脚本仅创建本机回环、临时容器和临时库，未访问或修改现有业务库。

| 验证 | 本轮结果 | 证据 |
|---|---|---|
| 餐饮reactor verify | 136项实际通过，20项数据库条件跳过 | /private/tmp/food-next10-reactor.log |
| 一次性MySQL | 64项实际通过，0跳过：15主库、48业务/基础守卫、1真实server | /private/tmp/food-next10-mysql.log |
| 默认零售宿主 | 5项实际通过、1项形态条件跳过 | /private/tmp/food-next10-host-default.log |
| embedded宿主 | 5项实际通过、1项形态条件跳过 | /private/tmp/food-next10-host-embedded.log |
| standalone宿主 | 6项实际通过、0跳过 | /private/tmp/food-next10-host-standalone.log |
| 设计/DDL/模块/方法守卫 | 30项通过，0失败、0跳过 | /private/tmp/food-next10-design.log |

上述日志是本轮本机证据，临时路径不承诺长期保留；命令与实际执行数在本记录中保留。关键命令：

```sh
# 下列Maven命令均加 -Dmaven.repo.local=/private/tmp/food-next08c-m2，JAVA_HOME指向JDK21
mvn -o -f backend-plugins/shop-industry-food/pom.xml verify
# 通过MAVEN_OPTS传入同一私有缓存
python3 scripts/verify-food-primary-mysql.py
mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
# 分别追加 -Pfood-embedded -Dfood.build.shape=embedded
# 或 -Pfood-standalone -Dfood.build.shape=standalone
```

范围包含餐饮reactor、三形态宿主及其零售禁用接线回归；没有执行或宣称整个后台所有业务测试通过。版本保护和审计快照的RED→GREEN及消融证据已在NEXT-09记录，本轮未改业务逻辑，不重复编造开发过程。

## 3 架构和迁移边界

FoodArchitectureTest验证五模块依赖无环、food-web不依赖food-core、food-api不依赖运行时及Service接口实现命名。FoodWebIsolationTest与宿主形态测试核验独立入口不持有本地核心实现；FoodDeploymentContractTest核验独立server没有B/C/平台用户路由或用户会话链。

已核对迁移源码唯一；server实际打包构件仅含一个food-core、不含food-web；该core jar只含一个餐饮V1迁移。冻结V1 SHA256：`215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`。构建target中的资源副本不视为重复迁移源码。

## 4 就绪与权限的剩余边界

| 能力 | 当前结论 | 后续准入 |
|---|---|---|
| 门店范围、原STORE权限、可信actor | 本批真实入口及越权测试通过 | 新端点继续复用，不凭业务编号授权 |
| Schema校验 | 缺表列、历史异常、checksum漂移及缺核心能力会阻止Mapper就绪 | 继续冻结迁移和validate策略 |
| 内部服务密钥 | 缺失或错误拒绝请求，readiness保持503 | 不替代B端账号认证 |
| 营业readiness | 当前固定OUT_OF_SERVICE，即使schema已就绪也不开放 | 尚无可营业UP状态，因此未验证运行中数据库断连的UP→DOWN动态降级；真实远程合同及动态依赖检查另行实现验收 |
| API081平台餐饮能力授权 | 未交付 | 沿平台原授权体系单独推进，不默认授予跨主体权限 |
| 独立支付、库存 | NEXT-07已登记合同及环境缺口 | 依赖能力未齐时继续关闭正式交易；不阻断无交易配置开发 |
| 订单接单判定接线与事件下游 | 日历判定及Outbox事实已交付，消费闭环未交付 | 分别随订单与消费端验收 |

NEXT-10的配置交付收口完成；完整营业就绪及运行时依赖动态降级仍未验收，不能将其勾选为已完成。

## 5 下一切片

进入[TBL-02桌台配置需求细化](../requirements/餐饮系统-TBL02桌台配置需求细化.md)：原API024、096～100，复用既有fnb_table和TableService，先展开DTO/VO、Mapper及锁顺序。建桌、移区和重新启用必须与桌区停用共用桌区仲裁行。

只推进桌台配置；桌码、开台占用、清台、转并拼桌、AA、点餐支付、打印和日结仍按原模块顺序逐项交付。resourceNo权威查询与归档Operation回执在开发设计时先对齐，不接受无法校验的资源引用。
