# 打印模块合入统一分支核对 · 2026-10-25

状态：统一域适配及集中专项验收完成，形成包含最新统一基线的合并候选。本记录不包含远端推送，未执行生产迁移。

> 本页为合并准备阶段的历史记录。后续正常全量检查及远端推送已完成，最终ba44c7f0c、6213项后端无失败，见[最终推送结果](remote-push-final-results.json)。下文历史既有失败已在后续处理，不能据此将其继续列为当前阻断。生产迁移与实机不在该结论内。

- 目标基线：远端 `codex/ai-shop-codex`，`d0e1b93ee`，已 fetch 核对。
- 待合入：`codex/order-printing`，`98632ce4c`。
- 准备分支：`codex/print-unified-integration`，位于现有隔离工作区。
- 统一分支原工作区有未提交餐饮开发，未改动其文件、索引或分支引用。

## 已处理

两条分支分叉后统一分支新增78个提交。已按统一分支为真源，整合144处文本冲突，保留现行定价、税务、储值、退款状态转换与接口类型；添加打印词条、角色权限、原始PDF响应例外和订单打印接入。25个生成器已运行，商家端331项测试及vue-tsc通过。多契约后端UI延期登记保留现有显式contract参数，增加打印契约；相关脚本6项通过。

## 原数据模型冲突与处理

统一分支已有公共打印域 `backend/shop-core/src/main/java/ai/neargo/shop/printing` 与 `V567__shared_print_delivery.sql`。待合入独立模块 `backend/print` 的 `V551__print_center.sql` 重复创建以下4张表：

| 表 | 统一分支所有者 | 待合入模块所有者 |
|---|---|---|
| prn_job | printing.entity.PrnJob | print.persistence.entity.PrnJob |
| prn_attempt | printing.entity.PrnAttempt | print.persistence.entity.PrnAttempt |
| prn_route | printing.entity.PrnRoute | print.persistence.entity.PrnRoute |
| prn_template | printing.entity.PrnTemplate | print.persistence.entity.PrnTemplate |

两者列结构、版本字段、路由设备模型均不同，不是可直接复用的同一张表。两个迁移都使用CREATE TABLE，直接整合会出现重复建表或实体读写列不匹配。不能通过删除已有迁移、忽略测试、修改守卫基线或自动覆盖表解决。

合并后后端集中验证已启动，在原有PrintDeliveryTest的H2夹具遇到多列ALTER语法错误，未执行完宿主应用专项。共享检查还揭示同名实体和元数据解析等错误，不认定合并后验收通过。此前独立分支72项通过的结论仍只适用于独立分支。

已确认的DDL解析器合并兼容修复仅扩展ADD/MODIFY的可选存在性条件；解析器focused回归由1项失败恢复为4项通过，未改业务迁移或放宽检查基线。

## 已确认的整合方向

用户已选择适配现有统一打印域。执行[TDD](../../TDD-统一打印域兼容整合.md)和[ADR-041](../../ADR/ADR-041-统一打印投递所有权与兼容整合.md)，按同一PrintService、规范表和设备gate收敛。原V551/V552/V553候选迁移原样归档，V567/V579保持字节不变，V582起追加附属能力。新的领域实现移至core/printing/center，print-domain成为兼容聚合依赖。

历史UNKNOWN及SENT任务保留冻结内容、原租约和编号。历史设备上的多个未决尝试按账本逐个治理，确认旧队列终止后才释放对应attempt；UNKNOWN本身不改写为成功。新增页面支持显式接管原设备编号，后端阻止同一物理设备另建独立门控。

## 集中验收进展

专项验收完成，全仓既有失败单独列明，未声明全仓通过。

