# 运营端密钥管理与 API

> 状态：管理员签发、员工自助申请、运营页面和 macOS 客户端已实现，待部署验证 · 档位 1 · 2026-11-24

## §0 需求与验收

| 验收 | 原始设计 | 落点 |
|---|---|---|
| 管理员为指定运营员工签发、查询、撤销有期限的凭据 | [运营端登录安全基线](./TDD-安全基线P0-运营登录与真实IP.md) · [运营端规范](./design/规范-运营端.md) | 员工密钥接口、哈希存储、审计 |
| 员工从运营页面自助申请、查看和撤销本人密钥，本机存储后调用 API | 本轮确认的无审批自助分发 | 运营端账号抽屉、自助接口、系统凭据库客户端 |
| 密钥请求按当前员工角色、数据域及密钥准入规则执行 | [门店与商品治理](../requirements/运营端门店与商品治理-需求.md) · [运营端门店与商品治理 TDD](./design/TDD-运营端门店与商品治理.md) | 运营安全链、员工身份加载器、既有业务端点 |
| 运营人员及自动化按模块和角色发现可用操作 | [统一销售渠道接入](../requirements/PRD-统一销售渠道接入.md) · [行业化扩展](./TDD-行业化扩展-餐饮与服务业.md) | 代码生成的凭据端点清单、调用指南及 JSON 索引 |

## §1 凭据方案

运营 API 调用使用 `Authorization: Bearer oak_…`。在每次请求上校验密钥及员工状态，从原员工加载器取得角色和数据域。凭据分为 `READ_ONLY` 和 `MANAGE`，可调用的操作来自后端按 HTTP 方法与实际 MVC 路由模板维护的清单。各端点继续执行自己的权限码及资源范围判断。门店及商户查询复用既有数据域。全平台的字典、费率、履约汇总和原实现跨域的处置操作均要求员工持有全量数据域。运营浏览器登录和邮箱二次验证继续用于交互式操作及密钥管理。沿 [ADR-027](./ADR/ADR-027-自动化测试用密钥票据登录.md) 的环境与用途隔离，既有测试自动化继续使用 Ed25519 票据。生产运营使用员工绑定的可撤销凭据，每次请求从共享库确认其状态。

密钥有效期按签发日起算，最多 90 天。管理员按员工和用途签发独立密钥，用受控的密码库共享给本人，明文只在签发响应中出现一次。轮换时签发新密钥、验证调用、撤销旧密钥。账号停用和正式改密或重置密码会撤销该员工此前签发的凭据。

对比方案包括将现有自动化票据扩为按员工登记的公钥交换、直接使用运营会话和独立员工密钥。票据交换需要签名客户端、共享防重放及交换后的凭据撤销关联。普通运营会话适合短时交互，自动化还需要独立的生命周期和路由权限。首批采用独立员工密钥并按请求校验，客户端只需发送标准 Bearer 头。

### 员工自助分发与本地密钥

员工在运营页面用既有运营账号登录。本机客户端生成凭据，员工在页面提交申请，即申即用。服务端以已登录的 `staffNo` 作为凭据属主，员工需处于启用状态并完成初始改密。申请过程无审批步骤。

| 步骤 | 员工或本机客户端 | 服务端 |
|---|---|---|
| 1. 登录 | 在运营页面沿原密码和邮箱验证码流程登录，打开「API 密钥」 | 按现行规则建立运营交互会话 |
| 2. 生成并保存 | 为本次申请生成 UUID `keyNo`。用系统安全随机源生成 32 字节，按现有格式编码成 `oak_` 加无填充的 Base64URL。将完整密钥写入操作系统凭据库后，输出可粘贴到运营页面的申请信息 | 此时无服务端记录。保存失败时结束本次申请 |
| 3. 自助申请 | 在运营页面粘贴申请信息、填写用途并提交 `keyNo`、用途 `label`、`access`、`validDays` 和完整密钥的 SHA-256 十六进制摘要 `secretHash` | 从运营会话取员工号，复用密钥表保存摘要和元数据。成功即生效，返回元数据。只读为默认；需要管理时显式申请 `MANAGE`，实际写权限仍逐请求判定 |
| 4. 使用 | 按运营环境和凭据编号从本地凭据库取得真实密钥，由 HTTP 客户端加上 `Authorization: Bearer <密钥>`。先调用 `GET /ops/auth/me` 验证身份，再调用业务 API | 复用现有 `OpsApiKeyAuthFilter`、路由准入、实时 RBAC 和数据域，摘要本身不能作为 Bearer 使用 |
| 5. 换机、轮换或回收 | 换机时在新机器另行生成并申请。轮换时验证新密钥后撤销旧密钥；丢失设备时从其他受控设备登录并撤销 | 撤销员工账号下指定 `keyNo`。账号停用、正式改密和重置密码沿原事务撤销旧密钥 |

