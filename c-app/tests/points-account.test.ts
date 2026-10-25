// @vitest-environment happy-dom
import { expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
const m=vi.hoisted(()=>({show:(()=>{}) as ()=>void,entries:vi.fn(),status:"ACTIVE"}));
vi.mock("@dcloudio/uni-app",()=>({onShow:(fn:()=>void)=>{m.show=fn;}}));
vi.mock("@/api",()=>({api:{pointsAccounts:async()=>[{typeCode:"P",name:"平台积分",currency:"CNY",status:m.status,balance:10,ratePoints:1,rateMinor:1},{typeCode:"Q",name:"奖励积分",currency:"CNY",status:"ACTIVE",balance:20,ratePoints:1,rateMinor:1}],pointsEntries:m.entries}}));
import Page from "@/pages/points/index.vue";
it("shows names and discards a previous type's late response",async()=>{
 let finish!:(value:unknown)=>void;
 m.entries.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;})).mockResolvedValue({rows:[{entryNo:"Q1",bucket:"AVAILABLE",points:20,balanceAfter:20,at:1,direction:"EARN"}],next:1,hasMore:false});
 const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-tabs':{props:['items'],template:'<button @click="$emit(\'change\',\'Q\')">{{items[1].label}}</button>'},'sh-empty':true}}});
 m.show();await flushPromises();
 expect(w.text()).toContain("平台积分");
 await w.get('button').trigger('click');await flushPromises();
 finish({rows:[{entryNo:"OLD",points:999,bucket:"AVAILABLE",at:1}],hasMore:false,next:1});await flushPromises();
 expect(w.text()).toContain("奖励积分");expect(w.text()).not.toContain("999");expect(w.text()).toContain("points.directionEARN");w.unmount();
});

it("shows the actual lifecycle status and keeps balance visible while draining",async()=>{
 m.status="DRAINING";m.entries.mockResolvedValue({rows:[],next:0,hasMore:false});
 const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-tabs':true,'sh-empty':true}}});
 m.show();await flushPromises();expect(w.text()).toContain("平台积分");expect(w.text()).toContain("10");expect(w.text()).toContain("points.typeStateDraining");expect(w.text()).not.toContain("points.notActive");w.unmount();m.status="ACTIVE";
});
