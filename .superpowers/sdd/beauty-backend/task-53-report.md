# Task53 BM03 模块部署验收报告

状态：实现及本地证据完成，**待控制器独立 Task53 审查和 BM03 全模块审查**。不提前将 Task53 或 BM04～06 标记完成。
基线：`50eeaa5d9`，工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`。

## 交付与架构

新增独立 BM03 构件构建/部署 harness，保留 BM01/02 脚本和历史证据。按顺序复制 embedded、standalone HOST、beauty-server、默认 retail 四个 JAR，另构建既有 job-worker。独立验收前已停止 embedded，HOST 构件有 beauty-web/channel 而无 core，SERVER 有 core/channel 而无 web/shop-app；默认 retail 无 beauty 依赖。实际 JAR 内容存于最终证据目录的 `*-contents.txt`。

HOST、SERVER 预分配不同 localhost 端口，配置 HOST `shop.services.targets.BEAUTY` → source 代理 → SERVER，SERVER `shop.services.targets.PLATFORM` → owner 代理 → HOST。两代理只转发真实 HTTP、记录事实、丢弃指定真实提交后的响应/暂时阻断后续查询，不提供测试业务实现。最终实际 owner HTTP 316 次、source HTTP 19 次。HOST 的资源 owner 使用实际持久实现，反向美业 source 使用 SERVER 的实际来源授权实现；停止 SERVER 后同一真实已绑定未执行命令返回 503，owner 状态仍为 ACCEPTED，不能通过本地替身绕过来源许可。

私有 SERVER 只有一个主 datasource：`beauty_private`；最终 SHOW TABLES 为 23 张业务 bty 表、`bty_flyway_history` 和 6 张基础表（sys_idempotent、sys_outbox、sys_outbox_stream、sys_outbox_delivery、sys_outbox_operation、shedlock）。没有商品、账号、人员、排班、资源 owner、订单等业务副本。基础表复用既有 native fixture DDL，并移除 fixture 的 IF NOT EXISTS；beauty 由正式 V1～V4 和严格 manifest 迁移，history 为 baseline0、1、2、3、4。已应用 V1～V4 无任何修改，没有 clean/repair/漂移掩盖。

平台数据库使用隔离 `schema-test.sql` native fixture（IS JSON 转换为 MySQL JSON_VALID），目录表以正式 V536 替换 fixture 形状。**这不是全量历史平台迁移重放**。job-worker 使用单独 scheduler schema 和自身正式框架迁移。Docker 使用已缓存 mysql:9.7.2、--pull never、tmpfs、127.0.0.1 随机映射端口、TCP readiness；原生专项 runner 使用其既有缓存 MySQL8.4。所有属于本任务的容器/进程均已 finally 清理；未删除其他会话的 food-recipe 容器。

## Job 框架接线

把既有 JobHandlerEndpoint 处理实现抽到 shop-base 的抽象 JobTargetEndpoint；抽象类不注册，shop-app 原类和 beauty-server 新类分别是薄 controller adapter，每个 context 只注册一套既有 JobHttpPaths。继承后的 token 比较、JSON、错误状态和 ShedLock 行为保持，未复制第二份实现。注册表配置在 shop-base，JDBC/DBtime LockProvider 在 shop-store-mybatis；app 保留旧配置类薄兼容 import，SERVER 通过 beauty enabled + job enabled + api 三个条件导入。关闭形态不被新锁配置迫使连接数据库。

新增实际 JobDeclaration：beauty-appointment-recovery，默认 cron `0 * * * * *`，timeout 60 秒，lockAtMost 120 秒，enabled/manualTrigger/logEveryRun 为 true。既有 worker 可持久配置 cron/timeout/lock；本地验收 poll interval=2s，只配置 BEAUTY target，保留既有 start-disabled 默认保护，隔离验收显式 start-disabled=false。不启动 HOST 的其他任务、不新增私有 timer。SERVER 的两个精确 job 路径交给框架独立 X-Job-Token 验证；service token 不能替代 job token，没有放开整个 internal 前缀。

既有恢复扫描每次 LIMIT 50，按 nextRetryAt 到期挑选；只协调已绑定原操作/原终态，需要新用户授权的步骤保持 pending，不构造 LoginUser。真实调度证明：框架发现唯一 BEAUTY 定义；cron 真实调用，job_run 最后 SUCCESS、run_count=3；停止并重启 worker 后持久计数继续增长。`framework-run-records.json` 捕获实际 job_log 的 CRON / SUCCESS / HTTP200 / bm03-module-gate 记录。读取 job_log 的同一断言随后补入 harness 返回数据；最终运行启动时加载的 helper 尚无这个只读附加字段，因此 final/result.json 的 recovery 没有 jobRecords，但同一次数据库的独立只读证据文件已保存并核验，未伪造或补写运行记录。

## 真实 HTTP 业务与认证

初始 SQL 仅建立隔离 merchant/store/goods/SKU/账号认证前提；全部策略、定义、正式 UNRESTRICTED 商品目录、人员、排班、角色及门店授权通过真实 API。经营操作账号与技师业务资源独立。5 个真实账号以实际 BCrypt 独立 salt 注册 PASSWORD 身份，通过实际 B/C 登录获取 token。两名管理者关联真实 USR/mch_account，再由真实 staff/store API 授权/撤权。该前置由控制器批准：现有 phone-only staff 的 PASSWORD 登录路径不等同 PHONE_OTP，本次没有修改既有 Auth，也没有宣称额外 phone-only PASSWORD 流程支持。

embedded 和真正 standalone 均实际证明：

- B 指定技师基础可约、匿名公共可约；C 指定技师预览/创建、B 自动分配预览/创建均 CONFIRMED / operation SUCCEEDED；SQL 只核验实际 owner hold=CONFIRMED。
- C 原键重放同结果且 sys_outbox 数量不变；我的列表/详情；另一 C 用户访问返回业务 10403。
- B 列表/详情；重叠 15 分钟改约成功；先预览后真实占位导致改约 REJECTED，原 startAt 与 assignments 保留；C/B 取消后最终 CANCELLED。
- standalone 动态未来近时预约，等待真实系统时间达到 startAt 后 HTTP no-show 成功，最终 NO_SHOW；没有 SQL 修改预约/hold 时间。startAt<=now 守卫在基线已有，不虚报为本轮新增 RED→GREEN。
- accept 已提交后丢响应，源 receipt=NULL/PENDING；重启 SERVER 后撤销原经理权限，同原键请求 10403；另一现有有权经理原 operationNo recovery 成功。proxy 证明原 accept request.key 相同、owner commandNo 相同。
- CONFIRM 已在 owner 提交，丢响应并暂时阻断查询使源 PENDING；解除阻断并重启 SERVER，由真实框架 cron 查询已绑定终态并完成，无需新用户授权。
- 为来源停止试验先真实 accept 并绑定命令、阻断 execute，再停止 SERVER，重发捕获的真实内部 execute 请求，owner 返回503且未成功。

真实异常恢复原号：lost-accept operation `BTOP261022T0DHKYRYF4`，恢复后 SUCCEEDED；lost-confirm operation `BTOP261022GN1CMSBPV3`，owner CONFIRM `RBC261022WZKQA9WSEA`；来源停止命令 `RBC26102231YXNVXFM8` 仍 ACCEPTED。细节在 result.json、http-results.json、proxy-0.json（owner）、proxy-1.json（source）。归档操作事件存在并去重，但 ARCHIVED 不代表通知已投递。

默认 retail 最终真实启动，美业公共入口404且构件不含美业模块；真实登录及既有资源/目录 API 可用。共享 Job target 的既有 token/锁/返回契约回归与餐饮受影响模块回归见下表。

## TDD、失败和修复

- 声明 RED：`/tmp/task53-declaration-red.log`，BookingServerWiring 缺少 JobDeclaration；添加声明后通过。
- 服务目标 RED：`/tmp/task53-job-red.log` 缺少共用/adapter 类；新增后首次 `/tmp/task53-job-green.log` 实际仍有 H2 fixture error，**该文件名不代表绿色**。补 test-scope H2（不进入运行构件）后通过。
- disabled RED：`/tmp/task53-disabled-red.log`，无条件导入 JDBC 锁配置导致关闭 SERVER 无 DataSource 启动失败；改为三条件 JobTargetConfiguration 后通过且实际 JAR disabled 无库 smoke 通过。
- 安全消融：`/tmp/task53-job-ablation.log` 暂移除精确 job 路径 security 放行，正确 job token 请求从200变401，3测试1失败；恢复源码后最终5测试通过。
- 代理 RED：`/tmp/task53-proxy-red.log` 缺模块；GREEN `task53-proxy-green.log` 真实 upstream 完成后响应断开、原 body/key 重放、恢复转发1测试通过。
- app 单路由新增测试首次误用 getBean(RequestMappingHandlerMapping)，实际 actuator 有第二 mapping bean 导致 fixture 错误；改为遍历所有映射 bean，单路径断言真正覆盖实际 app context，不使用覆盖 bean。
- 部署 run1 的账号 fixture 复用 BCrypt hash 与既有 identity 唯一约束冲突，修为每账号独立 salt；run2 的 phone-only manager PASSWORD fixture 无法解析 USR，按上述批准方案关联真实账号。未改生产认证/预约语义。run3 全链通过后，又用最终关闭形态修复后的 SERVER 构件完整重跑 final 全链通过。

## 命令与结果

以下均从本工作树运行；`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，Maven `-o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2`。不使用受其他任务 SNAPSHOT 污染的全局 Maven repo；先 install 改动共享依赖。

