// 带归属列的表，必须登记进数据域表册 —— 否则那张表**永远不过滤**。
//
// `DataScopeRegistration` 的类注释里写着「由 `DataScopeCoverageTest` 校验
// 『带归属列的表都已注册』」。**那个测试不存在**，全仓搜不到 ——
// 于是这条被当成「有人在管」的规则，实际上一次都没被执行过：
// 65 张表带归属列（迁移里能扫到的），注册了 7 张。
//
// 缺登记不报错，表现是**看得见不该看见的**：一个只配了某社区的运营，
// 打开自提点列表看到全城的点，而页面、日志、报错里没有任何异常。
// 反过来漏登记某个维度也一样致命（见 DataScopeRegistration 类注释第 2 条）：
// handler 生成 `1=0` 而不是放行，那类主体整页空白。
//
// **这条守卫从出生就是红的**，所以用棘轮：记下今天的欠账数，只许降不许升。
// 它现在的作用不是逼人马上补完 56 张 —— 那是《权限体系-Java方案与调用链》§5.1
// 那个「几天量级」的独立批次 —— 而是**让新建的表不能再悄悄加入这个缺口**。
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const MIGRATION_DIR = join(ROOT, "backend/shop-app/src/main/resources/db/migration");
const REGISTRATION = join(
  ROOT,
  "backend/shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java",
);

/**
 * 归属列。**这几个名字就是数据域的全部词汇** ——
 * `DataScopeRegistration` 里映射的也是它们（entity_no→MERCHANT、user_no→SELF…）。
 */
const OWNER_COLUMNS = [
  "entity_no",
  "merchant_no",
  "user_no",
  "community_no",
  "pickup_no",
  "store_no",
  /*
   * **`receiver_no` 也是归属列。** V97 把 `msg_message.user_no` 改成了这个名字
   * （站内信的收件人），V162 又把表改名成 `notify_message`。
   *
   * <p>不认它的后果不是「多报一张」，是**少看见两张**：`notify_message`（9 处查询）
   * 与 `notify_push_token` 是按人分的表，未登记，而这道闸从头到尾没提起过它们 ——
   * 一张表在守卫的视野之外，比它红着更难被发现。
   *
   * <p>补上它记录值 63 → 65。**这是诚实的涨**：不是新欠了债，是原来就欠着、闸门看不见。
   */
  "receiver_no",
];

/**
 * 不需要数据域的表，**每一条都要写清为什么**。
 *
 * 判据是「这张表的行被别人看到，会不会泄露或误导」：
 * 字典与配置类的答案是不会（它们本来就该全局可见），
 * 业务数据的答案永远是会。
 */
