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

Flyway 迁移是本仓库出线上事故最多的一处。

| 症状 | 根因 | 做法 |
|---|---|---|
| 部署后应用起不来，报 checksum mismatch | **已应用的迁移是冻结的** —— 改一行注释、一个 `COLLATE` 都会变 checksum | 只加新迁移，不动旧的。「只有我的库跑过」在共享 HEAD 下通常是错的 |
| 本地全绿，生产起不来 | 两个会话同时加迁移，**版本号撞车** | 加之前先 `ls` 一遍最大号；改号后必须 `clean package`（jar 里装的是旧号） |
| 新加的列永远读出 null | 迁移加了列，**实体没补字段** | 加列同时改实体；跑 entity-alignment 守卫 |
| 生产迁移报语法错，H2 测试全绿 | `--（中文` 在 MariaDB 是语法错（`--` 后必须有空格）；`\n`、`CROSS JOIN` 两边语义也不同 | 写完跑 `node scripts/check-sql-portability.mjs`；`known-sql-dialect.txt` 是**止血线型**棘轮，一个字都改不得 |
| JSON 列种了脏数据，本地绿、生产迁移炸 | **H2 不执行 MariaDB 的 CHECK 约束** | 涉及 CHECK / JSON 的迁移，别只靠单测判定 |
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
# 编译 / 全量测试（enforcer 锁 [21,22)，本机默认 JDK 可能是 17 或 26）
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
  mvn -o -f backend/pom.xml test

# 只跑启动模块及其依赖
JAVA_HOME=... mvn -o -f backend/pom.xml -pl shop-app -am test

# 架构规则
JAVA_HOME=... mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest
```

- `-o`（离线）是必须的：私有父 POM `ai.neargo:neargo-parent` 只在本机 `~/.m2`，联网解析会失败。
- **改了 `shop-core` 要先 `install`**，否则本地 dev server 验的是 `.m2` 里的旧包。
- **端点还 404 就 `clean`**：`package` 会复用旧 lib，jar 的 mtime 不可信。
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
| 替身太干净 | 测试绿，真实链路是坏的 | 撤掉修复，测试**必须变红**。不变红就是没测到 |
| 前一道闸先拒 | 被测的那道闸根本没跑到 | 场景测试要确认走到了被测的那一行，不是在上一道就返回了 |

`backend/known-failures.txt` 是 2026-08-24 立闸门那天就存在的失败（1126 跑 / 128 红），
**只准变短**。恒红的闸门等于没有闸门 —— 它既是噪声掩体，也盖住了欠账的真实规模。
