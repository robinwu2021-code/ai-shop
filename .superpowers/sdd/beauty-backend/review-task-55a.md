# Task55A 独立 Spec + Quality 审查

- **Spec: FAIL**
- **Quality: Needs fixes**
- 发现：**Critical 0 / Important 4 / Minor 0**；另有 **2 项无法完整核验的历史证据**。
- 审查 HEAD：`de8409ea40b22029e66b1d71033a7bca035fd0b2`（实际 `git rev-parse HEAD` 一致）；BASE：`2bf892b98225e6f71bee2921df5e4935f5494119`。
- 根目录：`/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`。
- 先读取 Task55A brief、review brief，再读取实现报告及 supplied `review-2bf892b98..de8409ea4.diff`，定向核对设计合同、实现与实际日志。没有生成新 diff，没有修改工作树、HEAD、index，没有运行 Maven、生成器或容器，也没有委派代理。以下问题依据可追踪调用链和表达式语义；没有把未执行的反例称作已运行测试。

## Important findings

### I1 — 新 claim intent / 已 CLAIMED 补回执绕过 cr1 的完整 actor namespace 核验

位置：`beauty-core/.../policy/ReceptionIdentity.java:29–38`，`beauty-core/.../service/impl/GuestRegistrationServiceImpl.java:78–85`。

`ReceptionIdentity.customer` 调用 `CustomerIdentityPort.resolveReference`。实际本地适配器 `backend/shop-core/.../member/port/MemberCustomerIdentityPortImpl.java:27–32` 转至 `MemberCodeResolveServiceImpl.requireReference`，后者只匹配 tenant/entity/store/actorNo，不匹配票据 actorRealm/actorKind。Task54 的完整检查位于独立方法 `requireGuestClaimReference`（同文件约102行），只有新的 owner `claim` 路径调用它。

可达反例：同一字面 actorNo 的 MERCHANT/MCH 与 MERCHANT/USR 均有当前 CUSTOMER 权限；以 MCH 签发 cr1，切换到 USR，携该 cr1、有效 guest secret 和新 key 调本域 claimGuest。若 owner guest 已 CLAIMED 且 USER 相同，旧 resolver 返回 USER，verify 返回 CLAIMED，代码持久化新 intent 后在85行直接 `finish`，完全不经过严格 owner.claim 校验。若 guest 尚 REGISTERED，82行也会在 owner.claim 拒绝前先持久化这种未经过完整 namespace 证明的 intent；之后若 owner 已认领同 USER，prior/get 或 recover 分支可据此补回执。

这不等于“已严格验证的原 intent 在当前同店员工手中恢复”：漏洞发生在**首次建立作为未来恢复授权依据的 intent**。它违反 brief 的当前 cr1 真实核验、完整 namespace 以及“先持久已核验规范目标”的前提。原始 host 三例只测不同 actorNo，不能发现 same actorNo / different kind。

修复要求：在任何新 intent 持久化之前，通过共享身份所有者的真实能力核验完整当前 tenant/realm/kind/actor/entity/store（不要在 beauty 读取 member 表，也不要假装原 actor）。已 CLAIMED 快路径也必须经过同样核验。保留已有合法 intent 的当前员工 get 恢复和成功原键重放。补真实 host 反例：same actorNo MCH↔USR、REGISTERED 与 CLAIMED 两种状态，拒绝后无新本域 intent/receipt/outbox；正常同namespace及当前员工合法恢复仍成功。

### I2 — 显式 guest 预约在创建后被认领，会阻断原键成功重放及未完成操作续行

位置：`beauty-core/.../service/impl/AppointmentServiceImpl.java:73–81`，`AppointmentPreparationService.java:87–94`。

`createAppointment` 在查找既有原键 operation 之前，总是 `preparations.customer` → `identities.guest` → owner.get，并将最新 USER/GUEST 结果与原 preview 比较。既有 operation 的查找位于稍后的 `prepare` 内。