const EXEMPT: Record<string, string> = {
  /*
   * ── 2026-08-31：入驻申请单 —— **登记它反而会造出一片空白** ──
   *
   * 它有 entity_no 与 user_no 两个归属列，看着该登记。但入驻审核这条链路上
   * **审核通过之前商家还不存在**：`entity_no` 只在「无证照先开店」那一条路上
   * 被预填（认领 PENDING_LICENSE 占位主体），正常入驻的申请单在通过前是空的。
   *
   * 登记 MERCHANT → entity_no 的后果：配了商家域的运营打开审核队列，
   * 那些还没有主体的申请单**一条都不匹配** —— 队列是空的，且不报错。
   * 而入驻审核本来就是平台级的活（merchant:merchant:read 只在 BD 角色上）。
   *
   * SELF → user_no 更不对：那是**申请人**的用户号，不是运营自己的。
   *
   * 这条记在这里而不是留在待办里，是因为「待办」隐含着「将来该补」，
   * 而这张表的结论是**不该补**。
   */
  mch_entity_apply: "审核通过前商家还不存在，entity_no 多数为空；登记会让审核队列空白",

  /*
   * ── 2026-08-31：归因两张 —— **没有任何查询会经过数据域** ──
   *
   * `mkt_attribution_log` 全仓只有写（`log(...)` 里 insert），**一处 select 都没有**：
   * 它是留痕表，今天还没有人读它。
   * `mkt_attribution` 只有一处 `find(userNo)`，按用户号取自己那一行，跑在 C 端链路上。
   *
   * 登记它们不会改变任何行为 —— 与 mbr_reach_log 那条「登记但当下无效果」不同的是：
   * 那张表**将来加一条按时间的列表查询就会生效**，而这两张表的形状决定了
   * 它们的读法只会是「按 user_no 取一行」。没有列表，就没有可裁的集合。
   *
   * 什么时候要回来改这条：运营端出现「归因日志检索」那类页面时 —— 那是一条
   * 不带归属参数的全量列表，届时按第三批的做法登记 + 去绕过。
   */
  mkt_attribution: "只有 find(userNo) 一处读，按用户号取一行；没有列表就没有可裁的集合",
  mkt_attribution_log: "留痕表，全仓只有写、一处 select 都没有",

  /*
   * ── 2026-08-31：权限解析自己读的两张 —— **登记它们是循环依赖** ──
   *
   * `RolePermResolver` 读这两张表算出「这个角色有哪些权限码」，
   * 而**数据域是在同一次登录里、由同一份角色算出来的**（LiveIdentityResolver）。
   * 读角色表的那一刻，这个人的 DataScopeSpec 还不存在。
   *
   * 而且它们本来也不该按主体裁：角色定义是平台级的配置，
   * 「配了商家域的运营看到的角色列表变少」没有任何意义。
   * entity_no 那一列是给**商家自定义角色**用的（mch_role 才是商家侧那张），
   * 这两张的 OPS 侧行 entity_no 恒空。
   */
  sys_role: "权限解析器自己读它算角色权限，而数据域由同一份角色算出来 —— 循环依赖",
  sys_role_point: "同上，RolePermResolver 的另一半",

  /*
   * ── 门店公告审核：**旧队列，已经不再收新记录** ──
   *
   * `MerchantStoreServiceImpl` 里写着：服务范围改走 mch_service_area 之后，
   * 旧的待审队列（kind=SERVICE_AREA）不会再有新行。今天只剩公告（NOTICE）走它，
   * 而公告审核队列在运营端没有入口 —— 一处 selectList 都扫不到读的路径。
   *
   * 什么时候回来改：公告审核在运营端有页面的那天。
   */
  mch_store_audit: "门店公告/服务范围的旧待审队列，运营端无入口，读路径扫不到",

  /*
   * ── 媒体资产：**我登记过一次，被测试打回来了** ──
   *
   * 判据看着全都符合：运营端有「可回收清单」这条全量列表，读它的几处一处绕过都没有，
   * 表上有 entity_no。登记之后 MediaScanFlowTest 立刻 4 红、MediaUploadFlowTest 1 红。
   *
   * 原因是 `MediaScanner.scan()` **是平台完整性任务**：它要扫全量才能判断
   * 哪些图已经没人引用了。裁掉一部分 = 只对账一部分，而**部分对账的结果会被当成
   * 「对过了」** —— 比不对账更危险（与 InventoryBackfillServiceImpl 在
   * SCOPE_BYPASS_OK 里的理由是同一条）。
   *
   * 要接的话不是登记整张表，而是**把运营端那条可回收列表单独接上**
   * （给它一条带数据域的查询，扫描器那条保持全量）—— 那是另一个改动，
   * 不是补一行 register。
   */
  sys_media_asset: "MediaScanner 是平台完整性任务，要扫全量；登记会让「部分对账」被当成对过了",

  // 日志/流水类：归属列是「谁干的」而不是「谁的数据」，运营查审计本就要跨主体看
  sys_op_log: "运营操作审计，跨主体查是它的用途",
  mch_staff_log: "商家员工授权审计，同上",
  ord_status_log: "订单状态流水，随订单一起被过滤",
  // 关联表：本身不被直接查，总是从主表 join 进来
  mch_entity_community: "主体×社区关联，从主表进入",
  // 方案 v4（2026-08-22）：channel 的两张子表。总是随 mch_fulfillment_channel
  // （已登记 MERCHANT）一起查，自己不当检索入口
  mch_channel_pickup: "自提路×取货点关联，从 channel 主表进入",
  mch_channel_area: "channel×范围项关联，从 channel 主表进入",
  /*
   * ── 2026-08-29：会话与登录日志六张 ──
   *
   * 豁免理由不是「不重要」，是**这个机制根本够不到它们**：
   * 数据域是 MyBatis-Plus 的 `DataPermissionInterceptor`，只重写走 mapper 的 SQL。
   * 这六张表**没有 Java 实体**，只被 `SessionDao` / `LoginLogDao` 用 `JdbcClient`
   * 原生 SQL 访问（`WHERE token_hash = ?` / `WHERE user_no = ?` / GC 的 DELETE）。
   * 登记它们**一行行为都不会变** —— 那种登记比不登记更坏：清单上多一条，
   * 让人以为这张表已经被自动过滤保护着了。
   *
   * 而且它们本身就带着显式的 user_no 条件（查某人的会话/登录史），
   * 唯一跨用户的两处是**按用途如此**：撤销轮询与失败登录流（风控要看爆破）。
   */
  usr_session: "无实体，只走 SessionDao 的原生 SQL（按 token_hash 单点查），拦截器够不到",
  mch_session: "同 usr_session",
  ops_session: "同 usr_session",
  usr_login_log: "无实体，只走 LoginLogDao 原生 SQL；跨用户那一处是失败登录流，风控用途",
  mch_login_log: "同 usr_login_log",
  ops_login_log: "同 usr_login_log",
  /*
   * 幂等键。查询是 `WHERE idem_key = ? AND endpoint = ?` 的单点查，不是检索入口；
   * user_no 是「谁发起的」而不是「谁的数据」。
   *
   * **登记它反而有害**：加上归属过滤之后，幂等查询在锚点对不上的会话里恒 miss ——
   * 于是每一次重试都被当成新请求。重复下单比「看得见不该看见的」更贵。
   */
  sys_idempotent: "按 (idem_key, endpoint) 单点查；登记会让幂等恒 miss，重试变重复下单",
};

