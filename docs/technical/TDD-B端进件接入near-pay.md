# TDD · B 端进件接入 near-pay

状态：已实现并通过双项目本地联调（2026-11-10）；部署环境、B App 和真实渠道待验收

档位：1。依据：[PRD-支付域](./../requirements/PRD-支付域.md) §3 S1，以及 [near-pay 交接 README](../handoff/nearpay-biz-onboarding-2026-11-09/README.md)、会话 OpenAPI、routes.json。实现直接沿用交接的身份回查及有界转发方案；不引入进件存储、成员授权、渠道实现或新依赖版本。

## §0 需求 → 设计

| AC | 验收要求 | 落点 |
|---|---|---|
| A1 | 只认实时有效的 MERCHANT / USER 或 MCH_ACCOUNT / MAIN 会话；撤销过期 401、禁用或禁止身份 403、会话依赖失败 503；不采信身份头 | 独立的受认证 B 端安全链、PayBizSessionVerifier、BizPaySessionController |
| A2 | 只暴露清单中的 15 个方法与路径，限制编号与原始 URI，固定 HTTPS origin（开发可 loopback HTTP） | BizOnboardingForwarder、BizPayOnboardingController |
| A3 | 原始 Bearer、JSON、multipart boundary/body、版本、幂等字段透传；无服务身份、存储、重封装、body 日志 | 专用安全链避开本地 BizContext / 旧支付 Guard；原始流读取 |
| A4 | JSON 1 MiB、multipart 总计 6 MiB、每文件 5 MiB，在入口有界读取并用现有 Tomcat 流式解析器检查文件大小 | PayBizRequestBody、专用 multipart resolver |
| A5 | 原样返回状态/body及适配器允许的响应头；二进制不套信封；连接 3 秒、请求 20 秒、不跟随重定向、不应用级重试；通信失败 502、超时 504 | forwarder、协议异常处理、ApiResponseWrapper |
| A6 | 默认关闭；启用必须配置固定 origin 和 DB 商家会话。未做真实环境验收前不启用 App、不宣称支付渠道上线 | 配置装配、本文运行说明 |

## §1 契约

- `GET /biz/auth/session` 返回 `{code:0,data:{realm:MERCHANT,subjectType:USER或MCH_ACCOUNT,subjectRef,tenantNo:MAIN,status:ACTIVE}}`，不返回角色、个人资料或令牌。
- `/biz/pay/v2` 按交接 routes.json 显式映射；支付成员、商户和门店范围均由 near-pay 判定。X-Merchant-No / X-Store-No 只是支付侧资源选择，不转换为 ai-shop 经营主体。
- 配置 `shop.pay.biz-onboarding.enabled` 默认 false；`shop.pay.biz-onboarding.base-url` 为固定 origin。启用时商家会话存储必须为 db，沿用现有全局或按 realm 配置。无新表、权限码或依赖版本。
- 边界错误沿用 ApiResult 和现有错误码，使用真实 HTTP 状态；near-pay 响应不改写。

## §2 实施计划

### 2026-11-10 双项目本地联调批次

依据用户确认的[优先清单](./ai-shop与独立near-pay后端对齐清单-2026-11-10.md)，本批沿用既有接入契约，在当前两工作区执行：

- [x] 两边未提交文件、补丁和状态备份到私有目录；独立 near-pay 暂存后快进 `54303402`，恢复暂存并逐文件比对原内容一致，保留恢复点。
- [x] 各自匹配的依赖环境编译，验证收款提交及进件合同；不在同一 JVM 混装两套同名 pay 类。
- [x] 新增测试源码启动入口：ai-shop 使用真实会话查询/安全链/代理与 MySQL 9；独立 near-pay 使用 PayApplication、真实草稿/成员/附件/Vault 与独立 MySQL 9 临时库，身份回查指向实际宿主 HTTP。
- [x] 新增可复跑的 `scripts/verify-nearpay-biz-local.py`，准备独立临时库和本次随机 SANDBOX 密钥，分别启动两个 JVM；验证 USER/MCH_ACCOUNT、成员/租户隔离、原命令恢复、版本冲突、大图片、私有读取、确认/运营冻结/补件、实时撤权和故障响应；完成后关进程并删除仅本次临时资源。
- [x] 修复实际联调暴露的边界缺口，集中执行受影响验证，回填结果与清单状态。渠道送件仍不启用；不提交其他任务改动、不部署。

