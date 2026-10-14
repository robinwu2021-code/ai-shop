/**
 * 确认页：配到的自提点离你多远，以及「太远」那一句（TDD-C端定位地名与自提点距离）。
 *
 * 点是后端按地址配的、买家没得挑 —— 不说距离的话，他要到取货那天才知道有多远。
 * 距离本身在后端 `PickupSplitByMerchantTest` 钉着（预览必须带 `pickupDistanceM`），
 * 这里守的是端上那两条显示规则：**-1 不许显示成「0 米」**，**远只提醒不拦提交**。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { PICKUP_FAR_M } from "@shared/utils/constants";
import { distance as fmtDistance } from "@shared/utils/format";

const src = readFileSync(
  resolve(__dirname, "..", "src/pages/order-confirm/index.vue"),
  "utf-8",
);
/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
const code = src
  .replace(/\/\*[\s\S]*?\*\//g, "")
  .replace(/<!--[\s\S]*?-->/g, "")
  .replace(/\/\/[^\n]*/g, "");

describe("阈值是个常量，不是散落的数字", () => {
  it("★★★ 「远」的判据取自 PICKUP_FAR_M", () => {
    expect(code, "写死 2000 的话，改阈值要翻页面找").toContain("PICKUP_FAR_M");
    expect(code).toMatch(/m\s*>\s*PICKUP_FAR_M/);
  });

  it("两公里：步行约半小时。再近市区里几乎每单都弹，再远就不叫提醒了", () => {
    expect(PICKUP_FAR_M).toBe(2000);
    // 阈值本身要落在「说得出口」的量级上 —— 100 米或 50 公里都不是提醒
    expect(PICKUP_FAR_M).toBeGreaterThan(500);
    expect(PICKUP_FAR_M).toBeLessThan(10_000);
  });
});

describe("没坐标的点不许谎称 0 米", () => {
  it("★★★ distanceOf 对 -1 与空都给空串", () => {
    const body = code.slice(code.indexOf("function distanceOf("), code.indexOf("function isFar("));
    expect(body, "distanceOf 改名了 —— 守卫失去了扫描对象").not.toBe("");
    expect(body, "-1 是「这个点没标坐标」，显示成 0 米就是一句假话").toContain("m < 0");
  });

  it("格式化走共用的那一份，不在页面里自己拼「米 / 公里」", () => {
    expect(code).toContain("fmtDistance");
    // 共用那份本身给的就是人话，顺带钉住它没被改成裸数字
    expect(fmtDistance(1800)).not.toBe("1800");
  });
});

describe("远只是提醒，不是闸门", () => {
  it("★★★ 提示那一行不参与提交的禁用判据", () => {
    const tpl = src.slice(src.indexOf("<template>"));
    expect(tpl, "「离你约 X」那一行没出现在确认页上").toContain("confirm.pickupFar");
    // isFar 只出现在渲染提示的地方；一旦它进了按钮的 disabled，就从提醒变成了拦截
    const submitArea = tpl.slice(tpl.indexOf("sh-actionbar"));
    expect(submitArea, "远不等于不能选：顺路取两公里外的点很常见").not.toContain("isFar");
  });
});
