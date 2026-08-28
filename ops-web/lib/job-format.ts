// 任务页的两个纯函数：cron 说人话、时间说相对量。
//
// **为什么值得单独一个文件**：它们是这一页可读性的全部来源，且都容易写错边界
// —— 分开就能直接测。

/**
 * cron → 人话。**运营看不懂六段式 cron**（尤其带步长的那种）。
 *
 * 注意：块注释里不能出现步长写法的字面量 —— 那两个字符会提前闭合注释。
 *
 * 只翻译实际用到的那几种形状（这个系统 11 个任务全在其中），
 * 认不出来就原样返回 —— <b>猜错比不翻译更糟</b>：
 * 页面上写着「每天 03:10」而它其实每小时跑一次，没有任何地方会纠正这句话。
 */
export function cronText(cron: string, t: (k: string, p?: Record<string, unknown>) => string): string {
  const f = cron.trim().split(/\s+/);
  if (f.length !== 6) return cron;
  const [sec, min, hour, dom, mon, dow] = f;
  const everyDay = dom === "*" && mon === "*" && (dow === "*" || dow === "?");
  if (!everyDay || sec !== "0") return cron;

  if (min === "*" && hour === "*") return t("cron.everyMinute");
  const stepMin = /^\*\/(\d+)$/.exec(min);
  if (stepMin && hour === "*") return t("cron.everyNMinutes", { n: stepMin[1] });
  if (/^\d+$/.test(min) && hour === "*") return t("cron.hourlyAt", { m: pad(min) });
  const stepHour = /^\*\/(\d+)$/.exec(hour);
  if (stepHour && /^\d+$/.test(min)) return t("cron.everyNHours", { n: stepHour[1], m: pad(min) });
  if (/^\d+$/.test(min) && /^\d+$/.test(hour)) return t("cron.dailyAt", { hm: `${pad(hour)}:${pad(min)}` });
  return cron;
}

function pad(n: string): string {
  return n.padStart(2, "0");
}

/**
 * 相对时间。`2026-08-28T02:41:00` → 「2 分钟前」。
 *
 * **超过一天就回到绝对时间**：「3 天前」对排查没用，
 * 而人要拿它去和别的日志对时间。
 *
 * @param now 传进来而不是内部取 —— 否则这个函数没法测
 */
export function relTime(
  iso: string | null | undefined,
  t: (k: string, p?: Record<string, unknown>) => string,
  now: number = Date.now(),
): string {
  if (!iso) return "—";
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return iso;
  const diff = Math.round((now - ms) / 1000);
  const future = diff < 0;
  const s = Math.abs(diff);
  if (s < 45) return t(future ? "rel.soon" : "rel.justNow");
  if (s < 3600) return t(future ? "rel.inMin" : "rel.minAgo", { n: Math.round(s / 60) });
  if (s < 86400) return t(future ? "rel.inHour" : "rel.hourAgo", { n: Math.round(s / 3600) });
  return iso.replace("T", " ").slice(0, 16);
}

/** 一行里塞不下的话截断。**不在 CSS 里做**：detail 可能是很长的一行统计，
 * 而 CSS 截断在窄屏上会把整行压成一个字。 */
export function oneLine(s: string | null | undefined, max = 64): string {
  if (!s) return "";
  const flat = s.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat;
}
