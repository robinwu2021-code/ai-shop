# Java 规范（ai-shop）

基准：**[Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)**。
本篇只写两件事：**本仓库对 Google 的偏离**，和 **Google 不管、而这里反复出事的工程约束**。
Google 已经写清楚的（大括号、一行一句、命名、异常不吞、`@TODO` 格式），照它做，不在这里重抄。

---

## 一、对 Google 的偏离（有意的，照旧）

| 项 | Google | 本仓库 | 为什么 |
|---|---|---|---|
| 块缩进 | 2 空格 | **4 空格** | 1493 个文件全是 4 空格（`^    ` 12923 行 / `^  ` 0 行）。为对齐 Google 去重排一遍，收益是零，冲突是全部 |
| 行宽 | 100 列 | **新写的 ≤120** | 存量 >100 的有 20135 行（9.3%），>120 的只有 3264 行（1.5%）。120 是这个仓库实际的线 |
| Javadoc | 公开 API 必写 | **公开 API 必写，且要写「为什么」** | 比 Google 更严。参照 `BizGoodsController` 的类注释：它解释的是「为什么入参里一律不接受 merchantNo」，不是「这是一个控制器」 |

**其余全部照 Google**，其中这三条本仓库还没做到，新代码不许再欠：

- **不用通配符 import**（存量 21 处）。
- **不用全限定名书写注解和跨域类型**。存量：`shop-core` 24 处行首 `@org.springframework.…`、
  `shop-merchant` 8 处、spi 全限定引用 347 处。
  这不只是风格 —— 本仓库的契约比对、依赖分析、文档生成**全部按文本扫描**，
  全限定写法会让它们静默漏判。`ArchitectureTest#noFullyQualifiedReferences` 拦得住的
  **只有 `shop-app` 一个模块**。
- **静态成员用类名限定**，不用实例。

---

## 二、数据库与迁移

### 数据库支持范围（2026-10-30 用户确认）

- 本项目数据库仅支持 **MySQL 9**；本地开发/原生测试使用本机MySQL 9实例（当前127.0.0.1:3308），云端按环境配置同属MySQL 9。
- **不再支持MariaDB**：不新增MariaDB专用分支、环境、兼容承诺或验收任务；本机3306 MariaDB不作为本项目测试目标。
- H2可保留不依赖数据库原生语义的快速测试，但不能替代MySQL 9迁移、事务、锁、JSON/CHECK、排序规则及触发器验收。
- 新迁移直接使用MySQL 9语法及现有身份排序规则。已应用历史SQL/checksum继续冻结；历史MariaDB语法引用及使旧脚本在MySQL执行的迁移适配可保留，不表示继续支持MariaDB。不为清理名称批量改写旧迁移。
- 本机实例说明与私有认证路径以`/Users/robin/work/env/mysql-local.md`为入口，凭据不入库。

Flyway 迁移是本仓库出线上事故最多的一处。

| 症状 | 根因 | 做法 |
|---|---|---|
| 部署后应用起不来，报 checksum mismatch | **已应用的迁移是冻结的** —— 改一行注释、一个 `COLLATE` 都会变 checksum | 只加新迁移，不动旧的。「只有我的库跑过」在共享 HEAD 下通常是错的 |
| 本地全绿，生产起不来 | 两个会话同时加迁移，**版本号撞车** | 加之前先 `ls` 一遍最大号；改号后必须 `clean package`（jar 里装的是旧号） |
| 新加的列永远读出 null | 迁移加了列，**实体没补字段** | 加列同时改实体；跑 entity-alignment 守卫 |
| 生产迁移报语法错，H2 测试全绿 | `--（中文` 在 MySQL 9 是语法错（`--` 后必须有空格）；`\n`、`CROSS JOIN` 两边语义也不同 | 写完跑 `node scripts/check-sql-portability.mjs`；`known-sql-dialect.txt` 是**止血线型**棘轮，一个字都改不得 |
| JSON 列种了脏数据，本地绿、生产迁移炸 | **H2 不能证明 MySQL 9 的 JSON/CHECK 约束语义** | 涉及 CHECK / JSON 的迁移，别只靠单测判定 |
| 建表迁移的列被算到下游别的表上 | 收尾 `) ENGINE=… ;` 断成两行，解析器接不上 | 收尾写成单行。**不要为此放宽解析器**（会变 O(n²)） |
| 迁移里的 `RENAME TO` / 常量 `INSERT…SELECT` 被生成器丢掉 | 生成器的已知盲点，症状会伪装成毫不相干的错 | 这两种写法落地后，手工核一遍生成的清单 |
| 一次失败的迁移之后，所有版本的 jar 都起不来 | Flyway 记了一条 failed，回滚**救不了** | 先清 failed 记录，再切软链，且要再查一次 |

