/**
 * 内容契约的守卫（`site/content/README.md`）。
 *
 * 内容从 tsx 挪进 md 之后，原来由 TypeScript 兜住的一部分错误没人管了 ——
 * 少一个 frontmatter 字段、打错一个插值 token、把红线词写进正文，
 * 都不会让构建失败，只会让页面上出现一段不该出现的话。这组断言补上那一层。
 *
 * 渲染在 lib/content.ts（解析与插值）+ components/content/（版式）。
 * 那一侧只认这里断言过的形状 —— 契约破了先在这里红，而不是等构建时才炸。
 */
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const CONTENT = join(import.meta.dirname, "../content");

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".md")) out.push(p);
  }
  return out;
}

/** README 与 sitemap 是给人看的说明，不是页面 */
const META_FILES = new Set(["README.md", "sitemap.md"]);
const ALL = walk(CONTENT);
const PAGES = ALL.filter((f) => !META_FILES.has(relative(CONTENT, f)));

const read = (f: string) => readFileSync(f, "utf8");
const rel = (f: string) => relative(CONTENT, f);

/** 极简 frontmatter 解析：只取顶层 `key: value`，够用且不引依赖 */
function frontmatter(src: string): Record<string, string> | null {
  const m = src.match(/^---\n([\s\S]*?)\n---\n/);
  if (!m) return null;
  const out: Record<string, string> = {};
  for (const line of m[1]!.split("\n")) {
    const kv = line.match(/^([a-zA-Z][\w]*):\s*(.*)$/);
    if (kv) out[kv[1]!] = kv[2]!.trim();
  }
  return out;
}

describe("内容集 · frontmatter", () => {
  it.each(PAGES.map((f) => [rel(f), f] as const))("%s 有完整的 frontmatter", (_name, file) => {
    const fm = frontmatter(read(file));
    expect(fm, "缺 frontmatter（文件必须以 --- 开头）").not.toBeNull();
    for (const key of ["title", "slug", "description", "audience", "goal"]) {
      expect(fm![key], `缺 ${key}`).toBeTruthy();
    }
    expect(fm!.slug, "slug 必须以 / 开头并以 / 结尾").toMatch(/^\/(.*\/)?$/);
  });

  /**
   * frontmatter 里**只许有 `key: value`**。
   *
   * 这条是被一次真实事故换来的：一次批量替换的模式同时命中了正文与
   * `description:` 那一行，于是正文的句子被塞进了 frontmatter。
   * 解析器只挑认识的键，多出来的行静默丢弃 —— 页面照常构建、测试照常绿，
   * 而线上的 meta description 变成了半句废话。
   */
  it.each(PAGES.map((f) => [rel(f), f] as const))("%s 的 frontmatter 没有杂行", (_name, file) => {
    const block = read(file).match(/^---\n([\s\S]*?)\n---\n/)?.[1] ?? "";
    const junk = block
      .split("\n")
      .filter((l) => l.trim() && !/^[a-zA-Z][\w]*:\s*/.test(l));
    expect(junk, `frontmatter 里混进了正文：\n  ${junk.join("\n  ")}`).toEqual([]);
  });

  it("slug 不重复", () => {
    const seen = new Map<string, string>();
    const dupes: string[] = [];
    for (const f of PAGES) {
      const slug = frontmatter(read(f))?.slug ?? "";
      if (seen.has(slug)) dupes.push(`${slug}: ${seen.get(slug)} 与 ${rel(f)}`);
      seen.set(slug, rel(f));
    }
    expect(dupes, `slug 撞车：\n  ${dupes.join("\n  ")}`).toEqual([]);
  });
});

