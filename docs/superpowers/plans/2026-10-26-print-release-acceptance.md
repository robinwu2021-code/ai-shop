# 打印发布收尾与完整软件验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按用户“继续”完成已建议前三项：正常推送统一分支、退款/充值及运营页面浏览器补验、完整应用数据库安装/升级演练。

**Architecture:** 保留唯一打印引擎和真实业务所有者。使用隔离H2/本地临时MySQL和显式启用的验收夹具；生产接口、资金政策和既有迁移不因测试而放宽。

**Tech Stack:** 既有Java21/Spring/Flyway、MySQL9.7.2、Chrome/CUA、H5/Next及正常pre-push。

**Spec:** `docs/technical/acceptance/print-center/unified-merge-2026-10-26.md`，用户确认的后三项软件收尾工作。

## Global Constraints

- 正常推送，不跳过闸门、不扩大known基线；提交中的渠道模块一并由闸门检查，不替换为旧打印基线。
- 先写完本批验收代码，再统一编译、集中运行；未到位的实机、云渠道和COS不宣称完成。
- 仅使用loopback隔离服务及本地临时库，不运行默认连接远程服务器并删库的脚本。
- 原迁移字节保持不变；全历史MySQL重放与支持的安装基线分开验证，不将部分表脚本冒充全应用。
- 当前售后明确拒绝新增部分退款。真实浏览器验证其拒绝且不生成凭证；多事实选择使用明确标记的合同夹具，禁止修改退款政策或制造真实完成的部分退款。
- 在推送钩子完成前不推进其HEAD；其余验收修改留在隔离分支，验收后再提交。

## Review Focus

1. 推送检验的SHA与实际远端SHA一致，失败不当作成功。
2. 退款/充值浏览器使用真实owner事实，余额、退款号和金额来自冻结来源。
3. 同场景多来源选择、跨店及钱包专岗权限不能以夹具结果代替真实API隔离。
4. 空库/升级遇到历史方言或结构问题必须保留原失败证据，不吞错、不改旧迁移。
5. 运营试跑、实际执行、UNKNOWN和PDF必须保持原业务语义，不引发真实投递。

### Task 1: 正常推送统一分支

**Files:** 只修复闸门指出的生成输出；结果记录到本批验收文档。

- [x] 核对当前统一提交与远端，正常推送；发现README及依赖清单过期。
- [x] 按现有生成器更新两份文档，提交4ef10785b并快进统一分支。
- [ ] 正常重推，保留全仓检查与远端核验结果。

### Task 2: 浏览器补验

**Files:** `PrintBrowserAcceptanceServerTest.java`、必要的test-only来源准备辅助类、`scripts/print-browser-acceptance.mjs`及验收记录。

- [x] 在原显式启用的H2夹具中，用真实储值计划入账、v2退款结清创建来源；保护测试云渠道并保持物理worker关闭。
- [x] 编译验收代码，构建两端；真实Chrome完成退款/充值来源选择、模拟、PDF及权限；部分退款拒绝与多来源夹具分别留证。
- [x] 运营页面在真实隔离后端完成健康、异常、试跑对账、幂等恢复与操作查询；捕获截图和脱敏HTTP结果。

### Task 3: 完整应用数据库演练

**Files:** 新增显式启用的数据库验收工具/测试，复用`db/migration`和Flyway，不修改历史SQL。

- [x] 用本地MySQL9.7.2实际执行全应用空库安装，记录版本/表/权限及失败点。
- [ ] 对可用完整应用基线执行V588/V589升级与重入验证，保留旧打印任务、lease、UNKNOWN门控和自定义角色。
- [x] 核对历史MariaDB方言及仓库现有dump安装方式，能安全验证的路径继续；缺可靠基线时登记真实阻断和所需修复，不虚构通过。
- [ ] 集中验证、一次全分支独立审查、归档并提交验收材料；不部署生产。

验收结果：浏览器与真实API补验完成；完整Flyway两场景均在V1排序规则失败，587基线无法创建，升级保持断言待可靠完整基线。正常推送bf41e后端2816项发现打印BF编号内联UUID，已按统一BizKey修复，专项3类7项/5通过2显式opt-in跳过；MySQL显式运行的2项失败如实保留。最终独立审查与重推进行中。

独立审查完成：无Critical/Important；1项Minor延期（真实HTTP负向断言过宽），详见本批验收报告。最终推送以实际远端SHA为准。

33f28004c正常检查在M–N媒体引用覆盖失败后停止（4144条已执行）；已登记receipt_intent_key仅为SHA256业务键的精确非媒体分类，专项3/3通过，不扩known基线。余下O/P/Q–R/S–Z先在隔离目录预检，最终正常推送仍跑所有批次。