---

## 三、Spring 装配与配置

**这一节的共同点：全部零报错。** 配错了不会抛异常，只是那段代码不生效。

| 症状 | 根因 | 做法 |
|---|---|---|
| 配了开关，行为没变 | `@ConditionalOnProperty` **不可叠加**（不是 `@Repeatable`），写两个只有一个生效 | 多条件用 `@Conditional` 组合或拆 bean |
| `@Value` 读出默认值 | `@Value` **不做 relaxed binding**，`${shop.foo-bar}` 认不出 `shop.fooBar` | 属性名逐字对；多字段配置改用 `@ConfigurationProperties` |
| 校验注解从不触发 | Bean Validation 要**两条接线**：入参上的 `@Valid` + 类上的 `@Validated`，缺一条静默失效 | 存量 22 处已上棘轮闸门，新写的两条都要有 |
| 起不来，`APPLICATION FAILED TO START` | 带开关的 bean 硬依赖了开关更严的 bean（如 `shop.job.enabled=true` + `shop.inventory.enabled=false`） | 见 `known-conditional-wiring.txt`；`ConditionalBeanWiringTest` 读源码，不起上下文 |
| 第二个数据源的表读写全空 | `@Primary` 把数据源接走了；关掉 Flyway bean 会连带关掉**平台全部**迁移 | `inventory` 是独立库独立数据源，唯一合法入口是 `shop-base` 里的 Port |
| 生产上线才炸，测试全绿 | 测试只测了**开着**的那一半；生产常态是关着的那一半，且生产 profile 组合从没装配过 | 上线前按生产 profile 本地起一次（10 秒），见 `scripts/smoke-boot.sh` |

---

## 四、Web 层与契约

| 症状 | 根因 | 做法 |
|---|---|---|
| 内部端点返回 200 + 合法 JSON，但字段全 null | 全局 `ApiResponseWrapper` 把内部契约也裹了一层 | 新开 `/internal` 端点先把它从 wrapper 里排除 |
| 新加的 ops 端点线上看不到 | ops 端点要**五处登记**，只跑 ops-web 与后端测试查不出（跨域登记表在 `packages/shared`） | 照 `scripts/check-ops-contract.mjs` 的判据走一遍 |
| 端点没进 OpenAPI spec | 端点表里注释夹在 `{` 与 `method:` 之间，正则接不上 | 注释放在条目之前；加端点要在 `RESPONSE_TYPES` 登记 |
| 端点存在与否判不准 | **401 不是 404 的反面** —— 不存在的路径也返回 401 | 去跑着的 jar 的 lib 里 `strings` 找，别靠状态码 |
| Controller 越长越大 | 一个类装了不止一个资源，每次加的人都有一个局部合理的理由 | 判据是「路径第一段的种数 ≤3」，不是端点数。见 `known-fat-controllers.txt` |
| 越权访问 | 路径或入参里接受了 `merchantNo` —— 等于把「我是谁」交给调用方声明 | 作用域一律取自 `BizContext#requireMerchantNo()` |
| 发大 body 时请求挂死 | Java `HttpClient` 默认走 HTTP/2，对端（如 sglang）不处理 HTTP/2 大包 | 发大 body 的客户端锁 `HttpClient.Version.HTTP_1_1` |
| 新加的错误码在界面上显示成 `err.xxx`；或传了参数用户只看到一句泛话 | 码在枚举、文案在三份 properties，两边没有编译期联系：`Messages.get` 取不到 key 返回 key 本身；`MessageFormat` 遇到没有 `{0}` 的文案把参数**静默丢掉** | 新增 ErrorCode = 枚举一条 + 三份 properties 各一条；带参抛出的码文案要有 `{0}`（参数是中文业务原因的，该另开一个码）。守卫：`BackendI18nParityTest`（正反两向）、`packages/shared/tests/message-placeholder.test.ts`（两向） |

