# Task55A fix round 1 独立复审

**Spec: PASS**
**Quality: Approved**
**I1 / I2 / I3 / I4：全部 ADDRESSED**
本轮未关闭产品发现：**Critical 0 / Important 0 / Minor 0**。U1/U2保留为已明确披露的历史证据限制，不伪称历史证明已补齐。

## 审查范围与身份

- FIX_BASE：`de8409ea40b22029e66b1d71033a7bca035fd0b2`。
- HEAD：`59d49ed1bb24fa73596ded81cd9cacf1bd1f783a`，实际 `git rev-parse HEAD` 一致。
- 工作树：`/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`。
- 输入：原审查 `/tmp/review-bm04-55a.md`、当前55A brief、`task-55a-fix-design.md`、实现报告 fix appendix、supplied `review-de8409ea4..59d49ed1b.diff`（37文件），以及其实际日志和V6控制器预执行记录。
- 本次只复审I1–I4及fixdiff新引入问题，没有重做原258KB全面审计，没有重跑Maven/生成器/容器，没有生成新diff，没有修改repo/HEAD/index，没有spawn agents。只读源码、日志、git对象，并独立计算当前冻结文件hash；报告写入/tmp。

## I1 — ADDRESSED：新 intent 首次证明改用严格共享能力

`ReceptionIdentity.customer` 已改为 `CustomerIdentityPort.resolveClaimReference`。新默认能力明确抛出 unavailable，旧functional provider不会回退到宽松 `resolveReference`。

真实 `MemberCustomerIdentityPortImpl.resolveClaimReference` 在调用票据服务前核：当前MERCHANT、当前tenant/actor与context一致、kind仅MCH/USR、当entity/store的当前CUSTOMER权限；随后调用已有 `requireGuestClaimReference`。继续核到后者实际比较票据tenant/entity/store/actorNo/actorRealm/actorKind、有效期与活动USER。旧resolveReference行为未改。

本域仍先运行此严格方法，再guest.verify，再beginClaim；因此REGISTERED分支不会先保存跨kind意图，已CLAIMED快路径也不能跳过首次严格证明。既有合法intent的原键成功重放、当前同店员工get终态补回执路径未被改成重验过期原票据或冒充原actor。

新增typed route确实贯通：SPI声明 → 机器token入口 → 当前DelegatedIdentity上下文 → 真实owner方法；remote比对完整request echo及非空canonical USER，无legacy fallback。`InternalServiceResponses` 对BizException保留400/403/404/409等实际status，入口兜底503不将明确拒绝伪装成成功。请求内ReferenceQuery原有toString脱敏仍生效；未新增proof存储或跨域表访问。

证据：host参数化四例同时覆盖 MCH→USR / USR→MCH 和 REGISTERED / CLAIMED，每个都先确认两种身份各有CUSTOMER；拒绝后本域intent、receipt、相关outbox均为0。测试还显式保留旧resolver兼容行为。新owner独立测试覆盖上下文不符、异店和撤权；旧合法claim/当前经理恢复仍通过。实际RED为4 assertion failures/0 errors，最终host 8/8通过。

## I2 — ADDRESSED：原键重放先读取持久操作，并保留当前权限

`AppointmentServiceImpl.createAppointment` 现在先核当前BOOKING_MANAGE、preview entity/store与完整actor归属；显式guest另外调用 `ReceptionAccess.customer` 核当前CUSTOMER，且不为重放查询最新guest身份。financial guard、完整序列化requestHash和原key查找在live guest规范化之前。

命中本actor命名空间的原操作后，以原operationNo进入coordinator，沿持久规范身份恢复。仅没有原operation时才执行原guest与preview一致性检查。查找仍使用既有tenant/entity/store/realm/kind/actor/key范围并比较requestHash，未通过重放绕过变参检查；既有prepare中的重复查找/锁内校验继续保留。

