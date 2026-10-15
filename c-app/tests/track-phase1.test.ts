import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
const m=vi.hoisted(()=>({track:vi.fn(),mine:vi.fn(),miniLink:vi.fn(),load:undefined as undefined|((q:Record<string,string>)=>void)}));
vi.mock("@/api",()=>({api:{track:m.track,trackMine:m.mine,trackMiniLink:m.miniLink},ApiError:class ApiError extends Error{code:number;constructor(code:number){super();this.code=code;}}}));
vi.mock("@/stores/user",()=>({useUserStore:()=>({isLogin:true})}));
vi.mock("vue-i18n",()=>({useI18n:()=>({t:(k:string)=>k})}));
vi.mock("@dcloudio/uni-app",()=>({onLoad:(cb:typeof m.load)=>{m.load=cb;},onShareAppMessage:vi.fn(),onShow:vi.fn(),onHide:vi.fn()}));
import Page from "@/pages/track/index.vue";
async function flush(){for(let i=0;i<20;i++)await Promise.resolve();}
async function render(){const w=mount(Page,{global:{mocks:{$t:(k:string)=>k},stubs:{"sh-scaffold":{template:"<div><slot/></div>"},"sh-empty":{props:["text","tip","failed"],emits:["retry"],template:'<div>{{text}}{{tip}}<button v-if="failed" @click="$emit(\'retry\')">retry</button></div>'}}}});m.load?.({t:"token"});await flush();return w;}
beforeEach(()=>{vi.clearAllMocks();m.mine.mockResolvedValue(null);m.miniLink.mockResolvedValue(null);});
describe("物流事实与失败重试",()=>{
 it("网络错误显示重试，不能冒充令牌失效",async()=>{m.track.mockRejectedValueOnce(new Error("network"));const w=await render();expect(w.text()).toContain("track.loadFailed");expect(w.text()).not.toContain("track.invalidTitle");m.track.mockResolvedValue({status:"FULFILLING",expressNo:"SF1",items:[]});await w.get("button").trigger("click");await flush();expect(w.text()).toContain("SF1");expect(w.text()).toContain("track.noTrace");w.unmount();});
 it("真实无轨迹显示无轨迹而非虚构签收节点",async()=>{m.track.mockResolvedValue({status:"FULFILLING",expressNo:"SF2",items:[],trace:{nodes:[]}});const w=await render();expect(w.text()).toContain("track.noTrace");expect(w.text()).not.toContain("trace.step.signed");w.unmount();});
 it.each([["CREATED","trace.awaitingPickup"],["EXCEPTION","trace.exception"],["CANCELLED","trace.cancelled"]])("物流状态 %s 不冒充正常揽收运输",async(status,label)=>{m.track.mockResolvedValue({status:"FULFILLING",expressNo:"SF2",items:[],trace:{status,nodes:[]}});const w=await render();expect(w.text()).toContain(label);w.unmount();});

 it("微信链接可用时显示跳转入口和身份说明",async()=>{m.track.mockResolvedValue({status:"FULFILLING",items:[]});m.miniLink.mockResolvedValue({url:"https://wxaurl.cn/example"});const w=await render();expect(w.text()).toContain("track.openInMini");expect(w.text()).toContain("track.miniJumpHint");w.unmount();});
 it("微信链接不可用时说明仍可在本页查看",async()=>{m.track.mockResolvedValue({status:"FULFILLING",items:[]});const w=await render();expect(w.text()).toContain("track.miniUnavailable");expect(w.text()).not.toContain("track.openInMini");w.unmount();});

});