联调补测已复现独立 near-pay 同号双类型授权缺口：USER/SAME 授权存在后，MCH_ACCOUNT/SAME 的首开复用原行，后者 memberships 为空。根因是旧成员唯一键和查询缺 subject_type。独立项目新增 V76（只替换成员复合唯一键），typed 服务/存储/Guard 查询使用真实类型；旧无类型查找遇多行失败关闭。HTTP 复现日志 `/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/ai-shop-nearpay-local-zrhu3gul`。原始 V20/V22 等迁移不修改。

本批两个本地身份/来源主体为合成数据，BIZ 身份核验与双向 HTTP 不使用假会话回查器。运营身份使用测试源码中签发的受认证会话，目录为本地测试目录；schema 为本次进件所需真实 SQL 子集与宿主会话表，不冒充全量迁移或渠道验收。

批次结果：两仓备份 `/Users/robin/.codex/backups/nearpay-alignment-20261110-074109`；独立 near-pay 原修改恢复点 stash `56aa14158a7d316cdafcf02a5b6a110dff7b28db`。独立收款与成员/进件定向测试合计 **32 项不同用例通过**，宿主本批 **14 项通过**，均无失败或跳过；不重复累计重跑。独立项目使用本次克隆缓存 `/private/tmp/nearpay-alignment-20261110-repository`，不覆盖宿主或旧独立缓存。

最终双 JVM / MySQL 9.7.4 联调 **55 项 HTTP 检查通过，覆盖全部 15 条交接路由**。证据：`/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/ai-shop-nearpay-local-zthcsesi/result.json`，同目录保留脱敏日志；临时库、进程和包含令牌的 ready 文件已清理。实际验证：同号双类型分别获得 ADMIN/CASHIER，管理员可停用/恢复另一个类型而不能停用本人；私有 2 MiB 图片读取 SHA 一致且数据库仅存密文；草稿确认→OPS 冻结→补件→换图→再确认；原命令重放/查询、409、取消、实时撤权及 401/403/503。readiness 仍明确渠道未就绪，不推导门店可收款。

本轮修复另一个真实边界：chunked multipart 超过 6 MiB 时，分片关闭会继续读取原流，旧限流实现可能返回零字节并挂住。`PayBizRequestBody` 在超限后每次读取持续抛出 413；新增带超时回归，实际跨服务总量/单文件/JSON 超限均拒绝，未新增附件。限制仍为 1/6/5 MiB。

历史 V22 的敏感列在 MySQL 9 为保留字，测试启动器仅执行时加引用，冻结 SQL 未改。本批实际执行新增 V76 并核验唯一键列，同类型重复插入由 MySQL 1062 拒绝。旧无类型 internal/POS 合同若同时命中两种账号会失败关闭，不宣称该合同已经支持同号双类型。

复跑（JDK 21、本机 MySQL 9.7，私有配置默认 `~/.config/mysql9/client.cnf`；匹配独立项目的依赖缓存须预先准备）：

```sh
python3 scripts/verify-nearpay-biz-local.py \
  --near-pay-repository /private/tmp/nearpay-alignment-20261110-repository
```

默认先集中编译并验证本批相关用例，再分别启动测试源码入口 `PayBizAiShopLocalLauncher` 与独立项目 `BizOnboardingAiShopLocalLauncher`。只有当前源码已编译时才用 `--skip-build`。入口须显式打开本地测试标记，仅接受 loopback 的 `*_align_*` 临时库；没有生产 profile、固定测试密钥或真实渠道发送。

定向日志：`/tmp/nearpay-synced-receipts-tests-20261110.log`（收款等 11 项）、`/tmp/nearpay-typed-membership-final-20261110.log`（成员等 22 项，与前者重合 1 项）、`/tmp/ai-shop-bounded-upload-final-20261110.log`（宿主 14 项）。这是本批局部验证；下文保留上一轮宿主全接入验证记录。

按 writing-plans 技能组织，在当前工作区原生执行；遵循 Java规范§八，完成整批编码后集中验证，不因文档或提交重复验证。

- [x] 身份：新增 `shop-app/ai.neargo.shop.payclient.PayBizSessionVerifier`，单条 JDBC 查询实时会话与对应类型身份的状态/租户，建立标准 LoginUser；`PayBizAuthenticationFilter` 写入现有 SecurityContext。新增 `portal.biz.BizPaySessionController`。
- [x] 协议：在现有 `pay-domain/ai.neargo.shop.pay.client` 引入交接同名 `BizOnboardingForwarder`，不修改外部 near-pay 仓库或发布坐标。新增 `payclient.PayBizRequestBody`、`PayBizException`、`PayBizExceptionHandler` 与 `portal.biz.BizPayOnboardingController`。
- [x] 装配：`config.PayBizOnboardingConfiguration` 只在 api profile 且开关启用时装配专用安全链与保持原始 multipart 的 resolver；修改 application.yml 与 ApiResponseWrapper；登记端点授权决定。媒体 direct 过滤器跳过代理路径，确保入口限制之前没有全文读取和报文重写。
- [x] 验证：新增会话数据库测试、安全链/HTTP 转发集成测试、入口限制测试；验证未登录、错误类型、即时撤销/禁用、依赖失败、身份头伪造、路由白名单、原始 multipart、二进制、重定向、上游错误、开关关闭及配置缺失。运行相关授权与响应包装守卫。

