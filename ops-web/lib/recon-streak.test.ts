import { describe, expect, it } from "vitest";
import { cleanStreak } from "./recon-streak";
import type { JobLogRow, JobStatus } from "@/lib/types";

const row = (status: JobStatus): JobLogRow => ({
  runId: String(Math.random()), jobName: "inv-recon", triggerType: "SCHEDULE",
  bizDate: null, startedAt: "2026-09-01T03:00:00", finishedAt: "2026-09-01T03:00:10",
  status, durationMs: 10_000, detail: null, error: null,
} as JobLogRow);

describe("对差连续干净轮数", () => {
  it("从最近往回数，遇到失败就停", () => {
    expect(cleanStreak([row("SUCCESS"), row("SUCCESS"), row("FAILED"), row("SUCCESS")])).toBe(2);
  });

  it("★★ SKIPPED 不算断 —— 它是「锁没抢到」，不是「那天有差异」", () => {
    // 把 SKIPPED 算成断，连续计数会在完全正常的日子里清零，
    // 而看的人会以为对差出了问题、迟迟不敢切真相源
    expect(cleanStreak([row("SUCCESS"), row("SKIPPED"), row("SUCCESS")])).toBe(2);
  });

  it("★★ TIMEOUT 算断 —— 那一轮没有结论，而没有结论不等于「为零」", () => {
    expect(cleanStreak([row("SUCCESS"), row("TIMEOUT"), row("SUCCESS")])).toBe(1);
  });

  it("最近一轮就失败时是 0，不是「还没跑过」", () => {
    expect(cleanStreak([row("FAILED"), row("SUCCESS"), row("SUCCESS")])).toBe(0);
  });

  it("没有记录时是 0 —— 由调用方区分「没跑过」与「跑了不干净」", () => {
    expect(cleanStreak([])).toBe(0);
    expect(cleanStreak(undefined)).toBe(0);
  });
});
