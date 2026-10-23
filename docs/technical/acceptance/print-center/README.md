# 打印中心：软件验收与接入说明

> 最新排版增量：[紧凑版 V2 验收与启用说明](compact-v2.md)。本页原始全仓回归失败和硬件未验收结论继续保留。

执行顺序：全部业务代码和测试源码 → 统一编译（跳过执行测试）→ 集中测试及必要修复回归。2026-10-23 结论：**当前能力范围的软件专项验收通过；全仓回归未通过，不能据此认定全系统或实机验收完成。**

## 本轮结果

| 项目 | 结果 | 证据与限制 |
| --- | --- | --- |
| 后端统一编译与打包 | PASS，30 个模块 | 最终打包包含测试源码编译，跳过测试执行；JDK 21 |
| 打印专项、订单事实、启用装配 | PASS，54 项 | 7 个测试类，失败/错误均为 0 |
| MySQL 8.4 集成 | PASS，7 项 | 独立临时容器执行真实迁移及流程；非外部打印机测试 |
| 商家端 | PASS，48 个文件 / 266 项 | 类型检查、H5 构建亦通过 |
| Schema 生成 | PASS，2 项 | 打印与既有价格 schema 生成回归 |
| 页面冒烟 | PASS，3 个路由 | 仅验证页面加载与 Mock 模式保护，不等于真实后端联调 |
| PDF | PASS，40 份样票 | 20 场景 × 58/80mm；自动验证内容与布局，并进行渲染目检 |
| 全仓回归 | **FAIL** | 29 模块成功，shop-app 失败；已报告 1507 项、4 failures、53 errors、19 skipped，随后 JVM 堆内存耗尽，未跑完全部测试 |
| 飞鹅、Windows、实体打印机 | NOT_RUN | 用户确认资源暂未准备 |
| 私有 COS 部署 | NOT_RUN | 代码可配置，未对真实私有桶验收 |

全仓失败中包含订单支付证据冲突、积分门店策略不支持、运费退款快照断言失败及后续内存不足。运费退款失败已在定向运行复现；部分错误发生在内存耗尽后，未将全部问题认定为与打印无关。未修改无关金融策略或放宽既有失败断言。下一步应先处理全仓失败与运行资源，再进行系统级验收。

机器可读结果见 [验收结果](verification-results.json)、[MySQL 证据](native-mysql-results.json)、[页面冒烟](ui-smoke-results.json)、[PDF 清单及 SHA256](pdf-manifest.json)。JAR 路径及 SHA256 记录在验收结果中；本轮未提交、推送、合并、部署或执行生产迁移。

## 交付范围

- 独立 Maven 模块：`print-api`（公共合同）、`print-domain`（配置、任务、仲裁、回执、对账）、`print-channel`（飞鹅、字体、PDF、私有存储）、`print-agent`（Java 21 桌面代理）。
- V550：22 张打印表；V551：订单所有者的 `ord_print_fact`；V552：预置角色及运营动作权限增量。不会给自定义角色自动加权。
- 商家端：订单详情入口、打印任务/生成需求/故障事件、设备和代理、模板/路由/场景策略、故障模拟和 PDF 导出。
- 20 个小票场景、每场景 58/80mm 两个内置版本；模板发布后不可修改，任务冻结内容、路由、字体及几何布局。
- 订单支付事务生成不可变来源事实；事件和巡检均可驱动生成，重复触发复用需求。受理、实际完成、模拟通过、PDF 就绪分别记账。

## 当前能力边界

| 能力 | 可用范围 | 未验收/接入前置 |
| --- | --- | --- |
| 正式业务来源 | `ORDER_PAID` 的子单支付事实 | 其余 19 场景缺少正式不可变来源合同；场景 API 返回 `sourceAvailable=false`，不能开启自动打印 |
| 模板与样例模拟 | 全部 20 场景 × 58/80mm | 样例不会创建真实打印任务，不证明出纸 |
| 云端打印 | 飞鹅提交、按订单号查结果、设备探测、凭据版本冻结 | 用户暂未提供测试账号和打印机；原生布局需实机确认后开启 |
| 本地打印 | HTTPS 协议、配对/会话/租约、SQLite、JPS、DPAPI、Windows 打包脚本 | 用户暂未提供 Windows 测试机；安装、驱动及真实打印待验收 |
| 本地完成证据 | 队列受理后保持未确认，支持人工证据核实 | JPS 受理不能证明物理出纸；不提供伪造的系统完成回执 |
| PDF | 原始纸宽与 A4、私有本地/COS、短时授权、恢复及过期清理 | COS 真账号、独立私有桶部署另行验证 |
| 运营诊断 | 显式租户、主体、门店授权范围 | 当前不提供省略目标的全平台汇总查询 |

缺失来源时保留失败/阻塞需求，不通过读取变化中的订单、退款、餐饮或服务记录拼装“历史事实”。已有历史订单不会批量补印。退款后的历史支付凭证也不自动改写为退款小票。

## 运行配置

默认 `shop.print.enabled=false`。开发环境开启时通过外部配置覆盖，下面的密钥必须分别生成，不能复用示例占位符：

```yaml
shop:
  print:
    enabled: true
    credentials:
      key: ${PRINT_AES_KEY_BASE64} # 32 随机字节，Base64
      key-version: '1'
      previous-keys-json: '{}' # 历史版本 -> Base64 密钥；只用于旧任务查询
    agent:
      lease-key: ${PRINT_AGENT_LEASE_KEY_BASE64} # 至少 32 随机字节
      require-https: true
    artifacts:
      provider: local
      root: ./data/print-private # 不在 Web 静态目录
      signing-key: ${PRINT_DOWNLOAD_SIGNING_KEY_BASE64}
      download-base: /private/print-artifacts
    feie:
      native-profile-verified: false # 实机确认前保持关闭
```

