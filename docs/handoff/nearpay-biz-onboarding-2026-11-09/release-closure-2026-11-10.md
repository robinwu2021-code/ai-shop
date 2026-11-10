# T01 · B 端进件发布闭包

记录日期：2026-11-10。状态：**两仓已完成隔离源码构建与 fat jar 检查，当前宿主为 §11 的 N1 只读诊断修订；166 未部署，真实环境验收未完成**。本记录补充 [接入 TDD](../../technical/TDD-B端进件接入near-pay.md)。当前默认 live 三仓快照有 3 项其他会话漂移，未吸收进本轮；固定隔离来源与制品检查通过，精确边界见 §11。原交接 README 的“宿主待实现”是 11-09 时点，本记录与 TDD 描述当前工作区。

## 1. 先从 App 场景核对

只读核对独立 Flutter 仓库 `ai-shop-bapp`，未修改该仓库。路径下表省略 `apps/merchant_app/lib/features/`。全部请求仍走 ai-shop origin + 原 SessionManager；商户选择来自 near-pay memberships，不从当前店铺推定支付管理员。

| 场景 | 实际 Flutter 落点 | HTTP（公共前缀 `/biz/pay/v2`） |
|---|---|---|
| 管理入口、支付主体选择 | `payment_onboarding/presentation/payment_management_pages.dart`；`data/biz_onboarding_client.dart` | GET `/memberships` |
| 申请目录与渠道选择 | 同一管理页；client 的 `applications` 分页读取 | GET `/onboardings` |
| 新建蓝新申请、加载当前申请 | `payment_onboarding/presentation/pay_intake_page.dart`；client `create/application` | POST `/onboardings`；GET `/onboardings/{no}` |
| 资料读取与版本化保存 | 同一资料页；client `draft/save` | GET、PUT `/onboardings/{no}/draft` |
| 敏感值封存后仅保存 cipherRef | 同一资料页；client `seal` | POST `/onboardings/{no}/newebpay-materials` |
| 证照上传、列表、私有预览 | 同一资料页；client `upload/files/readFile` | POST、GET `/onboardings/{no}/files`；GET `/onboardings/{no}/files/{fileNo}` |
| 确认交运营、补件后再确认 | 同一资料页；client `confirm` | POST `/onboardings/{no}/draft/confirm` |
| 写结果未决恢复 | 同一资料页；client `outcome` | GET `/onboardings/{no}/draft/commands/{requestNo}` |
| 支付能力与申请交接进度 | 管理页、资料页；client `capabilityProgress/readiness` | GET `/capability-progress`；GET `/onboardings/{no}/readiness` |
| 取消申请 | 后端路由已经列入合同；当前 client 无 cancel 方法，页面无取消动作 | POST `/onboardings/{no}/cancel` |

表中合计 **15 个方法/路径，14 个有现成 client 方法**；不能将后端 15 路由覆盖等同 15 项 App UI 验收。精确路由真源仍为 [routes.json](./routes.json)，不另复制端点合同。版本、requestNo、敏感字段及附件规则沿用交接与 TDD。

App 后续优化/验收清单（本次不修改）：

- B04 已将 `standard_onboarding/onboarding_payments.dart` 的 TW 入口切向 `payment_onboarding/presentation/taiwan_store_payment_page.dart`，不再触发旧 refreshPayments；新页按当前门店读取来源状态、支付商户映射与 near-pay 进度。当前工作区路由切换已有源码落点，实际恢复、范围隔离及真机证据由主任务继续汇总，不能从“资料确认”推导可收款。
- 若产品要求商户取消，补 client/UI 与原命令未知结果恢复；现有取消 HTTP 合同不代表该界面已完成。
- 真实会话、指定来源/门店/成员和真机旅程仍待受控环境验收；不能沿用本地合成身份作为开户证据。
- 交接原列的历史补件展示、英文/阿拉伯语文案与 NWP-01～07 渠道合同缺口继续按原清单管理，不阻塞本地源码整理，也不被本记录宣告完成。

## 2. 两仓最小来源与排除范围

[release-manifest.json](./release-manifest.json) 是唯一逐文件清单：每仓一个 baseline HEAD，按文件列 SHA256、用途；不按整个工作区打包。`production-delta` 与 `migration-delta` 是需叠加到该 HEAD 的最小生产源码增量；HEAD 里的其他依赖源码仍由原仓库提供，不重复罗列。`build-anchor` 固定构建 POM；最新清单不包含测试；旧 `verification-only` 行随历史清单保存。`bapp-reference` 仅记录只读场景依据，不发布 App。

| 仓库 | 基线 HEAD | 生产增量范围 |
|---|---|---|
| ai-shop-codex | `c390e6ac4dbfa8c049e2ad99b5a00fe166d69957` | 45 个生产文件 + V645：前期 41 个生产文件，新增 N1 只读诊断的 4 个文件并更新精确 GET 路由；准确 SHA 见 manifest |
| **独立** near-pay | `54303402078ae166e4e568eca3b0ddf61e1d6f27` | 版本草稿/私有附件/readiness/蓝新封存与交接、成员类型隔离、审批与 Guard 协作、BIZ/OPS controller、独立身份桥、multipart 配置及四个迁移；准确文件数与 SHA 见 manifest |

这不是把 ai-shop 内嵌 `backend/pay` 当独立支付部署。宿主只从自身构建 `shop-app`；支付制品必须从 `/Users/robin/work/ai/near-pay` 构建 `pay-svc`。两仓 pay 类和 Maven 坐标有重名，分别使用隔离的 Maven 缓存，禁止把一仓产物 install 到另一仓缓存，也不在一个 JVM 同装两套 pay 类。独立仓新出现的 `BizOnboardingForwarder.java` 是给宿主的适配器副本，独立进程不需要它；它未列入独立生产增量。

独立支付模块闭包：`pay-svc → pay-domain/pay-guard/pay-portal/pay-channel/secret-core`；domain 还依赖 `secret-api/shop-base/shop-store-mybatis`，portal/guard 复用 `shop-base-auth` 等既有基础依赖。`NewebPayOnboardingPreparation` 和 `NewebPayOnboardingCredentials` 导入 channel 的 `NewebPayOnboardingProtocol`，因此不能漏打 pay-channel。这些只是本轮依赖边界，最终传递版本由已固定 POM 与实际构建依赖记录确定，不另造依赖版本。

以下脏改动明确排除：宿主促销/券核销、储值原型、i18n 及其任务文档；独立仓 `ops-web`（包括 vendor tgz）、API 生成文档/脚本、其他交付记录；整个 Flutter 工作区。不能直接复制两个 dirty workspace 的构建目录或 `git diff` 总补丁。若同一个列入文件继续被其他会话修改，SHA 检查会失败，必须先核对 diff/hunk 并刷新清单；不得把旧 SHA 当当前制品来源。`PayCredentialService` 的环境选择与回执导入同在一个文件，本清单覆盖其当前完整源码，审查时需同时保留其环境隔离语义。

## 3. 实际适用的迁移