**自助接口。** 这三个接口都使用运营交互会话，从当前身份识别属主。业务密钥只用于准入清单中的业务接口。

| 方法及路径 | 用途 | 合同 |
|---|---|---|
| `POST /ops/staffs/me/api-keys` | 自助申请并绑定 | `keyNo` 为 UUID，`label` 最长 80 字符，`access` 默认 `READ_ONLY`，`validDays` 为 1–90 天，`secretHash` 为 64 位小写十六进制。响应返回密钥元数据及服务端计算的状态 |
| `GET /ops/staffs/me/api-keys` | 查看当前员工的密钥 | 返回本人密钥编号、用途、级别、签发时间、到期时间、撤销时间及状态 |
| `POST /ops/staffs/me/api-keys/{keyNo}/revoke` | 撤销本人密钥 | 按当前员工号和密钥编号更新，重复撤销返回成功 |

首版本机客户端使用 macOS Keychain。后续 Windows 和 Linux 可沿用同一 HTTP 契约接入系统凭据库。以运营环境、`staffNo` 和 `keyNo` 定位条目。普通配置只放服务地址、员工号和凭据编号。客户端仅向指定的运营 HTTPS 源附加凭据，本机开发允许 loopback HTTP。客户端拒绝跟随跳转。AI 工具由本机客户端注入授权头，模型只看操作参数和必要结果。

同一环境和用途可按设备独立持有密钥。用途标签可以包含设备名称，便于识别和回收。申请超时后，客户端继续使用已保存的密钥和原 UUID 重试。服务端同员工、同 UUID、同摘要及申请参数返回原元数据；相同 UUID 换摘要或参数返回冲突。过期或已撤销的记录按原状态返回，轮换时生成新 UUID。收到明确的注册拒绝后，可在本机清理未注册条目。结果未知时保留条目，页面按原申请参数重试或刷新列表查证。

### 表结构与状态

沿用 MySQL 9 迁移 [V701__ops_staff_api_key.sql](../../backend/shop-app/src/main/resources/db/migration/V701__ops_staff_api_key.sql) 的 `sys_ops_api_key`。自助申请复用当前表结构，审批状态、申请队列和设备信息均无需持久化。历史迁移保持原有 checksum。 [V702](../../backend/shop-app/src/main/resources/db/migration/V702__align_ops_api_key_staff_collation.sql) 将 `staff_no` 的排序规则与员工表对齐，确保 MySQL 可按员工号完成认证查询。

| 字段 | 类型与约束 | 自助申请的写入及用途 |
|---|---|---|
| `key_no` | `CHAR(36) NOT NULL`，主键 | 本机 UUID，绑定和轮换的唯一标识 |
| `staff_no` | `VARCHAR(64) NOT NULL` | 从当前会话取得。逻辑关联 `sys_ops_staff.staff_no` |
| `secret_hash` | `CHAR(64) NOT NULL`，唯一索引 `uk_ops_api_key_hash` | 完整 `oak_` 密钥的 SHA-256 小写十六进制摘要，用于查找凭据 |
| `label` | `VARCHAR(80) NOT NULL` | 员工填写的用途，可包含设备名 |
| `access_level` | `VARCHAR(16) NOT NULL` | `READ_ONLY` 或 `MANAGE` |
| `issued_by` | `VARCHAR(64) NOT NULL` | 自助时记录本人；管理员代签时记录签发人 |
| `created_at` | `BIGINT NOT NULL` | 服务端首次绑定的 Unix 毫秒 |
| `expires_at` | `BIGINT NOT NULL` | 签发时间加申请有效天数。相同 UUID 重试沿首次时间 |
| `revoked_at` | `BIGINT NULL` | 首次撤销的 Unix 毫秒 |
| 查询索引 | `idx_ops_api_key_staff(staff_no, created_at)` | 本人列表、员工名下凭据查找 |

