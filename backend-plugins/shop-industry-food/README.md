# 餐饮业务工程 · NEXT-01～04

本工程复用平台 `shop-parent → neargo-parent`、Java 21 与统一依赖版本管理。
已实现五模块独立构建、条件装配、宿主可选挂载及内部服务安全骨架，未实现营业 API、数据库装配或支付/库存联调。

| 构件 | 职责 | 当前直接依赖 |
|---|---|---|
| food-api | 后续 Service、DTO 和餐饮专有协议 | shop-base |
| food-core | 后续业务实现、实体、Mapper | food-api、shop-base、shop-store-mybatis；Boot/AutoConfigure、spring-context |
| food-web | 后续 C/B/平台 Controller、远程 Service 适配 | food-api、shop-base、shop-base-auth；Boot AutoConfigure、spring-context |
| food-channel | 后续跨服务协议与 Port 适配 | food-api、shop-base、svc-client |
| food-server | 独立进程启动入口 | food-core、food-channel；Web/Actuator starter、MySQL runtime |

子模块直接继承平台父 POM；聚合不承担第二套 BOM。只有 server 打为可执行 jar。
food-web 不依赖 core，channel 不依赖 web/core；不引入零售、支付或库存业务实现。
store 传递带入共享 auth 基础构件；独立入口仅扫描自己的 server 包，内部密钥控制见 NEXT-03，营业接线尚未完成。

## 构建

使用 JDK 21，确保同版本平台父构件及共享构件已安装到本机 Maven 缓存。在仓库根运行：

```sh
mvn -o -f backend-plugins/shop-industry-food/pom.xml verify
mvn -o -f backend-plugins/shop-industry-food/pom.xml dependency:tree
```

