// 后端要求 reason 的写操作，ops-web 必须真的发出去。
//
// 为什么需要它：`reason == null || reason.isBlank()` 在后端是一条**硬校验**
// （停别人的券、停别人的活动、平台改价 —— 都要写进审计，说不出为什么就不许做）。
// 而 ops-web 这边曾经压根没有这个参数：
//
//   setCouponStatus(couponNo, status)  →  POST /ops/coupons/{no}/status  { status }
//
// 后端收到空 reason，返回 10400。**运营在真后端下点「暂停」必然失败**，
// 而 mock 里没有这条校验，所以演示、单测、E2E 全绿 —— 这个洞一直没人看见。
//
// 判定方式：从后端源码里找出「校验了 reason 非空」的 service 方法，顺着它找到
// 对应的 ops 端点路径，再看 ops-web 的 https 层发那条路径时有没有带 reason。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/** 后端所有 Controller 源码（含路径） */
function controllerSources(): { path: string; src: string }[] {
  const out: { path: string; src: string }[] = [];
  const walk = (dir: string) => {
    if (!existsSync(dir)) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith("Controller.java")) out.push({ path: p, src: readFileSync(p, "utf8") });
    }
  };
  for (const m of ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel"]) {
    walk(join(ROOT, "backend", m, "src/main/java/ai/neargo/shop"));
  }
  return out;
}

/**
 * 请求体里带 reason 字段的 ops 端点。
 *
 * 用 record 上的字段名判定，而不是去追 service 里那句 isBlank ——
 * 后者要跨文件解析调用链，正则做不可靠；而**请求体里有 reason，
 * 就意味着这条路径期待调用方给它**，这已经足够。
 */
function opsEndpointsExpectingReason(): Set<string> {
  const out = new Set<string>();
  for (const { src } of controllerSources()) {
    // record XxxReq(..., String reason, ...)
    const reqWithReason = new Set<string>();
    for (const m of src.matchAll(/record\s+(\w+)\s*\(([^)]*)\)/g)) {
      if (/\bString\s+reason\b/.test(m[2]!)) reqWithReason.add(m[1]!);
    }
    if (!reqWithReason.size) continue;

    // @PostMapping("/ops/...") + 方法签名里用了那个 Req
    const lines = src.split("\n");
    for (let i = 0; i < lines.length; i++) {
      const map = lines[i]!.match(/@(?:Post|Put)Mapping\(\s*"(\/ops\/[^"]*)"/);
      if (!map) continue;
      // 往下找 4 行内的方法签名
      const sig = lines.slice(i, i + 5).join(" ");
      for (const req of reqWithReason) {
        if (new RegExp(`\\b${req}\\b`).test(sig)) out.add(map[1]!);
      }
    }
  }
  return out;
}

/** ops-web 契约里，方法名 → 签名文本（用来解「整体透传一个对象」的情形） */
function contractSignatures(): Map<string, string> {
  const dir = join(ROOT, "ops-web/lib/api/contracts");
  const out = new Map<string, string>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    for (const m of src.matchAll(/^\s*(\w+)\(([^)]*)\):/gm)) out.set(m[1]!, m[2] ?? "");
  }
  return out;
}

/**
 * ops-web https 层：端点路径 → 「这次调用有没有把 reason 发出去」。
 *
 * 有两种写法都算发了，**不能只认第一种**：
 *   client.post(path, { status, reason })   ← body 里字面写着
 *   client.put(path, v)                     ← 整个入参透传，而 v 的类型里有 reason
 *
 * 只认第一种的话，第二种会被误报成「没发 reason」。
 * **一条会误报的守卫比没有守卫更坏** —— 它逼人去改本来就是对的代码，
 * 改完还得回来把守卫调松，两次都在做无用功，而真正的漏网之鱼混在噪音里。
 */
function opsWebSendsReason(): Map<string, boolean> {
  const dir = join(ROOT, "ops-web/lib/api/https");
  const sigs = contractSignatures();
  const out = new Map<string, boolean>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    // methodName: (args) => client.post(`/ops/...`, body),
    for (const m of src.matchAll(
      /(\w+):\s*\([^)]*\)\s*=>\s*\n?\s*client\.(?:post|put)\(\s*[`"]([^`"]+)[`"]\s*,?\s*([\s\S]*?)\)\s*,\n/g,
    )) {
      const [, method, path, body] = m;
      const inBody = /\breason\b/.test(body ?? "");
      // 整体透传：body 是个光秃秃的标识符，去契约签名里看它带不带 reason
      const passthrough = /^\s*\w+\s*$/.test(body ?? "") && /\breason\b/.test(sigs.get(method!) ?? "");
      out.set(path!, inBody || passthrough);
    }
  }
  return out;
}

/** `/ops/coupons/${no}/status` → `/ops/coupons/{x}/status`，与后端的 {pathVariable} 对齐 */
function normalize(path: string): string {
  return path.replace(/\$\{[^}]+\}/g, "{x}").replace(/\{\w+\}/g, "{x}");
}

describe("运营端写操作的 reason", () => {
  it("★★★ 后端要 reason 的端点，ops-web 必须真的发 reason", () => {
    const backend = opsEndpointsExpectingReason();
    // 一个都没扫到 = 正则失效，不能静默通过
    expect(backend.size, "一个要 reason 的端点都没扫到，正则或目录变了？").toBeGreaterThan(0);

    const calls = opsWebSendsReason();
    const byNormalized = new Map<string, boolean>();
    for (const [p, sends] of calls) byNormalized.set(normalize(p), sends);

    const missing: string[] = [];
    for (const ep of backend) {
      const sends = byNormalized.get(normalize(ep));
      if (sends === undefined) continue; // ops-web 还没接这条 —— 那是另一回事
      if (!sends) missing.push(ep);
    }

    expect(
      missing.sort(),
      "这些端点后端要求 reason（停别人的东西要说得出为什么，写进审计），\n" +
        "  而 ops-web 发过去的 body 里没有它 —— **后端会直接 10400**。\n" +
        "  失败方式很隐蔽：mock 没有这条校验，所以演示、单测、E2E 全绿，\n" +
        "  只有运营在真后端上点那个按钮时才会发现按不动：",
    ).toEqual([]);
  });
});