服务端按当前时间计算自助响应的 `status`，供页面直接展示。已填 `revoked_at` 显示「已撤销」，其次在 `expires_at <= now` 时显示「已到期」，其余显示「有效」。撤销或到期的记录可查。申请重试通过 `expires_at - created_at` 核对原有效期，由归属、摘要、规范化用途和级别共同判定是否为同一次申请。

### 运营页面交互

入口放在运营端顶栏账号区，与现有「修改密码」同组。复用账号抽屉的布局、会话和三语文案机制。抽屉下的「申请 API 密钥」面向已登录员工；初始密码待修改时提示完成改密，服务端仍校验账号状态。

| 页面环节 | 行为 |
|---|---|
| 我的 API 密钥 | 进入时查询本人列表，列出用途、密钥编号、访问级别、创建时间、到期时间和状态。分别显示加载、空列表和查询失败，失败时提供重试 |
| 申请表单 | 用途必填，最长 80 字符。默认只读，有效期默认 7 天且可设为 1–90 天。选择管理时提示其可调用已授权的管理操作 |
| 本机交接 | 页面提供匹配当前员工号和 API 源的本机命令。客户端先写入系统凭据库，成功后输出申请信息，包含环境、当前员工号、`keyNo` 和 `secretHash`。页面允许一次粘贴，解析环境及员工号用于防误操作，只发送注册合同字段 |
| 提交与核验 | 正在提交时锁定按钮。成功后关闭表单、刷新列表并提示在本机调用 `GET /ops/auth/me` 核验。运营页面处理申请信息及元数据，真实密钥由本机客户端持有 |
| 失败与重试 | 超时保留当前申请参数并允许原参数重试。登录到期提示重新登录；重复 UUID 与参数不符时提示检查本机申请信息；环境或账号不一致时提示重新生成对应申请信息 |
| 撤销和轮换 | 列表行按编号撤销，确认提示影响该设备或脚本。操作成功后刷新列表。轮换由员工先申请新凭据并在本机验证，再撤销旧编号 |

申请信息只在表单内存和当前 HTTPS 注册请求中使用，埋点、URL、浏览器持久化和业务日志只允许记录必要元数据。本人管理接口凭运营交互会话访问。路由层显式登记静态 `/me` 契约，避免与管理员 `/{staffNo}` 路由混用。签发和撤销均沿原审计记录员工号及凭据编号。业务调用沿运营认证过滤器和路由准入检查，沿用方法级权限与数据域。

## §2 实施与幂等落点

| 顺序 | 层 | 开发任务与交付 |
|---|---|---|
| 1 | 后端与数据 | 在 `OpsApiKeyService` 和 `OpsApiKeyStore` 增加同人注册及原记录核对。Controller 增加 3 个 `/me` 操作，从会话取得属主，复用审计。先用 DTO 约束契约，再补 HTTP 测试与 MySQL 9 迁移验证 |
| 2 | 本机客户端 | 增加初始化、申请信息输出、核验、按 keyNo 读取系统凭据库并调用业务 API 的路径。首个联调平台为 macOS，其余平台沿相同凭据存储接口接入 |
| 3 | 运营页面 | 从 `ops-web/components/layout/header.tsx` 的账号区接入抽屉。按现有 API 客户端和契约组织申请、列表及撤销，提供角色说明、表单校验、请求状态和国际化文案 |
| 4 | 资料与联调 | 代码落地后将自助操作加入契约生成与角色矩阵。验收走页面提交 → 本机重启后读取密钥 → 按授权访问门店或商户数据 → 页面撤销 → 本机调用收到 401 |