依据 TDD 中 166 只读记录：实际运行容器为 `nearpay-prod`、端口 18093、挂载 `/data0/app/nearpay-prod`；支付历史最高 V70，非旧 nearpay-test/18083。此信息是既有只读证据，本次未再次连接远端。宿主已成功 V639～V643；T01 代理本身无新增迁移，本轮 B02 增加 V645 登录票据表。V644/V646 属其他未提交任务，未加入当前制品；本记录不擅自执行、补造或设置 outOfOrder，后续各任务迁移顺序由受控发布计划统一协调。

| 版本 | 本批用途 / 应用条件 | 回退边界 |
|---|---|---|
| V71（基线已有） | PiPay 网络授权上下文；不是进件功能新增。目标仅到 V70，按选定 pay-svc 全量 migration location 升级前必须把 V71 纳入待执行差异评审，不能假设无关而静默跳过 | 不改冻结文件、不从清单擅自删除；是否同批执行由受控迁移计划明确 |
| **V72** | `pay_onboarding_newebpay_credential`；**本批必需**，drafts 启用的 readiness 无条件查此表，即使 credentials 开关为 false | 新表保留，不因关功能删表 |
| **V74** | 草稿、命令摘要、补件历史三表；drafts 主路径必需 | 关入口、保留草稿/幂等历史 |
| **V75** | 私有附件密文与引用元数据表；files 主路径必需 | 保留密文、Vault 密钥和已固定版本引用 |
| **V76** | 成员唯一键增加 subject_type，使 USER/MCH_ACCOUNT 同 ref 不共用行；本批成员隔离必需 | **不可盲目反改旧键**：上线后可能已有同 ref 双类型行，旧键会冲突或恢复错误授权 |
| **宿主 V645** | B02 `usr_login_ticket` 表；随机票据及来源 token 仅存 SHA256，含过期索引与精确来源主体/类型 | 表保留；回退统一身份入口时协调 App，不删除历史会话或批量撤销同主体其他会话 |

当前独立仓没有 V73 文件；版本空号不是补造 SQL 的理由。V70 是 Java migration，核对历史时不能仅扫 `.sql`。V72/74/75/76 来源 SHA 均在 manifest；SHA256 不是 Flyway 自身 checksum，受控升级前仍须以正确 location/history 做 validate 与 pending 清单比对，禁止 repair 或改冻结迁移绕过冲突。当前 pay-svc `spring.flyway.enabled=false`，更换 jar 不会自动执行迁移；需指定支付数据源的既有受控迁移执行者，不能交给宿主缺这些脚本的旧内嵌 pay 包代跑。本地 Launcher 的 SQL 子集和执行时保留字适配不是全量生产迁移证明。

## 4. 默认关闭与启用配置

| 配置 | 缺省 / 本批行为 |
|---|---|
| 宿主 `shop.pay.biz-onboarding.enabled` | false；环境名 `NEARPAY_BIZ_ONBOARDING_ENABLED` |
| 宿主 `shop.pay.biz-onboarding.base-url` | 空；环境名 `NEARPAY_BIZ_ORIGIN`，受控固定 HTTPS origin |
| 宿主商家会话存储 | 启用前必须 DB（全局或 merchant realm）；不能用测试会话启动器 |
| pay `near-pay.biz.ai-shop.enabled/base-url` | enabled 缺省 false；启用时 origin 指向宿主可信 HTTPS。Spring 环境名为 `NEAR_PAY_BIZ_AI_SHOP_ENABLED/BASE_URL`，不要把宿主 `NEARPAY_BIZ_*` 当此桥的自动别名 |
| pay `near-pay.onboarding.drafts.enabled`、`files.enabled` | 缺省不装配；启用各为 true。Spring 环境名 `NEAR_PAY_ONBOARDING_DRAFTS_ENABLED`、`NEAR_PAY_ONBOARDING_FILES_ENABLED` |
| pay `near-pay.onboarding.newebpay.credentials.enabled` | 默认 false；本批不为真实渠道密钥交接而打开；V72 仍必需 |
| pay `shop.secret.enabled/env` 与 Vault | 按既有受控配置提供；不复制本地 SANDBOX 合成主密钥，不覆写现有环境或密钥别名 |
| App `NEARPAY_BIZ_ONBOARDING_ENABLED` | Dart 构建开关默认 false；服务真实环境验收后才打开。域名使用 `MERCHANT_API_BASE_URL`，不在 App 配支付 origin/服务 token |
| B02 `shop.auth.login-ticket.ttl` | 默认 `PT5M`，环境名 `SHOP_LOGIN_TICKET_TTL`；票据签发/到期判断统一使用 DB 时钟。本项是 TTL 配置，**统一身份端点没有新增默认关闭开关** |

**启动装配约束**：独立仓 `StandaloneBizIdentityRequired` 在 `api` profile 下强制要求已启用并安装的 BIZ 回查过滤器，且 `shop.pay.guard.entries` 必须在保留原入口基础上含 BIZ（默认列表不含 BIZ）。因此“新 pay-svc 上 api、BIZ bridge 以后再配”会启动失败。发布新制品时须原子带上 BIZ bridge + Guard 配置；宿主尚未就绪时请求失败关闭，App 和宿主代理仍关。若该进程启用 `ops` profile，继续满足原有 OPS identity bridge、OPS Guard 和方法权限；BIZ 配置不代替 OPS。不能为了通过启动而禁用 Guard 或复用本地测试过滤器。

宿主开启后每次请求实时核验原 Bearer；支付端再回查宿主会话端点。双向网络需允许这两个固定 HTTPS origin，禁止用户输入目标地址。保留宿主 JSON 1 MiB、总 multipart 6 MiB、单文件 5 MiB；支付 Servlet 5/6 MiB，网关允许合法上传但不替代应用限制。敏感正文、Authorization、附件及 Vault 内容不进网关/body 日志。

## 5. 可复核构建、受控发布与回退步骤

下列是交付给后续受控发布者的执行顺序。第 2～4 步的本地构建与制品检查已完成（最新版见 §11，§7～§10 为历史）；**未执行远端操作、迁移、会话签发或部署**。

