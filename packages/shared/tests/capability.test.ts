// 支付能力矩阵的三个判断函数。
//
// 这三个函数在**三处**被调用（商品列表 / 购物车结算 / 下单接口），
// 且服务端还要用 Java 再实现一遍。用例表就是两侧的共同规格 ——
// 任何一侧改了行为而没改这里，就是分叉的开始。
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { POINTS, SETTLE } from "@shared/utils/constants";
import {
  INDUSTRIES,
  PAY_CHANNELS,
  canPay,
  canPoints,
  canSell,
  SCENE_PAY_METHOD,
  type ChannelCategoryRule,
  type MerchantPayment,
  type Scene,
} from "@shared/capability";

const SCENES: Scene[] = ["MP_WECHAT", "MP_ALIPAY", "IOS", "ANDROID", "H5"];
const CATEGORIES = ["GOODS", "FRESH", "SERVICE", "VIRTUAL", "CARD"];

/** 与 V20 迁移的初始数据同构：全量 25 行，仅 (IOS, VIRTUAL) 不可售 */
const RULES: ChannelCategoryRule[] = SCENES.flatMap((scene) =>
  CATEGORIES.map((categoryType) => ({
    scene,
    categoryType,
    sellable: !(scene === "IOS" && categoryType === "VIRTUAL"),
    reason:
      scene === "IOS" && categoryType === "VIRTUAL"
        ? "iOS 平台规则限制，请在小程序端购买"
        : undefined,
  })),
);

const payment = (over: Partial<MerchantPayment> = {}): MerchantPayment => ({
  channel: "WECHAT",
  subjectType: "INDIVIDUAL",
  applyStatus: "ACTIVE",
  payMethods: ["JSAPI", "APP", "H5", "NATIVE"],
  invoiceCapable: true,
  ...over,
});

describe("canSell —— 端 × 品类可售性", () => {
  it("iOS 上虚拟品类不可售，且给出原因", () => {
    const v = canSell({ categoryType: "VIRTUAL" }, "IOS", RULES);
    expect(v.ok).toBe(false);
    // 原因要能直接展示给用户，不是内部错误码
    expect(v.reason).toContain("小程序端");
  });

  it("同一个虚拟商品在小程序端可售 —— 屏蔽是端级的，不是商品级的", () => {
    expect(canSell({ categoryType: "VIRTUAL" }, "MP_WECHAT", RULES).ok).toBe(true);
    expect(canSell({ categoryType: "VIRTUAL" }, "ANDROID", RULES).ok).toBe(true);
  });

  it("规则缺失时**拒绝**而不是放行", () => {
    // 这是本函数最重要的一条：新增品类或新增端时，「查不到 = 可售」会静默放行，
    // 而那正是 App 审核被拒的场景。宁可误拒，不可误放。
    const v = canSell({ categoryType: "NEW_CATEGORY" }, "IOS", RULES);
    expect(v.ok).toBe(false);
    expect(v.reason).toContain("未配置");
  });

  it("商品级例外优先于品类规则，两个方向都生效", () => {
    expect(
      canSell({ categoryType: "VIRTUAL", sellableOverride: { IOS: true } }, "IOS", RULES).ok,
    ).toBe(true);
    expect(
      canSell({ categoryType: "FRESH", sellableOverride: { IOS: false } }, "IOS", RULES).ok,
    ).toBe(false);
  });
});

