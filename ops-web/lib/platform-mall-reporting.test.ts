// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { act, createElement as h } from "react";
import { createRoot, type Root } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
const s = vi.hoisted(() => ({ perms: new Set<string>(), orders: vi.fn(), claims: vi.fn(), cash: vi.fn(), detail: vi.fn(), opened: vi.fn(), auth: { tenantNo: "MAIN", username: "operator1", token: "session1", merchantNo: "", communityNo: "", pickupNo: "", perms: [] as string[] } }));
vi.mock("@/lib/api", () => ({ api: { platformMallOrders: s.orders, platformMallClaims: s.claims, platformMallCash: s.cash, entityPlanPurchase: s.detail } }));
vi.mock("@/lib/auth", () => ({ useAuth: (selector: (state: typeof s.auth) => unknown) => selector(s.auth) }));
vi.mock("@/lib/use-can", () => ({ useCan: () => (p: string) => s.perms.has(p) }));
vi.mock("@/lib/use-copy", () => ({ useCopy: (copy: { zh: unknown }) => copy.zh }));
vi.mock("@/lib/i18n", () => ({ useI18n: () => ({ locale: "zh", t: (k: string) => k }), useT: () => (k: string) => k }));
vi.mock("@/app/mall/order-drawer", async () => {
 const {useState} = await import("react"); const {useFinancialLock} = await import("@/components/offline/financial-lock");
 return { OrderDrawer: ({ storeNo, orderNo, onClose }: { storeNo: string; orderNo: string; onClose: () => void }) => { const [pending,setPending]=useState(false);useFinancialLock("store-test",pending);s.opened(storeNo, orderNo); return h("div",null,h("button", { onClick: onClose }, "STORE-DETAIL"),h("button",{onClick:()=>setPending(v=>!v)},pending?"STORE-PENDING":"STORE-LOCK")); } };
});
vi.mock("@/app/merchants/plan-purchases", async () => {
 const {useState} = await import("react"); const {useFinancialLock} = await import("@/components/offline/financial-lock");
 return { EntityPlanPurchaseDetail: () => { const [pending,setPending] = useState(false); useFinancialLock("entity-test",pending); return h("button",{onClick:()=>setPending(v=>!v)},pending?"ENTITY-PENDING":"ENTITY-DETAIL"); } };
});
import { PlatformOrderList } from "@/app/mall/platform-order-list";
import { PlatformCashReport } from "@/app/mall/platform-cash-report";
import { formatReportingMinor, validReportingRange } from "./platform-mall-reporting";
import { REPORTING_COPY } from "@/app/mall/reporting-copy";
let root: Root, host: HTMLDivElement, client: QueryClient;
async function flush() { await act(async () => { await new Promise(r => setTimeout(r, 20)); }); }
async function start(component: ReturnType<typeof h>) { await act(async () => root.render(h(QueryClientProvider, { client }, component))); await flush(); }
const button = (text: string) => [...document.querySelectorAll("button")].find(b => b.textContent === text)!;
beforeEach(() => {
  Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true }); vi.clearAllMocks(); s.perms.clear(); s.auth = { tenantNo: "MAIN", username: "operator1", token: "session1", merchantNo: "", communityNo: "", pickupNo: "", perms: [] as string[] };
  host = document.createElement("div"); document.body.appendChild(host); root = createRoot(host); client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  s.orders.mockResolvedValue({ items: [], nextCursor: null, includedModels: ["STORE"], queriedAt: 1 }); s.claims.mockResolvedValue({ items: [], nextCursor: null, includedModels: ["ENTITY"], queriedAt: 1 }); s.detail.mockResolvedValue({ orderNo: "E-ORDER" });
});
afterEach(async () => { await act(async () => root.unmount()); client.clear(); document.body.innerHTML = ""; });
it("does not query global lists without the dedicated read permission", async () => { await start(h(PlatformOrderList)); expect(s.orders).not.toHaveBeenCalled(); expect(host.textContent).toContain("没有该查询权限"); });
it("queries all authorized orders without preselecting a store and pages with server cursors", async () => {
 s.perms.add("commerce:platform:orders:read"); s.perms.add("commerce:mall:order:read");
 s.orders.mockResolvedValue({ items: [{model:"STORE",orderNo:"S-ORDER",entityNo:"E1",storeNo:"S1",currency:"TWD",payableMinor:"19900",status:"AWAITING_PAYMENT",claimStatus:null,createdAt:1}], nextCursor:"opaque-next",includedModels:["STORE"],queriedAt:1 });
 await start(h(PlatformOrderList)); expect(s.orders).toHaveBeenCalledWith({after:undefined,limit:20}); await act(async () => button("下一页").click()); await flush(); expect(s.orders).toHaveBeenLastCalledWith({after:"opaque-next",limit:20});
 await act(async () => button("详情").click()); await flush(); expect(s.opened).toHaveBeenCalledWith("S1","S-ORDER"); expect((host.querySelector('input[aria-label="主体编号"]') as HTMLInputElement).disabled).toBe(true);
});
it("uses the pending queue endpoint and original entity details without posting or changing scope", async () => {
 s.perms.add("commerce:platform:claims:read"); s.perms.add("merchant:merchant:read");
 s.claims.mockResolvedValue({ items:[{model:"ENTITY",orderNo:"E-ORDER",entityNo:"E1",storeNo:"S1",currency:"TWD",payableMinor:"100",status:"SUBMITTED",claimStatus:"SUBMITTED",createdAt:1}],nextCursor:null,includedModels:["ENTITY"],queriedAt:1 });
 await start(h(PlatformOrderList,{pendingOnly:true})); expect(s.claims).toHaveBeenCalledOnce(); expect(s.orders).not.toHaveBeenCalled(); await act(async () => button("详情").click()); await flush(); expect(s.detail).toHaveBeenCalledWith("E1","E-ORDER"); expect(document.body.textContent).toContain("ENTITY-DETAIL");
});
it("shows full-window server totals even when detail page contains a different amount", async () => {
 s.perms.add("commerce:platform:cash:read"); s.cash.mockResolvedValue({ summary:[{model:"STORE",currency:"TWD",classification:"NORMAL",receivedMinor:"900719925474099300",refundedMinor:"1",netMinor:"900719925474099299",receiptCount:123,refundCount:1}],items:[{paymentNo:"P1",orderNo:"O1",model:"STORE",entityNo:"E1",storeNo:"S1",direction:"PAY",currency:"TWD",amountMinor:"100",classification:"NORMAL",occurredAt:1}],nextCursor:"cash-next",queriedAt:1,coverage:"CONSISTENT_PER_REQUEST" });
 await start(h(PlatformCashReport,{details:true})); expect(host.textContent).toContain("TWD 9,007,199,254,740,993.00"); expect(host.textContent).toContain("TWD 1.00"); const first=s.cash.mock.calls[0][0]; await act(async () => button("下一页").click()); await flush(); expect(s.cash).toHaveBeenLastCalledWith({...first,after:"cash-next"});
});
it("shows cash backend failure rather than fabricated zero totals", async () => {s.perms.add("commerce:platform:cash:read");s.cash.mockRejectedValue(new Error("ledger unavailable"));await start(h(PlatformCashReport));expect(host.textContent).toContain("ledger unavailable");expect(host.textContent).not.toContain("TWD 0.00");});
it("formats arbitrary precision signed aggregates and validates complete date windows", () => {
 expect(formatReportingMinor("TWD","-900719925474099300000")).toBe("TWD -9,007,199,254,740,993,000.00");expect(formatReportingMinor("JPY","123456789012345678901")).toBe("JPY 123,456,789,012,345,678,901");expect(validReportingRange(0,366*86400000)).toBe(true);expect(validReportingRange(0,366*86400000+1)).toBe(false);expect(validReportingRange(2,1)).toBe(false);expect(validReportingRange(NaN,2)).toBe(false);
});
it("keeps all reporting copy keys translated", () => {const keys=Object.keys(REPORTING_COPY.zh).sort();expect(Object.keys(REPORTING_COPY.en).sort()).toEqual(keys);expect(Object.keys(REPORTING_COPY["zh-TW"]).sort()).toEqual(keys);expect(Object.values(REPORTING_COPY.en).some(v=>/[一-龥]/.test(v))).toBe(false);});
async function inputValue(label: string, value: string) {
 const input = host.querySelector(`input[aria-label="${label}"]`) as HTMLInputElement;
 await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,"value")!.set!.call(input,value); input.dispatchEvent(new Event("input",{bubbles:true})); input.dispatchEvent(new Event("change",{bubbles:true})); });
}
it("applies creation-time boundaries only when the operator submits filters", async () => {
 s.perms.add("commerce:platform:orders:read"); await start(h(PlatformOrderList));
 await inputValue("创建起始","2026-11-01T00:00"); expect(s.orders).toHaveBeenCalledOnce();
 await act(async () => button("查询").click()); await flush(); expect(s.orders).toHaveBeenLastCalledWith({createdFrom:new Date("2026-11-01T00:00").getTime(),after:undefined,limit:20});
});
it("rejects reversed cash dates locally without querying a different window", async () => {
 s.perms.add("commerce:platform:cash:read"); s.cash.mockResolvedValue({summary:[],items:[],nextCursor:null,queriedAt:1,coverage:"CONSISTENT_PER_REQUEST"}); await start(h(PlatformCashReport));
 await inputValue("开始时间","2026-11-15T00:00"); await inputValue("结束时间（不含）","2026-11-01T00:00");
 await act(async () => button("查询").click()); await flush(); expect(s.cash).toHaveBeenCalledOnce(); expect(host.textContent).toContain("请选择有效区间，最长366天。");
});
it("offers queue models only with the complete source review capability", async () => {
 for (const permission of ["commerce:platform:claims:read","merchant:merchant:read","commerce:mall:order:read"]) s.perms.add(permission);
 await start(h(PlatformOrderList,{pendingOnly:true})); const options=()=>[...host.querySelectorAll('select[aria-label="套餐类型"] option')].map(option=>(option as HTMLOptionElement).value);
 expect(options()).toEqual([""]);s.perms.add("commerce:mall:offline:review");await start(h(PlatformOrderList,{pendingOnly:true}));expect(options()).toEqual(["","STORE"]);
 s.perms.add("merchant:merchant:ban");await start(h(PlatformOrderList,{pendingOnly:true}));expect(options()).toEqual(["","ENTITY","STORE"]);
});
it("separates global order and entity detail caches when an operator session changes", async () => {
 s.perms.add("commerce:platform:orders:read");s.perms.add("merchant:merchant:read");
 s.orders.mockResolvedValue({items:[{model:"ENTITY",orderNo:"E-ORDER",entityNo:"E1",storeNo:"S1",currency:"TWD",payableMinor:"100",status:"SUBMITTED",claimStatus:"SUBMITTED",createdAt:1}],nextCursor:"next",includedModels:["ENTITY"],queriedAt:1});
 await start(h(PlatformOrderList));await act(async()=>button("详情").click());await flush();expect(s.detail).toHaveBeenCalledOnce();
 const firstKeys=client.getQueryCache().getAll().filter(q=>q.state.status==="success").map(q=>q.queryKey);
 s.auth={...s.auth,username:"operator2",token:"session2"};await start(h(PlatformOrderList));expect(document.body.textContent).not.toContain("ENTITY-DETAIL");expect(s.orders).toHaveBeenCalledTimes(2);
 await act(async()=>button("详情").click());await flush();expect(s.detail).toHaveBeenCalledTimes(2);
 const detailKeys=client.getQueryCache().getAll().filter(q=>q.queryKey[0]==="entity-plan-purchase" && q.state.status==="success").map(q=>q.queryKey);expect(detailKeys).toHaveLength(2);expect(detailKeys[0]).not.toEqual(detailKeys[1]);expect(JSON.stringify(firstKeys)).not.toContain("session1");expect(JSON.stringify(detailKeys)).not.toContain("session2");
});
it("starts a new cash query and resets paging after same-user reauthentication", async () => {
 s.perms.add("commerce:platform:cash:read");s.cash.mockResolvedValue({summary:[],items:[],nextCursor:"next",queriedAt:1,coverage:"CONSISTENT_PER_REQUEST"});await start(h(PlatformCashReport,{details:true}));await act(async()=>button("下一页").click());await flush();expect(s.cash.mock.calls[1][0].after).toBe("next");
 s.auth={...s.auth,token:"new-session-for-same-user"};await start(h(PlatformCashReport,{details:true}));expect(s.cash).toHaveBeenCalledTimes(3);expect(s.cash.mock.calls[2][0].after).toBeUndefined();
 const keys=client.getQueryCache().getAll().filter(q=>q.queryKey[0]==="platform-mall-cash").map(q=>q.queryKey);expect(keys).toHaveLength(3);expect(keys[0][1]).not.toEqual(keys[2][1]);expect(JSON.stringify(keys)).not.toContain("new-session-for-same-user");
});
it("retains entity details when close is requested with a pending financial command", async () => {
 s.perms.add("commerce:platform:orders:read");s.perms.add("merchant:merchant:read");s.orders.mockResolvedValue({items:[{model:"ENTITY",orderNo:"E-ORDER",entityNo:"E1",storeNo:"S1",currency:"TWD",payableMinor:"100",status:"SUBMITTED",claimStatus:"SUBMITTED",createdAt:1}],nextCursor:null,includedModels:["ENTITY"],queriedAt:1});
 await start(h(PlatformOrderList));await act(async()=>button("详情").click());await flush();await act(async()=>button("ENTITY-DETAIL").click());await flush();
 const close=()=>document.querySelector('[role="dialog"] button') as HTMLButtonElement;await act(async()=>close().click());await flush();expect(document.body.textContent).toContain("ENTITY-PENDING");expect((host.querySelector('input[aria-label="主体编号"]') as HTMLInputElement).disabled).toBe(true);
 await act(async()=>button("ENTITY-PENDING").click());await flush();await act(async()=>close().click());await flush();expect(document.body.textContent).not.toContain("ENTITY-DETAIL");
});
it("drops cached reports when effective permissions are refreshed",async()=>{
 s.perms.add("commerce:platform:orders:read");s.auth.perms=["commerce:platform:orders:read","merchant:merchant:read"];s.orders.mockResolvedValueOnce({items:[{model:"ENTITY",orderNo:"OLD-PRIVILEGED-ORDER",entityNo:"E1",storeNo:"S1",currency:"TWD",payableMinor:"100",status:"SUBMITTED",claimStatus:null,createdAt:1}],nextCursor:null,includedModels:["ENTITY"],queriedAt:1});
 await start(h(PlatformOrderList));expect(host.textContent).toContain("OLD-PRIVILEGED-ORDER");s.auth.perms=["commerce:platform:orders:read"];await start(h(PlatformOrderList));expect(s.orders).toHaveBeenCalledTimes(2);expect(host.textContent).not.toContain("OLD-PRIVILEGED-ORDER");
});
it("retains store details when a nested cash command holds the shared financial lock",async()=>{
 s.perms.add("commerce:platform:orders:read");s.perms.add("commerce:mall:order:read");s.orders.mockResolvedValue({items:[{model:"STORE",orderNo:"S-ORDER",entityNo:"E1",storeNo:"S1",currency:"TWD",payableMinor:"100",status:"PAID",claimStatus:null,createdAt:1}],nextCursor:null,includedModels:["STORE"],queriedAt:1});
 await start(h(PlatformOrderList));await act(async()=>button("详情").click());await flush();await act(async()=>button("STORE-LOCK").click());await flush();await act(async()=>button("STORE-DETAIL").click());await flush();expect(host.textContent).toContain("STORE-PENDING");
 await act(async()=>button("STORE-PENDING").click());await flush();await act(async()=>button("STORE-DETAIL").click());await flush();expect(host.textContent).not.toContain("STORE-DETAIL");
});
