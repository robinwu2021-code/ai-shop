import { Logo } from "@/components/brand/logo";
import { site } from "@/lib/site.config";

/**
 * 导航即站点地图（content/sitemap.md §三）。
 *
 * 「支持哪些店」排第一 —— 它回答商家的第一个问题。
 * 「功能总览」**不进顶栏**：它是附录，进了顶栏就会把宣传站变成说明书。
 */
const NAV = [
  { href: "/scenarios/", label: "支持哪些店" },
  { href: "/how-it-works/", label: "怎么开店" },
  { href: "/#plans", label: "套餐" },
  { href: "/mini-program/", label: "自有小程序" },
  { href: "/download/", label: "下载 App" },
];

/**
 * 顶栏。移动端菜单用 **checkbox + peer 纯 CSS** 开合，不用 JS ——
 * 为一个汉堡菜单加 "use client" 会把整棵子树推进客户端 bundle，
 * 而全站只允许一个客户端组件（SkinShowcase）。副作用是它在水合前就能用。
 *
 * 主 CTA 全站统一是**「免费开店」**四个字。同一个动作写成「立即入驻」「马上开店」
 * 「免费开店」三种，商家会以为它们通向不同的地方。
 */
export function SiteHeader() {
  return (
    <header className="sticky top-0 z-30 border-b border-line bg-white/88 backdrop-blur-[10px]">
      <input type="checkbox" id="nav-open" className="peer sr-only" aria-hidden />
      <nav className="edge relative mx-auto flex h-[74px] max-w-[1160px] items-center gap-4 lg:gap-9">
        <a href="/" aria-label={`${site.name} 首页`}>
          <Logo />
        </a>

        <div className="ml-auto hidden gap-7 text-[15px] lg:flex">
          {NAV.map((n) => (
            <a key={n.href} href={n.href} className="whitespace-nowrap hover:text-brand">
              {n.label}
            </a>
          ))}
        </div>

        <div className="ml-auto hidden gap-2.5 lg:flex">
          <a
            href={site.entry.merchant}
            className="inline-flex min-h-11 items-center rounded-full bg-brand px-5 text-[15px] font-semibold text-white transition-colors hover:bg-brand-deep"
          >
            免费开店
          </a>
        </div>

        <label
          htmlFor="nav-open"
          role="button"
          tabIndex={0}
          aria-label="菜单"
          className="ml-auto grid size-11 cursor-pointer place-items-center rounded-[11px] border border-line hover:bg-panel lg:hidden"
        >
          <svg
            width="20"
            height="20"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            aria-hidden
          >
            <path d="M3 6h18M3 12h18M3 18h18" />
          </svg>
        </label>
      </nav>

      <div className="edge hidden border-t border-line bg-white pb-3.5 peer-checked:block lg:!hidden">
        {NAV.map((n) => (
          <a
            key={n.href}
            href={n.href}
            className="block border-b border-line py-3 text-[15.5px] last:border-b-0"
          >
            {n.label}
          </a>
        ))}
        {/* 窄屏把主 CTA 放进菜单里 —— 折叠状态下顶栏只剩汉堡，没有它就点不到开店 */}
        <a
          href={site.entry.merchant}
          className="mt-3.5 inline-flex min-h-11 items-center rounded-full bg-brand px-5 text-[15px] font-semibold text-white"
        >
          免费开店
        </a>
      </div>
    </header>
  );
}
