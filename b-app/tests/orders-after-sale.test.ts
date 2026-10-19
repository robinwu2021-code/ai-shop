import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import ts from "typescript";
import vm from "node:vm";

function page(api: Record<string, unknown>, name = "orders", hooks = "load, switchTab, tab, list, statusText") {
  const source = readFileSync(new URL(`../src/pages/${name}/index.vue`, import.meta.url), "utf8").split('<script setup lang="ts">')[1].split("</script>")[0];
  const js = ts.transpileModule(source + `\nexport const hooks = { ${hooks} };`, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports: any = {};
  const merchant = { canOperate: true, can: () => true, ensureStores: async () => {} };
  vm.runInNewContext(js, { exports, require: (name: string) => {
    if (name === "vue") return { ref: (value: unknown) => ({ value }), computed: (get: () => unknown) => ({ get value() { return get(); } }) };
    if (name === "@/api") return { api };
    if (name === "@/stores/merchant") return { useMerchantStore: () => merchant };
    if (name === "vue-i18n") return { useI18n: () => ({ t: (key: string) => key }) };
    if (name === "@dcloudio/uni-app") return { onShow: () => {}, onLoad: () => {}, onReachBottom: () => {} };
    if (name.includes("order-view")) return { DELIVERY_SHAPE: {}, fulfillmentsOf: () => [], tabQuery: () => ({}) };
    if (name.includes("constants")) return { FULFILLMENT: {} };
    return {};
  }});
  return exports.hooks;
}

describe("商家售后订单隔离", () => {
  it("普通订单的迟到响应不能覆盖售后列表", async () => {
    let resolveAll!: (data: unknown) => void;
    const api = {
      mOrderList: vi.fn(() => new Promise(r => { resolveAll = r; })),
      mAfterSaleWorklist: vi.fn().mockResolvedValue({ records: [{ afterSale: { subOrderNo: "AS1", status: "APPLIED" }, order: { orderNo: "AS1", status: "PAID" } }], total: 1 }),
    };
    const p = page(api);
    p.tab.value = "all";
    const first = p.load();
    p.tab.value = "afterSale";
    await p.load();
    resolveAll({ records: [{ orderNo: "OTHER", status: "FULFILLING" }] });
    await first;
    expect(p.list.value.map((o: any) => o.orderNo)).toEqual(["AS1"]);
    expect(p.statusText(p.list.value[0])).toBe("afterSale.status.APPLIED");
  });
});

it("售后处理独立页只列售后单，不因待发货或运输中订单而增加记录", async () => {
 const p = page({ mAfterSaleWorklist: async () => ({ records: [{ afterSale: { subOrderNo: "AS1", status: "APPLIED" }, order: { orderNo: "AS1", status: "PAID" } }], total: 1 }), mOrderList: vi.fn(() => { throw new Error("售后列表不应扫描普通订单"); }) }, "after-sale", "load, list");
 await p.load(); expect(p.list.value.map((r: any) => r.as.subOrderNo)).toEqual(["AS1"]);
});
