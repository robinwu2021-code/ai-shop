// 会员卡与权益的规则测试（P-7.4）。
//
// 这一域的核心只有一句：**卖出去的是承诺，不是配置**。
// 所以测得最狠的是"已有持卡人还改权益/改月费/归档"这三条。
import { beforeEach, describe, expect, it } from "vitest";
import { marketingMock } from "@/lib/api/mocks/marketing";
import { coupons, memberCards } from "@/lib/mock/db";
import { MIN_MEMBER_DISCOUNT } from "@/lib/constants";

const snapshot = memberCards.map((m) => ({ ...m, benefits: m.benefits.map((b) => ({ ...b })) }));
const couponSnapshot = coupons.map((c) => ({ ...c }));

beforeEach(() => {
  memberCards.splice(0, memberCards.length,
    ...snapshot.map((m) => ({ ...m, benefits: m.benefits.map((b) => ({ ...b })) })));
  coupons.splice(0, coupons.length, ...couponSnapshot.map((c) => ({ ...c })));
});

const draft = () => {
  const m = memberCards.find((x) => x.holderCount === 0)!;
  return { ...m, benefits: m.benefits.map((b) => ({ ...b })) };
};

describe("卖出去的是承诺（P-7.4 核心）", () => {
  it("**已有持卡人的卡改权益要拒绝** —— 用户买的是当初那份权益", async () => {
    const sold = memberCards.find((m) => m.holderCount > 0 && m.status === "ACTIVE")!;
    await expect(
      marketingMock.saveMemberCard({
        ...sold,
        benefits: [{ kind: "DISCOUNT", value: 9800 }], // 悄悄把 95 折改成 98 折
      }),
    ).rejects.toThrow(/已有 \d+ 人持卡/);
  });

  it("**已有持卡人的卡改月费也要拒绝**", async () => {
    const sold = memberCards.find((m) => m.holderCount > 0 && m.status === "ACTIVE")!;
    await expect(
      marketingMock.saveMemberCard({ ...sold, priceMonthly: sold.priceMonthly + 100 }),
    ).rejects.toThrow(/已有 \d+ 人持卡/);
  });

  it("原样保存（只改名字）是允许的 —— 拦的是权益与价格，不是一切改动", async () => {
    const sold = memberCards.find((m) => m.holderCount > 0 && m.status === "ACTIVE")!;
    const m = await marketingMock.saveMemberCard({ ...sold, name: "邻里卡（改个名）" });
    expect(m.name).toBe("邻里卡（改个名）");
    expect(m.holderCount).toBe(sold.holderCount);
  });

  it("**有持卡人的卡归档不了** —— 权益还要继续兑现", async () => {
    const sold = memberCards.find((m) => m.holderCount > 0)!;
    await expect(marketingMock.archiveMemberCard(sold.cardNo)).rejects.toThrow(/还要兑现/);
  });
});

describe("权益校验", () => {
  it("至少要配一项权益 —— 没有权益的会员卡就是纯收费", async () => {
    await expect(marketingMock.saveMemberCard({ ...draft(), benefits: [] })).rejects.toThrow(/至少要配一项权益/);
  });

  it("权益类型不能重复 —— 同类两条时命中哪条取决于顺序", async () => {
    await expect(
      marketingMock.saveMemberCard({
        ...draft(),
        benefits: [
          { kind: "DISCOUNT", value: 9500 },
          { kind: "DISCOUNT", value: 9000 },
        ],
      }),
    ).rejects.toThrow(/权益类型重复/);
  });

  it(`折扣不得低于 ${MIN_MEMBER_DISCOUNT / 1000} 折 —— 月费远补不回被打穿的毛利`, async () => {
    await expect(
      marketingMock.saveMemberCard({ ...draft(), benefits: [{ kind: "DISCOUNT", value: MIN_MEMBER_DISCOUNT - 1 }] }),
    ).rejects.toThrow(/不得低于/);
  });

  it("积分倍率不能低于 1 倍（低于 1 倍是「会员反而更亏」）", async () => {
    await expect(
      marketingMock.saveMemberCard({ ...draft(), benefits: [{ kind: "POINTS_BOOST", value: 9000 }] }),
    ).rejects.toThrow(/不能低于 1 倍/);
  });

  it("**赠券必须绑一张已启用的券** —— 绑草稿券，用户开卡当天就领不到", async () => {
    const paused = coupons.find((c) => c.status !== "ACTIVE")!;
    await expect(
      marketingMock.saveMemberCard({
        ...draft(),
        benefits: [{ kind: "COUPON_PACK", value: 2, couponNo: paused.couponNo }],
      }),
    ).rejects.toThrow(/未启用/);

    await expect(
      marketingMock.saveMemberCard({
        ...draft(),
        benefits: [{ kind: "COUPON_PACK", value: 2, couponNo: "NOT_EXIST" }],
      }),
    ).rejects.toThrow(/必须绑定一张券模板/);
  });

  it("免运费次数必须是正整数", async () => {
    await expect(
      marketingMock.saveMemberCard({ ...draft(), benefits: [{ kind: "FREE_SHIPPING", value: 0 }] }),
    ).rejects.toThrow(/正整数/);
  });

  it("合法草稿卡落库，新卡拿到新编号且持卡人数从 0 起", async () => {
    const before = memberCards.length;
    const m = await marketingMock.saveMemberCard({
      name: "测试卡", level: 3, priceMonthly: 1900,
      benefits: [{ kind: "FREE_SHIPPING", value: 3 }],
    });
    expect(m.cardNo).toMatch(/^MC\d+$/);
    expect(m.holderCount).toBe(0);
    expect(m.status).toBe("DRAFT");
    expect(memberCards.length).toBe(before + 1);
  });
});

describe("状态机", () => {
  it("停售是终态 —— 已售出的权益要继续兑现，重新开卖得新建一张", async () => {
    const ended = memberCards.find((m) => m.status === "ENDED")!;
    await expect(marketingMock.setMemberCardStatus(ended.cardNo, "ACTIVE")).rejects.toThrow(/不允许从/);
  });

  it("草稿不能直接停售（要先启用过）", async () => {
    const d = memberCards.find((m) => m.status === "DRAFT")!;
    await expect(marketingMock.setMemberCardStatus(d.cardNo, "ENDED")).rejects.toThrow(/不允许从/);
  });

  it("草稿→启用→暂停→启用→停售，全程合法", async () => {
    const d = memberCards.find((m) => m.status === "DRAFT")!;
    await marketingMock.setMemberCardStatus(d.cardNo, "ACTIVE");
    await marketingMock.setMemberCardStatus(d.cardNo, "PAUSED");
    await marketingMock.setMemberCardStatus(d.cardNo, "ACTIVE");
    const m = await marketingMock.setMemberCardStatus(d.cardNo, "ENDED");
    expect(m.status).toBe("ENDED");
    expect(m.updatedBy).toBe("admin");
  });
});
