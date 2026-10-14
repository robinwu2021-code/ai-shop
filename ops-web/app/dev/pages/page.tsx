"use client";

// ⚠️ 与 /dev/ui 同一条注意事项：Tailwind 4 会把源文件里的字符串与注释当成类名候选去扫。
// 在这个目录里写「类名示例」时别写带通配符、又长得像类名的文本 —— 会生成一条非法 CSS，
// 让 globals.css 整个编译失败、全站白屏。举例一律举真实存在的那一档。
//
// ── 这页是什么 ─────────────────────────────────────────────────────────────
// **真实业务页的规范体检。** dev-only 工具页：不在 lib/nav.ts 里、不进阶段门禁、
// 只能靠 URL 直达（/dev/pages）。
//
// 为什么要有它：规范的执行此前有两张网，而两张网的孔开在同一处 ——
//
//   · `lib/design-tokens.test.ts` 扫**源码文本**。它拦得住「写没写」，
//     拦不住「算出来是多少」：圆角实际渲染成几 px、数字列到底右不右对齐、
//     焦点环展开后有没有 offset，都要在 DOM 上量。
//   · `/dev/ui` 在 DOM 上量，但它量的是**组件画廊** —— 那一页把 23 个组件的
//     各种状态摆出来，扫的是这些样例。21 个真实业务页一个都不进。
//
// 于是「组件本身合规」与「页面上合规」是两件事，而后者从来没被量过。
// 页面可以给组件传 className 覆盖、可以在组件外面自己拼 div、可以把两个字阶
// 挂同一个元素上 —— 这些在画廊里一个都看不到。
//
// 做法：把每个路由装进同源 iframe，等它渲染完，对 `[data-shell="body"]`
// （页体，不含顶栏与导航）跑与 /dev/ui 完全同一套 `audit()`。
// 同源所以拿得到 iframe 的 document 与 computed style，不需要额外依赖。
//
// **它不是闸门，也当不了闸门**：跑起来要一个真浏览器，而这个仓库的 pre-push
// 全是 node/python。它替掉的是「手点 124 个页面挨个看」，不是替掉守卫。
//
// 已知边界（诚实记在这里）：
// 1. 只看得到**当前渲染出来的**节点 —— 加上一遍浮层体检：
//    带 `aria-haspopup` 的声明式浮层（下拉、说明气泡）会被逐个打开、量、关掉。
//    **抽屉与确认弹窗仍在射程外**：它们是受控的，打开它的是一个普通 button，
//    DOM 上没有标记能把它与「删除」区分开 —— 要靠调用点显式登记，见 auditOverlays。
// 2. class 字符串扫描判断不了「这个 h-9 是不是刻意的」，结论是**线索**不是判决。
// 3. 顶栏与左侧导航每页都一样，只在第一条路由上扫一次，别重复计入。
// 4. 依赖登录态：iframe 与本页同源，直接复用你当前的账号与数据域。
//    换个角色重跑，看到的页面集合与内容都会变 —— 这是特性不是缺陷。
import * as React from "react";
import { audit, auditOverlays, groupFindings, type Finding, type Grouped } from "../ui/audit";
import { runContrastSweep, groupFails, type SweepFail } from "../ui/sweep";
import { fmtRatio } from "../ui/color";
import { THEMES } from "@/lib/stores/theme";
import { DataTable } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { NAV, isLeafLocked } from "@/lib/nav";
import { cn } from "@/lib/utils";

/** 待扫路由。直接从 NAV 推导 —— 手抄一份的话，新增页面永远不会被扫到。 */
function routes(withTabs: boolean): { href: string; label: string; section: string }[] {
  const out: { href: string; label: string; section: string }[] = [];
  const seen = new Set<string>();
  for (const s of NAV) {
    if (s.soon) continue;
    const leaves = s.children ?? [];
    if (!leaves.length) {
      if (!seen.has(s.href)) { seen.add(s.href); out.push({ href: s.href, label: s.label, section: s.label }); }
      continue;
    }
    for (const l of leaves) {
      if (l.soon || isLeafLocked(l)) continue;
      const key = withTabs ? l.href : l.href.split("?")[0];
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ href: key, label: l.label, section: s.label });
    }
  }
  return out;
}

