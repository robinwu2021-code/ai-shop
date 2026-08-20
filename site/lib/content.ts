/**
 * 内容加载 —— 官网页面的正文来自 `site/content/*.md`，不再写在 tsx 里。
 *
 * 只在**构建期**跑（`output: "export"` 下全部页面预渲染），所以可以直接读文件系统，
 * 不需要把 md 打进 bundle，也不需要运行时解析。
 *
 * 格式契约见 content/README.md §三；由 lib/content.test.ts 逐页断言。
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { SETTLE } from "@shared/utils/constants";
import { money } from "@shared/utils/money";
import { PLANS } from "./plans";
import { site } from "./site.config";

const CONTENT = join(process.cwd(), "content");

export type Section = {
  /** `##` 标题 */
  title: string;
  /** 锚点 id。yaml 里显式给 `id:`，否则由 type 兜底（faq → #faq） */
  id?: string;
  /** yaml 块里的 `type`，缺省 prose */
  type: string;
  /** 墨底等变体 */
  tone?: string;
  columns?: number;
  cta?: string[];
  ctaHref?: string[];
  chips?: string[];
  /** `link: [文字, 路径]` —— 小节末尾的次级出口 */
  link?: [string, string];
  /** 图位说明，真机图未产出前渲染成虚线槽位 */
  image?: string;
  /** 其余正文（markdown） */
  body: string;
};

export type Page = {
  title: string;
  slug: string;
  description: string;
  sections: Section[];
};

/** 插值表 —— 与 content/README.md §五 的表一一对应 */
function tokens(): Record<string, string> {
  const pct = (r: number) => `${+(r * 100).toFixed(2)}%`;
  const plan = (code: string) => PLANS.find((p) => p.code === code)!;
  return {
    "fee.ownedTraffic": pct(SETTLE.commissionRate.MERCHANT_OWNED),
    "fee.platformTraffic": pct(SETTLE.commissionRate.PLATFORM),
    "fee.fulfillPerItem": money(SETTLE.fulfillFeePerItemMinor, "CNY"),
    "fee.settlePeriodDays": String(SETTLE.periodDays),
    "plan.free.stores": String(plan("FREE").storeQuota),
    "plan.pro.stores": String(plan("PRO").storeQuota),
    "plan.chain.stores": String(plan("CHAIN").storeQuota),
    "plan.pro.staff": String(plan("PRO").staffQuota),
    "plan.chain.staff": String(plan("CHAIN").staffQuota),
    "plan.trialDays": String(plan("PRO").trialDays),
    "site.name": site.name,
    "site.email": site.contact.email,
    "site.merchantEntry": site.entry.merchant,
    /* 上架前全为空 —— 空值是合法的，Actions 会把它渲染成禁用态而不是死链 */
    "download.consumerAppStore": site.download.consumerAppStore,
    "download.consumerAndroid": site.download.consumerAndroid,
    "download.consumerMiniProgram": site.download.consumerMiniProgram,
    "download.merchantAndroid": site.download.merchantAndroid,
    "download.merchantAndroidVersion": site.download.merchantAndroidVersion,
  };
}

/**
 * 未知 token **直接抛错**，不静默留空。
 * 页面上一处空白是所有错误里最难被发现的那种 —— 它看起来像排版留白。
 */
function interpolate(src: string): string {
  const table = tokens();
  return src.replace(/\{\{([^}]+)\}\}/g, (_m, raw: string) => {
    const key = raw.trim();
    const v = table[key];
    if (v === undefined) {
      throw new Error(`content: 未登记的插值 token {{${key}}} —— 见 content/README.md §五`);
    }
    return v;
  });
}

/** 极简 yaml：只认 `key: value` 与 `key: [a, b]`，够这套契约用，不引依赖 */
function parseProps(block: string): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const line of block.split("\n")) {
    const m = line.match(/^([a-zA-Z][\w]*):\s*(.*)$/);
    if (!m) continue;
    const [, key, raw] = m;
    const val = raw!.trim();
    if (val.startsWith("[") && val.endsWith("]")) {
      out[key!] = val
        .slice(1, -1)
        .split(",")
        .map((s) => s.trim().replace(/^["']|["']$/g, ""))
        .filter(Boolean);
    } else {
      out[key!] = val.replace(/^["']|["']$/g, "");
    }
  }
  return out;
}

function parse(src: string, file: string): Page {
  const fm = src.match(/^---\n([\s\S]*?)\n---\n/);
  if (!fm) throw new Error(`content: ${file} 缺 frontmatter`);
  const meta = parseProps(fm[1]!) as Record<string, string>;

  const sections = src
    .slice(fm[0].length)
    .split(/^## /m)
    .slice(1)
    .map((chunk): Section => {
      const nl = chunk.indexOf("\n");
      const title = chunk.slice(0, nl).trim();
      let rest = chunk.slice(nl + 1);

      const yaml = rest.match(/^\s*```yaml\n([\s\S]*?)```\n/);
      const props = yaml ? parseProps(yaml[1]!) : {};
      if (yaml) rest = rest.slice(yaml[0].length);

      // 小节之间的 `---` 分隔线不是内容
      const body = rest.replace(/^\s*---\s*$/gm, "").trim();

      const link = props.link as string[] | undefined;
      const type = (props.type as string) ?? "prose";
      return {
        title,
        id: (props.id as string) ?? (type === "faq" ? "faq" : undefined),
        type,
        tone: props.tone as string | undefined,
        columns: props.columns ? Number(props.columns) : undefined,
        cta: props.cta as string[] | undefined,
        ctaHref: props.ctaHref as string[] | undefined,
        chips: props.chips as string[] | undefined,
        image: props.image as string | undefined,
        link: link && link.length >= 2 ? [link[0]!, link[1]!] : undefined,
        body,
      };
    });

  return {
    title: meta.title!,
    slug: meta.slug!,
    description: meta.description!,
    sections,
  };
}

/** `home` / `scenarios/fresh` → 解析好的页面 */
export function loadPage(path: string): Page {
  const file = path.includes("/") ? `${path}.md` : `${path}/index.md`;
  const abs = join(CONTENT, file);
  return parse(interpolate(readFileSync(abs, "utf8")), file);
}
