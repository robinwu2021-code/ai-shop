# Task60A-A4 CHECK/列定义解析

Status: IMPLEMENTATION_APPROVED after accepted A3 product ba07edc99c7b88a1d3de70c18c6e0586eae0073c. 档位0，守卫/解析器修复，无营业契约或DDL变化。实施基线由根交付。原总设计A4延续；本brief精确收敛实际5文件与共享Java测试helper。

唯一工作树 /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation。产品owned：scripts/lib/ddl.mjs、scripts/tests/ddl-column-definitions.test.mjs、backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDriftTest.java、backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaGeneratorTest.java，以及新增backend/shop-app/src/test/java/ai/neargo/shop/infra/SchemaDdl.java（package-private test-only）。允许task-60a-a4-report.md/evidence。只测试/工具，不改Java生产/SQL/Python/schema-test.sql/known/POM/entity登记/其它片/根文档；不派子agent，不stage/commit/push/merge/deploy/native。

根技术选择：两个Java守卫复用test-only SchemaDdl.createColumns(String sql):Map<String,Set<String>>，避免两份新scanner或一个Test依赖另一个Test。无现存同责helper已只读核实。它只提取CREATE表/列，SchemaDrift原迁移排序、rename/drop/add重放保持。JS原splitTopLevel在ALTER已有真实调用，扩展同一结构判据供CREATE定义拆分，不再复制单独lexer。

## 需求

1. readSchema(root,dirs)返回形状/类型/nullable/comment/indexes/uniques/indexNames/历史重放能力不变。CREATE body按顶层逗号定义切分，括号/单双引号/反引号、转义/双写引号、SQL行块注释中的逗号不能分列。CHECK/CONSTRAINT/KEY/UNIQUE/PRIMARY/INDEX/FULLTEXT等是整项约束，不看续行词猜列；列类型匹配完整token，CHAR不匹配CHAR_LENGTH。不得给AND加黑名单掩盖逐行错误。保留合法inline多列、多行CHECK、注释文字/逗号、生成列/索引的原行为。
2. Java两个真实guard均调用同一sharedhelper，列识别不依赖2/4空格；一空格/无indent合法定义可读。实际V506__entitlement_exception_action.sql含一空格列和匿名CHECK；V517__entitlement_migration_alias.sql与V533__shared_resource_identity.sql多行CHECK不能变成AND列。CHECK不能混入columns。不要改这些已执行文件。
3. 表体定位遵守quote/comment/paren结构，畸形未闭引号/括号/注释/表定义应明确失败，不能吞入后续CREATE表制造假的列。JS当前tabletail600上界不可放宽/缩小，表选项仍允许跨行。线性有界扫描；不重做所有SQL方言/迁移时间线/ALTER语义。真实新兼容缺口先报告，不能静默丢真实字段/表。
4. Python gen-test-schema.py现已合并续行/保留CHECK，不假设它有同bug；本片不授权编辑。scripts/tests/test-schema-constraints.test.mjs本片只执行、不编辑；新增JS lexer行为断言写在已owned的ddl-column-definitions.test.mjs，不为scope而写镜像静态测试。schema-test.sql不手改、不为适应错误解析而删CHECK。任何额外文件/真实generator缺口交根精准扩范围。

## TDD与有限验证

- 现存column lexer separates inline fields without treating multiline CHECK clauses as columns为真实RED；增加frozen V506 checks remain constraints at one-space indentation、multiline V517 and V533 checks cannot become AND columns、malformed definitions fail without swallowing the next table。使用真实冻结SQL/临时副本，不能复制预期实现算法。
- SchemaDriftTest新增singleSpaceColumnsAndMultilineChecksAreParsedStructurally，调用真实collectCreates/replay链；SchemaGeneratorTest新增checksAreNotColumns，通过真实parse到sharedhelper。覆盖合法注释/引号/嵌套表达式与缺闭合输入。初期Java缺helper编译错误不算行为RED；先用原路径取得真实列错断言，再抽取helper修复。
- node --test scripts/tests/ddl-column-definitions.test.mjs scripts/tests/test-schema-constraints.test.mjs RED/GREEN。
- JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=SchemaDriftTest,SchemaGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false RED/GREEN；不clean，不跑其它Java业务套件，fresh非零零failure/error/skip XML，H2生成校验只是fixture不是MySQL部署。
- pnpm --dir packages/shared exec vitest run tests/entity-alignment.test.ts tests/schema-lineage.test.ts，拆明真实结果：entity的新增12个无ORM表登记仍归A5，不能把整个失败套件标绿/修改登记/缩守卫。若其它真实列差异仍红，先归因不能绕过。
- 最小消融恢复CREATE逐行解析或去掉结构约束识别，应使对应inline/AND/CHECK案例真实RED；逐字还原后同focusedselectorGREEN，不重复未受改动套件。Java共享helper被两真实guard调用要在diff可见。
- 新改Java正常imports/4space/<=120/AOSP仅改hunks；不重排整个旧类。源码/冻结SQL与known前后SHA、真实命令/退出码/XML、RED-GREEN/消融恢复、三处对账报告；根独立review再精确提交。实际文件数以ownedmanifest真实记录，不虚报通过/部署。