---

## 五、持久层与数据域

| 症状 | 根因 | 做法 |
|---|---|---|
| 「清空一个字段」那句 update 没生效 | MyBatis-Plus `updateById` **默认跳过 null 字段**，那句 `set` 根本不生成 | 用 `UpdateWrapper` 显式 `set(col, null)`；夹具要先带旧值，否则测不到 |
| B 端直查带域表：`SELECT` 变 404、`UPDATE` 静默 0 行 | 数据域防线拦住了，**读写都要绕** | 两边都包 `DataScopeContext.executeWithoutScope(...)` |
| 加了列，界面看不出区别，闸门全绿 | **只写不读** —— 写入路径改了，读取路径没改 | 加字段时把读、写两条路一起改；替身吞参数会盖住这个症状 |

---

## 六、构建与测试

```bash
# 按实际范围选择编译/测试；下例为显式需要全量验证时使用，不是默认任务
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml test

# 只跑启动模块及其依赖
JAVA_HOME=... mvn -o -f backend/pom.xml -pl shop-app -am test

# 架构规则
JAVA_HOME=... mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest
```

- `-o`（离线）**不再是必须的**（2026-10-25）：父 POM 已是 `neargo-build-parent:2.0.0-SNAPSHOT`，在公司 Nexus 上。首次构建联网并带 `-U`，之后 `-o` 只是为了快。`JAVA_HOME` 指向任一 JDK 21 即可，上面的 Homebrew 路径只是一例。
- **实际使用 `.m2` 依赖的本地 dev server**，更新其依赖时才执行对应模块的 `install`；同一 reactor 已包含的源码不因改了 `shop-core` 就重复安装。
- **端点异常先定位产物与装配**：确认运行包确实陈旧时才清理对应模块重打包；不把 `clean` 作为每次构建或所有404的默认处理。
- **别覆盖正在跑的 jar**：接口挂起且无日志，多半是 `mvn package` 把在跑的 jar 覆盖了。
- **模块 pom 里不写任何第三方版本**（字面量与 `${...}` 都不写）。写了在 `shop-app` 里也**不生效**：
  根工程继承的 Spring Boot BOM 会覆盖传递依赖声明的版本，只会让模块测试与发布物跑两个版本。
  Boot BOM 已管的包父 POM 也不写，要偏离用 BOM 自己的属性名覆盖并写明原因；
  规矩在 `backend/pom.xml` 顶部，`gen-glossary.mjs` 扫到模块里的版本直接红。
  改了父 POM 记得 `mvn -o -N install`，否则 `~/.m2` 里的旧父 POM 会让单独 install 的模块静默丢依赖。

### 测试的四个坑

| 坑 | 症状 | 做法 |
|---|---|---|
| `-Dtest="A+B"` | 一条都不跑，却 `BUILD SUCCESS` | surefire 的分隔符是 `,` 不是 `+`。看耗时、`grep "Tests run"` |
| 改了共享种子没还原 | 单独跑绿、全量红，**报错永远不指向真因** | 改了就还原。查归属：强制 `-Drunorder=reversealphabetical` 先跑嫌疑类 |
| 替身太干净 | 测试绿，真实链路是坏的 | 确认断言覆盖被修复行为；已有失败复现后修复通过即可，不强制撤回修复再次制造失败 |
| 前一道闸先拒 | 被测的那道闸根本没跑到 | 场景测试要确认走到了被测的那一行，不是在上一道就返回了 |

