// @vitest-environment happy-dom
import {act,createElement as h} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
const state=vi.hoisted(()=>({context:vi.fn(),submit:vi.fn(),list:vi.fn()}));
vi.mock('@/lib/api',()=>({api:{onBehalfContext:state.context,listOnBehalfApplies:state.list,applyOnBehalf:state.submit,listIndustries:async()=>[],onBehalfStoreIndustries:async()=>[
 {enabled:true,industryCode:'FOOD',nameZh:'餐饮'},
 {enabled:true,industryCode:'FOOD',subIndustryCode:'FOOD_BREAKFAST',businessTypeCode:'STORE_V1',nameZh:'早餐'}],listCommunities:vi.fn(),listAuthCodes:vi.fn()}}));
vi.mock('@/lib/api/master-data',()=>({useCodeLabel:()=>({industry:(v:string)=>v,subject:(v:string)=>v})}));
vi.mock('@/lib/i18n',()=>({useI18n:()=>({locale:'zh',t:(k:string)=>k}),useT:()=>((k:string)=>k)}));
vi.mock('@/lib/notify',()=>({notify:{error:vi.fn(),success:vi.fn()}}));
import {OnBehalfTab} from '@/app/merchants/on-behalf-tab';
import {MERCHANTS_COPY} from '@/app/merchants/copy';
const c=MERCHANTS_COPY.zh;
let host:HTMLDivElement,root:Root,client:QueryClient;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;host=document.createElement('div');document.body.append(host);root=createRoot(host);client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});vi.clearAllMocks();state.context.mockResolvedValue({market:'TW',marketEnabled:true,recordOnly:true});state.list.mockResolvedValue({records:[{applyNo:'A1',name:'Test',subject:'ENTERPRISE',status:'PENDING',onBehalf:true,createdAt:0}],total:1});state.submit.mockResolvedValue({applyNo:'MA1',ownerUserNo:'U1'});});
afterEach(async()=>{await act(async()=>root.unmount());client.clear();host.remove();});
async function settle(){await act(async()=>{await new Promise(r=>setTimeout(r,20));});}
async function render(canSubmit=true){await act(async()=>root.render(h(QueryClientProvider,{client},h(OnBehalfTab,{c,canSubmit}))));await settle();}
async function click(text:string){const button=[...document.querySelectorAll('button')].find(b=>b.textContent===text)!;await act(async()=>button.click());await settle();}
async function input(id:string,value:string){const el=document.getElementById(id) as HTMLInputElement;await act(async()=>{Object.getOwnPropertyDescriptor(el.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype,'value')!.set!.call(el,value);el.dispatchEvent(new Event(el.tagName==='SELECT'?'change':'input',{bubbles:true}));});}
it('starts with an assisted application table and opens a grouped modal with fixed system region',async()=>{
 await render();expect(host.querySelector('table')).not.toBeNull();expect(document.querySelector('[role="dialog"]')).toBeNull();expect(state.list).toHaveBeenCalled();
 await click(c.obNew);const dialog=document.querySelector('[role="dialog"]')!;
 expect(dialog.textContent).toContain(c.obOwnerSection);expect(dialog.textContent).toContain(c.applySectionStore);expect(dialog.textContent).toContain(c.obEntitySection);expect(dialog.textContent).toContain('TW');expect(dialog.querySelector('#ob-market')).toBeNull();
 await click(c.obCancel);expect(document.querySelector('[role="dialog"]')).toBeNull();
});
it('submits raw Taiwan phone without a caller region, closes on success and refreshes the table',async()=>{
 await render();await click(c.obNew);
 await input('ob-owner-email','owner@example.test');await input('ob-phone','待补 / 分机');await input('ob-store-name','早餐店');await input('ob-store-address','门店地址');await input('ob-store-industry','FOOD');await input('ob-store-sub-industry','FOOD_BREAKFAST');await input('ob-name','经营主体');await input('ob-license','https://example.test/license.jpg');
 const before=state.list.mock.calls.length;await click(c.obSubmit);
 expect(state.submit).toHaveBeenCalledWith(expect.objectContaining({ownerEmail:'owner@example.test',phone:'待补 / 分机',storeProfile:expect.objectContaining({businessTypeCode:'STORE_V1'})}));expect(state.submit.mock.calls[0][0]).not.toHaveProperty('market');expect(document.querySelector('[role="dialog"]')).toBeNull();expect(state.list.mock.calls.length).toBeGreaterThan(before);expect(host.textContent).toContain('MA1');
});
it('does not query protected resources or open creation without permission',async()=>{
 await render(false);expect(host.textContent).toContain(c.obNoPerm);expect(state.context).not.toHaveBeenCalled();expect(state.list).not.toHaveBeenCalled();expect(document.querySelector('[role="dialog"]')).toBeNull();
});
it('shows context failure and prevents submission instead of guessing a region',async()=>{
 state.context.mockRejectedValue(new Error('Region unavailable'));await render();await click(c.obNew);
 expect(document.body.textContent).toContain('Region unavailable');expect([...document.querySelectorAll('button')].find(b=>b.textContent===c.obSubmit)?.disabled).toBe(true);
});
it('keeps entered values and the modal open when submission fails',async()=>{
 state.submit.mockRejectedValue(new Error('Application rejected'));await render();await click(c.obNew);
 await input('ob-owner-email','owner@example.test');await input('ob-store-name','早餐店');await input('ob-store-address','门店地址');await input('ob-store-industry','FOOD');await input('ob-store-sub-industry','FOOD_BREAKFAST');await input('ob-name','经营主体');await input('ob-license','https://example.test/license.jpg');
 await click(c.obSubmit);expect(document.querySelector('[role="dialog"]')).not.toBeNull();expect(document.body.textContent).toContain('Application rejected');expect((document.getElementById('ob-name') as HTMLInputElement).value).toBe('经营主体');
});