### INT01–INT04 执行续批（2026-11-10）

使用现有工作树执行用户批准的全量计划，保留三仓原未提交内容；本批先增加联调工具与测试，不改已确认进件合同。App当前由其他会话使用 `codex/ui-review-p2-fixes`（HEAD仍为 `b79e7a4`），本批只新增独立测试，不切换该分支、不覆盖业务页。

- INT01：新增 `scripts/nearpay-delivery-snapshot.py`，记录三仓实际分支/HEAD、未提交路径哈希与迁移哈希，不输出凭据内容；快照 `/tmp/nearpay-delivery-20261110-first-batch-final.json`（0600）。这只是工作树清单，不冒充构建制品/完整迁移验收；审查后补记录暂存区/index blob，临时Git仓复现“工作树等于HEAD但暂存区有修改”并验证不遗漏；两仓原备份和stash恢复点保留。
- INT02：`verify-nearpay-biz-local.py` 新增可选 `--app-root`，在两个真实JVM运行期间调用Flutter HTTP验收。新增App测试 `test/integration/nearpay_aishop_runtime_test.dart` 从当前运行的私有ready文件取得随机有效会话，不再硬编码固定令牌；只接受loopback，令牌不进命令行或结果JSON，最后清理ready文件。
- 实际结果：原55项HTTP检查全部通过，新增1项实际Dart HTTP全旅程通过：读取支付成员→创建申请→封存→保存→2MiB附件及幂等重放→私有读取→确认→原命令查询→readiness仍未就绪→退出拒绝请求。日志与结果 `/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/ai-shop-nearpay-local-vgeatstp/`，`result.json.flutterHttpVerified=true`；临时进程/库已清理。
- INT02的真机实际登录、INT03的真实来源/门店/成员准备仍待明确受控环境；当前验证继续使用合成身份和测试源码Launcher，不将该结果写作真实开户或正式部署验收。
- INT04已核对当前台湾页面仍可能展示旧进件状态，业务页当前有其他会话未提交变动；后续以near-pay进度/资格真源收口，不能仅凭新增测试宣告完成。

复跑（当前服务源码已编译时）：

```sh
python3 scripts/verify-nearpay-biz-local.py \
  --near-pay-repository /private/tmp/nearpay-alignment-20261110-repository \
  --app-root /Users/robin/work/ai/ai-shop-bapp --skip-build
```

### 166 只读核查（2026-11-10，用户明确授权）

通过既有UME跳板核查192.168.3.166，未修改配置、授权、数据库或容器。数据库查询使用 `SET SESSION TRANSACTION READ ONLY` 和只读事务；临时SSH转发结束已关闭，未发验证码、未登录签发会话、未提交申请或付款。