`backend/known-failures.txt` 是 2026-08-24 立闸门那天就存在的失败（1126 跑 / 128 红），
**只准变短**。恒红的闸门等于没有闸门 —— 它既是噪声掩体，也盖住了欠账的真实规模。

---

## 七、包规划、类名与业务边界

本节将既有ArchitectureTest和core实际结构收敛为统一书面规则，适用于零售、餐饮及其他新增业务；不允许业务设计文档再维护平行规范。现有代码的历史欠账不作为新增代码的命名依据。业务词汇另遵循[全域命名基准](../../../../docs/technical/reference/全域命名基准.md)。

### 7.1 包规划

| 类型 | 统一位置 | 例子/约束 |
|---|---|---|
| 单域Controller | `ai.neargo.shop.<domain>.api.biz/mp/ops/internal` | 业务域随功能走，不按收银/厨房设备单独复制业务域 |
| 跨域入口组合 | `ai.neargo.shop.portal.<端>`，宿主shop-app | 只做组合，不能借此形成域间Service互相引用 |
| Service接口 | `<domain>.service` | 接口`XxxService` |
| Service实现 | `<domain>.service.impl` | `XxxServiceImpl implements XxxService` |
| 持久化实体 | `<domain>.entity` | 表名完整驼峰，`fnb_check`→`FnbCheck`；沿BaseEntity |
| Mapper | `<domain>.mapper` | `FoodMappers.CheckMapper extends BaseMapper<FnbCheck>`；集合内去掉表域前缀，不建Repository转发层 |
| 请求和响应 | `<domain>.dto`；简单record可在对应Controller/Service中 | `CreateCheckReq`、`CheckVO`，不与持久化Entity混用 |
| 跨域Port接口/跨域事件 | `ai.neargo.shop.spi.<能力所属域>` | 放既有shop-base/spi；独立契约jar也保持spi命名和唯一接口，不因调用方是餐饮就复制商品Port |
| Port实现 | `<提供实现的域或桥接>.port` | `GoodsQueryPortImpl`；远程实现可`RemoteGoodsQueryPortImpl`，同一部署只激活一种 |
| 协议客户端 | 通道模块的`client`/`adapter` | Http Interface/RestClient只处理协议；不冒充Port契约或Service |
| 业务辅助 | `<domain>.policy/statemachine/assembler/event/job/config` | 有真实职责才建；域与模块划分独立，不强制每种辅助都建模块 |

Controller、Service、Mapper各尽其责；ServiceImpl直接调本域Mapper。模块只因部署、数据源或明确可复用契约边界拆分，不把每层或每个业务对象都建成Maven模块。普通DTO不强制三次等义转换。

### 7.2 类及方法命名