缓存缺失时按平台构建流程安装共享构件，不单独升级餐饮的第三方版本。
当前 macOS 可用的 JDK：`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；运行 Maven 前设置 `JAVA_HOME`。

## 本轮验证 · 2026-10-20

- 先执行架构测试：13 项中 8 项因餐饮 POM 不存在失败，0 错误、0 跳过。
- 创建五模块后执行 reactor verify：13 项通过，0 失败、0 错误、0 跳过。
- 包括禁止业务实现依赖、Web/Core 隔离、Channel 隔离、无环、父 POM/版本、打包及反例守卫。
- 消融传递依赖守卫，负例必须失败；恢复后执行完整 verify。
- dependency:tree 核对五构件；jar 核对四个普通库、一个 server BOOT-INF 包。

最小 FoodApplication 从 NEXT-03 提前到本任务，只用于验证 server repackage。
Boot execution 沿父配置使用同一 `repackage` ID，避免重复执行。
这里的可执行包是构建产物，尚不能作为营业服务启动验收结果。

## NEXT-02 条件装配 · 2026-10-20

| 配置/形态 | 当前装配行为 |
|---|---|
| shop.food.enabled 缺省或 false | core/web 自动配置关闭；带对应组件条件的扫描目标不注册 |
| enabled=true，deployment 缺省 | core 配置绑定为 EMBEDDED |
| enabled=true，deployment=embedded / standalone | core 在两种进程形态都可启用；standalone 独立服务仍需要本地业务实现 |
| api profile | Web 装配入口启用；C/B 探针路由存在，ops 不存在 |
| ops profile | Web 装配入口启用；ops 探针路由存在，C/B 不存在 |
| 无 api/ops profile | Web 装配入口及三端探针关闭 |

core 使用 `ConditionalOnFoodEnabled`，web 使用独立的 `ConditionalOnFoodWebEnabled`。
两者均默认关闭；web 不引用 core 的属性类或条件注解。core 的 FoodProperties 只绑定 enabled/deployment，非法 deployment 在开启 core 时拒绝启动。
Web 在两种部署模式均保留接入职责，本轮未创建远程 Service 适配器。

后续新增组件须在组件自身应用对应开关；三端 Controller 同时沿平台使用 `@Profile("api")` 或 `@Profile("ops")`。
不能只依赖自动配置入口的条件保护宿主全包扫描。当前自动配置不做包级扫描或 Mapper 扫描，不提前注册空 Service/Job。
两个模块分别发布自己的 `AutoConfiguration.imports`；宿主可选接入见 NEXT-03。

验证范围：

- FoodConditionalWiringTest：8 项，覆盖默认关闭、显式关闭、两种部署、默认 embedded、standalone 绑定及非法部署值。
- FoodWebIsolationTest：9 项，覆盖真实无 core classpath、关闭/API/OPS/组合 profile 的 RequestMappingHandlerMapping，以及共享 ConsumerTokenAuthFilter 的匿名拒绝、跨 realm 拒绝和正确令牌访问。
- 独立 reactor 完整 verify：30 项通过，0 失败、0 错误、0 跳过（含 NEXT-01 的 13 项）。
- 开关实现前 core/web 关闭场景均失败；配置绑定接线前 3 项失败。分别删除 core/web 组件开关条件后，关闭场景各产生 1 个失败，恢复后执行完整 verify。
- Controller/Service/Mapper/Job 探针均在 src/test，未打入生产 jar；Mapper 探针验证组件条件，不是 MyBatis 工厂绑定测试。
- 认证测试的安全链由测试宿主定义，复用真实共享过滤器和内存会话；未新增生产安全链，尚不替代 NEXT-03 的真实宿主启动验证。

## NEXT-03 宿主与独立进程 · 2026-10-20

### 构建与运行形态

| 宿主构建 | 餐饮构件 | 启用时运行配置 |
|---|---|---|
| 默认零售 | 无 | food 必须关闭 |
| Maven -Pfood-embedded | food-web + food-core（及 api） | shop.food.enabled=true、shop.food.deployment=embedded（缺省 embedded） |
| Maven -Pfood-standalone | food-web（及 api），禁止 core/server | shop.food.enabled=true、shop.food.deployment=standalone |
| 独立 food-server | food-core + food-channel，不含 web/shop-app | 默认 enabled=true、deployment=standalone |

Maven profile 只选择构件，不替代运行配置。宿主 FoodDeploymentConfiguration 在开启时核对 Web、Core 的实际 classpath；错配、缺构件或非法 deployment 拒绝启动。
两个 host profile 不可同时启用。standalone 的 Enforcer 禁令检查传递依赖；餐饮依赖不声明 Maven optional，因为实测 Enforcer 3.5.0 会过滤 optional 根依赖，导致混合构建漏检。默认零售不激活 profile，依赖保持原样。

平台全局 Mapper 扫描增加 food 排除，原 inventory/pay 排除、主 sqlSessionFactory 及数据域→分页→乐观锁拦截器保持。
餐饮自己的条件 Mapper 注册在 NEXT-05 实现；当前未添加 Mapper 或业务写者。

宿主矩阵验证前安装同版本餐饮库（需要 JDK21，本机 Maven 缓存可写）：

```sh
mvn -o -f backend-plugins/shop-industry-food/pom.xml -pl food-api,food-core,food-web,food-channel -am install
mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=FoodEmbeddedWiringTest,PayDeploymentModePairingTest,ConditionalBeanWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am -Pfood-embedded test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am -Pfood-standalone test -Dtest=FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
```

### 独立服务边界

FoodApplication 只扫描 `ai.neargo.shop.food.server`，导入 FoodAutoConfiguration；排除共享用户 Security/Session/RBAC 自动配置及默认用户密码装配。
内部安全链无用户登录、会话签发或三端过滤器：`/internal/food/**` 校验既有 `X-Internal-Token`，缺失配置、缺请求头、错误密钥都拒绝；匿名可读三个 health 路径，其余路径拒绝。
服务密钥仅校验调用方，未来各 typed 内部合同仍须验证主体、门店及资源范围。

配置沿 `spring.datasource.*` 指向商品/订单同一主库，环境入口为 FOOD_DB_URL/FOOD_DB_USER/FOOD_DB_PASSWORD，无开发密码回退；默认端口 8086，可由 FOOD_SERVER_PORT 设置。
SHOP_INTERNAL_TOKEN 接既有 shop.services.internal-token。独立进程明确关闭默认 core Flyway，不执行共享主库迁移。

`foodReadiness` 目前固定 OUT_OF_SERVICE，readiness 返回 503；schema 与远程 Port 就绪尚未实现，后续必须以真实检查替换本阶段阻塞指示器。
当前没有营业 Controller；内部路由及三端扫描陷阱均为 src/test 探针，不进入生产 jar。

验证结果：

- FoodDeploymentContractTest 5 项：核对真实 RequestMappingHandlerMapping；无公开三端映射、有内部正例；GET/POST 密钥校验、默认用户认证缺席、非营业 readiness。
- 餐饮 reactor verify 共 35 项通过，0 失败、0 错误、0 跳过。
- 宿主默认与 embedded 各执行 7 项，standalone 执行 8 项，均通过；前两种形态各有 1 项 standalone 专用测试按条件跳过。
- 实际依赖树确认 embedded 有 web/core，standalone 只有 web/api；同时启用两 profile 的 validate 必须因禁止 food-core 失败。
- 删除密钥校验或扩大 server 扫描范围，各有 1 个目标断言失败，0 错误；恢复后完整 verify。
- 宿主关闭测试启动真实 PlatformDataSourceConfig/MybatisPlusConfig，使用隔离 H2 核验主工厂、连接与三拦截器；不代表完整 ShopApplication 营业验收。
- 全量后端 `mvn -o -f backend/pom.xml -pl shop-app -am test` 最初被沙箱阻止 svc-client 的本地测试端口；允许本地端口后重跑，发现 SchemaDriftTest.migrationAndTestSchemaMatch 失败（pay_ent_exception_action 列比对）及 SchemaSeedReplayTest.full_schema_and_extra_seeds_can_be_replayed 错误（pmt_preset_template 种子重复回放约束）。
- 为区分本轮影响，复制原两个测试和原 SQL 到独立临时 Maven 工程，仅依赖 Spring JDBC/H2/JUnit，不依赖 host 或 food：3 项中 1 失败、1 错误，复现相同原因。未改支付/活动 SQL；全量回归在确认这两项失败后主动中止，不能标记全量通过。
- 未连接共享业务数据库，未运行真实 jar 联调；完整营业、迁移与独立支付/库存合同验收仍在 NEXT-05/07/10。

## NEXT-04 冻结迁移归属 · 2026-10-20

唯一源码：[V1__food_business_tables.sql](food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql)。
原文件未被 Git 跟踪，本轮只在 food-core 新位置入库，同时纳入此前餐饮专属生成器、校验依赖和匹配的设计基线，供新检出复验。

- 移动前后 SHA-256：`215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`。
- classpath 仍为 `db/industry/food/V1__food_business_tables.sql`；版本1、fnb_flyway_history 和 SQL 字节均不变。
- 已应用 V1 原有结尾空行保持不变；项目级 .gitattributes 禁止该文件换行转换，并只对它豁免 blank-at-eof 检查。
- 宿主源码不留副本，不放入平台默认 `db/migration`。后续宿主构建应先安装当前餐饮库，并 clean 旧构建输出，避免旧 target/classes 或本机旧 SNAPSHOT 造成假验证。
- gen-food-schema 固定已应用摘要；即使同步修改设计与 SQL，生成/检查都拒绝回写 V1。改表应追加新版本迁移。
- 隔离 MySQL 校验读取实际冻结文件，只用本机缓存镜像、无网络、无暴露端口、tmpfs 和一次性随机容器；执行完删除，不读取应用库凭据。

验证命令（JDK21；在仓库根）：

```sh
node --test scripts/tests/food-schema-sql.test.mjs scripts/tests/food-design-contract.test.mjs
node scripts/gen-food-schema.mjs --check
mvn -o -f backend-plugins/shop-industry-food/pom.xml clean verify
node scripts/verify-food-schema-mysql.mjs
```

结果：Node 15 项通过（DDL6+设计9）；Java 38 项通过（原35+verify阶段3项构建产物检查），均无失败/错误/跳过。
`FoodMigrationPackagingIT` 通过现有平台 Surefire 的独立 verify-phase execution 读取实际普通jar和server嵌套jar；只有 core 包含一份 V1，server 通过嵌套 core 包含一份，摘要一致。报告位于 server/target/migration-resource-reports，不与普通单元测试报告混淆。
独立 server 的验收只依赖它的 Maven 构件闭包，不要求 food-web 的 target 存在；实际移除 web target 后 server-only clean verify 的29项测试通过。
另以 HEAD 工程与本轮 Git 暂存文件构造独立临时检出，Node15项及Maven38项复验通过，不依赖原工作区其他未跟踪文件。

一次性 MySQL 9.7.2 实际创建73表，验证一餐牌多店、一菜多选择组、菜项多SKU、跨店多工位、任务与源行去重、配额及容量检查。
这不是应用库迁移；本轮未连接 ai_shop，也未执行共享库 migrate/repair/validate。已应用环境的 Flyway.validate 和自动迁移接线属于 NEXT-05。
NEXT-03 记录的两项独立 schema 回归失败未在本轮修复，不将上述定向通过解释为整个 backend 全量回归通过。

## 后续边界

下一步是 NEXT-05 主库、事务及独立迁移校验，再接可信范围和首个桌区切片。
Actuator 与 MySQL 驱动已随独立进程加入；餐饮 Flyway 校验与业务协议按对应接线任务落地，不生成空业务实现。
当前未新增 Controller/Service/Mapper 业务壳，已原字节移动冻结 V1；未连接或修改应用数据库。
