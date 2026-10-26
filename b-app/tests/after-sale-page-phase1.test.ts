// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { uniMock } from "./component-setup";
import type { AfterSale } from "@shared/types";
const m=vi.hoisted(()=>({list:vi.fn(),approve:vi.fn(),receive:vi.fn(),reject:vi.fn(),show:undefined as undefined|(()=>void)}));
vi.mock("@/api",()=>({api:{mAfterSaleWorklist:m.list,mApproveAfterSale:m.approve,mConfirmReturn:m.receive,mRejectAfterSale:m.reject}}));
vi.mock("@/stores/merchant",()=>({useMerchantStore:()=>({can:()=>true})}));
vi.mock("@/shared/mch-subscribe",()=>({askMchSubscribe:vi.fn()}));
vi.mock("vue-i18n",()=>({useI18n:()=>({t:(k:string)=>k})}));
vi.mock("@dcloudio/uni-app",()=>({onShow:(cb:typeof m.show)=>{m.show=cb;},onLoad:vi.fn(),onHide:vi.fn(),onReachBottom:vi.fn()}));
import Page from "@/pages/after-sale/index.vue";
async function flush(){for(let i=0;i<25;i++)await Promise.resolve();}
const sale=(extra:Partial<AfterSale>={})=>({afterSaleNo:"AS1",subOrderNo:"SUB1",status:"APPLIED",type:"REFUND_ONLY",refundMinor:1000,fulfillStage:"RECEIVED",...extra} as AfterSale);
const page=(sales:AfterSale[])=>({records:sales.map(afterSale=>({afterSale,order:undefined})),total:sales.length,page:1,size:20});
async function render(s:AfterSale){m.list.mockResolvedValue(page([s]));const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{"sh-scaffold":{template:"<div><slot/></div>"}}}});m.show?.();await flush();return w;}
beforeEach(()=>{vi.clearAllMocks();m.approve.mockResolvedValue(sale({status:"REFUNDING"}));m.receive.mockResolvedValue(sale({status:"REFUNDING"}));});
describe("商家基础全额退款",()=>{
 it("默认只展示待处理申请，切换后可查看全部",async()=>{m.list.mockImplementation(async(q:{pending:boolean})=>page(q.pending?[sale()]:[sale(),sale({afterSaleNo:"AS2",status:"REFUNDED"})]));const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{"sh-scaffold":{template:"<div><slot/></div>"}}}});m.show?.();await flush();expect(w.findAll(".sh-card")).toHaveLength(1);await w.findAll("text").find(n=>n.text()==="afterSale.allTab")!.trigger("tap");await flush();expect(w.findAll(".sh-card")).toHaveLength(2);w.unmount();});
 it("已收货同意也不展开部分退款或积分形式",async()=>{const w=await render(sale());await w.findAll("text").find(n=>n.text()==="afterSale.agree")!.trigger("tap");await flush();expect(m.approve).toHaveBeenCalledWith("AS1","",undefined,"CASH");expect(w.find("input").exists()).toBe(false);expect(w.text()).not.toContain("afterSale.refundModePoints");w.unmount();});
 it("未录入实收件数不能确认；收到退货提交实收数量与品质",async()=>{const w=await render(sale({status:"RETURNING",type:"RETURN_REFUND",returnExpressNo:"SF9",returnItems:[{itemId:11,title:"商品A",requestedQty:2}]}));await w.findAll("text").find(n=>n.text()==="afterSale.confirmReceived")!.trigger("tap");await flush();expect(m.receive).not.toHaveBeenCalled();expect(uniMock.showToast).toHaveBeenCalledWith(expect.objectContaining({title:"afterSale.receiptAllRequired"}));await w.findAll(".stepper__btn")[1]!.trigger("tap");await w.findAll(".stepper__btn")[1]!.trigger("tap");await w.findAll("text").find(n=>n.text()==="afterSale.confirmReceived")!.trigger("tap");await flush();expect(m.receive).toHaveBeenCalledWith("AS1",{receiptNo:"RR-AS1",lines:[{itemId:11,receivedQty:2,quality:"UNSALEABLE"}]});expect(uniMock.showToast).not.toHaveBeenCalledWith(expect.objectContaining({title:"afterSale.refunded"}));expect(uniMock.showToast).toHaveBeenCalledWith(expect.objectContaining({title:"afterSale.accepted"}));w.unmount();});
 it("将退货入库进度与退款资金进度分开展示",async()=>{const w=await render(sale({status:"REFUNDING",type:"RETURN_REFUND",returnInventoryStatus:"PENDING"}));expect(w.text()).toContain("afterSale.returnInventory.PENDING");expect(w.text()).toContain("afterSale.refundingHint");w.unmount();});
 it("失败与资源待恢复显示对应进度",async()=>{const w=await render(sale({refundExecution:{status:"FAILED",resourceStatus:"PENDING"} as AfterSale["refundExecution"]}));expect(w.text()).toContain("afterSale.execution.failed");w.unmount();});
 it("拒绝必须说明理由并按原售后号提交",async()=>{const w=await render(sale());await w.findAll("text").find(n=>n.text()==="afterSale.reject")!.trigger("tap");const reject=()=>w.findAll("text").find(n=>n.text()==="afterSale.confirmReject")!;await reject().trigger("tap");expect(m.reject).not.toHaveBeenCalled();await w.get("textarea").setValue("非本店商品");await reject().trigger("tap");await flush();expect(m.reject).toHaveBeenCalledWith("AS1","非本店商品");w.unmount();});

});