1. 按**业务资源**命名Controller，格式为`Biz/Mp/Ops/Internal + 资源 + Controller`，例如`BizMenuController`、`BizReservationController`。避免宽泛的`BizFoodCatalogController`或`XxxManagerController`承载多个独立资源。是否再限定业务词以避免同名不同义，按全域词汇规则判断，不能机械加Food/Core前缀。
2. Service按业务能力命名，如`MenuService`、`KitchenTaskService`、`DiningCheckoutService`。`DiningCheckout`与资金结算`Settlement`不同，不能因为餐饮表叫settlement就再造同义`SettlementService`；不加没有语义增量的`Core/Application/Domain`。
3. Entity是表的驼峰；Mapper按对象命名；集合按域命名，如`FoodMappers.MenuMapper`。共享打印、报表的Entity/DAO/Mapper归其原域，并沿该域技术栈，不在food复制。已有报表使用JdbcClient/DAO，不为统一餐饮强制改MyBatis。
4. 请求以`Req`、响应以`VO`表达边界用途；查询可复用`PageData`和Query record，Command按实际用例需要创建。存量`Request`命名不在本次批量迁移；新增同类类型先查同域已有名字，禁止同义Req/Request并存。
5. 方法用业务动词，如`openCheck`、`place`、`quote`、`transfer`、`approve`、`sign`；不得用`api001`、序号后缀或技术动词排列掩盖语义。接口编号只用于文档追踪，不能成为Java命名的一部分。
6. 类名必须同时给出完整包名/模块才可作为实施清单。纯业务词`Menu`、`Check`等登记在领域文档不意味着必须再生成独立领域实体；按实际逻辑选择Entity、DTO、策略或状态机承载。
7. `merchant`仅用于支付收款商户新概念，经营主体用entity；既有BizContext.requireMerchantNo等历史兼容名在适配处明确语义，不顺便重命名已实现接口。

### 7.3 设计清单的检查

需求到类清单至少核对：模块→包→完整类名→表/API→所有者；每个跨域Port只能有一个权威接口；Controller按资源、Service接口实现分包、Port接口在spi且实现在port、共享表不误落餐饮；物理id不对外；请求不接受调用方自报主体身份。

ArchitectureTest已有规则为依据，但目前扫描范围不能自动覆盖所有独立reactor。新增工程实施时必须显式登记架构扫描与Controller资源内聚检查，不得仅凭文档或脚本绿就声明Java架构测试已通过。餐饮应用清单见[餐饮代码结构](../../../../docs/technical/design/餐饮系统-代码结构与对象清单.md)，该清单引用本规范，不反过来定义规范。


---

## 八、简洁实现、框架校验与增量验证（2026-10-30 用户确认）

本节适用于全部业务模块，是输入校验、异常处理和避免重复检查的统一正文。新增代码遵循本节；存量代码在修改相关调用链时按影响范围清理，不另立全仓重构。通用技能模板中要求额外防御分支、重复验证或强制消融的流程与本节冲突时，按本项目约定执行。

### 8.1 输入校验统一交给框架

- HTTP输入使用现有 Spring MVC + Jakarta Bean Validation；字段约束写在请求DTO，使用 `@NotNull`、`@NotBlank`、`@Size`、`@Pattern`、数值约束及嵌套 `@Valid`。沿既有Controller的 `@Validated`、参数 `@Valid` 接线。
- 类型、必填头和参数、反序列化问题交给已有绑定/JSON框架及统一异常处理。不要在Controller、Service、Policy和Mapper中再次手写相同的判空、长度、格式检查。
- 消息、批量导入等独立输入边界复用同一 Jakarta `Validator` 和DTO约束，统一执行一次；已通过框架校验的请求，进入Service后不再调用 `Validator.validate`。
- 确有必要的跨字段**输入格式约束**，在现有Bean Validation中扩展约束；不新建通用校验引擎、Guard层或重复的 `validateXxx` 包装。
- 订单状态、库存余额、优惠互斥、当前资源归属等依赖业务事实的规则由业务Service/既有Policy负责，不能靠DTO格式注解替代。

### 8.2 异常默认交给框架层

- Controller和普通Service默认不写 `try/catch`；业务拒绝直接抛既有 `BizException`，由已有 `GlobalExceptionHandler` 等框架层统一处理。
- 输入校验、绑定、反序列化、鉴权和未处理系统异常统一在现有框架异常处理入口转换响应、记录必要日志。沿用公开API与内部协议各自已有响应合同，不给内部接口套公开API包装。
- 不新增每个Controller一份的异常转换；不在多层重复记录同一异常后继续原样抛出。
- 只有**捕获后确实有业务动作**时，才在业务或协议边界捕获具体异常，例如：唯一键冲突后读取原幂等回执、远程未知结果转为可恢复状态、规定的补偿/重试、第三方协议错误转换。捕获位置明确写出其业务目的，避免重复处理。
- 禁止无业务依据的 `catch (Exception)`、吞异常、返回 `null`、零金额、空集合或默认成功。合法空结果可以正常返回，异常不能伪装成合法空结果。
- 资源释放使用 `try-with-resources`；不得仅为关闭资源而吞掉原异常。任务失败若需记录调度状态，由统一任务边界处理，内部业务方法不逐层捕获。