interface PageResult {
  href: string;
  label: string;
  section: string;
  /** 扫到的节点数。**0 说明这一页压根没渲染出来**，与「没有违规」是两回事 */
  scanned: number;
  /**
   * 这一页打开并量过的浮层数（下拉、说明气泡等声明式的那些）。
   *
   * **它是浮层这一层的分母**：全站合计为 0 时，「浮层里没有违规」等于没说 ——
   * 分不清是真的干净，还是一个都没打开过。抽屉与确认弹窗不在其中，见 `auditOverlays`。
   */
  overlays: number;
  findings: Finding[];
  /** 页面自己渲染了错误块（DataTable 的 error 分支或 ErrorState） */
  errored: boolean;
  ms: number;
}

/** 等 iframe 里的页面安定下来：先等 load，再等骨架屏消失，最多等 cap 毫秒。 */
async function settle(win: Window, cap = 4000): Promise<void> {
  const t0 = Date.now();
  // 骨架屏用的是 animate-pulse（ui/misc.tsx#Skeleton）。它消失 = 首屏数据到了。
  // 等不到也要往下走：有的页面本来就没有查询，永远不会出现骨架。
  while (Date.now() - t0 < cap) {
    await new Promise((r) => setTimeout(r, 120));
    const doc = win.document;
    if (!doc.querySelector('[data-shell="body"]')) continue;
    if (!doc.querySelector(".animate-pulse")) {
      // 再宽限一下，让 React 把数据渲染上去。
      // ⚠️ 这里**不能用 requestAnimationFrame**：标签页切到后台时 rAF 整个不触发，
      // 整轮扫描就停在原地不动 —— 而界面上只是进度条不走，看不出是卡住还是慢。
      // 实测过一次：面板隐藏后停在 2/21，一切回前台立刻继续。
      await new Promise((r) => setTimeout(r, 80));
      return;
    }
  }
}