1. 在私有目录保留原制品 SHA、启动参数/profile、非敏感配置差异和数据库可恢复备份的引用。使用真实 `nearpay-prod` 实例与挂载；旧 nearpay 目录不能作为其当前制品来源。TDD 已记录的旧宿主 SHA 为 `114d4369997a7bdbec847848f1f227fba21cbf81d7503d9f3024c1f4247b3aeb`，支付为 `841cfcafc46cf890d3b6731a2e1a48560b8740fce83f1565f0b76a5923066ca2`；实际部署前需由操作者确认它们仍是当时运行版本。
2. 为两仓分别准备基线 HEAD 的干净构建目录，只叠加 manifest 的 production/migration 增量；测试与真实 HTTP 验证由主任务另行负责，本次运行制品闭包不叠加测试。对每个来源文件验证 SHA；不要 reset/checkout 当前共享脏工作区，也不要 whole-workspace tar。固定 JDK 21、各仓匹配的依赖缓存，记录解析依赖版本与最终 jar SHA，不把 `0.1.0-SNAPSHOT` 字样当内容标识。
3. 统一编译由主任务协调，示意命令为各自干净目录中的 `mvn -o -f backend/pom.xml -pl pay/pay-svc -am package -Dmaven.test.skip=true -Dmaven.repo.local=<独立支付缓存>` 与 `mvn -o -f backend/pom.xml -pl shop-app -am package -Dmaven.test.skip=true -Dmaven.repo.local=<宿主缓存>`。这两个命令仅用于制品构建，相关测试证据仍以 TDD 和最终统一验证为准；不混用 Launcher 的 test classpath 作运行制品。
4. 检查宿主 jar 含两个 BizPay controller 与五个 PayBiz 类，内嵌 pay-domain 含 forwarder；独立 jar 含真实 PayApplication、四个 BIZ identity 类、草稿/文件 controller，嵌套 pay-domain/pay-channel/pay-guard/pay-portal 均匹配本次构建，迁移含 V72/74/75/76。记录两个最终 jar SHA 与依赖 BOM/缓存来源。仅 manifest 的源码 SHA 无法替代这一步。
5. 在目标支付库上核对历史、V71 待执行项、四个新迁移和 Vault/secret 表前置。经备份与受控窗口执行已审定的迁移集，确认 V76 唯一键真实列序为 `tenant_no,merchant_no,source_system,subject_type,subject_ref`。不修改旧 checksum、不删除现存成员或自动补管理员。
6. 按 **near-pay → 宿主 → App** 推进。先发布支付 jar 与匹配的身份桥/Guard/profile/存储配置；再发布宿主 api jar，保持进件开关关闭并核验既有登录、健康及原业务；两端回查/固定 origin/DB 会话准备完成后受控启用代理与资料功能。保留原 OPS 与其他收款渠道配置；本次不启用真实蓝新送件/凭据导入。功能发布影响现有生产支付实例，不能称作隔离预发布。
7. 用已指定且经授权的真实 USER/MCH_ACCOUNT、来源、商户/门店和支付成员完成 TDD 的 INT02/INT03：身份撤销、同 ref 双类型、跨主体拒绝、全部资料附件及版本/幂等/补件旅程、真实故障响应。不得新建或借用其他人的 token 来“先跑通”。readiness 的 channelReady、账户/密钥、产品/门店资格分别核验，不能从草稿确认推导收款。然后才出新 App 构建并启用 Dart 开关，验收真机。
8. 回退先关 App 入口与宿主进件代理，停止新的草稿/上传/运营动作；按原 SHA 恢复两服务制品和对应配置。保留新表和 Vault 密钥，禁止删表清历史。如果已产生 V76 双类型成员或新草稿/回执，旧支付代码的无类型查询不能证明安全，须保持 BIZ 关闭并由受控人员评估回退/前向修复；不得直接恢复旧唯一键。只有完整受控恢复方案可还原数据库，且不得覆盖窗口内其他交易。

本地构建及 jar/BOM SHA 已记录（最新版见 §11）。发布前仍待：迁移执行者与完整 pending 差异、真实账号/来源/成员、网关/Vault配置核验、窗口和受控发布授权。T01 完成的是可复核发布集，不代表这些外部条件已完成。

## 6. 本地记录验证

`python3 scripts/nearpay-delivery-snapshot.py --check` 只检查 manifest 里的 HEAD 与文件 SHA；不扫描全工作区，不读取配置凭据文件，不生成包。刷新必须先审查源文件变化，再执行：

```sh
python3 scripts/nearpay-delivery-snapshot.py --output /private/tmp/nearpay-release-reviewed.json
```

输出必须是不存在的新文件（0600），保留相同明确文件集。审查新旧 diff 后才将新记录替换 manifest；该命令不代表批准新源码。若继续修改业务源码，最终构建前必须刷新并复核 manifest。本地已有 HTTP/Flutter 验证证据沿用 TDD；快照脚本阶段只验证脚本和清单；实际制品构建见 §7，不重复数据库联调。

本次实际验证：manifest 的三仓 HEAD/文件 SHA 检查通过；快照创建→按新快照复核、0600 权限、拒绝覆写既存文件、源摘要不匹配返回失败、拒绝 `../` 越界路径均通过；本文本地链接存在检查通过。第一轮发现 Python `Path.open` 不支持 opener，已改用内置 `open` 并通过上述受影响验证。该脚本验证未执行 Maven、SQL 或远端操作；随后实际制品构建见 §7。

## 7. T01/T04 实际制品闭包（历史记录，已由 §8 修订）

本节保留 B01/B04 修订前的构建证据；当时的宿主 jar、source-manifest、artifact-inventory、build-commands 和构建日志已保存到 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b01-b04`。当前工作源码与共享目录内新 inventory 以 §11 为准。

受主任务明确要求，本地使用全新私有目录 `/private/tmp/nearpay-release-closure-v_9h06pm`（0700），先 `git archive` 固定 baseline 的 **backend** 子树，再逐文件校验 manifest SHA，**仅**叠加 production-delta、migration-delta、build-anchor。未复制脏工作目录、verification-only 源码、未跟踪配置或凭据。保留基线中原有测试源码，但构建显式跳过测试编译/执行；它们不会进入运行制品。两仓源码与 target 均在各自新目录，未更改两仓业务源码或安装依赖。

主任务随后要求将 T04 已完成的品牌管理准入修复 `ShopStoreMemberRoutes.java` 加入同批宿主，生产集由 12 增至 **13** 文件。已核对其 diff 仅新增 10 行方法/叶子路径登记，业务授权仍由 ManagementScopeService 判定；设计依据为 [P1 TDD](../../technical/TDD-P1-经营组织与品牌管理-详细设计.md)。叠加该文件后仅重 package 宿主，独立支付制品不重构建。主任务修正的测试 Launcher 注解也已刷新 verification-only SHA，但未叠加进生产构建目录。

实际源码准备通过 Python `subprocess` 管道执行，下列为相同的完整 archive 命令（目录预先新建；两个基线不变）：

```sh
git -C /Users/robin/work/ai/ai-shop-codex archive --format=tar c390e6ac4dbfa8c049e2ad99b5a00fe166d69957 backend | tar -xf - -C /private/tmp/nearpay-release-closure-v_9h06pm/ai-shop
git -C /Users/robin/work/ai/near-pay archive --format=tar 54303402078ae166e4e568eca3b0ddf61e1d6f27 backend | tar -xf - -C /private/tmp/nearpay-release-closure-v_9h06pm/near-pay
```

随后只对 manifest 中上述三种 role 的文件逐个读取、确认 SHA256 后写入相同相对路径。该阶段来源清单现保留为 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b01-b04/source-manifest.json`，仅对应该阶段历史制品；`initial-source-manifest.json` 仅用于区分 T04 加入前的首轮宿主产物，**不是最终来源**。未叠加其他任务脏文件。

