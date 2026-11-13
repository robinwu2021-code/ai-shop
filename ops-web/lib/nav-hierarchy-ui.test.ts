// @vitest-environment happy-dom
import {act,createElement as h} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import type {NavSection} from './nav';
const state=vi.hoisted(()=>({nav:[] as NavSection[],tab:'list'}));
vi.mock('next/navigation',()=>({usePathname:()=>'/merchants',useSearchParams:()=>new URLSearchParams({tab:state.tab})}));
vi.mock('next/link',()=>({default:({children,...p}:React.ComponentProps<'a'>)=>h('a',p,children)}));
vi.mock('@/lib/auth',()=>({useAuth:(s:(v:unknown)=>unknown)=>s({perms:['*']})}));
vi.mock('@/lib/stores/nav-prefs',()=>({useNavPrefs:(s:(v:unknown)=>unknown)=>s({panelCollapsed:false})}));
vi.mock('@/lib/stores/server-menu',()=>({useNavTree:()=>state.nav,useServerMenu:(s:(v:unknown)=>unknown)=>s({byHref:{}}),isPointUnimplemented:()=>false}));
vi.mock('@/lib/i18n',()=>({useI18n:()=>({t:(k:string)=>k,tNav:(k:string)=>k})}));
import {navTabs} from './nav';
import {SecondaryNav} from '@/components/layout/secondary-nav';
let host:HTMLDivElement,root:Root;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;host=document.createElement('div');document.body.append(host);root=createRoot(host);
 state.nav=[{key:'merchant',label:'商户中心',icon:'Store',modules:['merchant'],match:['/merchants'],children:[
 {href:'/merchants',label:'入驻审核',group:'入驻',ready:true},
 {href:'/merchants?tab=list',label:'商家档案',group:'档案',ready:true},
 {href:'/merchants?tab=on-behalf',label:'代办入驻',group:'入驻',ready:true},
 {href:'/merchants?tab=stores',label:'门店档案',group:'档案',ready:true}]}];});
afterEach(async()=>{await act(async()=>root.unmount());host.remove();});
it('shows named groups once, keeps every destination, and marks the current leaf in rendered order',async()=>{
 await act(async()=>root.render(h(SecondaryNav)));
 const nav=host.querySelector('nav')!;
 expect([...nav.querySelectorAll('h3')].map(x=>x.textContent)).toEqual(['入驻','档案']);
 expect([...nav.querySelectorAll('a')].map(x=>x.getAttribute('href'))).toEqual(['/merchants','/merchants?tab=on-behalf','/merchants?tab=list','/merchants?tab=stores']);
 expect(nav.querySelector('a[aria-current="page"]')?.textContent).toBe('商家档案');
 expect(nav.querySelector('section[aria-label="入驻"]')?.textContent).toContain('代办入驻');
 expect(nav.querySelectorAll('[class*="border-s"]')).toHaveLength(0);
});

it('carries the same menu groups into page view choices',()=>{
 expect(navTabs('/merchants',['audit','list','stores'],state.nav).map(t=>[t.key,t.group])).toEqual([['audit','入驻'],['list','档案'],['stores','档案']]);
});
