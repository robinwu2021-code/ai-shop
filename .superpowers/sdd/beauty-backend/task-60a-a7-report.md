# Task60A-A7 实施报告

状态：实施和约定验证完成，待根独立规格/质量审查。档位0；实施 BASE/当前 HEAD 均为 `3062d1b48d79d60e21c53113118aa6970c228a59`。本片未 stage、commit、push、merge 或部署，不代表 BM05、BM04 整体或 P1-P4 部署完成。

依据：本目录 `task-60a-a7-brief.md`、主 TDD §2.31、CLAUDE、本地开发标准及 Java reference；使用 TDD、系统化调试和完成前验证流程。证据目录：`task-60a-a7-evidence/`。

## 产品边界与实现

仅三个产品文件：

- 新增 `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyClaimedGuestResourceIT.java`。
- 修改 `scripts/verify-checkout-payment.mjs`，有限白名单增加该类和精确三方法，原 beauty 默认类/四方法、支付类、参数验证、XML、脱敏、锁与自有资源生命周期不变。
- 修改 `scripts/tests/checkout-payment-runner.test.mjs`，验证新类整体/方法/加号选择、未知方法与 Beauty 类拒绝、payment 模式拒绝和原 runner 的实际调度。

完整三产品 BASE diff 为 `full-three-product-files.diff`，最终清单/字节数/SHA 为 `final-manifest.json`。根维护的 `progress.md` 既有改动未触碰。未永久修改生产 Java、SQL、POM、known 文件、其他测试或根账本。

新类直接继承 `CheckoutFlowFixture`，只在 `beauty.source.test.native=true` 启用。美业类型只在 `Class.forName` 反射边界出现；未引用 BeautySource 的 spy fixture，未使用 mock Port、Bean 替换或 booking/预约/身份/接待结果种子。真实人员任职/资格、规范 STAFF 资源和排班均走 PersonnelService/ScheduleService；原 owner 只准备目录/政策/定义/人员/排班。主要业务动作使用真实非 owner `MERCHANT/USR`、实际 mch_account user 映射与当店角色，权限为 BizPerms 的 booking view/manage/customer。

三个方法与真实断言：

| 方法 | 本轮真实效果与断言 |
|---|---|
| `claimedGuestReplacementUsesCanonicalUserAndPreservesReceipts` | register → GUEST preview/create → CONFIRMED hold A → claim 到活动 buyer → 当前非 owner USR 作用域 mc1/cr1 → checkIn → 最新 revision → 原 guestNo 明确换到 B 开单。断言 work order customer_ref=USER:buyer、context USER/buyer、仅一工单/任务；REPLACE SUCCEEDED、owner current 指向不同新 hold、旧 hold RELEASED/后继链接、新 hold CONFIRMED/前驱链接、当前安排为 B、A 活跃单位/占用归零、B 为六个五分钟占用；命令冻结 plan/hash、目标资源、source revision、来源 decision/terminal/fence 对账；开单/登记/claim 原 key 重放保持原回执与事实；资金订单/支付计数不增。 |
| `revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment` | 独立相同已 claim/签到场景，仅撤实际角色 BOOKING_MANAGE，保留 view/customer 且真实 getAppointment 仍可查询。相同合法换人开单精确 FORBIDDEN；当前 grant 查询确实失效；原 hold/单位/桶/安排、current pointer、guest/claim 历史保留；无工单/任务/REPLACE。 |
| `failedClaimedGuestReplacementPreservesOriginalResourceHold` | 按 personnelNo 选择 A<B，在独立 JDBC 事务仅锁 A 人员行；异步真实 open，有限轮询确认 REPLACE ACCEPTED 且来源 phase=1 已许可；另一真实登记 guest 通过 preview/create CONFIRMED 占 B；finally 释放 A 门闩，真实 owner REPLACE REJECTED/CAPACITY_UNAVAILABLE，open CONFLICT。原 A 与 peer B 的 current hold/安排/六个占用同时保留；source terminal=拒绝/phase3、decision、释放 fence 和 reception REJECTED 对账；无新 hold/工单/任务。线程、连接、事务与门闩均 finally 释放。 |