| 核查项 | 实际结果 | 对执行计划的影响 |
|---|---|---|
| ai-shop API/OPS | `ai-shop-api`/`ai-shop-ops`运行；18082/18081健康200/UP；api/ops分别装配，`SHOP_PAY_DEPLOYMENT=standalone` | 服务存活，不代表进件接入已发布 |
| 宿主会话 | 实际 `SHOP_TOKEN_STORE=db`，主库MySQL9.7.4，`mch_session`存在 | 会话数据库前置具备；未读取或复用现有令牌 |
| 宿主制品 | 挂载 `/data0/app/ai-shop/shop-app.jar`；无 `BizPayOnboardingController`/`BizPaySessionController`；未发现新进件开关环境变量 | 本地进件增量尚未装入此制品，不能仅开App开关联调 |
| 宿主迁移 | 最近成功V639～V643（含V641业务单、V642角色、V643子行业） | 不再把这些宿主迁移列成166未执行 |
| 支付实例 | 实际容器为 `nearpay-prod`，挂载 `/data0/app/nearpay-prod`，服务端口18093；旧文档的nearpay-test/18083不适用 | 后续必须按真实实例规划，不能凭“166预发布”假设没有生产渠道或真实数据 |
| 支付制品 | 实际pay-svc及嵌套pay-*模块无本批身份桥/草稿Controller及V72/V74/V75/V76 | 不使用旧 `/data0/app/nearpay/pay-svc.jar` 判断当前运行版本；需先整理独立项目完整制品闭包 |
| 支付迁移 | MySQL9.7.4，最近成功V66～V70，最高V70 | 本地V71及进件/成员迁移须逐项核对发布集，不跳过或覆盖历史checksum |
| 进件存储 | 有旧 `pay_onboarding/material/version` 和 `pay_merchant_profile_revision`；未见本批 `pay_onboarding_draft`、`pay_onboarding_file` | 旧申请表存在不代表版本草稿/私有附件已迁移 |
| 成员 | 仅聚合读取：MCH_ACCOUNT/ACTIVE 1条；现有唯一键为tenant_no,merchant_no,source_system,subject_ref | V76未执行，USER/MCH_ACCOUNT同号隔离尚不具备；未核实该现存成员是否属于拟验收账号，不新增或变更它 |
| 配置 | 当前运行配置未见BIZ身份桥及草稿/附件开关 | 需要near-pay可信ai-shop origin、Vault及BIZ/OPS完整装配核验；不复制本地合成密钥 |

制品身份以实际SHA256记录，不猜测Git提交：宿主 `114d4369997a7bdbec847848f1f227fba21cbf81d7503d9f3024c1f4247b3aeb`；独立支付 `841cfcafc46cf890d3b6731a2e1a48560b8740fce83f1565f0b76a5923066ca2`。宿主 `/actuator/info` 的gitSha为unknown，构建时间为2026-11-09T14:14:26.005Z；支付18093的actuator请求受HTTP错误响应，未据此判定服务健康或故障。

下一步：INT01先完成两仓进件发布集/迁移集/配置与回退方案，再按“near-pay→宿主→App”顺序安排受控部署；本次只有只读授权，未部署。部署后才能执行INT02真实会话/真机与INT03指定来源、门店和成员的验收。台湾页面数据源收口等本地开发可独立继续，原NWP渠道依赖仍保留。

## §5 验证记录

完整定向验证（JDK 21，当前 reactor 40 模块编译）：

```sh
mvn -o -f backend/pom.xml -pl shop-app -am test \
  -Dtest=PayBizSessionVerifierTest,PayBizRequestBodyTest,PayBizOnboardingHttpTest,PayBizNativeHttpTest,BizEndpointPermTest,MediaHostRewriteFilterTest,ApiResponseWrapperTest,ApiResourceResponseTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.heap=1200m
```

结果：`BUILD SUCCESS`，47 项通过、0 失败、0 跳过。宿主本机 MySQL 9.7.4 位于 127.0.0.1:3308；原生测试创建 UUID 临时库，结束删除，仅使用合成身份及本地替身上游。命令日志：`/tmp/ai-shop-nearpay-biz-tests.log`。复现原生用例需设置 `NEARPAY_HOST_MYSQL_URL=jdbc:mysql://127.0.0.1:3308/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai` 及私有环境中的 `NEARPAY_HOST_MYSQL_USER`、`NEARPAY_HOST_MYSQL_PASSWORD`，不将凭据写入仓库。未设置原生环境时该用例显式跳过。

| AC | 实际证据 |
|---|---|
| A1 | `PayBizSessionVerifierTest` 4 项：同 ref 的 USR/MCH、撤销、过期、删除、禁用、跨租户、错误类型、依赖失败；`PayBizOnboardingHttpTest` 的身份头伪造及每请求核验；`PayBizNativeHttpTest` 经实际 HTTP 验证 USER/MCH_ACCOUNT 与 401/403/503 |
| A2 | `allHandoffRoutesReachUpstreamWithoutLocalStoreAdmission` 按 routes.json 遍历全部 15 个方法/路径；路由/编号/origin 拒绝测试；`BizEndpointPermTest` 12 项 |
| A3、A4 | body 测试 3 项涵盖恰好 1/5 MiB、超限、未知长度、格式错误；HTTP 测试验证 2 MiB multipart 原始字节、身份/选择头、版本和 requestNo。原生测试通过 chunked 上传验证真实 Servlet 原始流，超 5 MiB 的文件得到 413 且不进上游 |
| A5 | 上游 302/303/401/403/409/422/503 状态及 body 原样返回；允许的缓存/安全头、私有字节无包装，Cookie/Location 不转发；502、模拟请求超时 504、中断恢复标志及写调用次数；包装回归 9 项；媒体过滤回归 8 项 |
| A6 | 开关关闭不装配会话/代理；启用但非 DB 会话启动失败；非法 origin 构造失败 |

