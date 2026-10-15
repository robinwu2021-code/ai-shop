// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import "./component-setup";
import type { Order } from "@shared/types";
const m = vi.hoisted(() => ({ detail:vi.fn(),prepare:vi.fn(),precheck:vi.fn(),ship:vi.fn(),trace:vi.fn(),can:vi.fn(),load:undefined as undefined|((q:Record<string,string>)=>unknown) }));
vi.mock("@/api",()=>({api:{mOrderDetail:m.detail,mPrepare:m.prepare,mShipPrecheck:m.precheck,mShip:m.ship,mOrderTrace:m.trace,mExpressPickup:vi.fn(),mShipDefaults:vi.fn()}}));
vi.mock("@/stores/merchant",()=>({useMerchantStore:()=>({can:m.can,ensureScope:vi.fn(),storeNo:"ST1"})}));
vi.mock("@/shared/mch-subscribe",()=>({askMchSubscribe:vi.fn()}));
vi.mock("vue-i18n",()=>({useI18n:()=>({t:(k:string)=>k})}));
vi.mock("@dcloudio/uni-app",()=>({onLoad:(cb:typeof m.load)=>{m.load=cb;},onShow:vi.fn(),onPullDownRefresh:vi.fn(),onHide:vi.fn()}));
import Page from "@/pages/order/index.vue";
const order=(extra:Partial<Order>={}):Order=>({orderNo:"SUB1",merchantName:"店一",status:"PAID",fulfillment:"EXPRESS",items:[],amount:{paidMinor:1000,payableMinor:1000},timeline:[],createdAt:1,...extra} as Order);
async function flush(){for(let i=0;i<25;i++)await Promise.resolve();}
async function render(){const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{"sh-scaffold":{template:"<div><slot/></div>"}}}});m.load?.({orderNo:"SUB1"});await flush();return w;}
beforeEach(()=>{vi.clearAllMocks();m.can.mockReturnValue(true);m.detail.mockResolvedValue(order());
  m.precheck.mockResolvedValue({verdict:"UNKNOWN",checkedAt:Date.now(),confirmationRequired:false});});
describe("B端单次备货发货",()=>{
 it("售后申请期间不提供备货和发货",async()=>{m.detail.mockResolvedValue(order({afterSale:{status:"APPLIED"} as Order["afterSale"]}));const w=await render();expect(w.text()).not.toContain("order.prepare");expect(w.find(".ship").exists()).toBe(false);w.unmount();});
 it("无权限不提供发货",async()=>{m.can.mockImplementation((p:string)=>p!=="biz:ship");const w=await render();expect(w.text()).not.toContain("order.prepare");w.unmount();});
 it("普通详情不主动查询轨迹",async()=>{m.detail.mockResolvedValue(order({status:"FULFILLING",expressNo:"SF123",trace:{nodes:[],status:"TRANSIT"} as Order["trace"]}));const w=await render();expect(w.text()).toContain("SF123");expect(m.trace).not.toHaveBeenCalled();w.unmount();});
 it("备货成功回读当前子单",async()=>{m.prepare.mockResolvedValue(order({preparedAt:123}));const w=await render();const b=w.findAll(".sh-btn").find(n=>n.text()==="order.prepare");expect(b).toBeDefined();await b!.trigger("tap");await flush();expect(m.prepare).toHaveBeenCalledWith("SUB1");expect(m.detail.mock.calls.length).toBeGreaterThan(1);w.unmount();});
 it("整张门店子单发货后回读运单，失败保留输入",async()=>{
   const w=await render();
   await w.findAll("text").find(n=>n.text()==="order.shipMode.SELF")!.trigger("tap");
   await w.get("input.field__input").setValue("SF123456");
   await w.findAll(".carrier__chip")[0].trigger("tap");
   m.ship.mockRejectedValueOnce(new Error("运单错误"));
   const ship=()=>w.findAll(".sh-btn").find(n=>n.text()==="order.ship")!;
   await ship().trigger("tap"); await flush();
   expect((w.get("input.field__input").element as HTMLInputElement).value).toBe("SF123456");
   m.ship.mockImplementation(async()=>{m.detail.mockResolvedValue(order({status:"FULFILLING",expressNo:"SF123456"}));return order({status:"FULFILLING"});});
   await ship().trigger("tap");await flush();
   expect(m.precheck).toHaveBeenCalledWith("SUB1","SF123456",expect.any(String));
   expect(m.ship).toHaveBeenLastCalledWith("SUB1","SF123456",expect.any(String),false);
   expect(m.detail.mock.calls.length).toBeGreaterThan(1);expect(w.text()).toContain("SF123456");w.unmount();
 });

});
