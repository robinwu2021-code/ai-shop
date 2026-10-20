# 餐饮业务工程 · NEXT-01～02

本工程复用平台 `shop-parent → neargo-parent`、Java 21 与统一依赖版本管理。
已实现五模块独立构建、依赖守卫和条件装配骨架，未实现营业 API、数据库装配或支付/库存联调。

| 构件 | 职责 | 当前直接依赖 |
|---|---|---|
| food-api | 后续 Service、DTO 和餐饮专有协议 | shop-base |
| food-core | 后续业务实现、实体、Mapper | food-api、shop-base、shop-store-mybatis；Boot/AutoConfigure、spring-context |
| food-web | 后续 C/B/平台 Controller、远程 Service 适配 | food-api、shop-base、shop-base-auth；Boot AutoConfigure、spring-context |
| food-channel | 后续跨服务协议与 Port 适配 | food-api、shop-base、svc-client |
| food-server | 独立进程启动入口 | food-core、food-channel、spring-boot-starter-web |

子模块直接继承平台父 POM；聚合不承担第二套 BOM。只有 server 打为可执行 jar。
food-web 不依赖 core，channel 不依赖 web/core；不引入零售、支付或库存业务实现。
store 传递带入共享 auth 基础构件，启动入口仅扫描自己的 server 包，不代表已接通鉴权或业务配置。

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
两个模块分别发布自己的 `AutoConfiguration.imports`，尚不代表已被 shop-app 挂载。

验证范围：

- FoodConditionalWiringTest：8 项，覆盖默认关闭、显式关闭、两种部署、默认 embedded、standalone 绑定及非法部署值。
- FoodWebIsolationTest：9 项，覆盖真实无 core classpath、关闭/API/OPS/组合 profile 的 RequestMappingHandlerMapping，以及共享 ConsumerTokenAuthFilter 的匿名拒绝、跨 realm 拒绝和正确令牌访问。
- 独立 reactor 完整 verify：30 项通过，0 失败、0 错误、0 跳过（含 NEXT-01 的 13 项）。
- 开关实现前 core/web 关闭场景均失败；配置绑定接线前 3 项失败。分别删除 core/web 组件开关条件后，关闭场景各产生 1 个失败，恢复后执行完整 verify。
- Controller/Service/Mapper/Job 探针均在 src/test，未打入生产 jar；Mapper 探针验证组件条件，不是 MyBatis 工厂绑定测试。
- 认证测试的安全链由测试宿主定义，复用真实共享过滤器和内存会话；未新增生产安全链，尚不替代 NEXT-03 的真实宿主启动验证。

## 后续边界

下一步是 NEXT-03 宿主挂载与仅内部独立启动，再接迁移、主库与可信范围，最后实现首个桌区切片。
Actuator、MySQL 驱动、Flyway 及业务协议在对应接线任务实际需要时加入，不生成空业务实现。
当前不新增 Controller/Service/Mapper 壳，不移动冻结 V1，不连接或修改数据库。
