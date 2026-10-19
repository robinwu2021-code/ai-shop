import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { approveCap } from "@/shared/approve-cap";

/**
 * 售后页两处断点（TDD-订单售后三端断点修复 AC9 / AC10）。
 *
 * <p>b-app 还没有组件测试设施（见 vitest.config.mts 的说明），AC9 用模板结构断言：
 * 它要守的恰好就是「这几个元素在不在同一条 v-if 链里」这一件事。
 */
describe("核定上限 = 买家申请额（AC10）", () => {
  it("★★★ 申请额小于实付 → 取申请额（三件退一件，不能预填整单）", () => {
    expect(approveCap(1000, 3000)).toBe(1000);
  });

  it("★ 老数据没有申请额 → 回落实付", () => {
    expect(approveCap(undefined, 3000)).toBe(3000);
    expect(approveCap(0, 3000)).toBe(3000);
  });
});

describe("「一并退回」提示不吞掉操作按钮（AC9）", () => {
  const sfc = readFileSync(
    fileURLToPath(new URL("../src/pages/after-sale/index.vue", import.meta.url)), "utf8");
  const tpl = sfc.slice(sfc.indexOf("<template>"), sfc.lastIndexOf("</template>"));

  it("★★★ 提示行之后的第一个条件元素另起一条链（v-if），不是 v-else-if", () => {
    // 此前提示行是链头：`<text v-if="impactText(r)">` 后面紧跟 `v-else-if="approving…"` 与 APPLIED 按钮，
    // 提示一出现，同意/驳回/确认收货整块不渲染 —— 后端恰在 APPLIED / REFUNDING 时下发 impact
    const at = tpl.indexOf('v-if="impactText(r)"');
    expect(at, "提示行还在").toBeGreaterThan(-1);
    const next = tpl.slice(at + 1).match(/v-(else-if|else|if)=?/);
    expect(next?.[1], "提示行后面接的是 v-else-if → 按钮又被吞了").toBe("if");
  });
});

describe("积分接线后的商家核定", () => {
  const sfc = readFileSync(
    fileURLToPath(new URL("../src/pages/after-sale/index.vue", import.meta.url)), "utf8");

  it("只提交现金退款，积分返还由独立退款链路处理", () => {
    expect(sfc).not.toContain('approveMode = "POINTS"');
    expect(sfc).not.toContain('t("afterSale.refundModePoints")');
    expect(sfc).toContain('void agree(r, minor, "CASH")');
  });
});