实际构建环境：Homebrew OpenJDK **21.0.12.1**，Apache Maven **3.9.16**，Boot Maven Plugin **4.0.7**。完整命令/工作目录/日志在 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b01-b04/build-commands.json`。宿主工作目录为 `/private/tmp/nearpay-release-closure-v_9h06pm/ai-shop`，实际命令：

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MAVEN_OPTS='-Xmx1200m -XX:ActiveProcessorCount=4' mvn -o -f backend/pom.xml -pl shop-app -am package -Dmaven.test.skip=true -Dgit.sha=c390e6ac4dbfa8c049e2ad99b5a00fe166d69957+reviewed-overlay
```

独立工作目录为 `/private/tmp/nearpay-release-closure-v_9h06pm/near-pay`，实际命令：

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MAVEN_OPTS='-Xmx1200m -XX:ActiveProcessorCount=4' mvn -o -f backend/pom.xml -pl pay/pay-svc -am package -Dmaven.test.skip=true -Dgit.sha=54303402078ae166e4e568eca3b0ddf61e1d6f27+reviewed-overlay -Dmaven.repo.local=/private/tmp/nearpay-alignment-20261110-repository
```

两仓均 `BUILD SUCCESS`；宿主首轮 40 模块用时 52.008 秒，叠加 T04 后定向 package 40 模块 12.350 秒；独立支付 6 模块用时 25.661 秒。日志分别保留为 `history/before-b01-b04/ai-shop-package.log` 与该私有目录的 `near-pay-package.log`（首轮宿主另存 `ai-shop-initial-package.log`）。无缺模块/外部依赖失败，未下载/安装依赖、未修改 POM、未执行 `install`。宿主使用默认 `/Users/robin/.m2/repository`；支付只使用已确认存在的隔离缓存。

| 该阶段制品（历史） | 大小 | SHA256 |
|---|---|---|
| `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b01-b04/shop-app-0.1.0-SNAPSHOT.jar` | 122,912,380 bytes | `bc03a16779bd298d5bb51e8dbab6a1f242646b20bcb33a696eb8fb016f192d1e` |
| `/private/tmp/nearpay-release-closure-v_9h06pm/near-pay/backend/pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar` | 73,210,684 bytes | `3847e2cda0ab26219476ece98160833299caea417283e97fe5174829191dbcfa` |

首轮宿主 SHA `f14b61766eddc76c715333cfb3da8e9fced6cc0f04c95af78b3398018fe767a9` 曾被含 T04 的 `bc03…` 制品替代；两者现均非最新版，当前宿主交付见 §11。

实际 fat jar 检查通过：

- 宿主 **13** 个生产类/资源全部存在，包括两个 controller、五个 PayBiz 类、forwarder、包装/过滤器、默认关闭配置及 ShopStoreMemberRoutes；支付 **39** 个生产类/资源全部存在（35 个生产文件 + 四迁移）。资源内容 SHA 与来源一致；Java 类以全限定路径存在性检查并结合干净编译来源对账，不把 class 字节哈希冒充源码哈希。
- 两个 jar 的 Main-Class 都为 Boot `JarLauncher`；Start-Class 分别为真实 `ShopApplication` 和 `PayApplication`。build-info 中记录 baseline SHA 加 `+reviewed-overlay`，明确不是纯提交构建。
- 支付嵌套 pay-domain 包含 V72/V74/V75/V76 原始 SQL，逐个 SHA 与 manifest 相同；也检查了 V70 Java migration class。未把宿主的旧内嵌迁移包当独立支付迁移证明。
- 两个 fat jar 分别有 **189 / 139** 个嵌套依赖。`artifact-inventory.json` 逐个记录库路径、SHA、大小、可取得的 Maven GAV，以及全部生产文件落点；包括 pay-domain/pay-channel/pay-guard/pay-portal、secret-core/api、shop-base/auth/store。两仓同名 SNAPSHOT 模块的内容 SHA 不同，维持两套独立制品。

完整检查脚本：`/private/tmp/nearpay-release-closure-v_9h06pm/verify-fatjars.py`；该阶段依赖/检查记录：`/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b01-b04/artifact-inventory.json`，SHA256 为 `9f716f5b0add7fb8aedbdcfb7547927f4dee9d82f52a742c0f562b263009ba09`。其中已记录关键运行版本：支付模块/secret/shop 基础模块为 `0.1.0-SNAPSHOT`，neargo common 为 `2.0.0-SNAPSHOT`，Boot **4.0.7**、Flyway **11.14.1**。两缓存继承 POM 均为 `ai.neargo:neargo-build-parent:2.0.0-SNAPSHOT`（SHA `408f2ba13b872d5a0ce1382937a38109695c337cfe9e726e5e7d51c00ee7f0f4`）与 `ai.neargo:neargo-bom:2.0.0-SNAPSHOT`（SHA `30fbab4a5c0a416c2c8b7b35750728bd63715b2fb5ccc61abaa6ec9bab0cfe3d`）。完整依赖内容以私有 inventory 的逐库 SHA 为准。

限制：实际构建跳过测试，定向测试由主任务统一提供；本记录不将 package 成功等同启动、全量迁移、真实登录/开户或渠道验收。未启动这两个 jar、未加载真实配置/数据库、未签发 token、未部署。构建引用现有本地 SNAPSHOT 缓存，库存 SHA 已固定但不能仅凭版本名从别处重建相同内容；临时路径不是持久制品仓库，受控发布前需按既有流程保存批准的两个 jar、来源记录和 inventory，并重新核验 SHA。若生产源码再变化，现有 jar 不再代表新源码，需重新构建受影响仓库。

## 8. B01/B04 制品（历史记录，已由 §9 修订）

本节是 B02 之前的阶段记录；其 jar、来源、inventory、命令与日志保留在 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b02`。本节检查命令为当时执行记录，当前来源与重验命令见 §11。

该轮用户授权本地 B01/B03/B04 前后端优化与联调，**166 明确不部署**。本节只更新实际来源与制品闭包，不替主任务声明测试、联调或真实环境验收全部完成。

本次对原隔离源码目录仅叠加三个经 diff 与 SHA 核对的后台生产文件；宿主 production-delta 从 13 增至 **15**，没有新增迁移：

