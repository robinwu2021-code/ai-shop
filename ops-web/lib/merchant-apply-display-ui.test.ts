// @vitest-environment happy-dom
import {act,createElement as h} from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import type {MerchantApply} from './types';
const state=vi.hoisted(()=>({rows:[] as MerchantApply[],audit:vi.fn(),selfContext:vi.fn(),selfCreate:vi.fn()}));
vi.mock('@/lib/api',()=>({api:{selfOperatedContext:state.selfContext,createSelfOperated:state.selfCreate,listApplies:async()=>({records:state.rows,total:state.rows.length}),listCommunities:async()=>({records:[],total:0}),listAuthCodes:async()=>[],auditApply:state.audit,acceptApply:vi.fn(),listIndustries:async()=>[],onBehalfStoreIndustries:async()=>[],marketPhonePolicies:async()=>[]}}));
vi.mock('@/lib/api/master-data',()=>({useCodeLabel:()=>({industry:()=> '餐饮',subject:()=> '企业'})}));
vi.mock('@/lib/i18n',()=>({useI18n:()=>({locale:'zh',t:(k:string)=>k}),useT:()=>((k:string)=>k)}));
vi.mock('@/lib/notify',()=>({notify:{success:vi.fn()}}));
import {ApplyTab} from '@/app/merchants/apply-tab';
import {SelfOperatedTab} from '@/app/merchants/self-operated-tab';
import {OnBehalfTab} from '@/app/merchants/on-behalf-tab';
import {MERCHANTS_COPY} from '@/app/merchants/copy';
const c=MERCHANTS_COPY.zh;
let host:HTMLDivElement,root:Root,client:QueryClient;
beforeEach(()=>{(globalThis as typeof globalThis&{IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;host=document.createElement('div');document.body.append(host);root=createRoot(host);client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});state.audit.mockReset();state.selfContext.mockResolvedValue({market:"TW",recordOnly:true,marketEnabled:true});state.selfCreate.mockRejectedValue(new Error("test rejection"));
 state.rows=[{applyNo:'A1',ownerUserNo:'U1',marketCode:'TW',name:'申请经营主体',subject:'ENTERPRISE',industry:'FOOD',contactName:'联系人甲',contactPhone:'0912000000',category:'早餐',desc:'',status:'PENDING',createdAt:0,serviceScope:'CITY',communityNos:[],storeProfile:{name:'早餐门店',address:'门店地址',industryCode:'FOOD',subIndustryCode:'FOOD_BREAKFAST',businessTypeCode:'FOOD_BREAKFAST'}} as MerchantApply];
});
afterEach(async()=>{await act(async()=>root.unmount());client.clear();host.remove();});
async function render(node:React.ReactNode){await act(async()=>root.render(h(QueryClientProvider,{client},node)));await act(async()=>{await new Promise(r=>setTimeout(r,20));});}
async function open(){const view=[...host.querySelectorAll('button')].find(b=>b.textContent===c.actionView)!;await act(async()=>view.click());}
it('explains the review object and separates the applicant business from its proposed store in a compact list',async()=>{
 await render(h(ApplyTab,{c,canAudit:true}));
 expect(host.textContent).not.toContain(c.applySummary);
 expect([...host.querySelectorAll('thead th')].map(x=>x.textContent)).toEqual([c.applyColName,c.colContact,c.applyColStatus,c.colCreatedAt,c.colActions]);
 expect(host.textContent).toContain('申请经营主体');expect(host.textContent).toContain('早餐门店');
});
it('groups store details separately and keeps the existing approval command',async()=>{
 state.audit.mockImplementation(async()=>({...state.rows[0],status:'APPROVED',merchantNo:'M1',storeNo:'S1'}));
 await render(h(ApplyTab,{c,canAudit:true}));await open();
 const dialog=document.querySelector('[role="dialog"]')!;
 expect(dialog.textContent).toContain('经营主体与申请人');expect(dialog.textContent).toContain('开店资料');expect(dialog.textContent).toContain('门店地址');
 const approve=[...dialog.querySelectorAll('button')].find(b=>b.textContent===c.applyActionApprove)!;
 await act(async()=>approve.click());
 expect(state.audit).toHaveBeenCalledWith('A1',true,'','CITY',[],[]);
 await act(async()=>{await new Promise(r=>setTimeout(r,20));});
 expect(dialog.textContent).toContain('入驻结果');expect(dialog.textContent).toContain('经营主体编号');expect(dialog.textContent).toContain('默认门店编号');expect(dialog.textContent).not.toContain('M1 / S1');
});
it('keeps historical applications readable without inventing store details',async()=>{
 Object.assign(state.rows[0],{storeProfile:null,merchantNo:'M-legacy',status:'APPROVED'});
 await render(h(ApplyTab,{c,canAudit:true}));expect(host.textContent).toContain('未单独填写');await open();
 const dialog=document.querySelector('[role="dialog"]')!;
 expect(dialog.textContent).toContain('M-legacy');expect(dialog.textContent).not.toContain(c.obStoreAddress);
 expect([...dialog.querySelectorAll('button')].some(b=>b.textContent===c.applyActionApprove)).toBe(false);
});
it('shows a concise pending-queue empty state',async()=>{
 state.rows=[];await render(h(ApplyTab,{c,canAudit:true}));
 expect(host.textContent).toContain('暂无待审核或审核中的入驻申请。');expect(host.textContent).not.toContain('放宽');
});
it('keeps missing-community warnings and disables approval until its actual prerequisite is met',async()=>{
 state.rows[0].serviceScope='COMMUNITY';await render(h(ApplyTab,{c,canAudit:true}));await open();
 const dialog=document.querySelector('[role="dialog"]')!;
 expect(dialog.textContent).toContain(c.applyScopeNeedCommunity);
 expect([...dialog.querySelectorAll('button')].find(b=>b.textContent===c.applyActionApprove)?.disabled).toBe(true);
});
it('does not expose review actions without review permission',async()=>{
 await render(h(ApplyTab,{c,canAudit:false}));await open();
 expect([...document.querySelectorAll('[role="dialog"] button')].some(b=>b.textContent===c.applyActionApprove)).toBe(false);
});
it('keeps general self-operation guidance out of the default page while retaining a current blocking error',async()=>{
 await render(h(SelfOperatedTab,{c,canCreate:true}));
 expect(host.textContent).not.toContain(c.soWarning);expect(host.textContent).not.toContain(c.soDesc);
 expect(host.textContent).toContain(c.soNoCommunities);
 expect(host.textContent).not.toContain("11 位大陆");expect(host.querySelector("#so-owner")?.getAttribute("type")).toBe("email");
});
it('keeps general on-behalf guidance out of the default page while retaining permission feedback',async()=>{
 await render(h(OnBehalfTab,{c,canSubmit:false}));
 expect(host.textContent).not.toContain(c.obWarning);expect(host.textContent).not.toContain(c.obDesc);expect(host.textContent).toContain(c.obNoPerm);
});
it('uses a registered email for Taiwan self-operation without mainland prompts or a phone identity payload',async()=>{
 await render(h(SelfOperatedTab,{c,canCreate:true}));
 async function set(id:string,value:string){const el=document.getElementById(id) as HTMLInputElement;await act(async()=>{Object.getOwnPropertyDescriptor(el.tagName==='SELECT'?HTMLSelectElement.prototype:HTMLInputElement.prototype,'value')!.set!.call(el,value);el.dispatchEvent(new Event(el.tagName==='SELECT'?'change':'input',{bubbles:true}));});}
 await set('so-owner','owner@example.test');await set('so-name','Taiwan self-operation');await set('so-scope','CITY');
 const submit=[...host.querySelectorAll('button')].find(b=>b.textContent===c.soSubmit)!;await act(async()=>submit.click());
 expect(state.selfCreate).toHaveBeenCalledWith(expect.objectContaining({ownerEmail:'owner@example.test',phone:undefined,name:'Taiwan self-operation'}));expect(host.textContent).not.toContain('大陆');expect(host.textContent).not.toContain(c.soIndustryHint);expect(host.querySelector('#ob-market')).toBeNull();
});
it('keeps audit-only users in the unified application list without exposing creation',async()=>{
 await render(h(OnBehalfTab,{c,canSubmit:false,canAudit:true}));expect(host.querySelector('table')).not.toBeNull();expect([...host.querySelectorAll('button')].some(b=>b.textContent===c.obNew)).toBe(false);expect(host.textContent).toContain(c.applyActionAccept);
});
