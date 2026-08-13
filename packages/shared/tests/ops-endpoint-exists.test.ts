// ops-web 调的每个 `/ops/**` 端点，后端必须真的有。
//
// 为什么需要它：ops-web 是**按完整产品设计写的**，后端按优先级分期实现 ——
// 到今天 189 条调用里有 123 条后端没有。这本身不是错，错的是**没人知道是哪 123 条**。
//
// 后果分两种，只有第二种是真的坏：
//   ① 整域未开工（风控、财务、内容…）—— 那个菜单进去整页都是 mock，一眼看得出来
//   ② **域做了却漏了几条** —— 页面看起来是活的，数据也是真的，
//      而某个按钮点下去 404。运营不会怀疑「这个功能还没做」，
//      只会觉得「系统坏了」。上一轮就撞到一个：券的预算列做出来了、
//      闸门也装了，而**改预算的端点不存在**，于是预算永远是 0，闸门永远不生效。
//
// 所以这条守卫是**棘轮**：把今天的缺口固定成基线，只准变少不准变多。
//   · 新出现的未接通调用 → 红，逼加的人回答一句「后端要不要做」
//   · 基线里已经接通的 → 也红，逼人把它从清单里删掉（否则清单几个月后就烂了）
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/**
 * **整域未开工**：后端一条都没实现的域。整域跳过，不逐条登记 ——
 * 逐条列出来是几十行噪音，而它们的状态是一样的：这块功能还没开始做。
 *
 * ⚠️ 一旦某个域开始实现（后端出现第一条），它就必须从这里移走，
 * 剩下的缺口逐条登记进 {@link KNOWN_GAPS} —— 下面那条断言会强制这件事。
 * 这正是最危险的时刻：域"活"了，而漏掉的那几条会变成死按钮。
 */
const UNBUILT_DOMAINS = new Set([
  // 平台治理的后期功能，UI 先行
  "risk-events", "risk-rules", "blacklists", "audit-logs",
  "faqs",
  // 财务与清结算（finance 已开始实现，移到 KNOWN_GAPS 逐条登记）
  "refund-split-backs",
  // 履约与物流
  "fulfillment", "shipments", "freight-templates",
  // 商品中心（现有的是 goods，skus 是另一套更细的视图）
  "skus",
  // 营销的后期部分
  "marketing", "content-slots", "fission-campaigns",
  "push-tasks", "demands", "attribution-rule", "attribution-traces",
  // 组织与权限：2026-08-12 roles 从这里移走 —— 角色配置接到了 /ops/perm/**，
  // 读写六条端点都真的存在。此前它是「页面有完整权限树和保存按钮、
  // 而 /ops/roles 是 404」，缺口诚实登记着，但对着屏幕的人不知道。
]);

/**
 * **域做了却漏了**：这些域后端已经有实现，而这几条没有 —— 页面看着是活的，
 * 点下去 404。每一条都要有个说法，不能只是列在这里。
 */
