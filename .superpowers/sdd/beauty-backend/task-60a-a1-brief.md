# Task60A-A1 有界架构修复

Status: IMPLEMENTATION_APPROVED after root technical gate. Accepted Task59 product c9d9518798ee74f93d7a1f899d356c4760d57b43; design base920482037595e39d3db7408758430511b39a8531. Actual IMPLEMENTATION_BASE is the following root documentation commit supplied at dispatch. Only A1 is released; A2–A11 remain candidates. No user reconfirmation required for authorized automatic code execution.

## 依据与精确范围

读本brief first，再读task-60a-design.md的§1/2/A1及task-60a-report.md相关源码证据。根已完整核定设计SHA22c7ac5df49235da95fc635d4e5ec8ced0dcf600025c880f753be41b6b1687c1/reportSHA6e3b65e7d75f0356a24541ca2a1dcafa4bdf9621d45fed0cacfb70172874480b。主TDD§2.23是本片实施计划，§0.2要求全P1～P4代码先行、实际部署后置。档位0：不改接口/字段/权限/配置键/外部JSON。

唯一工作树/分支：/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex / codex/beauty-foundation。只owned7逻辑文件（迁移共8路径）：

1. backend/shop-store-mybatis/src/main/java/ai/neargo/shop/store/config/JobLockConfiguration.java → backend/shop-store-mybatis/src/main/java/ai/neargo/shop/job/config/JobLockConfiguration.java。
2. backend/shop-app/src/main/java/ai/neargo/shop/config/ShedLockConfig.java。
3. backend-plugins/shop-industry-beauty/beauty-server/src/main/java/ai/neargo/shop/beauty/server/config/BeautyJobTargetConfiguration.java。
4. backend/shop-app/src/main/java/ai/neargo/shop/portal/ops/pay/OpsPointsInspectionController.java。
5. backend/shop-app/src/test/java/ai/neargo/shop/arch/ArchitectureTest.java。
6. backend/shop-app/src/test/java/ai/neargo/shop/scenario/JobTargetProfileTest.java。
7. backend-plugins/shop-industry-beauty/beauty-server/src/test/java/ai/neargo/shop/beauty/scenario/BeautyJobTargetTest.java。

JobHandlerEndpointTest只跑不编辑。另可创建本片task-60a-a1-report.md及task-60a-a1-evidence目录，不改根brief/progress/主TDD/设计/ADR/pins/reviews；不派子agent。

## 必须保持的行为

JobLockConfiguration仍同shop-store-mybatis模块；保留lockProvider(DataSource)、lockingTaskExecutor(LockProvider)、Bean名、usingDbTime及数据库锁语义。HOST ShedLockConfig仍无条件薄Import；独立SERVER仍由BeautyJobTargetConfiguration的beauty/api/job.enabled外层条件导入。只改两import，不新条件、不改默认值、不新同义Bean。Ops仅正常PathVariable import替代FQN。DOMAINS登记beauty为业务域，不能放infra，现有域间/Service/Port/Mapper/Web规则及所有known棘轮不放宽。

测试新增HOST/SERVER唯一provider/executor断言，扩展既有disabledServer方法分别测api下(beauty=false,job=true)、(beauty=true,job=false)、bothfalse；三者无DataSource/target/锁Bean。保留nonApi、route/token及真实数据库锁阻断测试。Architecture新增embeddedBeautyClassesArePresentWhenRequested，用EnabledIfSystemProperty(named="beauty.arch.required",matches="true")，对ArchUnit已导入集合实际包含ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl作断言。必须用类名字符串/ArchUnit元数据，不静态import beauty-core类到默认shop-app测试，保持默认retail编译边界。条件禁用必须显式skip而非无断言早返；本片HOST命令始终true，必须执行、非零零skip。

## 先RED再最小修复及验证

依设计A1准确命令顺序；先确认三个target无运行JAR/进程占用，不停止别人的进程。只clean本工作树shop-store-mybatis/shop-app/beauty-server三个target，不clean其它模块或整个cache。使用JDK21 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home，离线缓存/tmp/codex-beauty-bm01-m2。先仅补上述测试，产品旧包/旧DOMAINS/FQN保持，归档可信Architecture行为RED及各装配真实结果；setup/编译失败不冒充RED。再最小迁包/两import/FQN/beauty登记，按设计三条store clean install→HOST embedded clean test→SERVER clean test验证。

根对设计A1两项补充为本brief优先：①presence测试不引入默认HOST的beauty-core编译依赖（如上）；②最小消融只移除DOMAINS中的beauty，保持其余字节，精确执行HOST ArchitectureTest#topLevelPackagesMustBeRegistered应报beauty违规；恢复逐字后仅同selector GREEN，不重复未受影响的JobTargetProfile/JobHandler/SERVER。消融命令在设计HOST命令中仅将-Dtest改为ArchitectureTest#topLevelPackagesMustBeRegistered，其余profile/property/offline设置相同。原fullGREEN与最终恢复SHA证明相同源码，不虚报全套重跑。

保存每次命令、退出码、新鲜XML非零/零failure-error-skip、JAR及源SHA，安装-DskipTests只算构建。检查store JAR无旧store.config.JobLockConfiguration且新类唯一，记录HOST/SERVER实际classpath使用刷新JAR。若缺离线依赖/clean插件或发现其它真实beauty越界，给精确证据与最小建议交根；不能扩大clean/owned/规则范围自行遮盖。无native/Docker/远程/正式JAR部署、无其他60A片修复。

遵零售Java规范：正常imports、4space、新改行≤120、Javadoc解释责任；Google Java Format1.25.2 AOSP仅迁移新文件及修改范围。不Repository/循环Service/POM/SQL/新权限。所有已执行迁移冻结。

## 交付

不stage/commit/push/merge。完整报告task-60a-a1-report.md记录逐AC/实际RED-GREEN/消融恢复、范围/偏差、命令output/XML/JAR/sourcehash、exact owned manifest包含deleted/added迁移关系、包含未跟踪新文件的完整diff。根独立spec+quality审查后才精确提交。只返回DONE/DONE_WITH_CONCERNS/NEEDS_CONTEXT/BLOCKED、报告/manifest路径与hash、测试一句话及关注点。不可把A1通过写成全60A完成、BM04部署通过或BM05实现。