可达反例：REGISTERED GUE1 preview 保存 `customerRef=null, guestRef=GUE1`；同请求/key K 创建成功（或已经持久 operation、资源结果仍未知）；随后 guest 被合法认领 USER U1；原 actor 在当前权限仍有效时用完全相同请求/key K 重放。owner.get 现在返回 USER，92行要求 U1 等于原 preview.customerRef，实际是 null，故直接 CONFLICT，读不到原成功结果或原待恢复操作。

设计§5.1要求成功原键重放核当前身份/权限后读原结果；“preview期间身份变化需新preview”应限制**尚未接受的新创建**，不能覆盖已经持久化的不可变操作。未知窗口迫使用户改 key/new preview 还可能重复创建预约。新增 AppointmentBusinessIT 只测创建前 claim 的冲突，没有测创建后 claim 的重放。

修复要求：在保持当前完整身份、所需实时权限、preview归属及完整 requestHash 检查的前提下，先查本命名空间的既有 operation，重用原规范身份和原号；只有未存在原操作的新请求才重核 guest 与 preview 的一致性。补创建成功后 claim 的同键重放、owner未知后 claim 的同键恢复，以及创建前 claim 仍冲突的回归。

### I3 — V5 的 identity 成对约束接受 kind=NULL / ref非空

位置：`beauty-core/src/main/resources/db/industry/beauty/V5__beauty_reception_work_order.sql:43`；对应真源 `docs/technical/design/schema/beauty-reception-schema.json` 中 reception operation 的第4个 CHECK。

当前表达式：

```sql
(identity_kind IS NULL AND identity_ref IS NULL)
OR (identity_kind IN ('USER','GUEST') AND identity_ref IS NOT NULL AND CHAR_LENGTH(identity_ref) > 0)
```

当 `identity_kind=NULL, identity_ref='U1'` 时，左支 FALSE、右支 UNKNOWN，整体 UNKNOWN。MySQL CHECK 拒绝 FALSE，允许 UNKNOWN，因此这条无 kind 的非空引用可入库。brief/设计§4.1要求两列同空或合法 USER/GUEST+非空ref；当前校验未表达该不变量。现有迁移测试检查 CHECK 存在、缺失、大小写漂移，没有插入这一 NULL 边界反例。

修复要求：追加明确 `identity_kind IS NOT NULL` 的非空分支（或等效 fail-closed 表达式），覆盖四种空值组合以及非法kind/空ref。**V5已执行并冻结，不得编辑V5或伪更新其SHA**；按冻结规则追加后续迁移，并相应更新 manifest/ready/生成真源与历史升级验证。新增版本和本步收口由控制器协调，不能因发现问题追改已执行V5。

### I4 — CHECK 字符串支持继续全局删除反引号，导致语义漂移被判等

位置：`beauty-core/.../config/BeautyCheckExpression.java:20–29`（新增字符串 grammar 与既有预处理组合）。

`canonical` 在词法分析之前对整个表达式执行 `.replace("`", "")`。现已支持字符串字面量，所以这不仅去掉 identifier 的引用符，也删除字面量内真正的数据字符。例如：

```text
phase IN ('RECEIVED', ...)
phase IN ('RECE`IVED', ...)
```

两者在第21行之后变成完全相同的输入，严格校验就无法区分。实际 JDBC 形式 `_utf8mb4\'RECE`IVED\'` 也先删除内容中的反引号，再匹配新增 ASCII-enum normalization。对 utf8mb4_bin phase，两个 literal 的业务取值明显不同，改写后的 CHECK 可以禁止正常 RECEIVED 并允许异常值，但 manifest 比较仍能通过。

此项不是要求支持复杂 SQL；要求**遇到未知/不允许的 literal 保守拒绝，不能先改写成允许的 enum**。原 numeric/time-only grammar 不接受这些字面量，本步新增字符串支持后引入该实际盲区。当前 case-drift 测试证明大小写保留，但没有保护其它内容字符。