| 命令（Maven 公共选项同上） | 结果 | 日志 |
|---|---|---|
| `bash scripts/build-beauty-bm03-artifacts.sh` | 共享 install、beauty install、四形态和 worker 构建通过；每形态先复制再覆盖 target | `/tmp/task53-artifact-build.log`、`/tmp/beauty-bm03-artifacts/*build.log`、shared.log、beauty.log |
| `mvn -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-server -am package -Dtest=BeautyJobTargetTest,BookingServerWiringTest -Dsurefire.failIfNoSpecifiedTests=false` | 最终 SERVER 5/5；随后复制最终 server.jar、刷新 SHA | `/tmp/task53-job-final.log` |
| `BEAUTY_BM03_EVIDENCE_DIR=/tmp/beauty-bm03-final node scripts/verify-beauty-bm03-deployment.mjs` | BM03_RUNTIME_CASES_PASSED，退出0 | `/tmp/task53-runtime-final.log`、`/tmp/beauty-bm03-final/` |
| `mvn -f backend-plugins/shop-industry-beauty/pom.xml test` | 96测试，0失败/错误/跳过；含协议、架构、装配，不含 gated native IT | `/tmp/task53-beauty-reactor.log` |
| `mvn -f backend/pom.xml -pl shop-app -am test -Dtest=JobHandlerEndpointTest,JobTargetProfileTest,BeautyEmbeddedWiringTest,FoodEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false` | 30发现，27执行通过，3形态条件跳过 | `/tmp/task53-platform-final2.log` |
| `mvn -f backend-plugins/shop-industry-food/pom.xml test` | 104发现，88执行通过，16 native/形态条件跳过 | `/tmp/task53-food-reactor.log` |
| `node scripts/verify-beauty-persistence.mjs --appointment-migrations-only` | 17/17：Schema14 + ResourceIntent3，正式版本/严格manifest/支持升级 | `/tmp/task53-native-migrations.log` |
| `node scripts/verify-beauty-persistence.mjs --appointment-business-only` | 16/16 native业务；外部owner受控fixture，独立于真实部署证明 | `/tmp/task53-native-business.log` |
| `node scripts/verify-resource-booking-persistence.mjs` | 50/50：Persistence17 + Execution33；同技师并发不超卖、跨店bucket、未知保护、持久拒绝/过期 | `/tmp/task53-native-owner.log` |
| `MAVEN_OPTS=-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 node scripts/verify-resource-source-authorization.mjs` | 16/16：过期/未生效/提交前到期回滚/当前身份 | `/tmp/task53-native-source.log` |
| `node scripts/smoke-beauty-server.mjs` | 最终可执行 SERVER disabled 无数据库启动，liveness200/readiness503 | `/tmp/task53-disabled-smoke.log` |
| `node --test scripts/tests/beauty-bm03-proxy.test.mjs` | 1/1 | `/tmp/task53-proxy-green.log` |
| `node scripts/gen-beauty-schema.mjs --check`、`gen-beauty-resource-intent-schema.mjs --check`、`gen-beauty-appointment-decision-schema.mjs --check`、`gen-beauty-appointment-business-schema.mjs --check` | V1～V4均通过，无冻结迁移diff | 工具输出 |
| `python3 scripts/gen-beauty-database-design.py --check` | 原41表设计基线/10输出一致，字段字典追加正式V3/V4 | 工具输出 |
| `node scripts/check-sql-portability.mjs`、`git diff --check` | 通过；无新增方言依赖，known ratchet未改 | 工具输出 |
| SERVER上述target命令改 `test` 再跑 | 整理新Java imports/120列后的5/5 | `/tmp/task53-job-style.log` |

