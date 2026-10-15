// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { uniMock } from "./component-setup";
import type { AfterSale } from "@shared/types";
const m=vi.hoisted(()=>({list:vi.fn(),orders:vi.fn(),approve:vi.fn(),receive:vi.fn(),reject:vi.fn(),show:undefined as undefined|(()=>void)}));
vi.mock("@/api",()=>({api:{mAfterSaleList:m.list,mOrderList:m.orders,mApproveAfterSale:m.approve,mConfirmReturn:m.receive,mRejectAfterSale:m.reject}}));
vi.mock("@/stores/merchant",()=>({useMerchantStore:()=>({can:()=>true})}));
vi.mock("@/shared/mch-subscribe",()=>({askMchSubscribe:vi.fn()}));
vi.mock("vue-i18n",()=>({useI18n:()=>({t:(k:string)=>k})}));
vi.mock("@dcloudio/uni-app",()=>({onShow:(cb:typeof m.show)=>{m.show=cb;},onLoad:vi.fn(),onHide:vi.fn()}));
import Page from "@/pages/after-sale/index.vue";
async function flush(){for(let i=0;i<25;i++)await Promise.resolve();}
const sale=(extra:Partial<AfterSale>={})=>({afterSaleNo:"AS1",subOrderNo:"SUB1",status:"APPLIED",type:"REFUND_ONLY",refundMinor:1000,fulfillStage:"RECEIVED",...extra} as AfterSale);
async function render(s:AfterSale){m.list.mockResolvedValue([s]);const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{"sh-scaffold":{template:"<div><slot/></div>"}}}});m.show?.();await flush();return w;}
beforeEach(()=>{vi.clearAllMocks();m.orders.mockResolvedValue({records:[]});m.approve.mockResolvedValue(sale({status:"REFUNDING"}));m.receive.mockResolvedValue(sale({status:"REFUNDING"}));});
describe("商家基础全额退款",()=>{
 it("已收货同意也不展开部分退款或积分形式",async()=>{const w=await render(sale());await w.findAll("text").find(n=>n.text()==="afterSale.agree")!.trigger("tap");await flush();expect(m.approve).toHaveBeenCalledWith("AS1","",undefined,"CASH");expect(w.find("input").exists()).toBe(false);expect(w.text()).not.toContain("afterSale.refundModePoints");w.unmount();});
 it.each([false,true])("收到退货明确传入回库决定 %s，受理不弹到账",async(restock)=>{const w=await render(sale({status:"RETURNING",type:"RETURN_REFUND",returnExpressNo:"SF9"}));if(restock)await w.get(".restock-option").trigger("tap");await w.findAll("text").find(n=>n.text()==="afterSale.confirmReceived")!.trigger("tap");await flush();expect(m.receive).toHaveBeenCalledWith("AS1",restock);expect(uniMock.showToast).not.toHaveBeenCalledWith(expect.objectContaining({title:"afterSale.refunded"}));expect(uniMock.showToast).toHaveBeenCalledWith(expect.objectContaining({title:"afterSale.accepted"}));w.unmount();});
 it("失败与资源待恢复显示对应进度",async()=>{const w=await render(sale({refundExecution:{status:"FAILED",resourceStatus:"PENDING"} as AfterSale["refundExecution"]}));expect(w.text()).toContain("afterSale.execution.failed");w.unmount();});
 it("拒绝必须说明理由并按原售后号提交",async()=>{const w=await render(sale());await w.findAll("text").find(n=>n.text()==="afterSale.reject")!.trigger("tap");const reject=()=>w.findAll("text").find(n=>n.text()==="afterSale.confirmReject")!;await reject().trigger("tap");expect(m.reject).not.toHaveBeenCalled();await w.get("textarea").setValue("非本店商品");await reject().trigger("tap");await flush();expect(m.reject).toHaveBeenCalledWith("AS1","非本店商品");w.unmount();});

});