- MySQL 9.7.2隔离临时实例：空打印域安装、V567/V579历史升级、冻结内容/租约/多未决任务门控、权限追加及幂等6组验证通过，最终SQL已复跑。该验证不是全仓全部Flyway迁移验收。
- 共享全量测试发现8项统一基线原有失败，已在真实HEAD归档副本重跑确认，未以扩基线或删除断言处理。日志`/tmp/print-shared-head-evidence.log`。
- 后端集中专项144项分轮通过：规范任务/门控27、模板与状态7、布局/DB/PDF45、agent6、宿主应用及权限33、餐饮调用26。包含物理身份接管、旧UNKNOWN门控、rich租约同步释放、feature关闭时治理。恢复组装夹具修正后单项补跑通过。
- 后端架构/权限目录额外24项中20通过、4失败：既有定价Port实现位置、品牌Service命名、储值FQN、非打印接口权限目录。10份相关源码与d0e1b93ee逐字相等，见同目录unified-backend-baseline-evidence.json。未放宽守卫。
- 商家端331项及类型检查通过；共享层581通过、8项基线失败；ops数据域9项专项通过。25生成器及新鲜度检查通过，相关脚本6项通过。
- 最终SQL已在隔离MySQL复跑6组通过。PDF/模拟成功不构成实机出纸证据；飞鹅简洁V2仍能力受限，Windows及58/80mm硬件未验收。
- 当前远端统一分支在其他开发推进中；生成本次合并提交后仍须整合并检查最新目标，不能覆盖其他工作。

独立分支旧集成修复曾追加C端merchant mock裁剪测试；统一基线的对应契约及实现已演进，本次保留统一基线C端源码，故未引入这份只适用于旧实现的测试。未改写当前商家字段或减少原有测试。

日志：/tmp/print-owner-focused-final.log、/tmp/print-owner-recovery-final.log、/tmp/print-unified-final-merchant.log、/tmp/print-unified-final-merchant-types.log、/tmp/print-unified-final-shared-after-scope.log、/tmp/print-unified-final-scripts.log、/tmp/print-unified-final-generated-check.log。MySQL结果见同目录unified-mysql-results.json。提交与本地/远端状态在整合最新目标后追加。

### 后端集中命令

临时聚合POM包含本工作区的backend与backend-plugins/shop-industry-food；使用JDK21，餐饮HTTP用例需要本机临时端口。

```sh
mvn -o -f /tmp/print-unified-reactor-pom.xml -P '!food-embedded' \
  -pl :shop-app,:print-agent,:food-core,:food-web,:food-channel -am test \
  '-Dtest=*Print*Test,UnifiedPrintDeviceGateTest,UnifiedPrintProjectionTest,AgentDurabilityTest,CompactReceiptAcceptanceTest,ReceiptSoftwareAcceptanceTest,SchemaDriftTest,SchemaSeedReplayTest,MerchantRoleFlowTest,FoodKdsSubscriptionTest,FoodStationRuntimeTest,DiningKitchenAcceptedConsumerTest,DiningKdsEventConsumerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false -fae
```

仅修正恢复配置夹具后，定向补跑：

```sh
mvn -o -f /tmp/print-unified-reactor-pom.xml -P '!food-embedded' \
  -pl :shop-app -am test -Dtest=PrintRecoveryConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

前端与元数据命令：`npm run test -w ai-shop-b-app`、`npm run type-check -w ai-shop-b-app`、`npm run test -w @ai-shop/shared`、`node --test scripts/tests/backend-ui-delivery.test.mjs scripts/tests/print-schema-generation.test.mjs`、`node scripts/check-generated-docs.mjs`。共享全量退出1，原因是上述8项已确认基线失败。

## 最新统一基线交叉验证

打印整合提交为`260674ffa`（父提交d0e1b93ee及98632ce4c）。随后合入统一基线快照`47c0aaed0`，包含并行推进的储值和积分提交，自动合并无文本冲突。新增迁移版本没有冲突，打印owner源码未被这些提交修改。

该快照上重新验证：后端10类37项全部通过、BUILD SUCCESS；商家端341项及类型检查通过；共享581通过、8项既有失败；25生成器重建及新鲜度检查通过。后端包含主体授权、储值财务访问、积分任务/恢复，以及打印装配、订单、通知、数据域和恢复治理。结果见`unified-latest-backend-results.json`；日志`/tmp/print-unified-latest-*.log`。

原统一工作区的22份已修改或未跟踪文件在操作前另存原文和SHA256于临时目录`/private/tmp/print-unified-target-preservation`；仅对与本次同样修正的PrintDeliveryTest做必要的临时保存与恢复，其余餐饮和异常处理文件不参与本次提交。
