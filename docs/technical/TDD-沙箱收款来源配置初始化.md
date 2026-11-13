# 沙箱收款来源配置初始化

状态：已实现，待独立review与部署决策。范围：ai-shop `shop-app/paybridge`；不新增公开API、不接收payment fact自注册、不修改V664与原接收授权。

## 0 需求与验收

来源事实必须分别从PAY应用/来源/门店与SHOP门店/实体核对；配置是受保护部署输入，不是支付成功证明。当前收款源表没有正常provision入口，旧沙箱收款TDD中的“运维受控SQL配置”改为本配置初始化流程。原TDD尚未提交，本文件只交付本轮独立增量。

| 验收 | 落点 |
| --- | --- |
| 无配置不查表、不改旧映射，兼容未装V664 | 默认空source-bindings；空列表直接返回 |
| SANDBOX/CHECKOUT且sourceId=payAppId；真实两端ID独立 | 配置Bean Validation、独立paymentStoreId/shopStoreId |
| 校验当前SHOP ID/店号/实体/market=TW及店/实体未删除 | 平台数据源事务内当前行读取 |
| 同键同target enabled=1幂等；不同target/disabled拒绝 | V664既有复合主键、无覆盖占位写及原行核对 |
| 批量失败全部回滚；早于outbox启动 | eager bean初始化事务，失败阻止Spring上下文refresh |
| 不泄漏秘密 | 仅记录来源安全坐标与稳定指纹；不记录Environment/全部配置 |

## 1 配置合同

复用前缀`shop.payment-collections`，同现有`enabled=true`开关，新Properties只绑定`source-bindings`，默认空。每个列表项显式填写：`environment`、`pay-app-id`、`source-type`、`source-id`、`payment-store-id`、`shop-store-id`、`store-no`、`entity-no`、`market`。

本轮仅允许SANDBOX/CHECKOUT/TW及source-id=pay-app-id；ID为正数。PAY paymentStoreId和SHOP shopStoreId可以不同，PAY来源projection ID不能替代PAY核心store ID。不向默认配置写任何环境业务fixture或凭据。

配置上线前记录两侧独立只读事实及确认人/变更依据：PAY实例、SANDBOX绑定、CHECKOUT应用及sourceId、核心store ID和来源坐标；SHOP ID、store_no、entity_no、market、未删除状态。启动仅核对本地SHOP事实，不能反过来声称已验证远端PAY事实。禁止从收到的payment fact推断/注册来源授权。

移除配置只停止未来初始化，不删除映射；已禁用映射不能被配置重新启用。来源变化或冲突必须另行审核，不能靠更换配置覆盖旧授权。

## 2 实施计划与幂等

| 文件/模块 | 职责 |
| --- | --- |
| shop-app / ai.neargo.shop.paybridge.PaymentCollectionSourceProperties | 校验单一配置边界，嵌套列表默认空 |
| 同包 / PaymentCollectionSourceConfiguration | 按enabled装配eager初始化bean，并依赖数据库初始化完成 |
| 同包 / PaymentCollectionSourceBootstrap | 平台数据源/transactionManager，整批provision及安全审计 |
| 同包测试 / PaymentCollectionSourceBootstrapTest | 真实内存JDBC事务、Spring启动顺序、配置绑定与冲突测试 |

| 参数 | 数据库 | 服务端 |
| --- | --- | --- |
| 受保护显式来源元组及独立SHOP ID，无请求自动注册 | 复用payment_collection_source复合主键，不加表/迁移/第二套幂等日志 | 当前SHOP事实校验；占用原键后读回，完整相同才幂等；整批同事务、失败传播阻止启动 |

所有普通eager singleton的创建/初始化发生在SmartLifecycle启动之前，因此不使用启动较晚的ApplicationRunner。仅已提交事务的配置映射能够被后续outbox worker观察。

## 5 验证

`PaymentCollectionSourceBootstrapTest`覆盖：空配置、开关关闭、新建与两端不同ID、重复启动、旧数据保持、冲突整批回滚、disabled、跨实体/店号/店删除/实体删除/market、环境/type/sourceId与正数约束、提交早于outbox、失败不启动outbox。

验证使用进程内H2，只证明普通JDBC事务与Spring接线，不冒称MySQL9原生唯一键/并发锁证据；本任务不连接任何真实数据库。原生测试须在单独明确授权的MySQL9临时schema进行。

## 6 交付记录

本轮只新增上述三个生产类、一个测试类和本TDD；基于干净HEAD `8363a308` 的独立 `codex/payment-source-bootstrap` worktree，未搬运主工作区尚未提交的收款实现/迁移/旧TDD，也未修改接收契约、默认环境配置或原V664表。

验证命令（JDK21）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=PaymentCollectionSourceBootstrapTest,OutboxConfigurationTest,ConditionalBeanWiringTest -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.heap=2g
```

结果：40模块reactor BUILD SUCCESS；新增bootstrap 21项、既有outbox装配3项、条件依赖1项，共25项通过，0失败/错误/跳过。日志 `/tmp/payment-source-bootstrap-verify.log`。初轮新增20项及编译已通过，补入安全审计测试后执行上述最终验证。未执行全仓测试，不作全仓通过声明。

附加执行同reactor `-Dtest=ArchitectureTest`：22项、5失败、1跳过。失败为当前HEAD既有文件的 `implsMustLiveInDedicatedPackage`（LoginIdentityServiceImpl/StoreEntityClaimServiceImpl）、`noFullyQualifiedReferences`（旧类全限定引用）、`everyScheduledMethodMustBeLocked`（ExecutionStockRetentionRecoveryJob/StoreEntityClaimRecovery）、`serviceMustBeInterface`（FoodStockProjectionQueryService/NativeChannelAfterSaleService/FoodProductionStockService）、`controllersMustNotTouchMappers`（InternalMaterialCatalogController的JsonMapper被规则识别）。本轮三个生产类均未出现在违规清单；没有为通过门禁改动无关代码/规则。日志 `/tmp/payment-source-bootstrap-architecture.log`，不作ArchitectureTest通过声明。`git diff --check`通过。

原未跟踪 `TDD-沙箱收款消息与SSE.md` §配置建议由主流程替换为：

> 显式来源绑定由受保护 `shop.payment-collections.source-bindings` 配置在启动时事务provision，默认空、无配置不读写来源映射表。仅支持经PAY/SOURCE与SHOP双边事实独立核对的SANDBOX/CHECKOUT/TW来源；paymentStoreId与shopStoreId独立填写。已有相同键与目标且enabled=1幂等，不同目标/禁用记录拒绝并整批回滚；不覆盖、不删除旧映射，不提供公开写API、不从收到的payment fact自注册。详细合同见《沙箱收款来源配置初始化》。