首轮编译暴露 Tomcat `FileUploadException` 继承 IOException；首轮运行暴露其流式解析器必须使用 UploadContext。已按实际 API 修正，不改库、不吞异常。只读审查发现 direct 媒体过滤器在入口限制前全文读取并重写 JSON，已排除代理路径并新增“不允许提前读取”的回归。未重复回退正确修复。

最终显式方法映射与端点发布面验证：`PayBizOnboardingHttpTest,BizEndpointPermTest,ControllerProfileTest`，24 项通过，`BUILD SUCCESS`，日志 `/tmp/ai-shop-nearpay-final-routes.log`。合并不同测试类的有效证据，共 49 项通过，不重复累计重跑数量。`python3 scripts/check-app-backend-orphan.py --check` 已通过；Flutter 调用不在 uni-app 扫描范围，按既有规范在 known-app-backend-orphan.txt 登记。`git diff --check` 及本文本地链接检查通过。未运行无关全量回归。

## §6 交付与运行边界

实际修改与 §2 一致，另有 `shop-channel/.../MediaHostRewriteFilter.java` 及其测试（确保 raw relay 不被容器级媒体过滤改写）、授权登记 `BizEndpointPermTest`、Flutter 出口登记 `known-app-backend-orphan.txt`、本 TDD 与技术索引。交接包以原样快照纳入 `docs/handoff`，其“尚未接入”描述是交接时点，不覆盖本文实施状态。宿主未更改旧表、迁移、其他登录链或支付权限；本批独立 near-pay 增量为成员账号类型隔离及 V76，未修改 B App。

部署前配置宿主（api profile）：

```yaml
shop:
  auth:
    token-store-by-realm:
      merchant: db
  pay:
    biz-onboarding:
      enabled: true
      base-url: https://受控的-near-pay-origin
```

对应代理开关环境变量为 `NEARPAY_BIZ_ONBOARDING_ENABLED`、`NEARPAY_BIZ_ORIGIN`；不配置用户可控 origin 或服务令牌。near-pay 的 ai-shop base-url 应指向本服务受控 HTTPS origin。其余 near-pay 身份桥、源主体/成员、Vault、上传/资料开关沿用交接 README；网关须允许合法 6 MiB multipart 通过，宿主仍逐请求执行 1/6/5 MiB 限制。

适配器保持交接的无应用级重试策略，并强制 HTTP/1.1；写请求超时后由 B App 保留原命令并查询结果，宿主不生成幂等键、不重发。不能将进件确认推导为可收款。

生产配置、真实成员/源主体、near-pay Vault/MySQL迁移、网关及蓝新测试环境不由本次代码修改自动完成；本次没有部署或开启 App，真实环境清单沿用交接 README。

### P0 发布闭包与测试隔离（2026-11-10）

已按独立bapp场景→15路由→迁移/身份桥→实际两仓源码整理[发布闭包](../handoff/nearpay-biz-onboarding-2026-11-09/release-closure-2026-11-10.md)与逐文件SHA清单，构建来源、制品SHA/依赖、默认关闭配置、V72/V74/V75/V76及受控回退步骤以该记录为准。前端当前有14个client方法，cancel尚无App动作；优化清单见[功能对齐§7](./注册登录到进件-功能与代码对齐-2026-11-10.md)。未部署，不变更166只读边界。

新增P0全应用测试发现test classpath里的PayBizAiShopLocalLauncher.LocalDatabase被扫描，缺host-test-db-url导致原测试无法启动；改为@TestConfiguration，LocalOperatorIdentity加@TestComponent。Launcher仍显式注册两者，生产源码不受影响。同批管理组织/准入测试22项正常加载并通过，本地实际登录和授权MySQL9测试11项通过；未重复运行既有55+1进件HTTP旅程，不据此新增进件渠道验收结论。

### B03台湾状态及本轮实际联调（2026-11-10）

bapp台湾首页和两处收款详情已隔离旧merchant/payment资格，改读平台entity、pay-service来源、typed memberships、申请及readiness；缺少绑定证明保持未确认。7项状态/页面定向通过；本轮55项HTTP与1项实际Dart双JVM旅程通过，证据`/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/ai-shop-nearpay-local-b8sdr72a/result.json`。两服务仍使用本地隔离库/会话夹具及渠道桩，不推导真实开户、绑定或可收款。另行本地真实凭证登录/连锁联调见功能对齐§8。166不部署。