最后一次源码整理仅涉及新 Java imports/空白和测试行宽。对当前 shared source 运行 `mvn -f backend/pom.xml -pl shop-store-mybatis -am compile -DskipTests`（日志 `/tmp/task53-shared-style-compile.log`），用 Java21 `javap -c -p` 对比当前编译与部署 nested class：JobTargetEndpoint、JobTargetConfiguration、JobLockConfiguration 的执行指令和签名完全相同（忽略 source debug 行号），证据 `/tmp/beauty-bm03-final/current-source-bytecode-equivalence.json`。未为纯格式变化覆盖已验证构件。另核验四个业务构件中的 shop-base/shop-store-mybatis nested JAR 二进制哈希完全一致，embedded/SERVER 的 beauty-core nested JAR 完全一致，且均包含本轮新增类/声明；证据 `nested-artifact-consistency.json`。因此不存在只更新 target 而漏进某个形态的生产逻辑变化。未重跑不相关全后端历史大套件；既有全局 Architecture 的 OpsPoints FQN 债未改动，不声称全后端历史基线全绿。

## 最终构件 SHA256

目录 `/tmp/beauty-bm03-artifacts`，与 final/result.json 一致：

| 构件 | SHA256 |
|---|---|
| embedded.jar | d98a765292c0090086436b0931ff25ec170cae81272103d8b0551dc8e52ac907 |
| standalone.jar | 411a2633a05fcc58d355c5e983bb219d7a6fca244054626132b09d42a876e3d2 |
| beauty-server.jar | 7366d97f528dbbe6d78ee501a9fbd0d07a3a9c1fb8561032952e61be333ced7f |
| retail.jar | 8e3a4d0c3da1b812a425bc9ef05d814ddab4ba5f996ff68d8784be68eb4d83fc |
| job-worker.jar | db4a44076b3faa11d8cf14f0a6d6c17a50701d97a3c24869d39045015a5a2846 |

