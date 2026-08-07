// 时区感知的时间格式化。
//
// 时间戳一律 UTC 毫秒在内部流转，只在展示层按「市场时区」渲染。
// 用固定 UTC 偏移而非 IANA 时区：小程序没有可靠的 tz 数据库，而目标市场
// （中国 +8 / 海湾 +4）均无夏令时，固定偏移是准确的。
// ⚠️ 进入有夏令时的市场（欧美）时必须换成带 tz 数据的方案 —— 见 TDD 风险表。
import { DEFAULT_MARKET, MARKETS } from "@shared/utils/constants";

const defaultOffset =
  MARKETS.find((m) => m.id === DEFAULT_MARKET)?.utcOffsetMinutes ?? 8 * 60;

let offsetMinutes = defaultOffset;

export function setCurrentOffset(minutes: number): void {
  offsetMinutes = minutes;
}

export function currentOffset(): number {
  return offsetMinutes;
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

/** UTC 毫秒 → 市场本地时区的日历字段 */
function parts(ts: number) {
  const d = new Date(ts + offsetMinutes * 60_000);
  return {
    y: d.getUTCFullYear(),
    m: d.getUTCMonth() + 1,
    d: d.getUTCDate(),
    hh: d.getUTCHours(),
    mm: d.getUTCMinutes(),
  };
}

/** 「2026-08-05 14:30」（市场时区） */
export function datetime(ts: number): string {
  const p = parts(ts);
  return `${p.y}-${pad(p.m)}-${pad(p.d)} ${pad(p.hh)}:${pad(p.mm)}`;
}

/** 「08-05」（市场时区） */
export function monthDay(ts: number): string {
  const p = parts(ts);
  return `${pad(p.m)}-${pad(p.d)}`;
}

/** 「14:30」（市场时区） */
export function hourMinute(ts: number): string {
  const p = parts(ts);
  return `${pad(p.hh)}:${pad(p.mm)}`;
}

/** 「2026-08-05」（市场时区），用于预约日期比对 */
export function isoDate(ts: number): string {
  const p = parts(ts);
  return `${p.y}-${pad(p.m)}-${pad(p.d)}`;
}

/**
 * 市场时区的「今天某个钟点」对应的 UTC 时间戳。
 * 生鲜截单时间是市场本地的 21:00 —— 不做这个换算，海外用户看到的截单时刻会是错的。
 */
export function todayAtLocal(hhmm: string, dayOffset = 0): number {
  const [h = 0, m = 0] = hhmm.split(":").map(Number);
  const nowLocal = new Date(Date.now() + offsetMinutes * 60_000);
  const utcMidnight = Date.UTC(
    nowLocal.getUTCFullYear(),
    nowLocal.getUTCMonth(),
    nowLocal.getUTCDate() + dayOffset,
    h,
    m,
  );
  return utcMidnight - offsetMinutes * 60_000;
}

/** 剩余毫秒 → 「02:15:30」，用于截单/成团倒计时（与时区无关） */
export function countdown(ms: number): string {
  if (ms <= 0) return "00:00:00";
  const s = Math.floor(ms / 1000);
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

/**
 * 列表里的紧凑倒计时：**超过 1 小时就不显示秒**。
 * 距截单还有 6 小时时，秒位每秒都在跳却毫无信息量，只是占宽度 ——
 * 英文卡片里「Closes in 05:51:24」比「Closes in 05:51」宽出约三分之一，
 * 正是这点宽度让倒计时挤不进价格行、被迫单占一行。
 * 不足 1 小时才切到「分:秒」—— 那时候秒才真的是决策信息。
 */
export function countdownShort(ms: number): string {
  if (ms <= 0) return "00:00";
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h > 0 ? `${pad(h)}:${pad(m)}` : `${pad(m)}:${pad(s % 60)}`;
}
