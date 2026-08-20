import type { Section } from "@/lib/content";
import { Markdown, inline } from "@/lib/markdown";
import { Capabilities } from "@/components/plans/capabilities";
import { SkinShowcase } from "@/components/home/skin-showcase";

/**
 * 小节渲染 —— 按 `type` 分派版式。
 *
 * **未识别的 type 按 prose 渲染**（content/README.md §三）：
 * 加了新 type 而这里没跟上时，页面照常出内容，不是白屏。
 */
export function ContentSection({ s, first }: { s: Section; first: boolean }) {
  switch (s.type) {
    case "hero":
      return <Hero s={s} first={first} />;
    case "cta":
      return <Cta s={s} />;
    case "cards":
      return <Cards s={s} />;
    case "tags":
      return <Tags s={s} />;
    case "faq":
      return <Faq s={s} />;
    case "capability-list":
      return <Capabilities />;
    case "skins":
      return (
        <Wrap s={s}>
          <Head s={s} />
          {s.body && (
            <div className="mt-5">
              <Markdown src={s.body} invert={dark(s)} />
            </div>
          )}
          <SkinShowcase />
        </Wrap>
      );
    default:
      return <Plain s={s} />;
  }
}

/* ── 版式基件 ───────────────────────────────────────────── */

const dark = (s: Section) => s.tone === "dark";

function Wrap({ s, children }: { s: Section; children: React.ReactNode }) {
  const tone = dark(s) ? "bg-ink text-white" : s.tone === "panel" ? "bg-panel" : "";
  return (
    <section id={s.id} className={`py-[clamp(52px,6.5vw,92px)] ${tone}`}>
      <div className="edge mx-auto max-w-[1160px]">{children}</div>
    </section>
  );
}

function Head({ s, as = "h2" }: { s: Section; as?: "h1" | "h2" }) {
  const H = as;
  return (
    <H
      className={`font-display leading-tight font-normal tracking-[-0.01em] ${
        as === "h1" ? "text-[clamp(32px,4.4vw,54px)]" : "text-[clamp(25px,3.2vw,38px)]"
      } ${dark(s) ? "text-white" : ""}`}
    >
      {inline(s.title, "head")}
    </H>
  );
}

/** CTA 按钮组。第一个是主按钮 —— 全站主 CTA 只有「免费开店」一种写法 */
function Actions({ s, invert = false }: { s: Section; invert?: boolean }) {
  if (!s.cta?.length) return null;
  return (
    <div className="mt-7 flex flex-wrap gap-3">
      {s.cta.map((label, i) => {
        const href = s.ctaHref?.[i]?.trim();
        const primary = i === 0;

        /**
         * 链接没配就渲染成**禁用态**，不出死链。
         *
         * 死链点下去毫无反馈，用户以为是页面坏了；而应用商店的地址在上架前本来就没有。
         * 让「没配」是一种看得见的状态，比让它看起来能点更诚实。
         */
        if (!href || href === "#") {
          return (
            <span
              key={label}
              aria-disabled
              className={`inline-flex min-h-11 cursor-not-allowed items-center rounded-full px-6 text-[15px] font-semibold ${
                invert ? "border border-white/30 text-white/55" : "border border-line text-muted"
              }`}
            >
              {label} · 即将上线
            </span>
          );
        }

        const cls = invert
          ? primary
            ? "bg-white text-brand hover:bg-tint hover:text-brand-deep"
            : "border border-white/45 hover:bg-white/12"
          : primary
            ? "bg-brand text-white hover:bg-brand-deep"
            : "border border-line hover:bg-panel";
        return (
          <a
            key={label}
            href={href}
            className={`inline-flex min-h-11 items-center rounded-full px-6 text-[15px] font-semibold transition-colors ${cls}`}
          >
            {label}
          </a>
        );
      })}
    </div>
  );
}

function Link({ s, invert = false }: { s: Section; invert?: boolean }) {
  if (!s.link) return null;
  return (
    <a
      href={s.link[1]}
      className={`mt-7 inline-flex min-h-11 items-center text-[15px] font-semibold ${
        invert ? "text-white hover:text-white/80" : "text-brand-deep hover:text-brand"
      }`}
    >
      {s.link[0]} →
    </a>
  );
}

/**
 * 真机图未产出前的槽位 —— 放示意图会被当成已实现的界面。
 *
 * 比例**从图位说明里读**（`… · 16:11 · 待产出`）：写在 md 里的那个比例就是要的比例，
 * 在组件里再定一次，两处迟早对不上 —— 4:5 的槽位配 16:11 的说明，页面上是一块过高的空白。
 */
const RATIOS: Record<string, string> = {
  "4:5": "aspect-[4/5]",
  "16:11": "aspect-[16/11]",
  "1:1": "aspect-square",
};

function Slot({ note }: { note: string }) {
  const hit = Object.keys(RATIOS).find((r) => note.includes(r));
  return (
    <figure
      className={`grid ${RATIOS[hit ?? "4:5"]} place-items-center rounded-card border border-dashed border-[#cfd2d8] bg-panel p-5 text-center text-[13.5px] text-muted`}
    >
      {note}
    </figure>
  );
}

/* ── 各 type ────────────────────────────────────────────── */

