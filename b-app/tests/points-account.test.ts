// @vitest-environment happy-dom
import { expect, it, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
const m = vi.hoisted(() => ({ show: (() => {}) as () => void, account: vi.fn(), terms: vi.fn(), status: "ACTIVE", paused: false }));
vi.mock("@dcloudio/uni-app", () => ({ onLoad: vi.fn(), onShow: (fn: () => void) => { m.show = fn; } }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ storeNo: "S", currentStore: {name:"Store"}, can: () => true, ensureStores: async () => {}, ensureScope: async () => {} }) }));
vi.mock("@/api", () => ({ api: { mStorePointTypes: async () => [{typeCode:"P",name:"平台积分",status:m.status,earningPaused:m.paused,currency:"CNY",rate:{pointsUnit:1,minorUnit:1}}], mStorePointAccount:m.account, mStorePointTerms:m.terms } }));
vi.mock("@/components/points/StorePointStatements.vue", () => ({default:{template:"<div>Statements</div>"}}));
import Page from "@/pages/points/index.vue";
beforeEach(() => {vi.clearAllMocks();m.status="ACTIVE";m.paused=false;m.account.mockResolvedValue({exists:false,snapshot:{buckets:{},frozen:false}});});
it("loads account without terms or signing and displays names with zero buckets",async()=>{
 const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-empty':true}}});
 m.show();await flushPromises();
 expect(w.text()).toContain("平台积分");expect(w.text()).toContain("points.bucketAVAILABLE");
 expect(w.text()).not.toContain("points.bucketISSUABLE");expect(w.text()).not.toContain("points.bucketHELD");
 expect(m.account).toHaveBeenCalledWith("P");expect(m.terms).not.toHaveBeenCalled();
 expect(w.text()).not.toContain("points.sign");expect(w.text()).not.toContain("Statements");w.unmount();
});

it("shows a business status instead of a raw lifecycle code",async()=>{
 m.status="DRAINING";
 const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-empty':true}}});
 m.show();await flushPromises();expect(w.text()).toContain("points.typeStateDraining");expect(w.text()).not.toContain("DRAINING");w.unmount();
});
it("distinguishes earning pause from retiring the existing balance",async()=>{
 m.paused=true;
 const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-empty':true}}});
 m.show();await flushPromises();expect(w.text()).toContain("points.typeStateEarningPaused");expect(w.text()).toContain("points.earningPausedHint");expect(w.text()).not.toContain("ACTIVE");w.unmount();
});
