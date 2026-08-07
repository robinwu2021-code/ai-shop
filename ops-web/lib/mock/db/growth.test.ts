// 归因与裂变规则测试（P-9）。归因优先级的全序校验是本域最容易被写松的一条。
import { beforeEach, describe, expect, it } from "vitest";
import { growthMock } from "@/lib/api/mocks/growth";
import { ATTR_WINDOW_MAX, ATTR_WINDOW_MIN } from "@/lib/types";
import { attributionRule, fissionCampaigns } from "./growth";

const R0 = { ...attributionRule, priority: [...attributionRule.priority], newUserFactors: [...attributionRule.newUserFactors] };
const F0 = JSON.parse(JSON.stringify(fissionCampaigns)) as typeof fissionCampaigns;
beforeEach(() => {
  Object.assign(attributionRule, JSON.parse(JSON.stringify(R0)));
  fissionCampaigns.length = 0; fissionCampaigns.push(...(JSON.parse(JSON.stringify(F0)) as typeof fissionCampaigns));
});

const base = {
  priority: ["STORE_CODE", "INVITER", "CHANNEL"] as const,
  windowDays: 30,
  conflictPolicy: "KEEP_FIRST" as const,
  newUserFactors: ["DEVICE"] as const,
};

describe("归因优先级必须全序（P-9.1.1）", () => {
  it("缺一个来源被拒 —— 半个优先级表在冲突时会随机裁决", async () => {
    await expect(
      growthMock.saveAttributionRule({ ...base, priority: ["STORE_CODE", "INVITER"], newUserFactors: ["DEVICE"] }),
    ).rejects.toThrow(/覆盖全部/);
  });

  it("有重复被拒", async () => {
    await expect(
      growthMock.saveAttributionRule({ ...base, priority: ["STORE_CODE", "STORE_CODE", "CHANNEL"], newUserFactors: ["DEVICE"] }),
    ).rejects.toThrow(/覆盖全部/);
  });

  it("换序合法并落库（这正是 B1 要拍板的东西）", async () => {
    const r = await growthMock.saveAttributionRule({ ...base, priority: ["INVITER", "STORE_CODE", "CHANNEL"], newUserFactors: ["DEVICE"] });
    expect(r.priority).toEqual(["INVITER", "STORE_CODE", "CHANNEL"]);
  });
});

describe("窗口期与新客口径", () => {
  it(`窗口期需在 ${ATTR_WINDOW_MIN}–${ATTR_WINDOW_MAX} 天`, async () => {
    await expect(growthMock.saveAttributionRule({ ...base, windowDays: 0, newUserFactors: ["DEVICE"] })).rejects.toThrow(/窗口期/);
    await expect(growthMock.saveAttributionRule({ ...base, windowDays: 200, newUserFactors: ["DEVICE"] })).rejects.toThrow(/窗口期/);
  });

  it("新客因子一个都不选被拒 —— 等于所有人都是新客，新人券会被无限领", async () => {
    await expect(growthMock.saveAttributionRule({ ...base, newUserFactors: [] })).rejects.toThrow(/至少要选一个/);
  });

  it("三种冲突处置都能保存（B1 的候选，定了改默认值即可）", async () => {
    for (const policy of ["KEEP_FIRST", "OVERWRITE", "ASK_USER"] as const) {
      const r = await growthMock.saveAttributionRule({ ...base, conflictPolicy: policy, newUserFactors: ["PHONE"] });
      expect(r.conflictPolicy).toBe(policy);
    }
  });
});

describe("归因链路查询（P-9.1.3）", () => {
  it("只看冲突：筛出 B1 的现实场景", async () => {
    const page = await growthMock.listAttributionTraces({ conflictOnly: "1", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((t) => !!t.conflictWith)).toBe(true);
  });

  it("只看命中风控信号：这是风控页异常裂变事件的数据源", async () => {
    const page = await growthMock.listAttributionTraces({ riskyOnly: "1", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((t) => t.riskSignals.length > 0)).toBe(true);
  });
});

describe("邀请有礼（P-9.2.1）", () => {
  it("奖励只能是券（ADR-004：不存在现金激励）", async () => {
    await expect(
      // @ts-expect-error 运行时传入非法类型：类型层已挡，这里验运行时也挡
      growthMock.saveFissionCampaign({ fissionNo: "", name: "现金奖", rewardType: "CASH", couponNo: "CP9001", inviterCount: 1, inviteeCount: 1 }),
    ).rejects.toThrow(/只能发券/);
  });

  it("引用不存在的券模板被拒", async () => {
    await expect(
      growthMock.saveFissionCampaign({ fissionNo: "", name: "错券", rewardType: "COUPON", couponNo: "CP0000", inviterCount: 1, inviteeCount: 1 }),
    ).rejects.toThrow(/券模板不存在/);
  });

  it("双方都无奖励被拒（那不叫邀请有礼）", async () => {
    await expect(
      growthMock.saveFissionCampaign({ fissionNo: "", name: "空奖", rewardType: "COUPON", couponNo: "CP9001", inviterCount: 0, inviteeCount: 0 }),
    ).rejects.toThrow(/至少一方有奖励/);
  });

  it("合法配置落库，默认不启用（新活动先看配置对不对再开）", async () => {
    const f = await growthMock.saveFissionCampaign({ fissionNo: "", name: "夏日邀请", rewardType: "COUPON", couponNo: "CP9001", inviterCount: 1, inviteeCount: 2 });
    expect(f.enabled).toBe(false);
    expect(f.fissionNo).toMatch(/^FS/);
  });
});
