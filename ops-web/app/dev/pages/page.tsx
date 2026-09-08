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
// 1. 只看得到**当前渲染出来的**节点。抽屉、弹窗、确认框关着时不在 DOM 里。
// 2. class 字符串扫描判断不了「这个 h-9 是不是刻意的」，结论是**线索**不是判决。
// 3. 顶栏与左侧导航每页都一样，只在第一条路由上扫一次，别重复计入。
// 4. 依赖登录态：iframe 与本页同源，直接复用你当前的账号与数据域。
//    换个角色重跑，看到的页面集合与内容都会变 —— 这是特性不是缺陷。
import * as React from "react";
import { audit, groupFindings, type Finding } from "../ui/audit";
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
      acc.push({
        ...r,
        scanned: res.scanned,
        findings: res.findings,
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
              <p className="txt-body text-muted-foreground">
                页体上没有扫出线索。注意这只说明「当前渲染出来的节点」合规 ——
                抽屉与弹窗关着时不在射程内。
              </p>
            ) : (
              <table className="w-full txt-body">
                <thead>
                  <tr className="border-b border-border text-start txt-caption text-muted-foreground">
                    <th className="py-1.5 text-start">规则</th>
                    <th className="py-1.5 text-start">归属</th>
                    <th className="py-1.5 text-end">处数</th>
                    <th className="py-1.5 text-start">实测值</th>
                  </tr>
                </thead>
                <tbody>
                  {grouped.map((g, i) => (
                    <tr key={i} className="border-b border-border/50 align-top">
                      <td className="py-1.5 pe-3">{g.rule}</td>
                      <td className="py-1.5 pe-3 txt-caption text-muted-foreground">{g.comp}</td>
                      <td className="py-1.5 pe-3 text-end tabular-nums">{g.count}</td>
                      <td className="py-1.5 txt-caption text-muted-foreground">
                        {g.details.slice(0, 3).join(" · ")}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Section>

          <Section title="线索最多的页">
            <table className="w-full txt-body">
              <thead>
                <tr className="border-b border-border txt-caption text-muted-foreground">
                  <th className="py-1.5 text-start">路由</th>
                  <th className="py-1.5 text-start">功能</th>
                  <th className="py-1.5 text-end">节点</th>
                  <th className="py-1.5 text-end">线索</th>
                  <th className="py-1.5 text-end">耗时</th>
                </tr>
              </thead>
              <tbody>
                {worst.map((r) => (
                  <tr key={r.href} className="border-b border-border/50">
                    <td className="py-1.5 pe-3 font-mono txt-caption">{r.href}</td>
                    <td className="py-1.5 pe-3 txt-caption text-muted-foreground">
                      {r.section} · {r.label}
                    </td>
                    <td className="py-1.5 pe-3 text-end tabular-nums">{r.scanned}</td>
                    <td className={cn("py-1.5 pe-3 text-end tabular-nums",
                      r.findings.length > 0 && "text-[var(--warning-ink)]")}>
                      {r.findings.length}
                    </td>
                    <td className="py-1.5 text-end tabular-nums txt-caption text-muted-foreground">
                      {r.ms}ms
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Section>

          {shell && (
            <Section title={`外壳（顶栏 + 导航，只扫一次）—— ${shell.length} 条`}>
              {shell.length === 0 ? (
                <p className="txt-body text-muted-foreground">外壳没有扫出线索。</p>
              ) : (
                <ul className="txt-body space-y-1">
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

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2">
      <h2 className="txt-heading">{title}</h2>
      <div className="overflow-x-auto rounded-card border border-border p-3">{children}</div>
    </section>
  );
}
