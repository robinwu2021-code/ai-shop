import { describe, expect, it } from "vitest";
import { cronText, relTime, oneLine } from "./job-format";

// 词条替身：把 key 与参数原样拼出来，这样断言看得见「翻成了哪一条」
const t = (k: string, p?: Record<string, unknown>) =>
  p ? `${k}(${Object.entries(p).map(([a, b]) => `${a}=${b}`).join(",")})` : k;

describe("cronText", () => {
  it("认得这个系统实际用到的每一种形状", () => {
    expect(cronText("0 * * * * *", t)).toBe("cron.everyMinute");
    expect(cronText("0 */10 * * * *", t)).toBe("cron.everyNMinutes(n=10)");
    expect(cronText("0 5 * * * *", t)).toBe("cron.hourlyAt(m=05)");
    expect(cronText("0 10 3 * * *", t)).toBe("cron.dailyAt(hm=03:10)");
    expect(cronText("0 50 3 * * *", t)).toBe("cron.dailyAt(hm=03:50)");
  });

  it("★ 认不出来就原样返回 —— 猜错比不翻译更糟", () => {
    // 页面上写着「每天 03:10」而它其实每周一跑，没有任何地方会纠正这句话
    expect(cronText("0 10 3 * * MON", t)).toBe("0 10 3 * * MON");
    expect(cronText("0 10 3 1 * *", t)).toBe("0 10 3 1 * *");
    expect(cronText("乱写的", t)).toBe("乱写的");
    expect(cronText("0 10 3 * *", t)).toBe("0 10 3 * *");   // 只有 5 段
  });
});

describe("relTime", () => {
  const now = Date.parse("2026-08-28T12:00:00");
  it("过去与未来分开说", () => {
    expect(relTime("2026-08-28T11:58:00", t, now)).toBe("rel.minAgo(n=2)");
    expect(relTime("2026-08-28T12:05:00", t, now)).toBe("rel.inMin(n=5)");
    expect(relTime("2026-08-28T09:00:00", t, now)).toBe("rel.hourAgo(n=3)");
  });
  it("★ 超过一天回到绝对时间 —— 「3 天前」没法拿去和别的日志对时间", () => {
    expect(relTime("2026-08-25T03:50:00", t, now)).toBe("2026-08-25 03:50");
  });
  it("没有值 / 解析不了都不能崩", () => {
    expect(relTime(null, t, now)).toBe("—");
    expect(relTime("不是时间", t, now)).toBe("不是时间");
  });
});

describe("oneLine", () => {
  it("压平换行并截断", () => {
    expect(oneLine("a\n  b")).toBe("a b");
    expect(oneLine("x".repeat(80), 10)).toBe(`${"x".repeat(9)}…`);
    expect(oneLine(null)).toBe("");
  });
});