| 生产文件 | 本次行为 | 当前源码 SHA256 |
|---|---|---|
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMerchantController.java` | profile 增加来自真实 LoginUser 的 subjectKind，保留旧构造兼容 | `39d7798bcf0f3e9e9b37972f8c4dcc6e118d15a9c2152c30e5d1fbb943f1c5ba` |
| `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` | 将 P1 管理资源的精确方法/路径归入 ACCOUNT，保留资源范围授权 | `b1cede072bc07d10481891987ab8c84c91a1fb5293ca847639ba7e27cbb5986b` |
| `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` | 删除旧 management bootstrap 登记，替代 §7 的临时准入方式 | `be7a16c79de64cc819c582120f180c40458d10765288da98c963539876f74e37` |

manifest 的三个生产行已标注 B01/B04 修订。主任务确认 client 的语言/平台/版本头统一改动已稳定后，现有四个 bapp-reference 的摘要已按当前独立 Flutter 工作区最终刷新（其中 client、管理页、旧开店支付页已变化），只作为只读依据；没有复制或构建 Flutter 生产包，也不把这四个文件当本轮 App 全量交付集。

重构建前先保留上版实际 jar、来源清单、artifact-inventory、命令和日志到 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b01-b04`。上一版宿主 SHA `bc03a16779bd298d5bb51e8dbab6a1f242646b20bcb33a696eb8fb016f192d1e` 只属于该历史文件，不能标为当前源码的最终制品。当前来源清单保留在 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b02/source-manifest.json`，当时与仓库 release-manifest 字节一致，现仅对应历史制品（最终清单 SHA256：`beea4a93d16a0625a2872f9fbb77eaddb7dac1005620f1a083c263db60a923cf`）；全部 production/migration/build-anchor 的隔离源码 SHA 再次核对通过，没有从脏工作区全目录复制。

仅在工作目录 `/private/tmp/nearpay-release-closure-v_9h06pm/ai-shop` 执行下面完整命令：

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MAVEN_OPTS='-Xmx1200m -XX:ActiveProcessorCount=4' mvn -o -f backend/pom.xml -pl shop-app -am package -Dmaven.test.skip=true -Dgit.sha=c390e6ac4dbfa8c049e2ad99b5a00fe166d69957+reviewed-overlay
```

结果：**BUILD SUCCESS，40 模块，14.449 秒**。历史日志：`/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b02/ai-shop-package.log`；完整参数与修订说明：同目录 `build-commands.json`。未执行 install、未获取新依赖、未修改 POM。独立 near-pay 源码和配置均未改，未重构建。

| 该阶段制品 | 大小 | SHA256 |
|---|---|---|
| `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b02/shop-app-0.1.0-SNAPSHOT.jar` | 122,912,766 bytes | `53718c85684b69cd60eadcb52f8edd7822ccfc3f4f34097cc640dabf86ffdd2a` |
| `/private/tmp/nearpay-release-closure-v_9h06pm/near-pay/backend/pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar` | 73,210,684 bytes | `3847e2cda0ab26219476ece98160833299caea417283e97fe5174829191dbcfa`（与 §7 相同） |

实际检查命令：

```sh
python3 /private/tmp/nearpay-release-closure-v_9h06pm/verify-b01-b04.py
python3 /Users/robin/work/ai/ai-shop-codex/scripts/nearpay-delivery-snapshot.py --check
```

已通过的制品检查：

- 两 fat jar 的 **15 / 39** 项生产类/资源存在性、资源 SHA、真实启动类与构建标识复核通过；独立支付四个迁移和 V70 Java migration class 保持存在，独立 jar SHA 与上版逐字一致。
- 从该阶段宿主 jar 抽取并以 JDK21 `javap -p -c` 检查：`MerchantProfileVO.subjectKind()` 存在；controller 字节码含 LoginUser.subjectKind 与 SubjectKind.name 的调用；嵌套 `commerce-spring-boot-starter` 中的 CommerceRoutePolicy 含 MANAGEMENT_ACCOUNT、Lane.ACCOUNT 和 P1 精确路径；ShopStoreMemberRoutes 不再含旧 `/biz/management/` bootstrap 常量。每个 class 的 SHA、对应源码 SHA、原始落点、完整 javap 命令和输出路径都记录在新 inventory 的 revisionChecks；这不是执行运行时授权用例。
- 该阶段宿主 **189**、支付 **139** 个嵌套依赖全部重新记录 SHA/GAV。与历史宿主逐库、逐 entry 对比：只有 commerce starter 的 `CommerceRoutePolicy.class` 内容变化；另四个重打的 commerce-api/core/persistence、shop-merchant jar 仅 ZIP 元数据变化，其内部文件内容完全相同。依赖比较明细为 `/private/tmp/nearpay-release-closure-v_9h06pm/nested-delta-b01-b04.json`，也纳入新 inventory。当前 commerce starter jar SHA 为 `54abdd0161efffcc4bc77e1b274dc2761fb5310fb2ff55a9ebffb5b362852626`。

该阶段完整制品记录：`/private/tmp/nearpay-release-closure-v_9h06pm/history/before-b02/artifact-inventory.json`，SHA256 **`0ae58f3ba89dd475da51c4cf8fe4e968de16e3b2d532a4c45d11f74a483ae4d9`**。旧 inventory 未覆盖，仍保存在 history 路径（旧 SHA `9f716f5b0add7fb8aedbdcfb7547927f4dee9d82f52a742c0f562b263009ba09`）。`snapshot --check` 检查的是当前来源，不会把旧快照误判为本轮来源。

构建继续跳过测试，主任务的定向测试和新增恢复联调证据单独汇总；本节不扩大这些证据的范围。现有默认关闭、迁移/Vault/身份桥前置、真实成员/来源准备、批准的持久制品归档与回退边界继续适用。未启动新 jar、未连接远端、未签发 token、未提交或部署。

## 9. B02 统一身份制品（历史记录，已由 §10 修订）