## 文件归属与剩余边界

本提交拥有：

- shop-base/job 的 JobTargetEndpoint、JobTargetConfiguration；shop-store-mybatis 的 JobLockConfiguration/pom；shop-app 原 JobHandlerEndpoint、JobTargetConfig、ShedLockConfig 薄兼容和 JobTargetProfileTest。
- beauty-core BeautyAppointmentConfiguration；beauty-server pom、BeautyInternalSecurityConfiguration、BeautyJobTargetConfiguration、BeautyJobHandlerEndpoint、BeautyJobTargetTest、BookingServerWiringTest。
- scripts/build-beauty-bm03-artifacts.sh、verify-beauty-bm03-deployment.mjs、lib/beauty-bm03-{proxy,runtime}.mjs、tests/beauty-bm03-proxy.test.mjs、fixtures/BeautyPasswordHashes.java、gen-beauty-database-design.py。
- beauty README、功能矩阵、领域对象、数据库结构、生成字段字典、ADR043，以及 task-53-design.md/本报告。

主 TDD 和 progress 属控制器，不包含在本提交。API基线160、原任务基线49不重编号；明确现状为公开原操作恢复+1、领域machine30（框架job路由另计）、私有23（V1=16/V2=2/V3=4/V4=1）。原41表设计资产不重写成当前已建表事实。

范围仍是 BM03 后端基础可约和预约时间线，非 BM06 UI/日历；没有 BM04 到店接待/工单/交易来源、BM05服务作业/履约交付声明；整体 readiness503符合其能力门控。通知仅ARCHIVED；不声称生产通知送达、生产部署、完整历史平台迁移、所有认证grant路径或跨版本任意升级都已证明。持久层并发/保护由专项native测试证明，与实际JAR HTTP证据分开列出。独立审查及其必要修复尚由控制器推进。
