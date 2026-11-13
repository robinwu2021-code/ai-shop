// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {act,createElement as h} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
const fixture=vi.hoisted(()=>({merchant:{merchantNo:'MUME-test',name:'Seed merchant',status:'PENDING_LICENSE',createdAt:0,communityNos:[],categoryCodes:[],contactName:null,contactPhone:null}}));
vi.mock('@/lib/api',()=>({api:{listMerchants:async()=>({records:[fixture.merchant],total:1}),listCommunities:async()=>({records:[],total:0})}}));
vi.mock('@/lib/use-page-tab',()=>({usePageTab:()=>['list',vi.fn()],useNavTabs:()=>[{key:'list',label:'Merchants'},{key:'stores',label:'Stores'}]}));
vi.mock('@/lib/stores/locale',async()=>{const {create}=await import('zustand');return {useLocaleStore:create(()=>({locale:'zh'}))};});
vi.mock('@/lib/use-can',()=>({useCan:()=>()=>true}));
vi.mock('next/link',()=>({default:({children,...props}:React.ComponentProps<'a'>)=>h('a',props,children)}));
vi.mock('@/app/merchants/staff-block',()=>({StaffBlock:()=>null}));
vi.mock('@/app/merchants/fulfillment-block',()=>({FulfillmentBlock:()=>null}));
vi.mock('@/app/merchants/chain-line',()=>({MerchantChainLine:()=>null}));
vi.mock('@/app/merchants/pay-service-block',()=>({PayServiceBlock:()=>null}));
import Page from '@/app/merchants/page';
import {MerchantStatusBadge} from '@/components/status';
import {fmtTime} from '@/lib/utils';
import {useLocaleStore} from '@/lib/stores/locale';
import type {MerchantStatus} from '@/lib/types';
let root:Root,host:HTMLDivElement,q:QueryClient;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;useLocaleStore.setState({locale:'zh'});host=document.createElement('div');document.body.append(host);root=createRoot(host);q=new QueryClient({defaultOptions:{queries:{retry:false}}});});
afterEach(async()=>{await act(async()=>root.unmount());q.clear();host.remove();});
async function mountPage(){await act(async()=>root.render(h(QueryClientProvider,{client:q},h(Page))));await act(async()=>{await new Promise(r=>setTimeout(r,20));});}
it('renders missing backend timestamp sentinel as unknown while keeping real dates',()=>{expect(fmtTime(0)).toBe('-');expect(fmtTime(null)).toBe('-');expect(fmtTime(1794528000000)).toContain('2026');});
it.each([['zh','待补证'],['en','Pending license']] as const)('translates pending license badge in %s',async(locale,label)=>{useLocaleStore.setState({locale});await act(async()=>root.render(h(MerchantStatusBadge,{value:'PENDING_LICENSE' as MerchantStatus})));expect(host.textContent).toBe(label);});
it('keeps missing submission time unknown in both merchant table and drawer',async()=>{await mountPage();expect(host.textContent).not.toContain('1970');const view=[...host.querySelectorAll('button')].find(b=>b.textContent==='查看')!;expect(view).toBeDefined();await act(async()=>view.click());expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain('1970');});
it('closes merchant drawer when following its scoped store link',async()=>{await mountPage();const view=[...host.querySelectorAll('button')].find(b=>b.textContent==='查看')!;await act(async()=>view.click());const drawer=document.querySelector('[role="dialog"]')!;expect(drawer).not.toBeNull();const link=drawer.querySelector<HTMLAnchorElement>('a[href*="tab=stores"]')!;expect(link.getAttribute('href')).toBe('/merchants?tab=stores&merchantNo=MUME-test');link.addEventListener('click',e=>e.preventDefault());await act(async()=>link.click());expect(document.querySelector('[role="dialog"]')).toBeNull();});