export default function DevPagesAudit() {
  const [withTabs, setWithTabs] = React.useState(true);
  const [running, setRunning] = React.useState(false);
  const [done, setDone] = React.useState(0);
  const [results, setResults] = React.useState<PageResult[]>([]);
  const [shell, setShell] = React.useState<Finding[] | null>(null);
  const [contrast, setContrast] = React.useState<SweepFail[] | null>(null);
  const [contrastRunning, setContrastRunning] = React.useState(false);
  const [contrastDone, setContrastDone] = React.useState(0);
  const frameRef = React.useRef<HTMLIFrameElement>(null);

  const list = React.useMemo(() => routes(withTabs), [withTabs]);

  const run = async () => {
    const frame = frameRef.current;
    if (!frame || running) return;
    setRunning(true);
    setResults([]);
    setShell(null);
    setDone(0);

    const acc: PageResult[] = [];
    for (const [i, r] of list.entries()) {
      const t0 = Date.now();
      // load 也要有上限：某条路由若始终不触发 load（拼错的 href、被重定向吃掉），
      // 没有超时的话整轮扫描会永远停在那一条上，而进度条看着只是"慢"。
      await new Promise<void>((resolve) => {
        let settled = false;
        const finish = () => {
          if (settled) return;
          settled = true;
          frame.removeEventListener("load", finish);
          clearTimeout(timer);
          resolve();
        };
        const timer = setTimeout(finish, 8000);
        frame.addEventListener("load", finish);
        frame.src = r.href;
      });
      const win = frame.contentWindow;
      if (!win) continue;
      await settle(win);

      const body = win.document.querySelector<HTMLElement>('[data-shell="body"]');
      const res = body ? audit(body, "subtree", r.href) : { findings: [], scanned: 0, focusable: 0 };
      /*
       * 再把页面上声明式的浮层逐个打开量一遍。
       * 上面那次 `audit()` 量的是「此刻渲染出来的节点」—— 下拉菜单、说明气泡里的内容
       * 当时不在 DOM 里，一次都没被量到过。判据与边界见 `auditOverlays` 的注释。
       */
      const ov = await auditOverlays(win.document, r.href);
      acc.push({
        ...r,
        scanned: res.scanned,
        overlays: ov.opened,
        findings: [...res.findings, ...ov.findings],
        // 错误块与空态要分开看：前者是接口挂了，后者是真没数据
        errored: !!body?.querySelector("[data-audit-error]"),
        ms: Date.now() - t0,
      });

      // 顶栏与导航每页都一样，只在第一条路由上扫一次
      if (i === 0) {
        const nav = win.document.querySelector<HTMLElement>('[data-shell="nav"]');
        const head = win.document.querySelector<HTMLElement>("header");
        const f: Finding[] = [];
        if (nav) f.push(...audit(nav, "subtree", "layout/SecondaryNav+Rail").findings);
        if (head) f.push(...audit(head, "subtree", "layout/Header").findings);
        setShell(f);
      }

      setDone(i + 1);
      setResults([...acc]);
    }
    setRunning(false);
  };

  /**
   * 对比度体检：5 皮肤 × 明暗 = 10 组，逐条路由在 iframe 里跑。
   *
   * **只跑 21 个入口路由**（关掉「含子页签」那个开关的那份名单）。
   * 124 × 10 = 1240 次重绘要十几分钟，而子页签与入口共用同一套组件与同一份 token ——
   * 多出来的那 100 条路由几乎不会带来新的颜色组合。这是**刻意的取舍**，
   * 写在这里免得下一个人以为它覆盖了全部路由。
   */
  const runContrast = async () => {
    const frame = frameRef.current;
    if (!frame || contrastRunning) return;
    setContrastRunning(true);
    setContrast(null);
    setContrastDone(0);
    const entries = routes(false);
    const acc: SweepFail[] = [];
    for (const [i, r] of entries.entries()) {
      await new Promise<void>((resolve) => {
        let settled = false;
        const finish = () => { if (!settled) { settled = true; frame.removeEventListener("load", finish); clearTimeout(t); resolve(); } };
        const t = setTimeout(finish, 8000);
        frame.addEventListener("load", finish);
        frame.src = r.href;
      });
      const win = frame.contentWindow;
      if (!win) continue;
      await settle(win);
      const body = win.document.querySelector<HTMLElement>('[data-shell="body"]');
      if (!body) continue;
      const res = await runContrastSweep(THEMES.map((t) => t.key), undefined,
        { doc: win.document, root: body, scope: "subtree", label: r.href });
      acc.push(...res.fails);
      setContrastDone(i + 1);
      setContrast([...acc]);
    }
    setContrastRunning(false);
  };

  const all = results.flatMap((r) => r.findings);
  const grouped = groupFindings(all);
  const blank = results.filter((r) => r.scanned === 0);
  const errored = results.filter((r) => r.errored);
  const worst = [...results].sort((a, b) => b.findings.length - a.findings.length).slice(0, 12);

  return (
    <div className="p-6 space-y-5">
      <header className="space-y-1">
        <h1 className="txt-title">真实页面规范体检</h1>
        <p className="txt-body text-muted-foreground max-w-3xl">
          把每个路由装进同源 iframe，对页体跑与 <code className="txt-caption">/dev/ui</code> 同一套
          扫描。它量的是「算出来的样式」，源码守卫量不了这些；反过来它也拦不住
          「压根没写」——两种手段各有盲区，不能互相替代。
        </p>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={run} loading={running}>
          {running ? `扫描中 ${done}/${list.length}` : `开始体检（${list.length} 个路由）`}
        </Button>
        <Button variant="outline" onClick={runContrast} loading={contrastRunning} disabled={running}>
          {contrastRunning ? `对比度 ${contrastDone}/21` : "对比度体检（21 入口 × 10 组）"}
        </Button>
        <label className="flex items-center gap-2 txt-body text-muted-foreground">
          <input
            type="checkbox"
            className="focus-ring"
            checked={withTabs}
            disabled={running}
            onChange={(e) => setWithTabs(e.target.checked)}
          />
          含子页签（关掉只扫 21 个入口）
        </label>
      </div>

      {/* 扫描用的 iframe。给足宽度，否则窄屏下表格会横向滚，量到的是另一种布局。
          刻意不隐藏：跑的时候看得见它在翻页，比一个转圈的进度条可信。 */}
      <div className="overflow-hidden rounded-card border border-border" style={{ height: 260 }}>
        <iframe
          ref={frameRef}
          title="被扫页面"
          className="origin-top-left border-0"
          style={{ width: 1440, height: 900, transform: "scale(.42)" }}
        />
      </div>

      {results.length > 0 && (
        <>
          <section className="grid gap-3 sm:grid-cols-4">
            <Stat label="扫过的路由" value={`${results.length} / ${list.length}`} />
            <Stat label="节点总数" value={results.reduce((n, r) => n + r.scanned, 0).toLocaleString()} />
            {/*
              浮层这一层的分母。为 0 时上面那句「没扫出线索」对浮层不成立 ——
              分不清是真干净还是一个都没打开过。
            */}
            <Stat
              label="打开量过的浮层"
              value={results.reduce((n, r) => n + r.overlays, 0)}
              tone={results.length && results.reduce((n, r) => n + r.overlays, 0) === 0 ? "warn" : undefined}
            />
            <Stat label="线索条数" value={all.length} tone={all.length ? "warn" : "ok"} />
            <Stat
              label="没渲染出来的页"
              value={blank.length}
              tone={blank.length ? "bad" : "ok"}
            />
          </section>

          {blank.length > 0 && (
            <p className="txt-body text-[var(--destructive-ink)]">
              这几页扫到 0 个节点 —— 空集不是「全都合规」，是没渲染出来：
              {blank.map((b) => b.href).join("、")}
            </p>
          )}
          {errored.length > 0 && (
            <p className="txt-body text-[var(--warning-ink)]">
              这几页渲染了错误块（接口没通，量到的样式不完整）：
              {errored.map((b) => b.href).join("、")}
            </p>
          )}

          <Section title={`按规则归并（${grouped.length} 类）`}>
            {grouped.length === 0 ? (
              <Notice>
                页体上没有扫出线索。注意这只说明「当前渲染出来的节点」合规 ——
                声明式浮层（下拉、说明气泡）已经会被打开量过；**抽屉与确认弹窗仍不在射程内**。
              </Notice>
            ) : (
              <DataTable
                columns={[
                  { header: "规则", cell: (g: Grouped) => g.rule, className: "whitespace-normal" },
                  { header: "归属", cell: (g: Grouped) => <span className="txt-caption text-muted-foreground">{g.comp}</span> },
                  { header: "处数", cell: (g: Grouped) => g.count, numeric: true },
                  { header: "实测值", className: "whitespace-normal",
                    cell: (g: Grouped) => (
                      <span className="txt-caption text-muted-foreground">{g.details.slice(0, 3).join(" · ")}</span>
                    ) },
                ]}
                rows={grouped}
                rowKey={(g) => `${g.comp}||${g.rule}`}
                empty="这一轮没有归并出任何线索。空集与「全都合规」不是一回事，先看上面的节点总数与浮层数是不是 0。"
              />
            )}
          </Section>

          <Section title="线索最多的页">
            <DataTable
              columns={[
                { header: "路由", cell: (r: PageResult) => <span className="font-mono txt-caption">{r.href}</span> },
                { header: "功能", cell: (r: PageResult) => (
                  <span className="txt-caption text-muted-foreground">{r.section} · {r.label}</span>
                ) },
                { header: "节点", cell: (r: PageResult) => r.scanned, numeric: true },
                { header: "线索", numeric: true,
                  cell: (r: PageResult) => (
                    <span className={cn(r.findings.length > 0 && "text-[var(--warning-ink)]")}>
                      {r.findings.length}
                    </span>
                  ) },
                { header: "耗时", numeric: true,
                  cell: (r: PageResult) => <span className="txt-caption text-muted-foreground">{r.ms}ms</span> },
              ]}
              rows={worst}
              rowKey={(r) => r.href}
              empty="还没有扫过任何路由 —— 点上面那个按钮开始。"
            />
          </Section>

          {shell && (
            <Section title={`外壳（顶栏 + 导航，只扫一次）—— ${shell.length} 条`}>
              {shell.length === 0 ? (
                <Notice>外壳没有扫出线索。</Notice>
              ) : (
                <ul className="rounded-card border border-border p-3 txt-body space-y-1">
                  {groupFindings(shell).map((g, i) => (
                    <li key={i}>
                      {g.rule} · <span className="text-muted-foreground">{g.comp}</span> ×{g.count}
                      <span className="ms-2 txt-caption text-muted-foreground">
                        {g.details.slice(0, 2).join(" · ")}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Section>
          )}
        </>
      )}

        {contrast && (
          <Section title={`对比度（21 入口 × 5 皮肤 × 明暗）—— ${contrast.length} 条不达 AA`}>
            {contrast.length === 0 ? (
              <Notice>
                这 21 个入口在 10 组皮肤/明暗下都过 AA。注意只量了「当前渲染出来的文字」——
                抽屉、弹窗、以及数据为空时不出现的那些行不在射程内（对比度这一项还没接浮层体检）。
              </Notice>
            ) : (
              <DataTable
                columns={[
                  { header: "页面", cell: (g: ReturnType<typeof groupFails>[number]) => (
                    <span className="font-mono txt-caption">{g.comp}</span>
                  ) },
                  { header: "元素", className: "whitespace-normal",
                    cell: (g: ReturnType<typeof groupFails>[number]) => (
                      <span className="txt-caption text-muted-foreground">{g.sample}</span>
                    ) },
                  { header: "最差", numeric: true,
                    cell: (g: ReturnType<typeof groupFails>[number]) => (
                      <span className="text-[var(--destructive-ink)]">{fmtRatio(g.worst)}</span>
                    ) },
                  { header: "要求", numeric: true,
                    cell: (g: ReturnType<typeof groupFails>[number]) => g.need.toFixed(1) },
                  { header: "哪几组", className: "whitespace-normal",
                    cell: (g: ReturnType<typeof groupFails>[number]) => (
                      <span className="txt-caption text-muted-foreground">
                        {g.combos.slice(0, 6).join(" · ")}{g.combos.length > 6 ? " …" : ""}
                      </span>
                    ) },
                ]}
                rows={groupFails(contrast)}
                rowKey={(g) => `${g.comp}||${g.sample}`}
                empty="没有不达 AA 的文字。这一栏空着是好事，不是没跑。"
              />
            )}
          </Section>
        )}
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: React.ReactNode; tone?: "ok" | "warn" | "bad" }) {
  return (
    <div className="rounded-card border border-border p-3">
      <div className="txt-caption text-muted-foreground">{label}</div>
      <div className={cn("txt-title tabular-nums",
        tone === "warn" && "text-[var(--warning-ink)]",
        tone === "bad" && "text-[var(--destructive-ink)]",
        tone === "ok" && "text-[var(--success-ink)]")}>{value}</div>
    </div>
  );
}

/**
 * 分区。**自己不描边** —— 里面装的 `DataTable` 自带 Card 描边，
 * 外面再套一层就是两圈框（实测过一次）。说明性内容用 `Notice`，它自带底色。
 */
function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="txt-heading">{title}</h2>
      {children}
    </section>
  );
}