describe("canPay —— 商家 × 通道 × 端", () => {
  it("小微主体在 App 上收不了钱", () => {
    // 微信官方的主体授权规则：小微只有 付款码/JSAPI/Native，没有 APP 与 H5。
    // 这条是这套模型存在的最初理由 —— 不建模的话，商家的货在 App 上能加购、付不了。
    const micro = payment({ subjectType: "MICRO", payMethods: ["JSAPI", "NATIVE"] });
    expect(canPay([micro], "IOS", "WECHAT").ok).toBe(false);
    expect(canPay([micro], "H5", "WECHAT").ok).toBe(false);
    expect(canPay([micro], "MP_WECHAT", "WECHAT").ok).toBe(true);
  });

  it("未进件 / 审核中 / 已驳回，三种提示语不同", () => {
    // 商家看到「未开通」和「审核中」要能分辨该做什么，合并成一句就没用了
    expect(canPay([], "MP_WECHAT", "WECHAT").reason).toContain("未开通");
    expect(
      canPay([payment({ applyStatus: "APPLYING" })], "MP_WECHAT", "WECHAT").reason,
    ).toContain("审核中");
    expect(
      canPay([payment({ applyStatus: "REJECTED" })], "MP_WECHAT", "WECHAT").reason,
    ).toContain("未通过");
  });

  it("只进了微信的商家，支付宝端付不了", () => {
    const only = [payment({ channel: "WECHAT" })];
    expect(canPay(only, "MP_WECHAT", "WECHAT").ok).toBe(true);
    expect(canPay(only, "MP_ALIPAY", "ALIPAY").ok).toBe(false);
  });

  it("两个通道的主体类型可以不同，各自独立判定", () => {
    // 微信进小微、支付宝进个体户 —— 这是模型允许且现实会发生的
    const both: MerchantPayment[] = [
      payment({ channel: "WECHAT", subjectType: "MICRO", payMethods: ["JSAPI", "NATIVE"] }),
      payment({ channel: "ALIPAY", subjectType: "INDIVIDUAL" }),
    ];
    expect(canPay(both, "IOS", "WECHAT").ok).toBe(false);
    expect(canPay(both, "IOS", "ALIPAY").ok).toBe(true);
  });
});

describe("canPoints —— 通道能力 + 四级串联", () => {
  const base = {
    globalOn: true,
    communityOn: true,
    merchantOn: true,
    subjectType: "INDIVIDUAL" as const,
    channelSupportsSubsidy: true,
  };

  it("全部满足才生效", () => {
    expect(canPoints(base).ok).toBe(true);
  });

  it("通道不支持补差就拒绝 —— 而且提示的是「支付方式」不是「本店」", () => {
    // 积分抵扣要求平台在分账前把差额补进二级商户账户。没有这个能力，
    // 商家收到的钱就与订单金额对不上 —— 那是**做不到**，不是**没开**。
    // 提示语说成「本店未开启」的话，用户会去找商家，而商家什么也做不了。
    const v = canPoints({ ...base, channelSupportsSubsidy: false });
    expect(v.ok).toBe(false);
    expect(v.reason).toContain("支付方式");
    expect(v.reason).not.toContain("本店");
  });

  it("上层关，下层一定关", () => {
    expect(canPoints({ ...base, globalOn: false }).ok).toBe(false);
    expect(canPoints({ ...base, communityOn: false }).ok).toBe(false);
  });

  it("小微一律拒绝，且提示的是「升级后可开启」而不是「未开启」", () => {
    // 主体这一级排在商家开关之前是刻意的：小微是「不可开」不是「关着」。
    // 顺序反了的话，小微商家会看到「本店未开启积分」，以为自己打开就行。
    const v = canPoints({ ...base, subjectType: "MICRO" });
    expect(v.ok).toBe(false);
    expect(v.reason).toContain("升级");
    expect(v.reason).not.toContain("未开启");
  });

  it("小微即便商家开关是开的也拒绝 —— 主体优先于开关", () => {
    expect(canPoints({ ...base, subjectType: "MICRO", merchantOn: true }).ok).toBe(false);
  });
});