本节为 N3/N4 之前的 B02 阶段记录。该阶段的 jar、source-manifest、inventory、命令与日志现保留在 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n3-n4`；当前来源与制品检查见 §11。

依据 [统一登录 TDD 的 B02 增量](../../technical/TDD-商户App统一登录与账号.md#2026-11-10-b02-增量统一身份发现进入与切换)。本批增加身份发现、进入、本人身份列表/切换；不包含账号中心、凭证变更、注销或其他零售/退款/营销任务。三个新入口为 `POST /biz/auth/identities`、`POST /biz/auth/identities/enter`、`GET /biz/auth/identities/mine`；旧登录保持，profile 保留 subjectKind/旧构造并新增 currentIdentity。

该阶段来源为历史 release-manifest：宿主 **35 个生产文件 + V645 + 3 个构建锚点**，独立支付 **35 个生产文件 + 4 个迁移 + 7 个构建锚点**，另有 4 个只读 Flutter 依据。当前 manifest **没有测试文件**；先前 verification-only 清单随历史记录保留。snapshot 脚本新增 `.properties` 类型支持，三语资源与其他显式源文件一样逐个 SHA 核查，不增加工作区遍历。

必要依赖均已纳入，并保留既有模块边界：

| B02 生产范围 | 所在模块及本轮落点 |
|---|---|
| 凭证只验、进入时才建户 | shop-core 的 AuthService/Impl；新增 VerifiedPrincipal、verify、enterVerified；保留旧 login 行为 |
| 哈希票据、独立事务、单次消费 | shop-core 的 LoginTicketService/Impl；宿主 V645；TTL 默认 PT5M |
| typed 身份组合与来源会话生命周期 | shop-merchant 的 LoginIdentityService 接口；实现放 shop-app/merchantbridge，组合既有用户/商户能力，不让 merchant 反依赖 core |
| 精确会话哈希查询/撤销 | TokenStore 及 Db/Memory/Ehcache/Redis 四个具体实现；shop-auth-store 的 SessionDao 增加 hash 行锁查询，不让路由存储猜 pool |
| 真实入口、profile、范围检查 | BizIdentityController、BizMerchantController、SecurityConfig、BizContextFilter、CommerceRoutePolicy、ShopCommerceEndContextAdapter |
| 配置和错误文案 | application.yml 的 TTL；ErrorCode 的 LOGIN_TICKET_INVALID；中/英/阿三份 messages properties |

最新 LoginIdentityServiceImpl 依据**实际 MERCHANT 池的 `DbTokenStore` 类型**决定撤源时点：DB 源会话与票据、新会话同事务；非 DB 源在 afterCommit 才按原 hash/principal 撤销，回滚补偿仅清新会话。没有按全局配置字符串猜测存储，也不批量踢掉同主体 sibling 会话。

主任务实际 HTTP 联调发现 JVM UTC 与 DB 原时区会让 JVM LocalDateTime 写入的票据立即过期。已等待并叠加最终修复：签发 expiry 使用 SQL `TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP)` 与 ttl 秒数，locked/consume 同样用 DB CURRENT_TIMESTAMP；不改 V645 DDL。旧来源构建在完成前被中止（私有日志 `ai-shop-b02-pre-db-clock-interrupted.log`），**未作为最终制品**；以下结果来自最终 DB 时钟源码。

| 关键最终源文件 | SHA256 |
|---|---|
| `shop-app/.../merchantbridge/LoginIdentityServiceImpl.java` | `a3c222cbe7e85baffc88919b0f4b0af259dce47841e4b6b57f5529e079f72ffe` |
| `shop-core/.../user/service/impl/LoginTicketServiceImpl.java` | `1db57214ab7dd2435b827f0a8899cc4c01d7198e0168fa08f9e833eda95a41b8` |
| `shop-app/src/main/resources/db/migration/V645__merchant_login_ticket.sql` | `36152a5c3db6fc66e85fcb128a153d28dda9ba69b1d752c3364fd8233fa52640` |

其他逐文件摘要以 manifest 为准。最终 manifest/source snapshot SHA256：**`9dc953a008e524247e3796e47cda1086dda1220d5ee04b5f7806785df2e0cc96`**；当时仓库与该阶段 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n3-n4/source-manifest.json` 字节一致。旧 B01/B04 jar、inventory、source-manifest、命令与日志已保留在同目录 `history/before-b02`。

继续使用原独立基线源码目录，只叠加明确生产/migration/build-anchor 文件；未复制其他未提交文件、未使用根工作区 target。未纳入 V644/V646、支付退款、促销/券、结账/人工退款等无关 dirty 文件；构建后也明确检查 jar 不含 V644、V646 或本轮无关 OpsPaymentResolutionController。宿主基线保持 `c390e6ac4dbfa8c049e2ad99b5a00fe166d69957`，独立支付源码/缓存/制品未改。

实际构建工作目录：`/private/tmp/nearpay-release-closure-v_9h06pm/ai-shop`；完整命令：

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MAVEN_OPTS='-Xmx1200m -XX:ActiveProcessorCount=4' mvn -o -f backend/pom.xml -pl shop-app -am package -Dmaven.test.skip=true -Dgit.sha=c390e6ac4dbfa8c049e2ad99b5a00fe166d69957+reviewed-overlay
```

结果：**BUILD SUCCESS，40 模块，49.974 秒**。历史日志 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n3-n4/ai-shop-package.log`；命令和修订说明在同目录 build-commands.json。无模块/外部依赖缺失、无新依赖获取、无 install、无 POM 修改。JDK21/Maven/BOM 及隔离缓存规则沿 §7。

| 该阶段实际制品 | 大小 | SHA256 |
|---|---|---|
| `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n3-n4/shop-app-0.1.0-SNAPSHOT.jar` | 122,950,087 bytes | **`aa10584c0ff3f5c860b99eedc6b61abf805e3b054d9dac209e0ef35813be1b6b`** |
| `/private/tmp/nearpay-release-closure-v_9h06pm/near-pay/backend/pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar` | 73,210,684 bytes | `3847e2cda0ab26219476ece98160833299caea417283e97fe5174829191dbcfa`（保持不变，未重构建） |

实际复核命令：

```sh
python3 /private/tmp/nearpay-release-closure-v_9h06pm/verify-b02.py
python3 /Users/robin/work/ai/ai-shop-codex/scripts/nearpay-delivery-snapshot.py --check
```

复核通过：宿主 **36** 项生产类/资源（35 源 + V645）、支付 **39** 项，配置/三语资源/SQL 原始内容 SHA 与来源匹配；两个真实启动类和 build 标识存在。当前 inventory 保留宿主 **189**、支付 **139** 个嵌套依赖的路径、大小、SHA 与可取得的 GAV/BOM 哈希；不以 SNAPSHOT 名称替代内容身份。

新增 **12 项** B02 `javap -p -c -v` 检查包括 profile currentIdentity、三个身份入口调用、AuthService verify/enterVerified、DB-clock SQL、hash 精确查询/撤销、SessionDao FOR UPDATE、实际 DbTokenStore 判定，以及匿名事务同步类的 afterCommit/afterCompletion。每个 class 字节摘要、对应最终源码摘要、落点、完整指令和私有输出路径均写入历史 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n3-n4/artifact-inventory.json` 的 revisionChecks。该 inventory SHA256：**`2ce9c6ef0172c01365c0875b7d06031a678d81566cf347f7c17c8db612d604a1`**。这些是构建/字节码证据，不冒充运行时事务验收。

部署前还须协调 V645 与其他任务迁移顺序、批准制品持久归档和旧/新 App 登录流；新增统一身份端点没有独立关闭开关，不能把原 near-pay 默认关闭误套到它。回退需协调停止票据入口并恢复批准的旧宿主/App，保留票据表、既有会话和 sibling 语义，不通过删票据表或批量踢用户代替回退方案。

本任务仅构建与制品检查，跳过测试；真实 HTTP 联调及运行时用例结果由主任务更新 TDD。未启动新 jar、未操作 166/其他远端、未创建会话/token、未迁移、未 install、未提交或部署。不声明全部验收完成。

主任务已回传本地双 JVM 真实 HTTP 证据：`/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/bapp-unified-nearpay-8movdnop/result.json`。覆盖凭证验证→OWNER 票据进入→typed DB 会话→宿主代理→独立 near-pay，包含 2 MiB 私有附件回读、取消与原 body/requestNo 重放、mine 切 STAFF 后仅原 source 返回 401 而 sibling 为 200、staff 冷恢复及跨商户拒绝；结果 cleanupComplete=true、remoteAccess=false、vendorAccess=false。该运行时证据由主任务提供，后续仅修正测试的 R01 code/data 断言，生产来源未变。它是本地链路证据，当前 fat jar 未直接启动，166 与真实渠道仍未验收。

## 10. N3/N4 平台资料与审核回读（历史记录，已由 §11 修订）

本节为 N1 只读诊断之前的构建记录。该阶段 jar、source-manifest、inventory、命令和日志保存在 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n1-readonly`；当前来源与制品边界见 §11。