describe("内容集 · 篇幅", () => {
  /**
   * 罗嗦是这套内容最容易犯的错，而它不会让任何东西报错 —— 只会让读者走掉。
   * 上限来自 content/README.md §四；**写超了先删，不要压行**。
   *
   * 只数正文：frontmatter、yaml 版式块、markdown 标记不算进去。
   */
  const LIMITS = { page: 1400, section: 300, sections: 8, faq: 4 };

  function body(src: string): string {
    return src
      .replace(/^---\n[\s\S]*?\n---\n/, "")
      .replace(/```yaml[\s\S]*?```/g, "")
      .replace(/[#>*`|\-\s]/g, "");
  }

  /** 按 `## ` 切小节，返回 [标题, 正文] */
  function sections(src: string): [string, string][] {
    return src
      .split(/^## /m)
      .slice(1)
      .map((chunk) => {
        const nl = chunk.indexOf("\n");
        return [chunk.slice(0, nl).trim(), chunk.slice(nl)] as [string, string];
      });
  }

  it.each(PAGES.map((f) => [rel(f), f] as const))("%s 不超篇幅", (_name, file) => {
    const src = read(file);
    const total = body(src).length;
    expect(total, `正文 ${total} 字，上限 ${LIMITS.page}`).toBeLessThanOrEqual(LIMITS.page);

    const secs = sections(src);
    expect(secs.length, `${secs.length} 个小节，上限 ${LIMITS.sections}`).toBeLessThanOrEqual(
      LIMITS.sections,
    );

    const long = secs
      .map(([t, b]) => [t, body(`---\n---\n${b}`).length] as const)
      .filter(([, n]) => n > LIMITS.section);
    expect(long.map(([t, n]) => `${t}(${n}字)`), `小节超 ${LIMITS.section} 字`).toEqual([]);

    const faq = secs.find(([, b]) => /type:\s*faq/.test(b));
    if (faq) {
      const n = (faq[1].match(/^### /gm) ?? []).length;
      expect(n, `FAQ ${n} 条，上限 ${LIMITS.faq}`).toBeLessThanOrEqual(LIMITS.faq);
    }
  });
});

describe("内容集 · 插值 token", () => {
  /**
   * 与 content/README.md §四 的表一一对应。**加 token 要两处一起加** ——
   * 只写进 md 而渲染器不认识，页面上会出现一串 `{{fee.xxx}}`。
   */
  const KNOWN = new Set([
    "fee.ownedTraffic",
    "fee.platformTraffic",
    "fee.fulfillPerItem",
    "fee.settlePeriodDays",
    "plan.free.stores",
    "plan.pro.stores",
    "plan.chain.stores",
    "plan.pro.staff",
    "plan.chain.staff",
    "plan.trialDays",
    "site.name",
    "site.email",
    "site.merchantEntry",
    "download.consumerAppStore",
    "download.consumerAndroid",
    "download.consumerMiniProgram",
    "download.merchantAndroid",
    "download.merchantAndroidVersion",
  ]);

  it("正文里没有未登记的 token", () => {
    const offenders: string[] = [];
    for (const f of ALL) {
      const used = [...read(f).matchAll(/\{\{([^}]+)\}\}/g)].map((m) => m[1]!.trim());
      const unknown = [...new Set(used)].filter((t) => !KNOWN.has(t));
      if (unknown.length) offenders.push(`${rel(f)}: ${unknown.join(", ")}`);
    }
    expect(offenders, `未登记的 token：\n  ${offenders.join("\n  ")}`).toEqual([]);
  });

  /** 费率与额度**只许**走 token。手写一份，改真源时它不会跟着变 */
  it("费率与额度没有被手写成数字", () => {
    const offenders: string[] = [];
    for (const f of PAGES) {
      const src = read(f)
        .replace(/\{\{[^}]+\}\}/g, " ") // token 本身不算
        .replace(/^---\n[\s\S]*?\n---\n/, " "); // frontmatter 里的 description 允许概述
      const hits = [
        ...(src.match(/\d+(\.\d+)?\s*%/g) ?? []),
        ...(src.match(/[¥￥]\s*\d/g) ?? []),
        ...(src.match(/\d+\s*家门店/g) ?? []),
      ];
      if (hits.length) offenders.push(`${rel(f)}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `手写了费率或额度，请改用 token：\n  ${offenders.join("\n  ")}`).toEqual([]);
  });
});

describe("内容集 · 红线", () => {
  /** content/README.md §六 红线 7 —— 与竞品的同款说法无法区分，等于没说 */
  const BANNED = [
    // 空话：与竞品的同款说法无法区分
    "高效", "赋能", "一站式", "打造", "闭环", "抓手", "全方位", "生态化",
    // 营销腔：**这一类最容易混进来**，因为它读着像「写得好」。
    // 商家要的是陈述句 —— 你有什么、怎么用、多少钱，不需要被说服
    "别处没有", "独家", "超值", "划算", "神器", "爆款", "轻松搞定", "一键搞定", "免费送",
  ];

  it.each(PAGES.map((f) => [rel(f), f] as const))("%s 不出现禁用词", (_name, file) => {
    const hits = BANNED.filter((w) => read(file).includes(w));
    expect(hits, `禁用词：${hits.join("、")}（见 content/README.md §六）`).toEqual([]);
  });

  /**
   * 内部术语。这些词在需求文档与代码里是准确的，**在官网上是把门槛写给商家看** ——
   * 「个人主体免执照」他要先猜「主体」是什么意思，才明白这句话是在说他不用办执照。
   *
   * 左边是我们自己人的说法，右边是店主的说法。改文案时照着右边写。
   */
  const JARGON: [string, string][] = [
    ["主体", "身份 / 商户"],
    ["免执照", "没有营业执照也能开"],
    ["免资质", "不用交材料"],
    ["进件", "交一次收款申请"],
    ["自带客流", "老客扫码进店"],
    ["核销全链路", "扫码核销"],
    ["履约方式", "怎么把东西交到顾客手上"],
    ["经营范围", "能卖到哪几个小区"],
  ];

  it.each(PAGES.map((f) => [rel(f), f] as const))("%s 不出现内部术语", (_name, file) => {
    const src = read(file);
    const hits = JARGON.filter(([w]) => src.includes(w)).map(([w, alt]) => `${w} → 改成「${alt}」`);
    expect(hits, `内部术语：\n  ${hits.join("\n  ")}`).toEqual([]);
  });

  /**
   * 红线 2：一期只记账、线下结算，支付链路本身还是桩 —— 不许出现到账时效。
   * 「提现」只允许出现在明确标了状态的那一行（功能总览的未上线表）。
   */
  it("不承诺到账时效", () => {
    const offenders: string[] = [];
    for (const f of PAGES) {
      for (const line of read(f).split("\n")) {
        const bad =
          /到账|T\s*\+\s*\d/.test(line) ||
          (/提现/.test(line) && !/规划中|对接中/.test(line));
        if (bad) offenders.push(`${rel(f)}: ${line.trim().slice(0, 40)}`);
      }
    }
    expect(offenders, `出现了到账/提现表述：\n  ${offenders.join("\n  ")}`).toEqual([]);
  });

  /** 红线 3：数字未到位之前，一个覆盖数都不放 */
  it("不写覆盖数字", () => {
    const offenders = PAGES.filter((f) => /已覆盖\s*\d|覆盖\s*\d+\s*个社区/.test(read(f))).map(rel);
    expect(offenders, `出现了覆盖数字：${offenders.join(", ")}`).toEqual([]);
  });

  /**
   * 红线 5：模型里只有这四类活动，写第五种商家在后台找不到。
   *
   * **否定句放行**：「模型里没有百分比折扣」是在澄清边界，恰恰是我们要它出现的话。
   * 不放行的话，唯一能过测试的写法是「干脆别提」—— 而那正是这条红线想避免的。
   */
  const NOT_OFFERED = /没有|不支持|不做|无(?!法)/;

  it("不出现模型里没有的活动类型", () => {
    const offenders: string[] = [];
    const words = ["百分比折扣", "N 件优惠", "第二件半价", "折扣券"];
    for (const f of PAGES) {
      const hits = new Set<string>();
      for (const line of read(f).split("\n")) {
        if (NOT_OFFERED.test(line)) continue;
        for (const w of words) if (line.includes(w)) hits.add(w);
      }
      if (hits.size) offenders.push(`${rel(f)}: ${[...hits].join("、")}`);
    }
    expect(offenders, `活动只有券/满减/限时特价/买赠四类：\n  ${offenders.join("\n  ")}`).toEqual(
      [],
    );
  });
});

describe("内容集 · 路由", () => {
  /**
   * 每份 md 都要有页面渲染它。**写了内容却没有路由**是查起来很费劲的一类问题：
   * 文件在、测试过、sitemap 里也有，就是打不开。
   *
   * 六类店铺走 `app/scenarios/[slug]`，由 `generateStaticParams` 从目录列出来，
   * 所以只要文件在 content/scenarios/ 下就一定有路由。
   */
  it("每个页面都有对应的路由文件", () => {
    const APP = join(import.meta.dirname, "../app");
    const missing: string[] = [];
    for (const f of PAGES) {
      const r = rel(f);
      if (r.startsWith("scenarios/") && r !== "scenarios/index.md") continue; // [slug] 动态路由
      const dir = r.replace(/\/index\.md$/, "");
      const route = dir === "home" ? join(APP, "page.tsx") : join(APP, dir, "page.tsx");
      if (!existsSync(route)) missing.push(`${r} → ${relative(APP, route)}`);
    }
    expect(missing, `这些内容没有页面渲染：\n  ${missing.join("\n  ")}`).toEqual([]);
  });
});

describe("内容集 · 站点地图", () => {
  it("每个页面文件都在 sitemap 里出现", () => {
    const map = read(join(CONTENT, "sitemap.md"));
    const missing = PAGES.map((f) => frontmatter(read(f))?.slug ?? "").filter(
      (slug) => slug && !map.includes(slug),
    );
    expect(missing, `这些页在 sitemap.md 里查无此项：${missing.join(", ")}`).toEqual([]);
  });
});
