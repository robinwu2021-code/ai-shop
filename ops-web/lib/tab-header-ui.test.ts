// @vitest-environment happy-dom
import {act,createElement as h} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
vi.mock('@/lib/i18n',()=>({useI18n:()=>({t:(k:string)=>k==='common.switchView'?'切换视图':k})}));
import {TabHeader} from '@/components/ui/tab-header';
let host:HTMLDivElement,root:Root;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;host=document.createElement('div');document.body.append(host);root=createRoot(host);});
afterEach(async()=>{await act(async()=>root.unmount());host.remove();});
it('keeps the current heading and primary action visible, and opens grouped choices only on request',async()=>{
 const change=vi.fn();
 await act(async()=>root.render(h(TabHeader,{tabs:[{key:'audit',label:'入驻审核',group:'入驻'},{key:'list',label:'商家档案',group:'档案'},{key:'behalf',label:'代办入驻',group:'入驻'}],value:'list',onChange:change,action:h('button',null,'创建')})));
 expect(host.querySelector('h1')?.textContent).toBe('商家档案');
 expect(document.querySelector('[role="menuitem"]')).toBeNull();
 const trigger=[...host.querySelectorAll('button')].find(b=>b.textContent==='切换视图')!;
 expect(trigger).toBeDefined();expect(host.textContent).toContain('创建');
 await act(async()=>{trigger.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));});
 const menu=document.querySelector('[role="menu"]')!;expect(menu).not.toBeNull();
 expect(menu.querySelector('[aria-current="page"]')?.textContent).toBe('商家档案');
 expect([...menu.querySelectorAll('[role="group"]')].map(g=>g.textContent)).toEqual(['入驻入驻审核代办入驻','档案商家档案']);
 await act(async()=>{[...menu.querySelectorAll<HTMLElement>('[role="menuitem"]')].find(x=>x.textContent==='代办入驻')!.click();});
 expect(change).toHaveBeenCalledExactlyOnceWith('behalf');expect(document.querySelector('[role="menu"]')).toBeNull();
});
it('renders a single view as a heading without an unnecessary switcher',async()=>{
 await act(async()=>root.render(h(TabHeader,{tabs:[{key:'list',label:'商家档案'}],value:'list',onChange:vi.fn(),desc:'商户基础信息'})));
 expect(host.querySelector('h1')?.textContent).toBe('商家档案');expect(host.textContent).toContain('商户基础信息');expect(host.querySelector('button')).toBeNull();
});
