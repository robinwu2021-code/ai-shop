// @vitest-environment happy-dom
import { expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import Card from "@ai-shop/ui/components/sh-points-earning.vue";
import type { OrderPointsEarning } from "@shared/types";
it("accepted work does not appear credited and retry remains available after a read failure", async () => {
 const row:OrderPointsEarning={orderNo:"S",typeCode:"P",name:"平台积分",currency:"CNY",state:"PROCESSING",redemptionState:"NONE",usedPoints:0,refundState:"NONE",returnedPoints:0,expectedPoints:100,grantedPoints:0,pendingPoints:0,availablePoints:0,reclaimedPoints:0,reason:null};
 const w=mount(Card,{props:{rows:[row]},global:{mocks:{$t:(k:string)=>k}}});
 expect(w.text()).toContain("points.earningPROCESSING");expect(w.text()).not.toContain("points.earningAVAILABLE");
 await w.setProps({rows:[{...row,state:"PENDING",grantedPoints:100,pendingPoints:100}]});
 expect(w.text()).toContain("points.earningPENDING");expect(w.text()).not.toContain("points.earningAVAILABLE");
 await w.setProps({rows:[{...row,state:"NONE",redemptionState:"PARTIALLY_RETURNED",usedPoints:100,refundState:"PROCESSING",returnedPoints:25}]});
 expect(w.text()).toContain("points.redemptionPARTIALLY_RETURNED");expect(w.text()).toContain("points.refundPROCESSING");expect(w.text()).toContain("25");expect(w.text()).not.toContain("points.earningExpected");
 await w.setProps({failed:true});await w.get('.sh-btn').trigger('tap');expect(w.emitted('retry')).toHaveLength(1);w.unmount();
});
