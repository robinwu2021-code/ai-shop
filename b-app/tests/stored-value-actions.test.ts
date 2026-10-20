// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import './component-setup';
const m = vi.hoisted(() => ({load:null as null|((q:Record<string,string>)=>void), plan:vi.fn(), plans:vi.fn(), context:vi.fn(), create:vi.fn()}));
vi.mock('@dcloudio/uni-app',()=>({onLoad:(cb:typeof m.load)=>{m.load=cb;},onShow:vi.fn(),onHide:vi.fn()}));
vi.mock('@/api',()=>({api:{mSvPlan:m.plan,mSvPlans:m.plans,mSvContext:m.context,mSvCreatePlan:m.create}}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>({storeNo:'S1',multiStore:false,can:()=>true,ensureStores:async()=>{}})}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(k:string)=>k})}));
import PlanEdit from '@/pages/stored-value-plan-edit/index.vue';
import Detail from '@/pages/stored-value-plan-detail/index.vue';
import Recharge from '@/pages/stored-value-recharge/index.vue';
const plan={planNo:'P1',versionNo:'V1',revision:0,storeNo:'S1',name:'充50送10',currency:'CNY',paidAmountMinor:5000,bonusAmountMinor:1000,totalAmountMinor:6000,status:'ACTIVE',createdAt:'2026-10-20T01:00:00Z',updatedAt:'2026-10-20T02:00:00Z'};
const options={global:{stubs:{'sh-scaffold':{template:'<div><slot/></div>'}}}};
beforeEach(()=>{vi.clearAllMocks();m.plan.mockResolvedValue(plan);m.plans.mockResolvedValue({records:[plan],nextCursor:null});});
it('puts the destructive status action after audit information in its own management section',async()=>{
 const w=mount(Detail,options);m.load?.({planNo:'P1'});await flushPromises();
 expect(w.text()).toContain('storedValue.createdAt');expect(w.text()).toContain('storedValue.updatedAt');
 const manage=w.find('[data-testid="plan-management"]');expect(manage.exists()).toBe(true);
 const action=manage.find('[role="button"]');expect(action.classes()).toContain('sh-btn--danger');
 expect(action.text()).toBe('storedValue.stop');
 expect(w.text().indexOf('storedValue.updatedAt')).toBeLessThan(w.text().indexOf('storedValue.stop'));w.unmount();
});
it('keeps identification controls equal width and uses the shared full-width primary action',async()=>{
 const w=mount(Recharge,options);m.load?.({});await flushPromises();
 const identify=w.find('[data-testid="member-actions"]');expect(identify.exists()).toBe(true);
 const controls=identify.findAll('[role="button"]');expect(controls).toHaveLength(2);
 for(const c of controls)expect(c.classes()).toContain('sh-fill');
 const action=w.find('[data-testid="recharge-submit"]');expect(action.element.tagName.toLowerCase()).toBe('view');expect(action.classes()).toContain('sh-btn');w.unmount();
});

it('keeps plan next full width and confirmation actions equally sized without creating early',async()=>{
 m.context.mockResolvedValue({currency:'CNY',currencyScale:2});
 const w=mount(PlanEdit,options);m.load?.({});await flushPromises();
 const action=()=>w.findAll('.sh-btn').at(-1)!;
 expect(action().exists()).toBe(true);
 expect(action().element.tagName.toLowerCase()).toBe('view');
 expect(action().classes()).toContain('sh-fill');
 const inputs=w.findAll('input');await inputs[0].setValue('充50送10');await inputs[1].setValue('50');await inputs[2].setValue('10');
 await action().trigger('tap');
 expect(action().text()).toBe('storedValue.create');expect(m.create).not.toHaveBeenCalled();
 const back=w.find('[data-testid="plan-back"]');expect(back.classes()).toContain('sh-fill');
 expect(back.element.tagName.toLowerCase()).toBe('view');
 await back.trigger('tap');expect(action().text()).toBe('storedValue.next');
 expect((w.findAll('input')[0].element as HTMLInputElement).value).toBe('充50送10');w.unmount();
});