function Hero({ s, first }: { s: Section; first: boolean }) {
  const withImage = Boolean(s.image);
  return (
    <section className={`edge mx-auto max-w-[1160px] py-[clamp(44px,6.5vw,88px)]`}>
      <div
        className={
          withImage
            ? "grid items-center gap-[clamp(30px,5vw,68px)] lg:grid-cols-[1.06fr_0.94fr]"
            : "max-w-[56ch]"
        }
      >
        <div>
          <Head s={s} as={first ? "h1" : "h2"} />
          <div className="mt-[18px]">
            <Markdown src={s.body} />
          </div>
          {s.chips?.length ? (
            <ul className="mt-6 flex flex-wrap gap-2">
              {s.chips.map((c) => (
                <li
                  key={c}
                  className="rounded-full bg-panel px-3.5 py-1.5 text-[13.5px] font-medium"
                >
                  {c}
                </li>
              ))}
            </ul>
          ) : null}
          <Actions s={s} />
        </div>
        {withImage && <Slot note={s.image!} />}
      </div>
    </section>
  );
}

function Cta({ s }: { s: Section }) {
  const brand = s.tone === "brand";
  return (
    <section
      id={s.id}
      className={`py-[clamp(48px,6vw,84px)] ${brand ? "bg-brand text-white" : ""}`}
    >
      <div className="edge mx-auto max-w-[1160px]">
        <h2
          className={`font-display text-[clamp(25px,3.2vw,38px)] leading-tight font-normal ${brand ? "text-white" : ""}`}
        >
          {inline(s.title, "cta")}
        </h2>
        {s.body && (
          <div className={`mt-3.5 ${brand ? "[&_p]:text-white/78 [&_b]:text-white" : ""}`}>
            <Markdown src={s.body} />
          </div>
        )}
        <Actions s={s} invert={brand} />
      </div>
    </section>
  );
}

/** `###` 切卡片 */
function Cards({ s }: { s: Section }) {
  const cards = splitCards(s.body);
  const cols = s.columns ?? 3;
  const invert = dark(s);
  return (
    <Wrap s={s}>
      <Head s={s} />
      <div
        className={`mt-9 grid gap-4.5 ${cols === 2 ? "sm:grid-cols-2" : "sm:grid-cols-2 lg:grid-cols-3"}`}
      >
        {cards.map((c) => (
          <div
            key={c.title}
            className={`rounded-card border px-6 pt-6 pb-6.5 ${
              invert ? "border-white/14 bg-white/5" : "border-line"
            }`}
          >
            <h3 className={`text-[17.5px] font-semibold ${invert ? "text-white" : ""}`}>
              {inline(c.title, `c-${c.title}`)}
            </h3>
            <div className="mt-2.5">
              <Markdown src={c.body} invert={invert} />
            </div>
          </div>
        ))}
      </div>
      <Link s={s} invert={invert} />
    </Wrap>
  );
}

/** 第一段按 `·` 切成词条，其余照常渲染 */
function Tags({ s }: { s: Section }) {
  const [head, ...rest] = s.body.split(/\n\s*\n/);
  const tags = (head ?? "")
    .split(/[·\n]/)
    .map((t) => t.trim())
    .filter(Boolean);
  return (
    <Wrap s={s}>
      <Head s={s} />
      <ul className="mt-8 flex flex-wrap gap-2.5">
        {tags.map((t) => (
          <li
            key={t}
            className="rounded-full border border-line px-4 py-2 text-[15px] font-medium"
          >
            {t}
          </li>
        ))}
      </ul>
      {rest.length > 0 && (
        <div className="mt-7">
          <Markdown src={rest.join("\n\n")} />
        </div>
      )}
      <Link s={s} />
    </Wrap>
  );
}

/**
 * FAQ。开合用原生 `<details>` —— 不为一个手风琴开客户端组件
 * （全站只允许 SkinShowcase 一个，见 lib/constraints.test.ts）。
 * `FAQPage` 结构化数据由**同一份内容**生成，不手抄。
 */
function Faq({ s }: { s: Section }) {
  const items = splitCards(s.body);
  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "FAQPage",
    mainEntity: items.map((it) => ({
      "@type": "Question",
      name: it.title,
      acceptedAnswer: { "@type": "Answer", text: it.body.replace(/[*`]/g, "") },
    })),
  };
  return (
    <Wrap s={s}>
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />
      <Head s={s} />
      <div className="mt-8 border-t border-line">
        {items.map((it) => (
          <details key={it.title} className="group border-b border-line">
            <summary className="flex cursor-pointer list-none items-center justify-between gap-5 py-5 text-[16.5px] font-semibold [&::-webkit-details-marker]:hidden">
              {it.title}
              <svg
                className="size-5 shrink-0 text-muted transition-transform group-open:rotate-45"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                aria-hidden
              >
                <path d="M12 5v14M5 12h14" />
              </svg>
            </summary>
            <div className="pb-6">
              <Markdown src={it.body} />
            </div>
          </details>
        ))}
      </div>
      <Link s={s} />
    </Wrap>
  );
}

function Plain({ s }: { s: Section }) {
  const invert = dark(s);
  return (
    <Wrap s={s}>
      <Head s={s} />
      <div className="mt-6">
        <Markdown src={s.body} invert={invert} />
      </div>
      <Link s={s} invert={invert} />
    </Wrap>
  );
}

/* ── 工具 ───────────────────────────────────────────────── */

function splitCards(body: string): { title: string; body: string }[] {
  return body
    .split(/^### /m)
    .slice(1)
    .map((chunk) => {
      const nl = chunk.indexOf("\n");
      return { title: chunk.slice(0, nl).trim(), body: chunk.slice(nl + 1).trim() };
    });
}
