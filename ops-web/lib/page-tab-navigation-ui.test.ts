// @vitest-environment happy-dom
import {act,createElement as h} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
const state=vi.hoisted(()=>({query:'tab=list&merchantNo=M1&locale=zh-TW',push:vi.fn()}));
vi.mock('next/navigation',()=>({useSearchParams:()=>new URLSearchParams(state.query),usePathname:()=>'/merchants',useRouter:()=>({push:state.push})}));
import {usePageTab,type PageTab} from './use-page-tab';
const tabs:PageTab[]=[{key:'audit',label:'入驻审核',href:'/merchants'},{key:'list',label:'商家档案',href:'/merchants?tab=list'},{key:'stores',label:'门店档案',href:'/merchants?tab=stores'}];
let host:HTMLDivElement,root:Root;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;state.query='tab=list&merchantNo=M1&locale=zh-TW';state.push.mockReset();host=document.createElement('div');document.body.append(host);root=createRoot(host);});
afterEach(async()=>{await act(async()=>root.unmount());host.remove();});
function Harness({choices=tabs,onSwitch=()=>{}}:{choices?:PageTab[];onSwitch?:()=>void}){const [tab,setTab]=usePageTab(choices,onSwitch);return h('div',null,h('output',null,tab),...choices.map(t=>h('button',{key:t.key,onClick:()=>setTab(t.key)},t.key)));}
async function click(key:string){await act(async()=>[...host.querySelectorAll('button')].find(b=>b.textContent===key)!.click());}
it('changes the actual route along with the rendered view and preserves merchant scope',async()=>{
 const reset=vi.fn();await act(async()=>root.render(h(Harness,{onSwitch:reset})));await click('stores');
 expect(host.querySelector('output')?.textContent).toBe('stores');expect(reset).toHaveBeenCalledTimes(1);
 expect(state.push).toHaveBeenCalledExactlyOnceWith('/merchants?merchantNo=M1&locale=zh-TW&tab=stores',{scroll:false});
});
it('uses the real default leaf URL rather than inventing tab=audit',async()=>{
 await act(async()=>root.render(h(Harness)));await click('audit');
 expect(state.push).toHaveBeenCalledExactlyOnceWith('/merchants?merchantNo=M1&locale=zh-TW',{scroll:false});
});
it('follows browser route changes and retains the simple caller fallback',async()=>{
 await act(async()=>root.render(h(Harness,{choices:tabs.map(({key,label})=>({key,label}))})));
 state.query='tab=stores';await act(async()=>root.render(h(Harness,{choices:tabs.map(({key,label})=>({key,label}))})));
 expect(host.querySelector('output')?.textContent).toBe('stores');await click('list');
 expect(state.push).toHaveBeenCalledExactlyOnceWith('/merchants?tab=list',{scroll:false});
});

it('returns to the actual default view when browser history removes the tab selector',async()=>{
 state.query='tab=stores&merchantNo=M1';await act(async()=>root.render(h(Harness)));
 expect(host.querySelector('output')?.textContent).toBe('stores');
 state.query='merchantNo=M1';await act(async()=>root.render(h(Harness)));
 expect(host.querySelector('output')?.textContent).toBe('audit');expect(state.push).not.toHaveBeenCalled();
});
it('resolves a default URL from leaf metadata even when server sorting moves it away from first',async()=>{
 state.query='';const reordered=[tabs[1],tabs[2],tabs[0]];
 await act(async()=>root.render(h(Harness,{choices:reordered})));
 expect(host.querySelector('output')?.textContent).toBe('audit');
});