### 8.3 同一规则、同一事实、同一阶段只处理一次

| 责任 | 唯一归属 | 不再叠加的检查 |
|---|---|---|
| 输入格式 | 框架接入边界 | 多层手写同义判断 |
| 身份与访问范围 | 既有认证/授权入口，查询携带scope | 反复解析相同身份、重复查询归属 |
| 业务状态和金额规则 | 对应Service/既有Policy或计算引擎 | 多个入口各复制一套规则 |
| 并发数量和版本 | 数据库原子条件、唯一约束及现有事务方案 | 先查存在、再查余额、提交前再重复查同一事实 |
| 幂等 | 既有原键、永久回执和唯一约束 | 为同一动作叠加多套锁和防重机制 |
| 必需配置与组件 | 启动绑定、配置和装配 | 每个业务方法判断配置/依赖是否存在 |

- 必需依赖直接构造器注入；模块开关在配置与装配中处理，不把必需依赖全部改成可选对象后到处判空。
- 内部已满足合同的数据直接使用；没有明确需求和事实，不增加假想故障分支、降级来源、旧格式兼容或备用算法。
- 重试只由一个明确执行者负责；沿原键恢复，不在客户端、Service与任务层同时重试。
- 预检用于明确的展示/诊断需求，不强制每次业务执行都先跑一遍完整预检。实际写入所需当前事实由执行方一次处理。
- 状态已变化后的业务决策以及新的独立请求，不与前一次相同事实混为一谈。例如试算与正式下单是不同业务动作，各计算一次；同一下单流程不连续重复试算。
- 不删除权限、实际业务规则、库存原子条件或真实幂等语义。清理前明确其归属，将等价重复实现合并到该位置，而非让规则消失。

### 8.4 编译、测试与工作过程不重复

- 先完成本批编码，再统一编译受影响模块及必要依赖，随后集中运行相关测试；编译失败修复后再编译，不逐个小改动默认启动整套构建。
- 缺陷复现一次，修复后执行受影响用例；已记录的失败复现足以证明回归，不强制回退正确修复重复制造失败。
- 代码、依赖、运行配置与迁移结构没有影响结果的变化，复用已有验证证据。文档修改、提交号变化、提交和推送本身不触发业务测试重跑。
- 新增或修改测试只运行相关测试；共享夹具改动按实际影响选择范围。新增失败或依赖变化确实影响其他场景时才扩大验证，并说明具体原因。
- 全量回归、整仓扫描、图谱重建、额外安装/清理、多代理和多轮评审均不作为小改动默认流程；按实际任务需要执行。已有图谱可复用，没有图谱不为普通代码问题默认新建。
- 文档类任务只检查修改内容、引用和必要格式，不执行编译、业务测试或代理压力测试。此次用户明确要求生成规范/记忆，直接更新既有文件，不另建PRD/TDD/ADR。
- 单份规范为正文，入口和记忆引用它；任务和验收状态沿既有清单更新，不重复生成同义文档。不为这些约定新增一套检查框架或强制扫描器。

### Outbox使用收缩（2026-10-30用户确认）

默认Service/Port直接调用，跨进程复用原客户端，不因解耦或异步概念引入Outbox。已有业务意图、任务表和恢复任务足以恢复时，不再叠加第二套通用投递状态。仅在需要可靠跨事务交付且原业务恢复不足时保留；不得删掉原幂等、业务审计或失败恢复。具体本轮清单见TDD-Outbox收缩与直接调用。