新增两例分别证明成功和PENDING未知结果后guest变CLAIMED，原请求重放均得到原appointmentNo/operationNo，最终仅一个root和operation。还覆盖同key变guest拒绝、全部权限撤销拒绝，以及**BOOKING_MANAGE仍为true而仅CUSTOMER撤销时也拒绝**。原“创建前claim造成preview身份变化应冲突”用例保留并在最终21例预约套件执行。

最初修复过程中CUSTOMER单独撤销的反例确实再出现2 assertion failures/0 errors；加回显式权限门后2/2通过，最终native全套也通过。这是本轮有效补强，未遗留该中间缺口。

## I3 — ADDRESSED：V6追加总布尔约束，V1–V5保持冻结

V6只向既有 `bty_reception_operation` 添加 `ck_bty_reception_operation_5`，非空分支明确要求 `identity_kind IS NOT NULL`；NULL/非空不再得到可放行的UNKNOWN。NULL/NULL、USER+非空、GUEST+非空合法；其余半空、非法kind、空ref拒绝。保留V5旧CHECK，不重写旧数据。

严格manifest新增V6固定SHA读取、唯一资源所有者检查、精确目标表/约束名及add-only校验，再把新CHECK合并到原表合同。原列/索引/其它CHECK校验保持；ready/persistence/writeguard一致要求6，仍27私表。JSON、renderer、字典增量及fixture同步，不修改41表/160项原目标。

native新增测试覆盖四种NULL组合与非法值；有效V5行升级后值与V5 checksum不变；脏V5行使升级失败且原行仍在、V6无成功历史、再次migrate/validate继续失败；删除新guard不能通过validate。没有clean/repair或假baseline修复数据。

独立冻结核验：

- 本次逐个比较V1–V5在FIX_BASE和HEAD的git blob，全部IDENTICAL。
- 读取控制器 `.superpowers/sdd/beauty-backend/v6-preexecution-root-record.json`：UTC `2026-10-22 10:22:25`，含实际SQL全文和SHA。
- 当前V6实际bytes与记录中的SQL逐字相同，SHA独立计算为 `220ed777c56a77c22cf835f2a5bcc42ccb41cc98a4348831df06e0e404b89f20`，与记录及manifest相同。
- `/tmp/task55a-fix-native-first.log:95–96` 首次记录V6执行为本地18:25:35，即UTC10:25:35，晚于控制器预执行记录。随后OOM不改变“V6已执行并必须冻结”的事实。

此次V6有可核对的执行前记录，不应据此倒推原V5首次执行的历史hash时序已获证明。

## I4 — ADDRESSED：仅在词法识别后处理identifier引号

`BeautyCheckExpression.canonical` 已去掉全局删除反引号和全局正则替换。lexer将反引号标识符、普通字符串、有限charset-prefixed escaped ASCII enum分别识别。只对已识别标识符剥去边界引号；literal内容保留。charset转义仅完整token内处理，不能重写另一literal中的文本；不支持的escaped literal失败关闭。

parser测试证明普通 `RECEIVED` 与含内容反引号的 `RECE\`IVED` 不等、quoted identifier等价、unsupported escaped形式拒绝。native实际ALTER CHECK引入内容反引号后严格validate拒绝。大小写、未知函数/不完整语法等既有检查保留。实际初始parser/native反例RED在日志中可见，最终parser8/8、schema22/22通过。

## 实际证据核对

以下为读取原日志确认的结果，没有在复审中重跑：