describe("规则表与端映射的完整性", () => {
  it("每个端都有支付产品映射 —— 漏一个端会让该端所有支付都判不了", () => {
    for (const s of SCENES) expect(SCENE_PAY_METHOD[s], `端 ${s} 没有映射`).toBeDefined();
  });

  it("初始规则恰好 25 条（5 端 × 5 品类）—— 与 V20 的种子数据同构", () => {
    // 全量铺而不是只存例外：缺行时 canSell 会判拒绝，
    // 表现是「商品突然全不可售」，比静默放行好，但仍是配置事故
    expect(RULES).toHaveLength(25);
  });

  it("sys_pay_channel 的种子与 PayChannel 联合类型一致 —— 表管能力，类型管取值", () => {
    // 只有表：会冒出一个谁都没定义过的 pay_channel，而代码里的 switch 会静默漏掉它。
    // 只有类型：通道能力一变（对方开放新接口、调额度）就要发版。
    // 两者都要，且必须一致。
    const mig = join(
      import.meta.dirname,
      "../../../backend/shop-app/src/main/resources/db/migration/V38__pay_channel_registry.sql",
    );
    if (!existsSync(mig)) return;

    const seeded = [
      ...readFileSync(mig, "utf8").matchAll(/\('(\w+)', '[^']*',\s*[01],/g),
    ].map((m) => m[1]!);

    expect(seeded, "没从 V38 解析到通道种子，多半是 INSERT 写法变了").not.toHaveLength(0);
    expect(seeded.slice().sort()).toEqual(PAY_CHANNELS.slice().sort());
  });

  it("通道的分账比例上限容得下我们要分走的钱", () => {
    // 支付宝直付通单笔最高分账 30%（V39 里落成 max_split_rate = 3000 万分比）。
    // 我们分走 = 佣金 + 履约服务费 + 积分服务费。佣金最高档 2%、积分服务费 1%，
    // 加上按件计的履约服务费，离 30% 很远 —— 但费率是会调的，
    // **调过头的表现是分账被通道拒绝**，而那时订单已经付过款了。
    const maxTake = SETTLE.commissionRate.PLATFORM + POINTS.defaultEarnRatio;
    const ALIPAY_CAP = 0.3;
    expect(
      maxTake,
      `平台分走 ${(maxTake * 100).toFixed(1)}% 已逼近支付宝 30% 的单笔分账上限`,
    ).toBeLessThan(ALIPAY_CAP * 0.5);
  });

  it("sys_industry 的种子与 Industry 联合类型一致 —— 表管能力，类型管取值", () => {
    const mig = join(
      import.meta.dirname,
      "../../../backend/shop-app/src/main/resources/db/migration/V40__industry_registry.sql",
    );
    if (!existsSync(mig)) return;
    const sql = readFileSync(mig, "utf8");
    const seeded = [...sql.matchAll(/\('([A-Z_]+)',\s*'[^']*',\s*\d+,/g)].map((m) => m[1]!);
    expect(seeded, "没从 V40 解析到行业种子，多半是 INSERT 写法变了").not.toHaveLength(0);
    expect(seeded.slice().sort()).toEqual(INDUSTRIES.slice().sort());
  });

  it("行业的小微准入默认值必须是 0 —— **默认允许 = 默认让商家撞墙**", () => {
    // 商家填完全部资料、选了小微，进件时才被通道拒绝 —— 那是最贵的失败时点。
    // 所以缺省一律不允许，要放开必须由运营显式改。
    const mig = join(
      import.meta.dirname,
      "../../../backend/shop-app/src/main/resources/db/migration/V40__industry_registry.sql",
    );
    if (!existsSync(mig)) return;
    const sql = readFileSync(mig, "utf8");
    for (const col of ["wechat_micro_allowed", "alipay_micro_allowed"]) {
      const decl = sql.match(new RegExp(`${col}\\s+TINYINT\\s+NOT NULL\\s+DEFAULT\\s+(\\d)`));
      expect(decl?.[1], `${col} 的默认值不是 0`).toBe("0");
    }
    // 支付宝的行业限制未确认 —— seed 里必须全部为 0，不能想当然照抄微信
    const alipayOnes = [...sql.matchAll(/\('[A-Z_]+',\s*'[^']*',\s*\d+,\s*\d+,\s*\d+,\s*(\d)/g)]
      .map((m) => m[1]);
    expect(
      alipayOnes.every((v) => v === "0"),
      "支付宝的行业限制还没确认，seed 不该有 alipay_micro_allowed=1",
    ).toBe(true);
  });

  it("迁移里的种子数据与本文件的用例表一致", () => {
    // 上面那张 RULES 是照着 V20 的 INSERT 手抄的。手抄必然漂移：
    // 运营改了迁移里的规则、这里没改，用例照样绿 —— 而用例是后端 Java 侧的规格来源，
    // 于是两侧行为分叉，且**没有任何地方会报错**。
    const mig = join(
      import.meta.dirname,
      "../../../backend/shop-app/src/main/resources/db/migration/V20__payment_capability.sql",
    );
    if (!existsSync(mig)) return; // 只装前端的场景

    const seeded = [
      ...readFileSync(mig, "utf8").matchAll(/\('(\w+)','(\w+)',([01]),/g),
    ].map((m) => ({ scene: m[1]!, categoryType: m[2]!, sellable: m[3] === "1" }));

    expect(seeded, "没从迁移里解析到种子数据，多半是 INSERT 写法变了").toHaveLength(25);

    const norm = (r: { scene: string; categoryType: string; sellable: boolean }) =>
      `${r.scene}/${r.categoryType}=${r.sellable ? 1 : 0}`;
    expect(seeded.map(norm).sort()).toEqual(RULES.map(norm).sort());
  });
});
