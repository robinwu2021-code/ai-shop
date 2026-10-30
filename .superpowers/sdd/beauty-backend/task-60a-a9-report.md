# Task60A-A9 实施与验证报告

状态：产品已冻结，待根协调的独立规格与质量审查。BASE `ddd3f8b073dc66a40931ecff6aa7a1405bd21b41`，工作树 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`，分支 `codex/beauty-foundation`。本报告不代表提交、合并、部署或 BM04/BM05 完成。

最终受影响验证：Java 全类 **10 tests / 0 failures / 0 errors / 0 skipped**；会员码真实 YAML 合同 **3/3**；Ops 真实矩阵与登记表完整文件 **37/37**，登记表消融恢复后的有限选择 **31/31**（6 个旧用例按选择器未运行）。新鲜独立快照全局生成闸门 **25 个生成器，exit 0**。未重跑未变更的 B242/typecheck/shared8 或任何原生/数据库套件。

## 交付边界与根裁决

原始11路径之外，根在同一 brief 末尾/main TDD2.34 批准了：同 owned Java 守卫内部的方法注解扫描修复与行为用例；6个实际派生输出；精确 Ops 登记表及其现有测试2路径。最终允许19产品路径，实际18路径变更；业务域权限矩阵确实重生成但与BASE一致。根自有 brief、progress、主TDD变动不属于实现者产品。

| # | 产品路径 | 最终状态 |
|---|---|---|
| 1 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 变更 |
| 2 | `packages/shared/tests/fixtures/ops-role-endpoint-matrix.json` | 变更 |
| 3 | `docs/technical/reference/运营端-角色×端点矩阵.md` | 变更 |
| 4 | `docs/technical/reference/B端功能点-权限码-页面.md` | 变更 |
| 5 | `docs/technical/reference/B端功能矩阵-按角色.md` | 变更 |
| 6 | `docs/technical/reference/三端权限矩阵-按业务域.md` | 已生成，与BASE逐字相同 |
| 7 | `docs/technical/README.md` | 变更 |
| 8 | `b-app/scripts/gen-openapi.mjs` | 变更 |
| 9 | `b-app/src/api/requests.ts` | 变更 |
| 10 | `docs/api/openapi-b.yaml` | 变更 |
| 11 | `b-app/tests/member-code-spec.test.ts` | 变更 |
| 12 | `docs/api/API清单.md` | 变更 |
| 13 | `docs/api/API详情-B端.md` | 变更 |
| 14 | `docs/technical/reference/数据库表清单.md` | 变更 |
| 15 | `docs/technical/reference/中英文对照-词条.md` | 变更 |
| 16 | `docs/technical/reference/中英文对照-实体与字典.md` | 变更 |
| 17 | `docs/technical/reference/glossary.json` | 变更 |
| 18 | `scripts/perm-endpoint-map.mjs` | 变更 |
| 19 | `packages/shared/tests/ops-perm-matrix.test.ts` | 变更 |

完整 owned BASE diff（包含未跟踪的新测试）见 [complete-BASE-owned.diff](./task-60a-a9-evidence/complete-BASE-owned.diff)；完整当前 tracked diff（另含根自有文档）见 [complete-BASE-worktree-tracked.diff](./task-60a-a9-evidence/complete-BASE-worktree-tracked.diff)。逐路径 BASE/final SHA256、大小、变化状态见 [product-manifest.json](./task-60a-a9-evidence/product-manifest.json)。证据目录的 [manifest.json](./task-60a-a9-evidence/manifest.json) 覆盖全部证据文件的SHA256。

## 权限事实与守卫修复

只在 REQUIRED 新增精确4项：personnel 的 assignments/account-links/status-changes → `BizPerms.STORE_ADMIN`；store-sales-catalog/versions → `BizPerms.GOODS`。真实人员控制器位于 shop-merchant/api/biz；其 account-links/status-changes 仍执行 `requireStore(..., true)` 的 owner-only 检查。登记不修改授权范围，不授予委派店长运行时访问。

初始真实守卫4方法有1断言失败，准确列出缺失4路由。登记后 everyBizEndpointHasADecision 已通过，但 decisionsAreActuallyEnforced 对同4路由失败：旧扫描只向 Mapping 之前找注解，真实控制器注解位于其后。依根裁决，将真实目录守卫与测试 fixture 共用 `scanGuardedEndpoints(String)`；使用 JDK21 自带 JavacTask 只解析语法树的方法注解列表，保留既有 CLASS_BASE/MAPPING 路径识别，不分析类型/执行代码/增加依赖。没有固定行窗口，注解无法跨方法借用；语法错误明确断言失败，标准文件管理器由 try-with-resources 关闭。

六个新增行为用例覆盖：Mapping之前（含既有全限定 PreAuthorize 写法）、之后、多行两种顺序、前后无权限邻方法、仅注释中出现授权、损坏Java源码。初版AST匹配简单注解名时，真实 `/biz/goods/zip-import` 的全限定注解导致8方法/1失败；保留该失败XML并修复名称识别，未改生产注解。最终新鲜XML10方法全绿，原4方法完整保留。

Ops四个GET的实际注解均是 `FINANCE_SETTLE_READ`。从不变 Perms.ROLE_PERMS 重算，JSON只新增 FINANCE/SUPER_ADMIN 各4路由，12角色无任何删除/其他新增；见 [ops-semantic-proof.json](./task-60a-a9-evidence/ops-semantic-proof.json)。生成MD中五角色旧 elec:* 描述移除，准确匹配不变真源：BD5、ELEC_ADMIN6、GOODS_OPS2、RISK2、SUPPORT1；非运行时角色变更。

生成MD的目标码区原有17项未归属，根逐项批准精确17条 method+anchored-path 登记：4个points GET→finance:settle:read；13个既有权益端点按真实注解分别read8/verify2/recover2/freeze1。真实控制器/行号/常量值表见 [unmapped-pay-authority.json](./task-60a-a9-evidence/unmapped-pay-authority.json)。新增17正例同时检查真实扫描注解、Perms值、codeOf结果，另有14个错方法/相似路径/多余后缀反例。无宽泛prefix、PUBLIC、ANY_OF、ROLE_PERMS或生产权限改动。

## 实际 OpenAPI 合同

仅补已有 REQUEST_TYPES 的 `mResolveMemberCode: ResolveMemberCodeReq` 和已有请求两个字段的 JSDoc。实际生成YAML新增 ResolveMemberCodeReq（严格两个必填字符串memberCode/storeNo，带描述，additionalProperties=false）及既有POST的required requestBody引用。响应CustomerReference三字段、ISO UTC expiresAt、操作名、鉴权、无查询身份参数保持已有合同。新测试直接parse真实 `docs/api/openapi-b.yaml`，没有第二生成器或复制DTO。原始实际YAML diff仅14行schema+6行requestBody。

## 真正 RED、消融与恢复

| 阶段 | 实际结果 | 证据 |
|---|---|---|
| Java原始 | 4/1失败/0error/0skip，缺精确4决定 | java-initial-red-authorized.log / java-initial-red.xml |
| 仅登记4项 | 4/1失败，旧注解顺序扫描假阴性 | java-green-1.log / java-registration-only-enforcement-red.xml |
| 新scanner fixtures，旧算法 | 8/4断言失败/0error/0skip | java-scanner-fixtures-red.log/.xml |
| 初版AST，完整真实目录 | 8/1断言失败，既有全限定注解 | java-scanner-green.log / java-qualified-annotation-red.xml |
| 最终Java | 10/0/0/0，BUILD SUCCESS | java-final-green.log/.xml |
| 删除assignments REQUIRED一行 | 1/1断言失败，仅缺此路由，随后恢复逐字相同 | java-mutation-red.log/.xml / java-mutation-proof.json |
| 新YAML测试原始 | 3项中1失败，requestBody实际undefined | spec-initial-red-valid.log |
| 正常生成后YAML | 3/3通过 | spec-green-1.log |
| 删除唯一新REQUEST_TYPES映射再生成 | 3项中1失败，真实requestBody消失 | openapi-mutation-generation.log / spec-mutation-red.log |
| 恢复映射再生成 | generator与spec均恢复原SHA；最终3/3 | spec-mutation-proof.json / openapi-mutation-restoration.log / spec-final-green.log |
| Ops旧完整守卫 | 6项中1断言失败，准确17未归属 | ops-registry-initial-red.log |
| 新Ops病例、旧registry | 17正例断言失败，14反例通过；6旧项未选择 | ops-new-cases-red.log |
| 精确17登记后完整Ops文件 | 37/37通过 | ops-registry-full-green.log |
| 删除accounts唯一新registry规则 | 唯一选择项断言失败；36项未选择 | registry-mutation-red.log |
| registry恢复 | 字节/SHA相同；31选择项全绿，6旧项未选择 | registry-mutation-proof.json / registry-restored-finite-green.log |

Java消融发生在scanner扩展之前，证实的原始 everyBizEndpointHasADecision 谓词与最终代码相同；随后最终全类已在四登记完整、scanner最终代码上新鲜运行。消融恢复生成不计作正常独立幂等代次。

环境/setup失败均保留，未冒充业务RED：首次Maven未升级sandbox，在资源复制时 Operation not permitted，未运行测试（java-initial-red.log）；首次Vitest把config相对路径重复成b-app/b-app，启动失败（spec-initial-red.log），随后用正确解析路径得到真实断言RED；首次快照引导遇本地Python tar.extractall不支持filter参数，修正对可信BASE archive的解包后才执行checker。没有修改依赖、POM、锁文件或安装工具。

## 实际生成、派生漂移及幂等

所有产品均来自现有生成命令。OpenAPI、B功能/角色矩阵、业务域矩阵、Ops矩阵、技术README各正常执行两代；Ops登记源扩展后，对其真实JSON+MD再各执行两代。六额外派生输出经根审阅313行实际diff后分别正常生成两代。13个最终允许的实际输出都有两次正常生成的相同SHA，详见 [final-normal-generation-1-output-sha256.json](./task-60a-a9-evidence/final-normal-generation-1-output-sha256.json) 与 [final-normal-generation-2-output-sha256.json](./task-60a-a9-evidence/final-normal-generation-2-output-sha256.json)。这些为按对应生成器汇总的实际代次，不冒充一次全局checker连续运行。

派生变化归因：API清单/API详情-B端只来自A9请求合同；表清单来自已接受结构解析真源的mch_resource19→17、ent_migration_alias24→23；词条/实体字典/glossary来自既有A8 locale、两项beauty错误码及文案、库存双占位消息、`BoundDelegatedCalls.Mode`（MERCHANT/CONSUMER）。早期消息把最后一个类名误称ShopSessionRealmPolicy，已向根更正，事实真源为 `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BoundDelegatedCalls.java:18`。技术README状态取当前真实文档头；无手剪生成输出。

完整生成物diff见 [actual-generated-BASE.diff](./task-60a-a9-evidence/actual-generated-BASE.diff)，生成源diff见 [generator-source-BASE.diff](./task-60a-a9-evidence/generator-source-BASE.diff)。gen-api-detail的C/平台输出、gen-glossary的静态常量/依赖等实际旁写保持BASE一致；业务域矩阵也逐字一致。既有Node模块类型警告与Java编译器弃用/注解处理警告保留于实际日志，未为消除提示修改无关产品。

## 隔离全局闸门与边界

从未对live worktree运行全局checker。两个快照均为 `git archive BASE` 解包到 `/tmp/task60a-a9` 内唯一目录，不创建gitworktree/branch；仅覆盖当时获准产品与所需根文档，node_modules软链到现有绝对依赖目录，无install。完整输入/最终文件SHA、overlay身份、BASE archive SHA及13输出子集SHA均归档。

- snapshot-1：原11产品+当时根progress；实际checker exit1，准确6漂移：API清单、API详情-B端、数据库表清单、中英文对照-词条、中英文对照-实体与字典、glossary.json。没有依赖缺失/生成器不可运行。保留原始失败后才按根精确裁决同步这些路径。
- snapshot-2：BASE+最终19产品+根progress/brief/主TDD；新鲜一次实际 `node scripts/check-generated-docs.mjs --check` exit0，**25个生成器产物最新**。全量input/final hashes与identity见同名JSON，实际日志为 [snapshot-2-global-check.log](./task-60a-a9-evidence/snapshot-2-global-check.log)。
- 两次checker唯一未恢复副产物均为快照内新增 `docs/technical/design/ui-lib.page.html`，SHA `a24a39ddb6929070319b6ab9df10fd4baa10ae609b47694abaf09b486607ec00`；只作副作用证据，不入产品，不复制回live。OpsMD虽不在checker的JSON输出表中，本次额外以实际正常生成与hash单独验证。

[scope-verification.json](./task-60a-a9-evidence/scope-verification.json) 证明tracked变更只有获准产品及根自有文档；[readonly-authority-sha256.json](./task-60a-a9-evidence/readonly-authority-sha256.json) 对权限/控制器/运行时BAPI/旧生成器/DDL解析/POM/锁文件等逐一与BASE比较不变。最终 `git diff --check` exit0。产品已按manifest冻结，独立审查可直接对比，不存在未决的实现scope ruling。尚未开展正式部署，也不将此文档闭环推断为BM04/BM05完成。

## 每条验证/生成命令与完整日志

完整命令数组、实际cwd、JDK21路径、起止时间及exit统一见 [commands.jsonl](./task-60a-a9-evidence/commands.jsonl)。Maven全程 `-o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=<实际selector> -Dsurefire.failIfNoSpecifiedTests=false`，未install。以下链接保留完整原始输出：

| 命令标识 | exit | 完整日志 |
|---|---:|---|
| `java-initial-red` | 1 | [java-initial-red.log](./task-60a-a9-evidence/java-initial-red.log) |
| `spec-initial-red` | 1 | [spec-initial-red.log](./task-60a-a9-evidence/spec-initial-red.log) |
| `java-initial-red-authorized` | 1 | [java-initial-red-authorized.log](./task-60a-a9-evidence/java-initial-red-authorized.log) |
| `spec-initial-red-valid` | 1 | [spec-initial-red-valid.log](./task-60a-a9-evidence/spec-initial-red-valid.log) |
| `openapi-generation-1` | 0 | [openapi-generation-1.log](./task-60a-a9-evidence/openapi-generation-1.log) |
| `spec-green-1` | 0 | [spec-green-1.log](./task-60a-a9-evidence/spec-green-1.log) |
| `matrix-generation-1-0` | 0 | [matrix-generation-1-0.log](./task-60a-a9-evidence/matrix-generation-1-0.log) |
| `matrix-generation-1-1` | 0 | [matrix-generation-1-1.log](./task-60a-a9-evidence/matrix-generation-1-1.log) |
| `matrix-generation-1-2` | 0 | [matrix-generation-1-2.log](./task-60a-a9-evidence/matrix-generation-1-2.log) |
| `matrix-generation-1-3` | 0 | [matrix-generation-1-3.log](./task-60a-a9-evidence/matrix-generation-1-3.log) |
| `matrix-generation-1-4` | 0 | [matrix-generation-1-4.log](./task-60a-a9-evidence/matrix-generation-1-4.log) |
| `java-green-1` | 1 | [java-green-1.log](./task-60a-a9-evidence/java-green-1.log) |
| `snapshot-1-global-check` | 1 | [snapshot-1-global-check.log](./task-60a-a9-evidence/snapshot-1-global-check.log) |
| `openapi-mutation-generation` | 0 | [openapi-mutation-generation.log](./task-60a-a9-evidence/openapi-mutation-generation.log) |
| `spec-mutation-red` | 1 | [spec-mutation-red.log](./task-60a-a9-evidence/spec-mutation-red.log) |
| `openapi-mutation-restoration` | 0 | [openapi-mutation-restoration.log](./task-60a-a9-evidence/openapi-mutation-restoration.log) |
| `spec-final-green` | 0 | [spec-final-green.log](./task-60a-a9-evidence/spec-final-green.log) |
| `snapshot-attribution-api-index` | 0 | [snapshot-attribution-api-index.log](./task-60a-a9-evidence/snapshot-attribution-api-index.log) |
| `snapshot-attribution-api-detail` | 0 | [snapshot-attribution-api-detail.log](./task-60a-a9-evidence/snapshot-attribution-api-detail.log) |
| `snapshot-attribution-table-inventory` | 0 | [snapshot-attribution-table-inventory.log](./task-60a-a9-evidence/snapshot-attribution-table-inventory.log) |
| `snapshot-attribution-glossary` | 0 | [snapshot-attribution-glossary.log](./task-60a-a9-evidence/snapshot-attribution-glossary.log) |
| `independent-generation-2-0` | 0 | [independent-generation-2-0.log](./task-60a-a9-evidence/independent-generation-2-0.log) |
| `independent-generation-2-1` | 0 | [independent-generation-2-1.log](./task-60a-a9-evidence/independent-generation-2-1.log) |
| `independent-generation-2-2` | 0 | [independent-generation-2-2.log](./task-60a-a9-evidence/independent-generation-2-2.log) |
| `independent-generation-2-3` | 0 | [independent-generation-2-3.log](./task-60a-a9-evidence/independent-generation-2-3.log) |
| `independent-generation-2-4` | 0 | [independent-generation-2-4.log](./task-60a-a9-evidence/independent-generation-2-4.log) |
| `independent-generation-2-5` | 0 | [independent-generation-2-5.log](./task-60a-a9-evidence/independent-generation-2-5.log) |
| `java-mutation-red` | 1 | [java-mutation-red.log](./task-60a-a9-evidence/java-mutation-red.log) |
| `java-scanner-fixtures-red` | 1 | [java-scanner-fixtures-red.log](./task-60a-a9-evidence/java-scanner-fixtures-red.log) |
| `java-scanner-green` | 1 | [java-scanner-green.log](./task-60a-a9-evidence/java-scanner-green.log) |
| `extra-generation-1-0` | 0 | [extra-generation-1-0.log](./task-60a-a9-evidence/extra-generation-1-0.log) |
| `extra-generation-1-1` | 0 | [extra-generation-1-1.log](./task-60a-a9-evidence/extra-generation-1-1.log) |
| `extra-generation-1-2` | 0 | [extra-generation-1-2.log](./task-60a-a9-evidence/extra-generation-1-2.log) |
| `extra-generation-1-3` | 0 | [extra-generation-1-3.log](./task-60a-a9-evidence/extra-generation-1-3.log) |
| `extra-generation-2-0` | 0 | [extra-generation-2-0.log](./task-60a-a9-evidence/extra-generation-2-0.log) |
| `extra-generation-2-1` | 0 | [extra-generation-2-1.log](./task-60a-a9-evidence/extra-generation-2-1.log) |
| `extra-generation-2-2` | 0 | [extra-generation-2-2.log](./task-60a-a9-evidence/extra-generation-2-2.log) |
| `extra-generation-2-3` | 0 | [extra-generation-2-3.log](./task-60a-a9-evidence/extra-generation-2-3.log) |
| `java-final-green` | 0 | [java-final-green.log](./task-60a-a9-evidence/java-final-green.log) |
| `ops-registry-initial-red` | 1 | [ops-registry-initial-red.log](./task-60a-a9-evidence/ops-registry-initial-red.log) |
| `ops-new-cases-red` | 1 | [ops-new-cases-red.log](./task-60a-a9-evidence/ops-new-cases-red.log) |
| `ops-registry-full-green` | 0 | [ops-registry-full-green.log](./task-60a-a9-evidence/ops-registry-full-green.log) |
| `registry-mutation-red` | 1 | [registry-mutation-red.log](./task-60a-a9-evidence/registry-mutation-red.log) |
| `registry-restored-finite-green` | 0 | [registry-restored-finite-green.log](./task-60a-a9-evidence/registry-restored-finite-green.log) |
| `registry-matrix-generation-1` | 0 | [registry-matrix-generation-1.log](./task-60a-a9-evidence/registry-matrix-generation-1.log) |
| `registry-matrix-generation-2` | 0 | [registry-matrix-generation-2.log](./task-60a-a9-evidence/registry-matrix-generation-2.log) |
| `snapshot-2-global-check` | 0 | [snapshot-2-global-check.log](./task-60a-a9-evidence/snapshot-2-global-check.log) |
| `final-diff-check` | 0 | [final-diff-check.log](./task-60a-a9-evidence/final-diff-check.log) |