使用私有 COS 时设置 `shop.print.artifacts.provider=cos` 和 `shop.print.artifacts.cos.bucket`，使用现有 COS 凭据/地域配置，必须是独立私有桶。API 获取下载授权时重新检查门店与业务字段权限，授权有效期不超过 60 秒。

模拟/PDF 通过已提交 Outbox 唤醒和既有 Job 体系维护；需要部署对应投递泵与 Job 调度。`shop.job.enabled=true` 时注册 `print-artifact-maintenance` 和 `print-lifecycle`，不能只开 HTTP 页面却漏部署维护任务。商家端使用真实后端配置（`VITE_USE_MOCK=0`）；Mock 模式会明确禁用真实打印操作。

Windows 代理步骤见 [代理安装说明](../../../../backend/print/print-agent/packaging/README.md)。DPAPI 使用当前交互用户，不能把凭据复制给其他用户或 LocalSystem。SQLite 先提交 SUBMITTING 再调用系统队列；恢复只核查原投递，不重发未知任务。

## 验收命令与证据

从仓库根目录执行，JDK 21：

```sh
mvn -B -f backend/pom.xml -DskipTests -DskipITs package
mvn -B -f backend/pom.xml verify
npm run type-check --workspace ai-shop-b-app
npm run build:h5 --workspace ai-shop-b-app
npm test --workspace ai-shop-b-app
node --test scripts/tests/print-schema-generation.test.mjs scripts/tests/pricing-schema-generation.test.mjs
```

独立打印定向回归（包括未被 shop-app 依赖的代理）：

```sh
mvn -B -f backend/pom.xml -pl print/print-channel,print/print-agent -am \
  '-Dtest=*Print*Test,*ReceiptSoftwareAcceptanceTest,AgentDurabilityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

PDF 样票由真实编译器、字体和 PDF 渲染器生成：设置 `-Dprint.acceptance.output=<绝对目录>`。本轮样票在 `output/pdf/print-acceptance/`，不是业务订单，不包含真实客户信息。

| 用例入口 | 检查内容 |
| --- | --- |
| `PrintStatePolicyTest` | 受理≠完成、部分出纸/未知不重试、不解锁、证据等级、终态与重试上限 |
| `ReceiptSoftwareAcceptanceTest` | 40 份模板、字宽/中文、金额不拆断、确定性 PDF、受保护必填绑定、防路径穿越/伪造下载 |
| `PrintDatabaseAcceptanceTest` | 真实 MyBatis/H2、幂等冲突/回滚、生产流程、回执证据、冻结补打、9 故障模拟隔离、PDF、调度及清理公平性 |
| `AgentDurabilityTest` | SQLite 原子提交、事件去重、崩溃不重投、取消与提交竞态 |
| `OrderPrintFactRecorderTest` | 子单金额、来源冻结、不支持组合阻塞、免支付不编造流水 |
| `PrintEnabledAssemblyTest` | 功能开关打开的完整 Spring 装配、商家 token 不能冒充 Agent、配对仍要求 HTTPS |
| 既有权限/schema/订单场景 | 权限落库、端点注解、DDL 同步与重复装载、支付及退款回归 |

原计划分散的测试名由这些集中用例承接，不生成仅转发的空测试类。实际类清单和原设计的适配说明见 [实现清单](../../design/schema/print-implementation-inventory.json)。

## 重要实现取舍

- 沙箱不允许写入新建 worktree，按技能回退在原 checkout 开发；保留工作区已有修改，未合并、推送或部署。
- 执行用户指定的集中验收顺序，覆盖通常的逐任务 TDD；独立审查发现的问题仍补充失败回归并修复。
- 实际仓库使用 `OutboxConsumer.consume(SysOutbox)`；公共合同不持有第三方 SDK 或订单实体。
- 使用带显式租户/主体/门店条件的 MyBatis BaseMapper；原设计中的若干独立查询 Mapper 方法合并到服务，避免无行为的转发层。
- 正式来源不足时关闭该场景自动打印；本地不确定结果保留门闸；飞鹅未支持的回调和逐票取消不会伪装为可用功能。
- 字体使用已捆绑、附 OFL 许可和 SHA256 的静态 TrueType，运行时不下载字体。原仓库的 CFF 字体不适合这条 PDFBox 嵌入链路。

## 发布与回退

本次仅开发和验收，不执行生产部署。灰度前准备飞鹅测试账号、Windows 测试机、58/80mm 打印机和私有存储，完成本文标记的实机项目。先关闭自动场景、登记暂停设备，完成样票、探测及真实出纸核对后按门店开启。

暂停/关闭场景阻止新任务；已受理或 UNKNOWN 任务必须核实原设备和队列，不能通过删表、清空整个队列或换设备自动重试来“回退”。关闭 `shop.print.enabled` 会关闭 HTTP 与打印消费者/维护任务；保留事实、任务及回执用于恢复核对。

## 提交前隔离复验

将仅含打印改动的 Git 暂存快照导出到独立临时目录后，统一编译打包再次通过（32 个模块，保留 HEAD 中既有的电子元器件模块，未混入工作区的退役改动）；该快照的打印领域/通道/代理 47 项专项测试再次通过。原工作区已有暂存内容另行保留。此前全仓失败及实机未验收的结论不变。
