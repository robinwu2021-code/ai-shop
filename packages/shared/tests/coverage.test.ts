// 「买家看不看得见这家店」——端上这条判据与后端 `reachableCommunities` 必须说同一句话。
//
// 它错了不会报错：工作台显示一切正常，而买家里没有一个人看得到他。
// 商家过几天发现一单没有，既不知道差什么，也不知道去哪补。
import { describe, expect, it } from "vitest";
import { includedAreas, visibleToBuyers } from "../src/utils/coverage";
import type { ServiceArea } from "../src/types/region";

const inc = (refCode: string): ServiceArea =>
  ({ level: "COMMUNITY", refCode, name: refCode } as ServiceArea);
const exc = (refCode: string): ServiceArea =>
  ({ level: "COMMUNITY", refCode, name: refCode, mode: "EXCLUDE" } as ServiceArea);

describe("经营范围为空的判据", () => {
  it("★★★ 排除项不算范围 —— 「只写了排除」的自提商家，谁也看不到他", () => {
    /*
     * **端上两处曾经都写成 `serviceAreas.length > 0`**，而排除项也占一行。
     * 于是「我上门送，就是不送 3 幢」的商家改成只自提之后，
     * 后端返回空集（纳入项为空 + 只自提 = 没有落点），
     * 工作台却因为「有 1 条范围」一条告警都不出 —— 说的和做的正好相反。
     */
    expect(visibleToBuyers("PICKUP", [exc("CM001B3")])).toBe(false);
    expect(includedAreas([exc("CM001B3")])).toHaveLength(0);
  });

  it("★★★ 只自提 + 一条纳入项 = 看得见（对照量，证明上一条不是恒 false）", () => {
    expect(visibleToBuyers("PICKUP", [inc("CM001")])).toBe(true);
    expect(visibleToBuyers("PICKUP", [inc("CM001"), exc("CM001B3")])).toBe(true);
  });

  it("★★★ 开了自送/快递：没框 = 不限，**不是**谁也看不到", () => {
    /*
     * 反过来写的后果同样严重且方向相反：存量的上门商家在迁移当天
     * 集体从 C 端消失，而他们什么也没改。
     */
    expect(visibleToBuyers("ONSITE", [])).toBe(true);
    expect(visibleToBuyers("SHIPPING", null)).toBe(true);
    expect(visibleToBuyers("ONSITE", [exc("CM001B3")])).toBe(true);
  });

  it("★★ 只自提 + 一条都没有 = 谁也看不到", () => {
    expect(visibleToBuyers("PICKUP", [])).toBe(false);
    expect(visibleToBuyers("PICKUP", undefined)).toBe(false);
  });

  it("★★ 待审的纳入项**算数** —— 否则等审期间挂着一条他消不掉的告警", () => {
    // 它已经写进库了，只是还没生效；当成「没有」会让商家看到「你还没选范围」，
    // 而他明明选了 —— 那条告警他消不掉，也无从消起。
    const pending = { ...inc("CM002"), status: "PENDING" } as ServiceArea;
    expect(visibleToBuyers("PICKUP", [pending])).toBe(true);
  });
});
