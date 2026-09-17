// 买家不选自提点（TDD-C端位置选择-地址取代自提点 §M2）。
//
// 这一组守的全是「不许再发生」的事，而它们失效时都不报错：
//   · 端上又替买家挑了一个点 —— 那个点决定履约服务费归谁，挑法是数组顺序
//   · 「绑没绑」又去看自提点 —— 没有点的聚落里的人永远算没绑，而他明明选了地址
//   · 确认页按商家分组而不是按取货点 —— 买家以为要跑两趟
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/** 判之前剥注释：解释规则的那句话本身也要能通过规则 */
function code(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

const communityStore = code("src/stores/community.ts");
const locationStore = code("src/stores/location.ts");
const confirmPage = code("src/pages/order-confirm/index.vue");

describe("自提点不再由买家挑", () => {
  it("★★★ 「绑没绑」判的是位置，不是自提点", () => {
    // 判 !!s.pickup 的话，没有自提点的聚落里的人永远算没绑 —— 而他明明选了地址
    expect(communityStore).toContain("bound: (s) => !!s.community");
    expect(communityStore).not.toContain("bound: (s) => !!s.pickup");
  });

  it("★★★ 解析出聚落就绑，不再因为「没有点」整个不绑", () => {
    /*
     * 此前是 `if (c && p)`：聚落没有自提点时一行都不写，
     * 用户静默看不到任何货 —— 没有提示、没有报错。
     */
    expect(locationStore).not.toContain("if (c && p)");
    expect(locationStore).toContain("if (c)");
  });

  it("★★★ 下单与预览都**不传** pickupNo —— 传就是让端上替他挑", () => {
    /*
     * 端上挑的话只能按 `pickups[0]` 挑，等于让数组顺序决定
     * 履约服务费归谁（pickup_owner_ref 是佣金归属依据）。
     */
    expect(confirmPage).not.toContain("community.pickup?.pickupNo");
    // 对照量：确实还在调这两个接口，不是整段被删了
    expect(confirmPage).toContain("api.orderPreview");
    expect(confirmPage).toContain("fulfillment: fulfillment.value");
  });

  it("★★★ 确认页按**取货点**分组，不按商家", () => {
    // 两家配到同一个点时按商家分会让人以为要跑两趟，而他只需去一个地方
    expect(confirmPage).toContain("applyPickupGroups");
    expect(confirmPage).toContain("byPoint");
    expect(confirmPage).toContain('v-for="g in pickupGroups"');
  });

  it("★★★ 配不出点的商家要**点名**，且在付款前", () => {
    // 笼统一句「没有可用取货点」的话，车里有三家店时他不知道该动哪一件
    expect(confirmPage).toContain("pickupMissing");
    expect(confirmPage).toContain("confirm.pickupNoneFor");
  });
});