共享 guest 历史摘要基于实际 V537 `mbr_guest_registration` 全行（含 claim_*、加密 contact/token）、真实美业 `bty_guest_claim_receipt` 和原 claim operation；没有虚构独立 member guest receipt 表。原预约 GUEST 身份、原 arrangement/assignment 历史摘要保持。敏感登记/claim 回执只比较 SHA-256；不在日志/失败断言输出 mc1、cr1、claimToken、contact、session 或内部密钥。

## 实际命令与分批结果

所有 Maven 命令使用 JDK21 和既有离线 `/tmp/codex-beauty-bm01-m2`。保留 Node 首轮结果，未重复不变 Node 检查。生产业务正确性未制造假 RED；早期配置失败单列于下一节。

| 验证 | 实际命令/结果 | 证据 |
|---|---|---|
| 有限 selector TDD RED | `node --test scripts/tests/checkout-payment-runner.test.mjs`，20 tests，19 pass/1 fail；新类正例被旧 runner 以 `invalid checkout selection: class or mode` 拒绝 | `node-red.log` |
| runner GREEN | 同命令，20 pass/0 fail/0 skipped，约0.42秒 | `node-green.log` |
| 正常 native 三例 | `CHECKOUT_PAYMENT_TESTS=BeautyClaimedGuestResourceIT node scripts/verify-checkout-payment.mjs --beauty-source`，fresh XML **3 tests/0 failures/0 errors/0 skipped**，精确三方法，44.637秒测试时间；runner 验证成功 | `native-three.log/.xml/.txt`、`native-three.sanitization.json`、`native-three-fingerprints.json` |
| 唯一临时权限消融 RED | `CHECKOUT_PAYMENT_TESTS=BeautyClaimedGuestResourceIT#revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment node scripts/verify-checkout-payment.mjs --beauty-source`，**1 test/1 failure/0 errors/0 skipped**；`Expecting code to raise a throwable.`，即真实撤权后开单未再抛 FORBIDDEN | `guard-ablation-red.log/.xml/.javap.txt`、`guard-ablation-metadata.json` |
| 原样恢复后定向 GREEN | 同精确 selector，fresh XML **1 test/0 failures/0 errors/0 skipped**；23.93秒测试时间，runner 验证成功 | `guard-restored-green.log/.xml/.javap.txt` 及 sanitization |
| 默认 retail test-compile | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test-compile -DskipTests`，**BUILD SUCCESS**，15.684秒。仅 touch 新 Java 强制重新编译733份测试源码；无 beauty profile/硬类型依赖，最终源码 SHA 不变 | `retail-final-compile.log`；此前首次默认编译也成功，见 `retail-compile.log` |
| 格式/边界 | Google Java Format `--aosp --dry-run --set-exit-if-changed` 成功；Java 924行/最大108列/无 beauty import；三产品 whitespace diff 无诊断 | 完整 diff、最终 manifest |

口径：正常三绿和恢复后定向一绿是两批；未重复或声称再次运行未变源码的完整三例。

## 失败定位与测试配置修正

这些不是产品业务 RED，也没有放宽业务断言：

1. 第一次 native 是 3 errors/0 failures：真实 PersonnelService.createPersonnel 发布处缺测试 archive-only 配置，`A registered unified PLATFORM outbox source is required`。依源码补齐 brief 允许的既有 personnel/schedule/resource publication archive-only 测试属性；不替换 publisher 或业务 provider。证据 `native-fixture-error.log/.xml`。
2. 下一次三例是 3 failures/0 errors：预约返回 HELD。加安全只读投影后单例显示 RESERVE 仍 ACCEPTED、source intent phase=1、operation=PENDING；没有 CONFIRM 命令。证据 `native-held-failure.log/.xml`、`native-state-diagnostic.log/.xml`。
3. 经根批准，仅在非 CONFIRMED 的临时诊断分支对同一原始 ACCEPTED 命令真实 execute 一次，暴露被协调器吞下的 `resource source permission is not currently effective`：ResourceSourceAuthorizationServiceImpl.effective:132 → requireAuthorized:97 → ResourceBookingServiceImpl.mutate:158。原因是父 fixture 的 MutableClock 固定在场景起点，而 AppointmentPersistence.now:94 用实际 UTC 签发许可，冻结时间早于 issuedAt。证据 `native-owner-diagnostic.log/.xml`。
4. 经根裁决，仅 A7 ClockSelection 选择 `@Primary Clock.systemUTC()`，保留父 MutableClock typed bean 与真实 provider；已彻底删除临时 execute 诊断。随后正常原始服务链三例通过。无生产业务 gap、无生产修复扩大范围。

## 临时生产消融的精确恢复与编译来源

唯一临时生产文件为 MerchantMappers.java 的 OperatorAccessMapper。仅将 `(a.is_owner=1 OR EXISTS (...)) AS allowed` 中完整 allowed 表达式改为 `1 AS allowed`；其他 tenant/entity/store/actor SQL 不动。

- 原始及恢复源码 SHA-256：`c021c3e1f29c533bfeb6e2d0e5a96285d5460ed84afa441512583b20c7c518e7`。
- 临时源码 SHA-256：`4f35b6e5245aaa4f5a4df86fefe5eb5a5d38629ab2a6ecd255ebfc262e38ca08`。
- 消融前/恢复后 OperatorAccessMapper 字节码 SHA 完全一致：`51876c34298b7bab496a611d717fbc6793762b9eb823c75f811d1189d1f9f8c6`。
- 临时字节码 SHA：`1f4c953635657b4ddf6729ce6847c0b8c14b701c7350a70595e93aab09755b0c`。
- RED/恢复 GREEN 均实际重编译219份 merchant source，mtime 显示编译晚于对应源写入；javap 保留 SQL 注解常量。`ablation-driver.py` 的 finally 逐字恢复并断言原 bytes/SHA，再执行恢复 selector；生产文件最终 BASE diff 为空。
- 最终测试源码 SHA：`78d3580766c5fbc9312431f769140ab4f9531eb85b16bdd6188bc9cca7b826f4`；三绿及最终 retail 测试字节码 SHA 一致为 `4cd010e700ab40d9d62884f505ed40bca74e4d4926e456d548776cd575937d49`。
- V537、V539、V540、美业 V8 的 bytes/SHA 均与实施 BASE 完全一致，见 `final-manifest.json`。

## 环境、警告和清理

全部 native 沿原 A2 runner：context `colima-hxkey-tests`、VM hxkey-tests；缓存 mysql:9.7.2、`--pull never`、普通磁盘 datadir、64M buffer；loopback 动态端口、env-only 凭据、同一共享串行锁 `/tmp/codex-beauty-bm04-native.lock`、随机自有 UUID 与 finally 清理。未 pull、未停 foreign container/VM、未触碰远端共享数据库。

七次必要开发验证/诊断均完成 cleanup。各轮 MemAvailable 为 2926920、2922556、2929936、2923720、2927180、2922676、2921472 KiB，均高于524288。正常三例自有 UUID 为 `345887df-ef65-4f55-9e7e-2d754b1062b9`，消融为 `94ebc3b1-9199-4e0e-85dd-14e5c2d80745`，恢复为 `f41cb2bf-55ef-4b77-9dd4-7ff01ece9b36`。全部七个精确自有 UUID 又经本地只读过滤查询确认不存在，串行锁不存在，见 `cleanup-verification.json`。无遗留线程、锁、事务、容器或临时生产变更；外部资源保持原状。

保留的实际 native 警告包括：现有重复 Mapper 扫描、H2/MySQL 版本比 Flyway 已验证版本新、现有 mapperLocations 无资源、物流缺可选渠道/phone-key、测试 profile 关闭 OTP 限流及默认 ship-track key、MacOS Netty DNS fallback、DEV-only seeder、Mockito 自附加/动态 agent 与 JDK CDS。未压制、未当作绿化手段；没有外部短信/支付/物流动作。这是隔离开发证明，正式部署仍后置。

runner 对 retained XML/txt 和捕获输出执行凭据发现及脱敏；sanitization JSON 保存原始/脱敏 SHA、fresh mtime、XML结构前后相等与凭据缺席证明。A7 业务日志仅输出安全事实结论。

## 自审与交接

已逐份检查完整三产品 diff（含新 Java 全文件），确认精确三个 @Test、非 owner USR/current role、真实 register/claim/member-code/reception/resource 服务链、非空合法替换安排、人员排序与独立行锁/phase许可门闩、真实 peer 预约、精确错误码和终态对账、敏感字段摘要、finally 清理及反射编译边界。未新增 BM05 执行/完工能力、支付交易或受管理履约旁路。

尚需根的独立规格和质量审查；本片未提交。无已知阻塞。A6 bare-CR 最终分支分流不属于本片，未触碰。
