// 积分滚动到期（V30）。
//
// 模型：账户级一个到期日，任何积分变动都把它推后 `POINTS.inactiveDays`。
// 批次级到期（每笔一个日子 + remaining + FIFO）已废除。
//
// 这里守三件事，每件都对应一个「错了不会有任何症状」的坑：
//   1. 续期真的发生 —— 不续期的话模型退化成固定期限，而没人会发现
//   2. 到期时刻落在本地当天末尾 —— 用 UTC 的话中国用户会在写着「12月31日到期」的
//      第二天早上 8 点看着积分消失
//   3. 到期是**账户级**的 —— 一旦有人按批次算，批次列已经被删了，会静默算错
import { describe, expect, it } from "vitest";
import { pointsExpireAt, setCurrentOffset, isoDate } from "@shared/utils/datetime";
import { POINTS } from "@shared/utils/constants";

setCurrentOffset(8 * 60); // 默认市场 CN（UTC+8）

const at = (iso: string) => Date.parse(`${iso}T10:00:00+08:00`);
const DAY = 86_400_000;

describe("积分滚动到期", () => {
  it("到期日 = 最近一次积分变动 + 无活动期", () => {
    expect(isoDate(pointsExpireAt(at("2026-08-07"), 365))).toBe("2027-08-07");
    expect(isoDate(pointsExpireAt(at("2026-01-15"), 180))).toBe("2026-07-14");
  });

  it("每次变动都把到期日往后推 —— 这是整个模型的全部内容", () => {
    const first = pointsExpireAt(at("2026-01-01"), POINTS.inactiveDays);
    const later = pointsExpireAt(at("2026-06-01"), POINTS.inactiveDays);
    expect(later).toBeGreaterThan(first);
    // 推后的幅度就是两次活动的间隔
    expect(later - first).toBe(at("2026-06-01") - at("2026-01-01"));
  });

  it("持续活跃就永不到期：每隔半个无活动期动一次，到期日始终在未来", () => {
    const step = Math.floor((POINTS.inactiveDays / 2) * DAY);
    let now = at("2026-01-01");
    for (let i = 0; i < 8; i += 1) {
      expect(pointsExpireAt(now, POINTS.inactiveDays)).toBeGreaterThan(now);
      now += step;
    }
  });

  it("停止活动就会到期：无活动期一过，到期时刻已在当下之前", () => {
    const lastActive = at("2026-01-01");
    const after = lastActive + (POINTS.inactiveDays + 1) * DAY;
    expect(pointsExpireAt(lastActive, POINTS.inactiveDays)).toBeLessThan(after);
  });

  it("到期时刻是市场本地当天 23:59:59 —— 否则用户在写明的日期之外丢分", () => {
    const ts = pointsExpireAt(at("2026-08-07"), 365);
    const local = new Date(ts + 8 * 60 * 60_000);
    expect(local.getUTCHours()).toBe(23);
    expect(local.getUTCMinutes()).toBe(59);
    // 同一天内的任何时刻活动，到期日都是同一天 —— 到期日是「日子」不是「时刻」
    const morning = Date.parse("2026-08-07T00:30:00+08:00");
    const night = Date.parse("2026-08-07T23:30:00+08:00");
    expect(pointsExpireAt(morning, 365)).toBe(pointsExpireAt(night, 365));
  });

  it("跨市场时区：同一 UTC 时刻在不同市场算出不同的本地到期时刻", () => {
    const ts = at("2026-08-07");
    setCurrentOffset(8 * 60);
    const cn = pointsExpireAt(ts, 365);
    setCurrentOffset(4 * 60); // 海湾 UTC+4
    const ae = pointsExpireAt(ts, 365);
    setCurrentOffset(8 * 60);
    // 都是「本地当天 23:59:59」，UTC+4 的那一刻更晚
    expect(ae).toBeGreaterThan(cn);
    expect(ae - cn).toBe(4 * 60 * 60_000);
  });

  it("批次级到期的痕迹已清干净 —— 留着会有人接着用", () => {
    expect(POINTS).not.toHaveProperty("validDays");
    expect(POINTS).not.toHaveProperty("expiryAlign");
    expect(POINTS.inactiveDays).toBeGreaterThan(0);
  });
});