修复要求：在词法层识别反引号包围的 identifier；保留字面量内容逐字符不变，或对当前封闭 ASCII enum 范围显式拒绝不支持字符。补 parser 的相等/不相等反例，并在下一次需要的原生 schema 验证中补带反引号内容的 CHECK 漂移。无需为修此项改写V5。

## 已核验通过的范围与限制

1. **031隐私和unknown分类**：registerGuest 不创建本域 operation/contact或displayName摘要/result/log/outbox；派生key覆盖 tenant/entity/store/realm/kind/actor/endpoint/raw-key hash。仅捕 typed ResultUnknown。remote仅将 status0 的 TIMEOUT/UNREACHABLE/REMOTE_ERROR 转为不带cause的固定消息；403/503/NOT_CONFIGURED保留失败。实际 loopback 不可解码200、403/503及缺地址测试确有执行。没有将此结论扩展成公网故障或所有owner能力验收。
2. **032事务与不可改绑**：除I1首次证明不足外，代码确实先REQUIRES_NEW持久规范guest/USER及派生号，再owner.claim；外调在本域事务外。finish锁同scope原operation，唯一receipt插入冲突后重新事务读取，并比较user/revision/hash；不更新既有receipt绑定。operation成功、ActionLog和真实安全Outbox同事务。真实owner提交后本域outbox失败与当前员工恢复都有不同层次的实际测试。
3. **C归属**：list/count使用同一SQL EXISTS（tenant/entity/store/guest/user/deleted）在分页前过滤；get/root通过CustomerOwnership核同scope receipt；直接USER与已认领guest并集、跨店/跨entity/他人/软删/BTG隔离都在native测试中断言。未发现本步新增的子父链绕过。工单/任务父链实现仍属55C，不在本次声称完成。
4. **challenge**：32字节SecureRandom、无padding base64url43、UTC毫秒精度、固定90秒、完整actor原key唯一、同键读原行不续期；权限在replay前检查BOOKING_MANAGE。code只进入专表和即时redacted VO，没有写operation/log/event。native精度失败后修复及最终绿灯存在。没有宣称C签到已实现。
5. **schema与冻结**：四Entity/BaseEntity、BeautyMappers、data-scope注册、27私表、manifest/ready=5装配一致；字段及索引未任意删减55B–D已冻结基础。当前V5 SHA实测为 `8e9aec144736ee3502fb9c0f9c4c95edf6f4a02c1cecbd597ff0c3b51aa4eab5`，与代码manifest/报告一致。V1–V4逐个比较BASE/HEAD git blob完全相同。I3/I4仍使严格schema要求未完全达标。
6. **边界与装配**：新service仅031/032/challenge，未增加052 default、OperationVO字段、generic dispatcher、controller/wire、签到、工单、恢复Job空壳。Reception配置经Appointment配置import纳入现有Business DAG；owner用ObjectProvider、receipt mapper为本域强依赖，实际host启用成功。未新增beauty生产依赖shop-core或跨域直写。ACCOUNT未被当作技师映射。
7. **生成真源**：supplied diff的schema JSON、JS renderer、字典增量、方法JSON/receptionFoundation及生成器输出相符。原41表/160编号/49任务目标没有被改称全部实现。生成器实际check日志存在。

## 实际日志证据核对