const KNOWN_GAPS: Record<string, string> = {
  // ── 归档 ──
  // V24 已补齐**四个实体**（券/商家/自提点/活动），见 ai.neargo.shop.archive。
  // 缩到四个而不是原先估的九个，是逐个点过之后的结论
  // （docs/technical/运营端死按钮实测清单.md）：其余的要么按钮本来就禁用，
  // 要么挂在整域未开工的页面上。
  //
  // 社区是**刻意不做**：页面上那个按钮本来就禁用着，还带了正确的解释
  // 「该社区下仍有自提点，请先迁移或停用」—— 前置校验做在了正确的位置，
  // 补一个永远点不到的端点没有意义。
  "POST /ops/communities/{x}/archive": "刻意不做：按钮本就禁用，前置校验在前端且正确",
  "POST /ops/communities/{x}/unarchive": "同上",

  // 员工那三条写接口**已接通**（2026-08-11），按棘轮规矩从这里删掉。
  // 留一句在这里是因为它容易被误当成缺口再补一遍：
  // /scope 存得下但**还没生效** —— 各域查询按数据域裁剪是单独一批。

  // ── 内容与素材：域已接通（2026-08-11），12 条端点齐 ──
  // ⚠️ **审核台目前是空的**：C 端还不能发种草内容、不能提问 ——
  // 平台侧的治理能力有了，生产者还没有。运营端空态文案已写明。
  // 这条不是缺口，是刻意的分批，写在这里免得下一个人以为坏了。
  "POST /ops/contents/posts": "刻意不做：内容由 C 端用户发布，平台端只审不发",

  // ── 系统与配置：四类已接通（2026-08-11），域从 UNBUILT 移出 ──
  // 剩下这两条是同一页面上的、后端确实还没有的能力。
  "GET /ops/env": "环境切换（ops-web 的 system:env:switch 仍标 UNIMPLEMENTED）",
  "GET /ops/params": "平台参数总览页（四类配置各有自己的端点，这个总览还没有）",

  // ── 结算：域刚活（settlements / split-records 的读端点落地）──
  // 这两条写接口还没有。**不是我们这批的活**，登记在这里是因为
  // 域一从 UNBUILT 移出，漏掉的就会变成死按钮 —— 谁接通谁负责删。
  "POST /ops/settlements/{x}/freeze-back": "结算单解冻回滚",
  "POST /ops/settlements/{x}/split": "手动触发分账",

  // ── 财务：域已经活了（自营应付账款那批端点落地），剩下这几条还没有 ──
  // 这正是守卫要盯的那个时刻：域一活，页面就看着能用，而漏掉的会变成死按钮。
  // 后端目前只有 invoice-title 两条（平台开票抬头）。
  "GET /ops/finance/invoices": "进项票列表",
  "POST /ops/finance/invoices/{x}/issue": "开票",
  "POST /ops/finance/invoices/{x}/reject": "驳回开票",
  "GET /ops/finance/withdrawals": "提现列表",
  "POST /ops/finance/withdrawals/{x}/decide": "提现审批",
  "GET /ops/finance/tax-rule": "税率规则",
  "PUT /ops/finance/tax-rule": "改税率规则",

  // ── 支付：域刚活（对账自查落地，recon-diffs 三条 + recon-coverage）──
  // 关单规则是 /orders 的「自动关单」tab：一个读回来能编、有保存按钮的配置表单，
  // 而两条端点都不存在 —— **保存点下去 404，页面却什么都不说**。
  // 这就是守卫盯的那个时刻：域一活，整页不再明显是 mock，
  // 漏掉的这两条就从「这块还没做」变成了「系统坏了」。
  // 谁接通 close-rule 谁把这两行删掉。
  "GET /ops/payments/close-rule": "订单自动关单规则（读）—— 后端未实现，见 /orders?tab=close",
  "PUT /ops/payments/close-rule": "订单自动关单规则（写）—— 同上，保存按钮当前是死的",

  // ── 平台投放场次：后端没有这个领域对象，见 运营端营销列表契约错配.md §3 ──
  // 平台场次：**一期刻意不做**（2026-08-12 决定）。
  // 平台自己出资、跨商家的活动与 mkt_campaign（店铺级、商家出资）不是一回事，
  // 混表会让分账重撞一次 MktCoupon.funder 踩过的墙；真做它是一个完整业务域
  // （新表 + 审批状态机 + 算价接入 + 分账），不是补一个端点。
  // ops-web 侧的契约/mock/http 三层已随之删除 —— 它本来就零个调用方。
  "POST /ops/campaigns": "刻意不做：一期平台不自出资做场次，见 TDD-ops-平台场次",

  // ── 门店经营支持 ──
  "GET /ops/stores/qrcodes": "门店码管理",
  "GET /ops/stores/acquisition": "获客数据",
  "GET /ops/stores/templates": "装修模板",
  "POST /ops/stores/templates": "装修模板",
  "POST /ops/stores/templates/{x}/enabled": "装修模板",

  // ── 其余零星 ──
  "POST /ops/groups/{x}/audit": "拼团审核（现只有 abort）",
  "POST /ops/groups/{x}/status": "改团状态（现只有 abort）",
  "POST /ops/tickets/{x}/assign": "工单指派",
  "POST /ops/tickets/{x}/proxy-actions": "工单里的代客操作",
  "POST /ops/after-sales/{x}/status": "改售后状态（现有 arbitrate 等具名动作）",
  "POST /ops/orders/proxy": "代客下单",
};