/** 迁移文件里每张表的列名（不依赖 DDL 解析器 —— 它自己另有守卫） */
function tableColumns(): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>();
  if (!existsSync(MIGRATION_DIR)) return out;
  /*
   * **按版本号排，不是按文件名排。** `.sort()` 是字典序：`V162` 会排在 `V97` 前面。
   * 只解析 CREATE 时看不出来（一张表只建一次），而下面要重放改名/删表 ——
   * 顺序错了就会「先把表改名，再去改一张已经不存在的表的列」。
   */
  const byVersion = readdirSync(MIGRATION_DIR)
    .filter((x) => x.endsWith(".sql"))
    .sort((a, b) => (Number(/^V(\d+)/.exec(a)?.[1] ?? 0) - Number(/^V(\d+)/.exec(b)?.[1] ?? 0))
      || a.localeCompare(b));
  for (const f of byVersion) {
    const src = readFileSync(join(MIGRATION_DIR, f), "utf8");
    for (const m of src.matchAll(
      /CREATE TABLE(?: IF NOT EXISTS)?\s+(\w+)\s*\(([\s\S]*?)\n\)\s*ENGINE/gi,
    )) {
      const cols = new Set<string>(
        [...m[2]!.matchAll(/^\s{4}(\w+)\s+\w/gm)].map((c) => c[1]!.toLowerCase()),
      );
      out.set(m[1]!.toLowerCase(), cols);
    }
    // 后续迁移加的列也算 —— 一张表可能是先建后补归属列的
    for (const m of src.matchAll(/ALTER TABLE\s+(\w+)\s+ADD COLUMN\s+(\w+)/gi)) {
      const t = m[1]!.toLowerCase();
      if (!out.has(t)) out.set(t, new Set());
      out.get(t)!.add(m[2]!.toLowerCase());
    }
    /*
     * **改列名**（`ALTER TABLE t RENAME COLUMN a TO b`）。
     * 不重放的话，这张表在模型里仍然带着那个**早就不存在的列名** ——
     * 而归属列判定看的正是列名：V97 把 `msg_message.user_no` 改成了 `receiver_no`，
     * 不重放就会一直按 `user_no` 把它报成「带归属列却没登记」。
     */
    for (const m of src.matchAll(
      /ALTER TABLE\s+(\w+)\s+RENAME COLUMN\s+(\w+)\s+TO\s+(\w+)/gi,
    )) {
      const cols = out.get(m[1]!.toLowerCase());
      if (cols?.delete(m[2]!.toLowerCase())) {
        cols.add(m[3]!.toLowerCase());
      }
    }
    /*
     * **改表名**与**删表**。守卫此前两个都不认，于是它报的是**早已不存在的表**：
     * V162 把 6 张 `msg_*` 改成了 `notify_*`，而清单里躺着 msg_message /
     * msg_subscribe / msg_ticket 三条。
     *
     * 「报错了对象」和「漏报」一样坏 —— 下一个人会照着它去登记一张不存在的表，
     * 而真正该登记的那张（改名之后的）从头到尾没被提起过。
     */
    for (const m of src.matchAll(/ALTER TABLE\s+(\w+)\s+RENAME TO\s+(\w+)/gi)) {
      const from = m[1]!.toLowerCase();
      const to = m[2]!.toLowerCase();
      if (out.has(from)) {
        out.set(to, out.get(from)!);
        out.delete(from);
      }
    }
    for (const m of src.matchAll(/DROP TABLE(?:\s+IF EXISTS)?\s+(\w+)/gi)) {
      out.delete(m[1]!.toLowerCase());
    }
  }
  return out;
}

