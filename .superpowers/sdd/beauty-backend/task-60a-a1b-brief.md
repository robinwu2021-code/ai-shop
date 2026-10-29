# Task60A-A1b 技术补充：内部准备辅助类命名

Status: IMPLEMENTATION_APPROVED as a serial subunit of the current A1 gate, not a separate accepted commit. HEAD/IMPLEMENTATION_BASE remains58744b8313e6fbf9693f0088467b6d9a04ceeac4. Current A1 partial candidate is preserved; no stage/commit. Resume original A1 implementer, one writer only. All original A1 constraints remain.

## 已确认问题与裁定

原A1登记beauty业务域后，HOST34项中唯一失败为serviceMustBeInterface：具体辅助类AppointmentPreparationService并非接口。根已读源码、全部7生产引用及三处现行方法设计产物；它没有@Service注解，只由BeautyAppointmentConfiguration构造，持久意图/身份/接待准备是内部协作者，与ReceptionPreparation/WorkOrderPreparation同类。不能放宽checker、把beauty移回infra、制造无意义转发接口或改成假业务ServiceImpl。

Ruling：重命名为AppointmentPreparation，更新构造/类型/嵌套ReceptionPlanExpired引用，职责及所有方法体/JSON/状态机/事务/权限/原号完全不变。保留@Bean方法名appointmentPreparationService，避免无需求的Bean名称变化。此A1b为原计划预期真实越界的最小技术补充，与A1联合验证和独立审查后一次提交，不接受部分失败。需要新增一个本工作树beauty-core target清理权限以移除旧helper.class；不扩大其它clean范围。

## 增量owned

以下10个现有文件在补充前均与BASE逐字相同，hash/原文根已冻结/tmp/task60a-a1b-baseline.json及/tmp/task60a-a1b-source-baseline。

在backend-plugins/shop-industry-beauty/beauty-core/src/main/java/ai/neargo/shop/beauty/：
- service/impl/AppointmentPreparationService.java → service/impl/AppointmentPreparation.java。
- service/impl/AppointmentServiceImpl.java。
- service/impl/ReceptionRecovery.java。
- service/impl/ReceptionReassignment.java。
- service/impl/ReceptionCoordinator.java。
- config/BeautyReceptionConfiguration.java。
- config/BeautyAppointmentConfiguration.java。

以及docs/technical/design/schema/beauty-java-method-design.json的两处helper类型真源，重新运行scripts/gen-beauty-java-method-design.py，仅其现有两产物docs/technical/design/美业系统-Java类与方法清单.md、docs/technical/design/美业系统-Controller-Service-Mapper方法清单.md。不改generator代码/API160/业务方法签名。若出现其它生成diff给根具体来源，不批量纳入。

原A1八路径加上述helper八路径与文档三路径，共19路径/17逻辑文件。无新业务类功能，无新接口/Entity/Mapper/POM/SQL/权限/config。只改类型标识与必要格式；helper是逻辑迁移旧文件，不全文件格式化扩大无关diff，新改行≤120按映射后的旧文件判断。不要修改真实方法体或历史TDD/旧报告中的类名。

## 验证与恢复

原green-host34/1失败已是真实RED，归档保留不覆盖；BLOCKED报告原文SHA dc15a8181045bec207d3af48c1160dd39d2cc47622bf50caef91ebaefd36f445、原8路径manifestSHA6bae15fa4eac85de96381ffe73aa9c59e1bde1b46bd5cbbf398f2a0622f66ea3保留历史。先新增a1b子目录保存增量baseline/证据。

在确认本工作树beauty-core target也无运行占用后，JDK21/离线缓存沿原brief，精确额外构建：mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend-plugins/shop-industry-beauty/beauty-core/pom.xml clean install -DskipTests。只clean此额外一个target，不cleanapi/web/channel或整个cache。构建准备skipTests不计测试通过。核core target/cache JAR新AppointmentPreparation唯一，旧AppointmentPreparationService及其inner classes为0，HOST/SERVER实际classpath匹配刷新core+原store JAR SHA。

然后复用A1 HOST embedded full命令（34项应全部通过）及SERVER clean test，SERVER的-Dtest扩成BeautyJobTargetTest,BookingServerWiringTest,BeautyBm04AssemblyTest以验证真实core配置类型接线（只跑后二类，不编辑其源码）。无native数据库或部署。若代码变化仅标识/接线，不重复未受影响的业务native。

全GREEN后完成原A1唯一DOMAINS beauty消融，精确HOST ArchitectureTest#topLevelPackagesMustBeRegistered应红；逐字恢复同selector GREEN，保留classpresence已实际执行的fullGREEN及source hash。新method生成和--check均须通过，API/项目生成源未变不重做无关生成。

如果继续暴露真实新缺口，保留实证交根不放宽守卫。A1整体仍待独立spec+quality审查和精确提交；root管理文档只由根写，实施者只更新原task-60a-a1-report.md及原evidence内a1b目录，完成后返回joint manifest/diff/report hash。原8路径manifest/失败XML/报告副本不得覆盖。不要stage/commit/继续A2，不派子agent。