function normalize(p: string): string {
  return p.replace(/\$\{[^}]+\}/g, "{x}").replace(/\{\w+\}/g, "{x}");
}

function domainOf(p: string): string {
  return p.split("/")[2] ?? "?";
}

/** ops-web https 层发出去的每一条 `/ops/**` 调用 */
function opsWebCalls(): Set<string> {
  const dir = join(ROOT, "ops-web/lib/api/https");
  const out = new Set<string>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    for (const m of src.matchAll(/client\.(get|post|put|del)\(\s*[`"]([^`"]+)/g)) {
      const path = m[2]!;
      if (path.startsWith("/ops/")) out.add(`${m[1]!.toUpperCase()} ${normalize(path)}`);
    }
  }
  return out;
}

/** 后端真正映射出来的每一条 `/ops/**` 端点 */
function backendEndpoints(): Set<string> {
  const out = new Set<string>();
  const walk = (dir: string) => {
    if (!existsSync(dir)) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) { walk(p); continue; }
      if (!e.name.endsWith("Controller.java")) continue;
      const src = readFileSync(p, "utf8");
      const base = src.match(/@RequestMapping\(\s*"([^"]+)"\s*\)/);
      const prefix = base?.[1] ?? "";
      for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
        const raw = m[2]!;
        const full = raw.startsWith("/ops/") ? raw : (prefix.startsWith("/ops") ? prefix + raw : null);
        if (!full) continue;
        const verb = m[1]!.toUpperCase().replace("DELETE", "DEL");
        out.add(`${verb} ${normalize(full)}`);
      }
    }
  };
  for (const m of ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel"]) {
    walk(join(ROOT, "backend", m, "src/main/java/ai/neargo/shop"));
  }
  return out;
}

describe("运营端端点存在性", () => {
  const calls = opsWebCalls();
  const backend = backendEndpoints();

  it("解析没失效 —— 扫不到东西时不能静默通过", () => {
    expect(calls.size, "一条 ops-web 调用都没扫到，正则或目录变了？").toBeGreaterThan(50);
    expect(backend.size, "一条后端端点都没扫到，正则或目录变了？").toBeGreaterThan(30);
  });

  it("★★★ 新增的调用后端必须有 —— 否则那个按钮点下去就是 404", () => {
    const gaps = [...calls]
      .filter((c) => !backend.has(c))
      .filter((c) => !UNBUILT_DOMAINS.has(domainOf(c.split(" ")[1]!)))
      .filter((c) => !(c in KNOWN_GAPS));

    expect(
      gaps.sort(),
      "这些 ops-web 调用后端没有，且不在已知缺口里 —— **点下去 404**。\n" +
        "  三选一：后端补上；从 ops-web 拿掉；或者登记进 KNOWN_GAPS 并写清楚为什么。\n" +
        "  最坏的是留着不管：页面看起来是活的，运营不会怀疑功能没做，只会觉得系统坏了。",
    ).toEqual([]);
  });

  it("★★ 已经接通的要从清单里删掉 —— 否则清单几个月后就烂了", () => {
    const stale = Object.keys(KNOWN_GAPS).filter((k) => backend.has(k));
    expect(
      stale.sort(),
      "这些已经接通了，从 KNOWN_GAPS 删掉。\n" +
        "  留着的害处是它会让人以为这块还没做，从而绕开它另写一套",
    ).toEqual([]);

    const built = [...UNBUILT_DOMAINS].filter((d) =>
      [...backend].some((b) => domainOf(b.split(" ")[1]!) === d));
    expect(
      built.sort(),
      "这些域后端**已经开始实现了**，不能再整域跳过。\n" +
        "  把它从 UNBUILT_DOMAINS 移走，剩下的缺口逐条登记进 KNOWN_GAPS。\n" +
        "  **这是最危险的时刻**：域「活」了，页面看着能用，而漏掉的那几条会变成死按钮",
    ).toEqual([]);
  });
});