/** 已登记进表册的表名 */
function registered(): Set<string> {
  if (!existsSync(REGISTRATION)) return new Set();
  const src = readFileSync(REGISTRATION, "utf8");
  return new Set(
    [...src.matchAll(/registry\.register\(\s*"(\w+)"/g)].map((m) => m[1]!),
  );
}

describe("数据域表册覆盖", () => {
  const cols = tableColumns();
  const reg = registered();

  it("两个源都读得到（正则失效时不要静默通过）", () => {
    if (!existsSync(MIGRATION_DIR)) return; // 只装前端的场景
    expect(cols.size, "一张建表都没扫到，迁移目录或写法变了").toBeGreaterThan(30);
    expect(reg.size, "DataScopeRegistration 里一条登记都没扫到 —— 写法变了？")
      .toBeGreaterThan(3);
  });

  it("★★★ 带归属列的表要么登记、要么写明为什么不用（棘轮：只许降不许升）", () => {
    if (!cols.size) return;

    const missing = [...cols.entries()]
      .filter(([t, c]) => OWNER_COLUMNS.some((o) => c.has(o)))
      .filter(([t]) => !reg.has(t) && !EXEMPT[t])
      .map(([t, c]) => `${t}（${OWNER_COLUMNS.filter((o) => c.has(o)).join("/")}）`)
      .sort();

    /*
     * 立此守卫时的欠账。**这个数只许降不许升。**
     *
     * 为什么不是 0：补登记一张表不是加一行配置就完事 —— 一张表被注册后，
     * <b>所有可能访问它的主体维度都要登记齐</b>，漏一个那类主体就整页空白
     * （DataScopeRegistration 类注释第 2 条）。而且现在数据域是每请求现算的，
     * 补一张表的影响立刻作用到所有在线的人。所以它属于 §5.1 那个独立批次。
     *
     * 补完一张就把这个数减一。
     *
     * ── 2026-08-30：56 → 62，而这 6 张是**悄悄涨上来的** ──
     *
     * 那天发现：这里写着 56，而 known-guard-failures.txt 里记着 `@<=62`。
     * 两处数字打架时，**宽的那个说了算** —— 包装器把这条测试当成
     * 「已知红着、观测值 62」放行，于是它红了不知多久没人看见，
     * 而这里的 56 早已没有任何约束力。
     *
     * 这 6 张不是新建的表（V260 之后的迁移一张都没建它们），
     * 是既有表后来加了归属列。把数字提到 62 不是放宽 —— **今天真实生效的
     * 闸门本来就是 62**，改的只是「让它写在人会读的那个地方」。
     *
     * 配套：这条测试**不许再出现在 known-guard-failures.txt 里**，见下一条断言。
     */
    /*
     * ── 2026-08-30 62 → 59：结算域三张（withdraw / purchase_invoice / settle_invoice）──
     * 挑它们的判据是「运营端有一条全量列表读它」，而**登记的同时去掉了那三条
     * 队列上的 executeWithoutScope** —— 只登记不去绕过是第一批白干过的那一轮。
     */
    const PENDING = 49;

    expect(
      missing.length,
      `带归属列却没登记数据域的表：${missing.length} 张（记录值 ${PENDING}）——\n`
        + "  没登记 = 这张表**永远不过滤**。表现是「看得见不该看见的」：\n"
        + "  只配了某社区的运营，打开列表看到全城的数据，而没有任何报错。\n"
        + "  新增的表不许加入这个缺口：补一行 registry.register(...)，\n"
        + "  或者确实不需要（字典/配置/审计）就写进 EXEMPT 并说明理由。\n"
        + "  补完一张把 PENDING 减一。\n  "
        + missing.join("\n  "),
    ).toBeLessThanOrEqual(PENDING);
  });

  /**
   * **这条测试的棘轮只能有一个数，而它必须是上面那个 `PENDING`。**
   *
   * 2026-08-30 的教训：`PENDING` 写着 56，而 `known-guard-failures.txt` 里
   * 同时记着 `@<=62`。包装器把这条当「已知红着」放行，于是
   * **宽的那个说了算**，`PENDING` 那个 56 变成了纯装饰 —— 它看起来像在管着什么。
   *
   * 这是「同一事实存了两份」的又一例，而这一份格外坏：
   * 两份都是数字、都叫欠账、都写着「只许降」，**从任何一处都看不出还有另一处**。
   *
   * 所以这里把「不许有第二份」变成一条断言。它红的时候只有两条正路：
   *   1. 把那几张表真的登记了，`PENDING` 减下来，删掉基线那行；
   *   2. 确实要记账，就抬 `PENDING`（写明为什么），删掉基线那行。
   *
   * 两条路都以「删掉基线那行」结尾 —— 这正是想要的：**账记在测试里**，
   * 因为那里才列得出是哪几张表；基线文件只有一个数字，读的人无从下手。
   */
  it("★★ 这条测试不许出现在 known-guard-failures.txt 里 —— 两个数字打架时，宽的那个说了算", () => {
    const baseline = join(ROOT, "packages/shared/known-guard-failures.txt");
    if (!existsSync(baseline)) return;

    const offending = readFileSync(baseline, "utf8")
      .split("\n")
      .filter((l) => !l.trim().startsWith("#"))
      .filter((l) => l.includes("data-scope-coverage.test.ts"));

    expect(
      offending,
      "known-guard-failures.txt 里有这条测试的记账行，而这个文件里已经有一个 PENDING。\n"
        + "  两处数字一旦不一致，**宽的那个说了算**，另一个就变成纯装饰 ——\n"
        + "  2026-08-30 实际发生过：这里写 56、基线写 62，中间那 6 张没人知道。\n"
        + "  修：把欠账记在这个文件的 PENDING 上（那里列得出是哪几张表），\n"
        + "  然后删掉基线里的这一行：\n  "
        + offending.join("\n  "),
    ).toEqual([]);
  });
});
