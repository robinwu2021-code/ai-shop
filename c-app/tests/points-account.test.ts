// @vitest-environment happy-dom
import { expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
const m=vi.hoisted(()=>({show:(()=>{}) as ()=>void,entries:vi.fn(),status:"ACTIVE",paused:false}));
vi.mock("@dcloudio/uni-app",()=>({onShow:(fn:()=>void)=>{m.show=fn;}}));
vi.mock("@/api",()=>({api:{pointsAccounts:async()=>[{typeCode:"P",name:"平台积分",currency:"CNY",status:m.status,earningPaused:m.paused,balance:10,ratePoints:1,rateMinor:1},{typeCode:"Q",name:"奖励积分",currency:"CNY",status:"ACTIVE",balance:20,ratePoints:1,rateMinor:1}],pointsEntries:m.entries}}));
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

it("shows paused earning while retaining balances and lifecycle priority",async()=>{
 m.paused=true;m.status="ACTIVE";m.entries.mockResolvedValue({rows:[],next:0,hasMore:false});
 const options={global:{mocks:{$t:(k:string)=>k},stubs:{'sh-scaffold':{template:'<div><slot/></div>'},'sh-tabs':true,'sh-empty':true}}};
 const w=mount(Page,options);m.show();await flushPromises();
 expect(w.text()).toContain("points.typeStateEarningPaused");expect(w.text()).toContain("10");w.unmount();
 m.status="DRAINING";const draining=mount(Page,options);m.show();await flushPromises();
 expect(draining.text()).toContain("points.typeStateDraining");expect(draining.text()).not.toContain("points.typeStateEarningPaused");
 draining.unmount();m.paused=false;m.status="ACTIVE";
});