依据 [商户资料 TDD](../../technical/TDD-商户App商户资料提交.md) 的「2026-11-10 N3/N4 增量执行」，采用用户最新确认的职责拆分：**平台资料、平台营业资质与协议在 host；渠道证照、结算账户及敏感材料走独立 near-pay 私有流程**。旧设计中经 host 的 source-links/apply 增加 settlement 的方案不属于本轮。

本轮精确新增 **6 个**生产文件，另更新原有 CommerceRoutePolicy；无新迁移、配置、权限码或依赖：

| 新增文件（完整路径统一以 `backend/` 起始，精确清单见 manifest） | 本轮落点 |
|---|---|
| `shop-merchant/.../merchant/dto/EntityVO.java` | 保留既有字段/构造，追加 review/applyNo/rejectReason/taxNo/owner |
| `shop-core/.../platform/OpsService.java` | 平台提交结果与已授权实体的最新申请批量读接口 |
| `shop-core/.../platform/impl/OpsServiceImpl.java` | 同单 PENDING 修改、相同内容重放、平台申请/协议同事务及批量回读 |
| `shop-app/.../portal/biz/BizEntityController.java` | 已授权实体列表/详情补审核字段 |
| `shop-app/.../portal/biz/BizMerchantOnboardingController.java` | `POST /biz/merchant/onboarding` 仅提交平台资料与营业资质/协议；不接受非空结算账户或渠道列表 |
| `shop-app/.../merchantbridge/EntityReviewAssembler.java` | 授权完成后批量合成审核视图，ACTIVE 证号取正式营业资质 |

既有 CommerceRoutePolicy 新增 **POST `/biz/merchant/onboarding` → foundation `biz:finance`** 精确登记，沿用门店头、头体一致、FoundationTarget 和真实权限范围，不加入宽泛 bootstrap 路由。N3 只处理占位主体；ACTIVE 主体不在此修改已核准资料。原唯一约束、REVIEWING 不可编辑、拒绝后新单及审核激活语义保留。

响应 `nextStep=PRIVATE_CHANNEL_ONBOARDING` 是下一步导航提示。它不创建/确认支付 source、成员授权、渠道账户或凭据，也不证明 source/member 已具备、渠道 ready 或门店可收款；App 仍须独立查询真实来源/映射/成员/渠道进度与门店资格，再进入 near-pay 的私有申请、封存和附件流程。

当前宿主来源为 **41 个生产文件 + 原 V645 + 3 个构建锚点**，无测试文件；支付清单仍为 35 生产 + 4 迁移 + 7 锚点，独立源码/制品未变。manifest 与私有 source-manifest 字节一致，SHA256：**`038a481677a1d8d3e74cc00a48c5837e7328104120bf15b5ee121ccc43f5cc11`**。关键来源摘要：

- OpsServiceImpl：`b5769e2b823683f5c1fcc726a4c9c892c11c29dea4f22570e2503c6cdd41f0c2`。
- BizMerchantOnboardingController：`cefe2a4931cb2b22d65b5f347c21a9f9fa4f74dff4300625ab3db5d6a6770125`。
- EntityReviewAssembler：`544d276ab7fa3cca2de1930353c782e70357e59041746d3e14ad79e62558a517`。
- CommerceRoutePolicy：`82d5494fa8d1a48bc0ec0b8c1c7069c765cfd620e454588ac6f774d76bd550c8`。

逐文件摘要仍只维护 manifest，不再复制整份清单。旧 B02 jar、source-manifest、inventory、命令、日志已保留在 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n3-n4`；旧宿主 SHA `aa10584c0ff3f5c860b99eedc6b61abf805e3b054d9dac209e0ef35813be1b6b` 不能用于本轮最终源码。

构建仍在原隔离源码目录，仅叠加这 6 个经 SHA 核验的文件与路由文件；不共用根 target、不复制其他脏改动。实际工作目录 `/private/tmp/nearpay-release-closure-v_9h06pm/ai-shop`，完整命令：

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MAVEN_OPTS='-Xmx1200m -XX:ActiveProcessorCount=4' mvn -o -f backend/pom.xml -pl shop-app -am package -Dmaven.test.skip=true -Dgit.sha=c390e6ac4dbfa8c049e2ad99b5a00fe166d69957+reviewed-overlay
```

结果：**BUILD SUCCESS，40 模块，25.730 秒**。历史日志：`/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n1-readonly/ai-shop-package.log`；同目录 build-commands.json 记录修订说明。无源码闭包/依赖缺失；未 install、未改 POM、未重构建独立支付。

| 该阶段制品 | 大小 | SHA256 |
|---|---|---|
| `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n1-readonly/shop-app-0.1.0-SNAPSHOT.jar` | 122,968,539 bytes | **`828a867dfe0ee3ea8e4630926dfeeeac6486c3d81765a7630ce174fa94bab16f`** |
| `/private/tmp/nearpay-release-closure-v_9h06pm/near-pay/backend/pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar` | 73,210,684 bytes | `3847e2cda0ab26219476ece98160833299caea417283e97fe5174829191dbcfa`（不变） |

实际复核命令：

```sh
python3 /private/tmp/nearpay-release-closure-v_9h06pm/verify-n3-n4.py
python3 /Users/robin/work/ai/ai-shop-codex/scripts/nearpay-delivery-snapshot.py --check
```

复核通过：当前 **42/39** 项生产类/资源与来源 SHA、启动类、原迁移/配置/三语资源一致；**189/139** 个嵌套依赖 SHA/GAV 重新记录；明确未包含无关 V644/V646 或退款 controller。保留 B02 的 12 项字节码检查，并追加 **8 项** N3/N4 `javap -p -c -v` 检查：新 DTO 审核访问器、批量读取与提交服务、列表/详情 assembler 调用、平台提交 controller 的 foundation/finance/nextStep 标记、Submit 的结算字段 Null 注解与渠道字段，以及精确路由。每项源/class SHA、落点和命令记录在 inventory，字节码检查不替代运行时授权或回滚用例。

