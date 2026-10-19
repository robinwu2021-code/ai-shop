import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import type { Order } from "@shared/types";
const m=vi.hoisted(()=>({detail:vi.fn(),apply:vi.fn(),load:undefined as undefined|((q:Record<string,string>)=>void)}));
vi.mock("@/api",()=>({api:{orderDetail:m.detail,applyAfterSale:m.apply,afterSaleReasons:vi.fn(async()=>["NOT_WANTED"]),subscribeReport:vi.fn()}}));
vi.mock("@shared/ports/push",()=>({SUBSCRIBE_TMPL:{afterSaleResult:"A",returnWait:"B"},requestSubscribe:vi.fn(async()=>({accepted:[],rejected:[]}))}));
vi.mock("vue-i18n",()=>({useI18n:()=>({t:(k:string)=>k})}));
vi.mock("@dcloudio/uni-app",()=>({onLoad:(cb:typeof m.load)=>{m.load=cb;},onShow:vi.fn(),onHide:vi.fn()}));
import Page from "@/pages/after-sale/index.vue";
function order(extra:Partial<Order>={}):Order{return {orderNo:"SUB1",merchantName:"店一",status:"PAID",fulfillment:"EXPRESS",items:[],amount:{goodsMinor:1000,payableMinor:1000,paidMinor:1000,freightMinor:0,discountMinor:0,pointsUsed:0,pointsEarn:0,pointsDeductMinor:0},timeline:[],...extra} as Order;}
async function flush(){for(let i=0;i<25;i++)await Promise.resolve();}
async function render(){const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{"sh-scaffold":{template:"<div><slot/></div>"}}}});m.load?.({orderNo:"SUB1"});await flush();return w;}
beforeEach(()=>{vi.clearAllMocks();m.detail.mockResolvedValue(order());});
describe("买家基础全额退款",()=>{
 it("提交门店子单，不传金额或积分退款；受理后回读进度",async()=>{m.apply.mockImplementation(async()=>{m.detail.mockResolvedValue(order({afterSale:{status:"REFUNDING",refundExecution:{status:"PROCESSING",resourceStatus:"PENDING"}} as Order["afterSale"]}));return {status:"REFUNDING"};});const w=await render();expect(w.text()).toContain("afterSale.fullCash");const reason=w.findAll(".sh-seg").find(n=>n.text()==="afterSale.reason.NOT_WANTED");expect(reason).toBeDefined();await reason!.trigger("tap");await w.findAll(".sh-btn").find(n=>n.text()==="afterSale.submit")!.trigger("tap");await flush();expect(m.apply).toHaveBeenCalledWith("SUB1","afterSale.reason.NOT_WANTED",[],"REFUND_ONLY","NOT_WANTED");expect(w.text()).toContain("afterSale.execution.processing");expect(w.text()).not.toContain("afterSale.refundedHint");w.unmount();});
 it("已有退款执行重新进入时只展示进度，不再申请一笔",async()=>{m.detail.mockResolvedValue(order({afterSale:{status:"REFUNDING",refundExecution:{status:"UNKNOWN",resourceStatus:"PENDING"}} as Order["afterSale"]}));const w=await render();expect(w.text()).toContain("afterSale.execution.unknown");expect(w.text()).not.toContain("afterSale.submit");expect(m.apply).not.toHaveBeenCalled();w.unmount();});
});
