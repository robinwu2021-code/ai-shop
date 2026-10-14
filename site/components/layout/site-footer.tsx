import { Logo } from "@/components/brand/logo";
import { site } from "@/lib/site.config";

const COLS = [
  {
    title: "店铺类型",
    links: [
      { href: "/scenarios/fresh/", label: "生鲜果蔬" },
      { href: "/scenarios/convenience/", label: "便利日用" },
      { href: "/scenarios/service/", label: "到店服务" },
      { href: "/scenarios/pickup-point/", label: "社区自提点" },
      { href: "/scenarios/chain/", label: "多门店" },
      { href: "/scenarios/own-brand/", label: "自有品牌" },
    ],
  },
  {
    title: "产品",
    links: [
      { href: "/how-it-works/", label: "怎么开店" },
      { href: "/#plans", label: "套餐" },
      { href: "/capabilities/", label: "功能总览" },
      { href: "/mini-program/", label: "自有小程序" },
      { href: "/download/", label: "下载 App" },
    ],
  },
  {
    title: "法务与联系",
    links: [
      { href: "/privacy/", label: "隐私政策" },
      { href: "/terms/", label: "用户协议" },
      { href: `mailto:${site.contact.email}`, label: site.contact.email },
    ],
  },
];

export function SiteFooter() {
  return (
    <footer className="border-t border-line">
      <div className="edge mx-auto grid max-w-[1160px] gap-9 pt-14 pb-10 sm:grid-cols-2 lg:grid-cols-[1.6fr_1fr_1fr_1.1fr]">
        <div>
          <Logo size={36} />
          <p className="mt-4 max-w-[34ch] text-sm text-muted">
            面向社区门店的线上经营系统 —— 开店更容易，经营更赚钱。
          </p>
          <a
            href={site.entry.merchant}
            className="mt-5 inline-flex min-h-11 items-center rounded-full bg-brand px-5 text-[15px] font-semibold text-white transition-colors hover:bg-brand-deep"
          >
            免费开店
          </a>
        </div>
        {COLS.map((c) => (
          <div key={c.title}>
            <h4 className="mb-3.5 text-[11.5px] font-semibold tracking-[0.14em] text-muted uppercase">
              {c.title}
            </h4>
            <ul className="grid gap-2.5 text-[14.5px]">
              {c.links.map((l) => (
                <li key={l.label}>
                  {/* py-1 把点按高度撑到 26px —— 纯文字行只有 18px，过不了 WCAG 2.5.8 的 24×24 */}
                  <a href={l.href} className="block py-1 text-ink hover:text-brand">
                    {l.label}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      <div className="edge mx-auto max-w-[1160px]">
        {/* 法律主体必须是全称，不能用品牌名代替（见视觉设计方案 §4.3） */}
        <div className="flex flex-wrap gap-5 border-t border-line py-5 pb-10 text-[13px] text-muted">
          <span>© 2026 {site.legal.company}</span>
          {/* 备案号**必须可点**到工信部查询页 —— 只写号不给链接不合规 */}
          <a
            href={site.legal.icpUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="hover:text-brand"
          >
            {site.legal.icp}
          </a>
          {/* 公安联网备案：拿到「粤公网安备 …号」才渲染。
              只有 32 位数据码时不渲染 —— 页面上没有可读的备案号，挂了等于没挂，
              反而给人「已经挂好了」的错觉。 */}
          {site.legal.policeNo && (
            <a
              href={`https://beian.mps.gov.cn/#/query/webSearch?code=${site.legal.policeCode}`}
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-brand"
            >
              {site.legal.policeNo}
            </a>
          )}
          <span>{site.domain}</span>
          <span>
            {site.legal.parentBrand}（{site.legal.parentDomain}）旗下业务
          </span>
        </div>
      </div>
    </footer>
  );
}