| 实际证据 | 核验结果 |
|---|---|
| `/tmp/task55a-red.log:75–91` | DTO guestNo行为RED：1测试/1 failure/0 error，expected GUE1 / actual空串；非环境失败 |
| `/tmp/task55a-green-contract.log:99–111` | 同DTO测试1/1 GREEN |
| `/tmp/task55a-check-red.log:75–99`、`check-green.log:83–97` | parser新增grammar先1 error；随后parser7+DTO1共8通过。parser error是目标grammar缺失，不当作认领业务RED |
| `/tmp/task55a-native-first.log:87`、`:818` | 真实应用5条迁移到v5；25测试、23 setup errors。报告明确没有将这次叫业务RED，属实 |
| `/tmp/task55a-native-second.log:586–702` | 25测试、1失败/0 errors；到店码首次返回与持久化毫秒精度不符 |
| `/tmp/task55a-native-third.log:420,609,691–703` | Schema17+Receipt6+Migration3 = 26/26 |
| `/tmp/task55a-host-first.log:617,644–675` | 真实host迁移至v5；BeautyReceptionIdentityIT 3/3、0 skips。测试调用真实member/mc1/cr1/guest存储，故障注入在真实owner提交返回之后 |
| `/tmp/task55a-reactor-third.log:124–125,471–474` | 17:48:28前已install beauty-core；host约17:49执行，满足本次host依赖先安装的顺序。host不是同reactor从beauty源码现场编译，此边界如实保留 |
| `/tmp/task55a-receipt-ablation.log:131–157` 与 `/tmp/task55a-ablation.py` | 删SQL receipt store等式后expected2/actual3，1 failure/0 errors；脚本finally恢复原文件。当前源含该等式，最终native同测试通过，构成有效消融 |
| `/tmp/task55a-native-final.log:706,1045,1234,1316–1328` | AppointmentBusiness19 + Schema17 + Receipt6 + Migration3 = **45/45**，0 skips |
| `/tmp/task55a-native-final.log:1017` | 原始JDBC CHECK codepoints含92/39分隔符，确实不是只凭CLI显示转义推测 |
| `/tmp/task55a-reactor-final.log:48,137,287,376,499,535` | api2/core37/web22/channel24/server28 = **113/113**；其中transport5、architecture13 |
| `/tmp/task55a-platform-guards.log:548,596,615,617,620,636–682` | 33 discovered / 28 pass / 2 profile skips / 3已知失败；i18n6、schema-generator1及embedded wiring实际6例通过 |
| `/tmp/task55a-generators-final.log` | 五Python生成器和五V1–V5 JS renderers均current；SQL方言扫描无新增依赖 |

三项全局失败实际输出是 OpsPointsInspectionController FQN、top-level `store` 未登记、`pay_ent_exception_action` schema parser列差异。它们不在本次产品diff；未发现修改ratchet掩盖，仍需既定Task60关口处理，不能把本次局部结果称作全backend通过。

core GuestClaimReceiptIT 是真实MySQL/事务/outbox + controlled member边界；AppointmentBusinessIT同样采用controlled resource/member端口；只有shop-app bridge是真实member消费链。没有把任何上述证据写成owner网络容量、公开HTTP或真实部署验收。

## 无法完整核验的历史证据（不与已确认缺陷重复计数）

- **U1：需求所列claim/真实host等业务测试的实现前RED历史不足。** 可见实现前行为RED仅DTO guestNo；首次完整native失败是共同schema解析setup error，真实host首次提供日志已3/3通过。后续challenge precision RED和receipt消融RED均有效，但不能反向证明登记/claim/真实身份消费链在产品实现前各自跑过预期业务RED。请保留/补交当时已有日志；无法追溯的历史应如实注明，不能现在重复跑未修改套件后声称补回历史TDD顺序。
- **U2：V5“首次执行当时已记录该SHA”的独立原始记录未见。** 已确认首次native真实执行V5、当前文件SHA与manifest/报告一致、V1–V4完全未改；所读首次执行日志没有同时输出V5 SHA或当时内容快照，因此“首次执行后从未变更V5”的时间链仍主要依赖实现报告。不是断言V5被改过。修复I3时须严格按已冻结版本处理。

## 交付判定

现有局部绿灯、有效消融和事务/隐私实现可保留为证据。当前版本因I1完整身份namespace缺口、I2原键重放回归、I3 NULL不变量未强制及I4严格漂移校验盲区，不能批准Task55A，也不应进入55B。修复后只针对实际改动补反例与受影响验证，已有同代码绿灯无需无差别重跑。