| 操作 | 身份 | 事务及复用 |
|---|---|---|
| 签发 | 员工号与客户端 UUID，UUID 在库中唯一 | 锁定员工行后插入，签发与审计同事务。重复 UUID 返回冲突，列表恢复元数据。明文只返回首次结果，响应丢失时撤销旧 UUID 后签发新 UUID |
| 自助申请 | 当前员工号与客户端 UUID | 复用锁定员工行、唯一索引与审计事务。比较原摘要及申请参数后返回原元数据，注册提交与审计同事务 |
| 撤销 | 员工号与密钥 UUID | 原记录上按未撤销条件更新，状态和审计同事务。重复调用返回成功 |
| 停用与改密 | 原员工操作 | 沿原事务撤销旧凭据；签发和员工状态变更用同一员工行协调 |
| 运营业务写入 | 既有操作身份和参数 | 不另建写入口，保留各业务的事务、执行记录和审计 |

幂等机制沿[幂等设计规范](https://github.com/robinwu2021-code/ai-shop/blob/33ad88009/docs/technical/reference/%E5%B9%82%E7%AD%89%E8%AE%BE%E8%AE%A1%E8%A7%84%E8%8C%83.md)。

## §5 验收

| 验收 | 验证 |
|---|---|
| 签发、元数据查询、单次明文、重复签发及撤销 | `OpsApiKeyFlowTest#bindsKeyAndAppliesCurrentRolesAndRouteScope`、`#allowsManagementSubjectToRoleAndRejectsExpiredOrRevokedKeys` |
| 员工实时角色和数据域、路由及跨端隔离 | `OpsApiKeyFlowTest#bindsKeyAndAppliesCurrentRolesAndRouteScope`、`#limitedDataScopeBlocksCrossPlatformOperations` |
| 到期、停用、重新启用及改密 | `OpsApiKeyFlowTest#allowsManagementSubjectToRoleAndRejectsExpiredOrRevokedKeys`、`#disablingStaffRevokesKeysPermanently`、`#initialPasswordBlocksIssueAndChangingPasswordRevokesKeys` |
| 旧运营接口权限和数据范围、原登录 | `OpsEndpointPermTest` 5 项、`OpsDataScopeFlowTest` 15 项、`OpsLoginProtectionFlowTest` 6 项 |
| 生成产物 | `node scripts/gen-ops-key-api.mjs --check` 通过。核对 19 个唯一操作 ID、JSON Schema 引用、权限矩阵和文档链接 |

Java 21 Maven 首批返回 `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0` 及 `BUILD SUCCESS`。补充场景后只复测密钥类，返回 `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`。该轮密钥 HTTP 流程使用 H2，MySQL 9 的独立迁移验证见后续发布前复核。

另抽查 6 项架构规则，2 项通过。4 项失败分别为全限定名、Service 命名、实现包位置、Controller 的 JSON Mapper 判定，报出的违规均位于基线已有文件和代码。

### 自助分发验收

| 场景 | 预期 |
|---|---|
| 顶栏入口、本人列表与申请表单 | 页面区分加载、空列表、失败和成功；字段验证、账号与环境不匹配提示、自助撤销均可覆盖 |
| 本地凭据库写入失败 | 客户端停止申请，服务端无凭据记录 |
| 员工用自己的运营会话注册 | 无需审批，服务端只存摘要。客户端重启后能从本地凭据库读取密钥并访问准入 API |
| 注册请求超时或重复发送 | 重用本地秘密和 UUID。相同参数只创建一条凭据及一条签发审计，改参数返回冲突 |
| 登录者停用、待初始改密或伪造员工号 | 账号状态不合规时拒绝，由会话确定属主 |
| B/C 端凭据、运营密钥调用自助管理，或尝试操作其他员工的凭据 | 自助管理仅接受运营会话，查询和撤销仅作用于当前员工 |
| 密钥持有角色或数据域变化，或密钥被撤销 | 下一次业务请求按原路由和实时权限判断，撤销后返回 401 |

自助开发验证使用 Java 21，`OpsApiKeyFlowTest` 7 项与 `OpsEndpointPermTest` 5 项均通过。前端 TypeScript 校验与 12 项专项测试通过。浏览器 Mock 验证了入口、账号不匹配提示、签发结果和撤销。macOS 原生 Keychain 测试覆盖编译、跨进程读回、摘要和请求头一致、重定向拒绝、本地清理。另用独立 H2 进程和真实 Tomcat HTTP 端口验证本机准备、员工会话自助绑定、核验身份、读取门店分页及撤销后 401。MySQL 9 的原生迁移和事务验收见下节。

### 发布前复核（2026-11-24）

| 检查 | 结果 |
|---|---|
| MySQL 9.7.4 原生迁移验证 | 在 `127.0.0.1:3308` 的独立临时库中，用 Flyway 执行密钥表迁移（验证时版本号为 V697，合入最新主分支后顺延为 V701），迁移 1 条。随后 checksum 校验通过，再次执行迁移 0 条。主键、摘要唯一索引和员工查询索引均核对通过 |
| MySQL 9 原生事务 | 用真实 `OpsApiKeyServiceImpl`、`OpsApiKeyStore` 和 Spring JDBC 事务并发申请及撤销。同参只产生一条密钥和一次签发审计写入，修改参数及重复摘要均冲突，并发撤销只写一次审计，撤销后凭据失效。使用最小员工及事务审计测试表 |
| HTTP 验收范围 | 本机 Keychain 到门店 API 的真实 HTTP 路径已在 H2 上通过。尝试迁用 H2 全量夹具在 MySQL 9 启动应用时，夹具里的 `IS JSON OBJECT` 成为方言阻塞。MySQL 原生证据覆盖此次迁移和密钥事务 |
| 前后端产物 | 开发提交 `39af4f3e4` 的 JAR 打包成功并核对内置完整 Git SHA。运营前端以生产同源真实 API 配置静态导出，通过 `assert-prod-build.mjs` |
| 发布基线 | `codex/ops-api-keys` 和远端 `main` 分叉。将本功能两个提交的改动在 `origin/main` 模拟整合，运营安全配置和多个契约文件有冲突。已针对主干网关调整接入，见下方主干适配记录 |

独立测试库验证结束后已清理。Flyway 对 MySQL 9.7 的版本覆盖发出提示，实际执行结果见上表。

### 主干网关适配

运营密钥接入由 `shop-gateway` 的 `OpsGatewayEnd` 安装，认证过滤器与 MVC 准入随同一个运营网关开关启用。员工密钥只能到本进程准入路由。网关资源转发、支付运营和 OpsKit 的整前缀转发在出站前拒绝此凭据。认证存储故障沿现有 503 处理。

## §6 实现对账

| 设计 | 实现 |
|---|---|
| 自助页面与本机申请 | `ops-web/components/layout/api-keys.tsx` 及 `scripts/automation/ops-api-key.swift`。页面提交摘要，本机持有真实密钥 |
| 员工密钥与审计 | `shop-core/platform` 的 `OpsApiKeyService`、`OpsApiKeyController`、`OpsApiKeyServiceImpl`、`OpsApiKeyStore`，以及 `OpsServiceImpl` 的停用和改密撤销 |
| 密钥持久化 | `V701__ops_staff_api_key.sql` 及 H2 测试表。UUID 主键、密钥哈希唯一索引 |
| 认证、准入及角色 | `OpsGatewayEnd`、`OpsApiKeyAuthFilter`、`OpsApiKeyRoutes`，复用原认证上下文及 `OperatorIdentityLoader`。管理员代签、查询和撤销使用 `iam:staff:api-key-manage`，V701 将该操作功能点默认授予 `SUPER_ADMIN` |
| 已有业务 Controller 和操作契约 | 复用门店、商户、渠道、行业现有路由，由 `scripts/gen-ops-key-api.mjs` 生成[模块与角色清单](../api/运营管理API-模块与角色清单.md)和[OpenAPI](../api/ops-management.openapi.json) |
| 数据及端点基线刷新 | 生成器排除测试用 Controller；现存权限矩阵和 OpenAPI 包含管理员与员工自助各 3 项凭据操作 |
