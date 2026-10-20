# 餐饮业务工程 · NEXT-01

本工程复用平台 `shop-parent → neargo-parent`、Java 21 与统一依赖版本管理。
本次仅实现五模块独立构建及依赖守卫，未实现营业 API、数据库装配或支付/库存联调。

| 构件 | 职责 | 当前直接依赖 |
|---|---|---|
| food-api | 后续 Service、DTO 和餐饮专有协议 | shop-base |
| food-core | 后续业务实现、实体、Mapper | food-api、shop-base、shop-store-mybatis |
| food-web | 后续 C/B/平台 Controller、远程 Service 适配 | food-api、shop-base、shop-base-auth |
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

## 后续边界

按已确认 M0 计划继续 NEXT-02 装配开关，再接宿主/内部服务、迁移、主库与可信范围，最后实现首个桌区切片。
Actuator、MySQL 驱动、Flyway 及业务协议在对应接线任务实际需要时加入，不生成空业务实现。
当前不新增 Controller/Service/Mapper 壳，不移动冻结 V1，不连接或修改数据库。