| 日志 | 实际结果 |
|---|---|
| `/tmp/task55a-fix-i1-red.log:644–688` | 4 tests / 4 failures / 0 errors；REGISTERED错误留下intent，CLAIMED错误未抛异常 |
| `/tmp/task55a-fix-i234-red.log:75–278` | 5 tests / 5 failures / 0 errors：两种预约重放CONFLICT、半空行被接受、parser与native漏判literal漂移 |
| `/tmp/task55a-fix-customer-red.log:146–188` | 2 tests / 2 failures / 0 errors，仅CUSTOMER撤权仍未抛异常 |
| `/tmp/task55a-fix-customer-green.log:76` | 中间constructor fixture编译错误；未计业务RED/GREEN |
| `/tmp/task55a-fix-customer-green-final.log:146–161` | 权限补强后2/2、0 skips |
| `/tmp/task55a-fix-native-first.log:2431` | 52 tests / 0 assertion failures / 47 errors；环境异常，不计通过 |
| `/tmp/task55a-fix-oom-events.jsonl` | 指定自建MySQL容器OOM事件，epoch1792664905，对应UTC10:28:25，与连接失败时序相符 |
| `/tmp/task55a-fix-native-green.log:773,1244,1439,1524–1539` | Appointment21 + Schema22 + Receipt6 + Migration3 = **52/52**，0 failures/errors/skips |
| `/tmp/task55a-fix-reactor-green.log:48,118,252,340,463,499` | api2/core38/web22/channel25/server28 = **115/115**，0 skips；含parser8、identity transport6、beauty architecture13 |
| `/tmp/task55a-fix-host-green.log:645–679` | 真实member消费链 **8/8**，0 skips |
| `/tmp/task55a-fix-owner-green.log:610,681–715` | 机器HTTP2 + legacy member reference8 = **10/10**，0 skips |
| `/tmp/task55a-fix-shared-install.log:145–148`、`core-install.log:289–292` | shared strict SPI与真实owner均安装成功 |
| `/tmp/task55a-fix-reactor-green.log:126,502` | beauty-core jar安装成功；18:35:01完成，早于18:36:12完成的host测试 |
| `/tmp/task55a-fix-generation.log` | 五Python生成器、六V1–V6 renderer、SQL方言检查共12命令通过；没有新增方言依赖 |

日志中的RED失败类型与源码新增断言对应；本轮9项初始反例RED及后续2项权限RED与报告所述修复顺序一致。它们是本轮缺陷修复证据，不是对原始实现前TDD历史的重构。

## fixdiff新增问题检查与边界

未发现本轮新增的Critical/Important/Minor问题。具体复核了：共享新SPI默认失败关闭与旧方法兼容、typed route认证/响应完整性、当前权限与idempotency顺序、装配依赖与ready6、严格manifest封闭合并、生成真源同步、测试未删除原安全断言、测试容器参数与清理范围。

环境调整已经当前brief/控制器裁决授权。脚本仅在 `BEAUTY_TEST_DISK=1` 时改用自建容器内 `/var/lib/beauty-test` 和64MB buffer/performance_schema关闭；默认tmpfs保留，未加host/shared mount。仍使用cached pull-never、随机localhost端口、TCP readiness与finally按UUID容器名删除，并清理该容器anonymous volumes。没有修改生产配置或既存容器的命令。OOM回合不作为行为RED或GREEN；此后最终有完整native通过日志。

范围继续限制为55A：本轮新增的是为I1所需共享严格身份内部route，不是提前交付接待公开wire；未进入签到、工单或recovery scanner。真实member服务/票据/存储通过native host覆盖；机器HTTP使用受控身份owner验证协议，core资源owner仍是controlled fixture。没有将两者合称完整远程owner网络/容量/生产部署验收。

三项原有全局guard失败仍为既定边界，本轮未重跑全backend或无关Task54套件，也未修改ratchet将其转绿。此次批准不代表整个BM04、公开HTTP或Task60验收完成。

## U1/U2证据限制处置

- **U1：保留为历史限制，报告表述已纠正。** 仍没有原始claim/host实现前业务RED日志；本轮有真实新RED/GREEN，不能宣称补回过去顺序。原报告fix appendix明确承认，无需通过重复跑已通过套件制造历史。
- **U2：保留为历史限制，新增迁移冻结链已补强。** 原V5首次执行时独立hash快照仍缺失；当前V5及V1–V5相对FIX_BASE完整不变已经独立核验。V6的控制器预执行SQL/hash/时间与实际后续执行及当前bytes一致。未发现改写冻结旧迁移的证据。

这两项不是尚未修复的产品缺陷；以明确保留证据边界的方式收口。本次Spec PASS / Quality Approved仅表示I1–I4已解决，且在限定fixdiff中没有发现新增阻断问题。
