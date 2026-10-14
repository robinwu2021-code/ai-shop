import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * HX 方章 + 字标。
 *
 * ⚠️ **字形与弧线不在这里画** —— 从 `brand/logo/mark-red.svg` 读进来。
 *
 * 上一版把路径手抄进了这个文件，注释还写着「与 brand/logo/hx-square.svg 逐字节同源」；
 * 而品牌 2026-08-20 换代（扁弧母题 `arc_flat 0.65` + 自绘 H/X 同源字形）之后，
 * 那个源文件连名字都没了，官网页头还在画**旧的正圆弧和旧 X**，
 * favicon 却已经是新的 —— 同一个页面上两套标识，没有任何东西会报错。
 *
 * 现在只有一处几何真源：`brand/build.py` 的参数 → `brand/logo/mark-red.svg` → 这里。
 * 改形状请改 build.py 后重跑 `python3 brand/build.py`。
 *
 * 服务端组件：构建期读文件，产物是静态 HTML（全站只允许 SkinShowcase 一个客户端组件）。
 */

/** 品牌产物里的红。整份 SVG 只用这一个色，按档位替换成对应的字色 */
const BRAND_RED = /#E1251B/gi;

/** 方章圆角 = 0.275 × 边长（品牌规范 §02；64 → 17.6）。app 图标不烘焙圆角，网页要自己套 */
const RADIUS = 17.6;

const MARK_SVG = join(process.cwd(), "..", "brand", "logo", "mark-red.svg");

/** 取 `<svg>` 里的内容：弧线 + H + X 三条路径 */
const GLYPHS = readFileSync(MARK_SVG, "utf8")
  .replace(/^[\s\S]*?<svg[^>]*>/, "")
  .replace(/<\/svg>[\s\S]*$/, "");

type Tone = "brand" | "reverse" | "ink";

const PLATE: Record<Tone, { plate: string; glyph: string }> = {
  brand: { plate: "#e1251b", glyph: "#ffffff" }, // 主色底 + 反白字，白压红 4.69 AA
  reverse: { plate: "#ffffff", glyph: "#e1251b" }, // 反白底 + 红字，用于深色区块
  ink: { plate: "#17181a", glyph: "#ffffff" }, // 单色稿 / 商标黑白稿
};

export function Mark({
  size = 40,
  tone = "brand",
  className,
}: {
  size?: number;
  tone?: Tone;
  className?: string;
}) {
  const { plate, glyph } = PLATE[tone];
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      className={className}
      role="img"
      aria-label="虹选 · 好物"
    >
      <rect width="64" height="64" rx={RADIUS} fill={plate} />
      <g dangerouslySetInnerHTML={{ __html: GLYPHS.replace(BRAND_RED, glyph) }} />
    </svg>
  );
}

export function Logo({
  size = 40,
  tone = "brand",
  invert = false,
}: {
  size?: number;
  /** 方章的配色 */
  tone?: Tone;
  /** 字标压在深色底上时反白 */
  invert?: boolean;
}) {
  return (
    <span className="flex items-center gap-[11px]">
      <Mark size={size} tone={tone} />
      <span className="grid gap-px leading-tight">
        <b
          className={`text-[18px] font-bold tracking-[0.02em] ${invert ? "text-white" : "text-ink"}`}
        >
          虹选 · 好物
        </b>
        <small
          className={`text-[11.5px] font-semibold tracking-[0.16em] ${invert ? "text-white/60" : "text-muted"}`}
        >
          HX MALL
        </small>
      </span>
    </span>
  );
}