历史 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n1-readonly/artifact-inventory.json` SHA256：**`ef1d1e3ca26865525387b7638ef33c32bb624be6b08318f3ab15fa7a856282f1`**。该阶段最终 `snapshot --check` 曾检查通过；最新 live 来源差异见 §11；历史清单保留在 history，不能据旧快照推导当前源码身份。

本任务只负责构建和制品检查；N3/N4 的真实 HTTP、Dart loopback 与 enabled 主数据用例证据由主任务统一汇总。当前 package 跳过测试，未直接启动该 fat jar；不声明全部验收完成。166/其他远端、独立渠道及部署操作均未执行，没有新增迁移/配置执行、会话签发、提交或部署。后续发布/回退仍需按批准制品 SHA、App/API 字段兼容和已有迁移/Vault/真实身份前置执行。

## 11. N1 只读诊断：当前宿主制品与固定支付来源

本轮仅发布 **GET `/biz/store/{storeNo}/entity/relink-check` 的只读诊断能力**；不实现或注册 POST 认领，不开放写门禁。`allowed=false`、`executionReady=false` 是明确合同。缺少完整金融历史、commerce 订阅历史及并发门闩时分别保留 `FINANCIAL_HISTORY_UNKNOWN`、`SUBSCRIPTION_UNKNOWN`、`RELINK_CONCURRENCY_UNAVAILABLE`；支付依赖不可判定保留 PAY_SERVICE_UNKNOWN。即使当前 source 返回 NONE，也不视为历史清洁，不据此允许改挂。

扩展写侧所需新表/恢复合同的范围问题仍待用户回复；本批不将 N1 标为全闭环或可执行认领。`revokedStaff` 只是诊断快照中的人数估算，不触发撤权或后续写入。既有本人/目标所有权、门店头/路径/当前上下文一致及 `biz:store:admin` 权限继续核验。

精确新增四个生产文件：

| 新增生产文件 | 来源 SHA256 |
|---|---|
| `backend/shop-base/src/main/java/ai/neargo/shop/spi/user/StoreRelinkHostFactsPort.java` | `2b8b7485ff93e2f3e4764ec3cf8c37669f052e65013f5eee2643e5e9fe92589a` |
| `backend/shop-core/src/main/java/ai/neargo/shop/platform/port/StoreRelinkHostFactsPortImpl.java` | `4e62ce6f2348471fc8a92500bbfea50b8f756281c642bc421fcb85ef275b3d45` |
| `backend/shop-app/src/main/java/ai/neargo/shop/merchantbridge/StoreRelinkPrecheck.java` | `ca3b7eb2a5eeaaf281464c133cab201c86e7e0a73da519f8f1a4866620992f49` |
| `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoreEntityController.java` | `b6cefc2c7160a4701456d47d68b53e2336a397f9cc319c8c41477d8b0d9e2c50` |

原有 ShopStoreMemberRoutes 仅增加精确 GET 门店路径，当前 SHA `c0a7a6cacc3d85cb2644a2673f076a774ffbc96449db9124d38c00a474c6d37e`。无新 DDL、配置、依赖或测试文件进入发布集；宿主现为 **45 个生产文件 + 原 V645 + 3 个构建锚点**。旧 N3/N4 jar、manifest、inventory、命令/日志已保留到 `/private/tmp/nearpay-release-closure-v_9h06pm/history/before-n1-readonly`，旧 SHA `828a…` 不代表本轮宿主。

仍从原 baseline 的隔离源码目录按明确清单叠加 4 个文件和路由文件，不使用根 target、不复制其他工作区改动。实际工作目录 `/private/tmp/nearpay-release-closure-v_9h06pm/ai-shop`，完整命令：

```sh
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MAVEN_OPTS='-Xmx1200m -XX:ActiveProcessorCount=4' mvn -o -f backend/pom.xml -pl shop-app -am package -Dmaven.test.skip=true -Dgit.sha=c390e6ac4dbfa8c049e2ad99b5a00fe166d69957+reviewed-overlay
```

结果：**BUILD SUCCESS，40 模块，49.689 秒**；日志 `/private/tmp/nearpay-release-closure-v_9h06pm/ai-shop-package.log`，命令说明在同目录 build-commands.json。未缺模块或依赖，未 install、未修改 POM、未重建独立支付。

| 本轮固定制品 | 大小 | SHA256 |
|---|---|---|
| `/private/tmp/nearpay-release-closure-v_9h06pm/ai-shop/backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar` | 122,979,086 bytes | **`fd87aaaac3fc29de1f1b8ac0c0b73dc04f71e253bfa57e0ab7397e43ccc4790c`** |
| `/private/tmp/nearpay-release-closure-v_9h06pm/near-pay/backend/pay/pay-svc/target/pay-svc-0.1.0-SNAPSHOT.jar` | 73,210,684 bytes | `3847e2cda0ab26219476ece98160833299caea417283e97fe5174829191dbcfa`（固定原制品） |

制品和固定来源复核命令：

```sh
python3 /private/tmp/nearpay-release-closure-v_9h06pm/verify-n1-readonly.py
python3 /private/tmp/nearpay-release-closure-v_9h06pm/verify-live-host.py
```

检查通过：宿主 **46**、支付 **39** 项生产类/资源与其隔离源码摘要匹配，迁移/配置/三语资源保持原始内容；当前 **189/139** 个嵌套依赖的 SHA/GAV 已记录。保留 B02/N3/N4 字节码检查，新增 **6 项**只读诊断检查，涵盖四个生产类、Inspection 访问器、精确 GET 路由、UNKNOWN blockers 与 host 历史查询只读注解；新 controller 不含 PostMapping。字节码检查不替代运行时权限/边界验收。

当前 manifest 与私有 source-manifest 字节一致，SHA256：**`d9b67c7254dd10163fe9bb1ea926c1885eda627a595555491f259720566cb60d`**。当前 `/private/tmp/nearpay-release-closure-v_9h06pm/artifact-inventory.json` SHA256：**`cd41523bc0f824fcbd80b5ef73677d249ede8230a46f6107536fc751a845d94d`**；包含逐源/class 摘要、检查命令、依赖与未纳入的 live 漂移说明。

**live 工作区快照与固定发布来源必须区分。** 默认 `python3 scripts/nearpay-delivery-snapshot.py --check` 当前返回非零，明确有三项不匹配：

- 独立 near-pay 的 `OnboardingDraftService.java`。
- 独立 near-pay 的 `onboarding/newebpay/NewebPayOnboardingFields.java`。
- Flutter 的 `payment_onboarding/presentation/pay_intake_page.dart` 参考文件。

主任务确认这三项属于并行会话，均保留原样、不纳入 N1。没有刷新它们在 manifest 中的旧摘要、没有用新 source SHA 冒充旧 pay jar 来源，也没有扩展前端闭包。支付部分以原 `/private/tmp/nearpay-release-closure-v_9h06pm/near-pay` 隔离源码和上表固定 jar 为准；其逐源/制品验证已通过。live 宿主 HEAD/源码单独核对通过，但不将这两个通过结论写成三仓 live 快照整体通过。默认快照实际输出与退出码保存在同目录 `snapshot-live-n1-readonly.json`。

本批构建跳过测试；主任务汇总 core/HTTP/权限及实际 Dart 本地 HTTP 证据，本文不宣告写侧或 N1 全闭环完成。没有修改其他会话的源文件或未提交改动，没有操作远端/166、执行迁移、签发令牌、install、提交或部署。后续若决定吸收上述 live 支付修改，必须另行确定来源和构建新独立制品，不能沿用本表旧 SHA。

主任务最终回传本地运行证据：host core 12 + HTTP 5 + 准入 12 + STORE_V1 1，合计 **30 个不同后端用例通过**；前端 **4 项通过**、静态分析通过；实际 Dart GET 诊断/候选/外部目标/原门店未变化的本地 H2 端到端通过，证据 `/var/folders/bc/zmjk1fl133572z92rysz2_k40000gn/T/bapp-platform-http-17146407855989602522/result.json`。功能矩阵及 TDD/ADR 只关闭只读诊断子集，完整 N1/N2 未关闭；上述验证不将并行会话的三个 live 漂移文件纳入本批来源。
