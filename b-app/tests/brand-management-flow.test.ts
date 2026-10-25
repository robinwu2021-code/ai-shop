// @vitest-environment happy-dom
import {it,expect,vi,beforeEach} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import './component-setup';
const mocks=vi.hoisted(()=>({brands:vi.fn(),check:vi.fn(),submit:vi.fn(),decide:vi.fn(),query:vi.fn(),context:vi.fn(),operating:vi.fn(),capability:vi.fn(),financial:vi.fn(),original:vi.fn()}));
vi.mock('@/api',()=>({api:{mManagementContext:mocks.context,mStoreOperatingContext:mocks.operating,mSvFinanceCapability:mocks.capability,mSvFinancialBrandStart:mocks.financial,mSvFinancialBrandOriginal:mocks.original,mBrands:mocks.brands,mCheckBrandChange:mocks.check,mSubmitBrandChange:mocks.submit,mDecideBrandChange:mocks.decide,mBrandChange:mocks.query}}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
vi.mock('@shared/net/http-client',async()=>({...await vi.importActual('@shared/net/http-client'),idempotencyKey:()=> 'stable-key'}));
import {ApiError} from '@shared/net/http-client';
import Sheet from '@/pages/brand-detail/components/BrandChangeSheet.vue';
beforeEach(()=>{vi.clearAllMocks();mocks.context.mockResolvedValue({actorKey:'MCH:A'});mocks.operating.mockResolvedValue({revision:12});mocks.capability.mockResolvedValue({crossEntityReady:true});vi.mocked(uni.getStorageSync).mockReturnValue('');Object.assign(uni,{removeStorageSync:vi.fn()});mocks.brands.mockResolvedValue({items:[{brandNo:'B1',name:'品牌一',status:'ACTIVE'}],nextCursor:null});mocks.check.mockResolvedValue({allowed:true,canDecide:true,checkToken:'signed',storeVersion:12,bindingRevision:0,fromRevision:null,toRevision:3,blockers:[]});mocks.submit.mockResolvedValue({changeNo:'C1',status:'CHECKED',revision:0});mocks.decide.mockResolvedValue({changeNo:'C1',status:'COMPLETED',revision:1,applied:true});});
it('keeps store fixed and uses a bottom-sheet brand selection with checked revisions',async()=>{
 const w=mount(Sheet,{props:{visible:true,managementNo:'M1',storeNo:'S1'}});await flushPromises();
 expect(w.find('select').exists()).toBe(false);await w.find('[data-testid="brand-option"]').trigger('tap');await flushPromises();
 await w.find('[data-testid="brand-confirm"]').trigger('tap');await flushPromises();
 expect(mocks.submit).toHaveBeenCalledWith('S1',{managementNo:'M1',toBrandNo:'B1',checkToken:'signed',expectedStoreRevision:12,expectedScopeRevision:0,expectedFromBrandRevision:null,expectedToBrandRevision:3,reason:'management.configureBrand'},'stable-key');
 expect(w.emitted('changed')).toHaveLength(1);w.unmount();
});
it('a blocked impact cannot be submitted',async()=>{
 mocks.check.mockResolvedValue({allowed:false,blockers:['STORED_VALUE_UNRESOLVED'],checkToken:'blocked'});
 const w=mount(Sheet,{props:{visible:true,managementNo:'M1',storeNo:'S1'}});await flushPromises();await w.find('[data-testid="brand-option"]').trigger('tap');await flushPromises();
 await w.find('[data-testid="brand-confirm"]').trigger('tap');await flushPromises();expect(mocks.submit).not.toHaveBeenCalled();w.unmount();
});
it('ignores stale responses when the target store changes',async()=>{
 let finish!:(v:unknown)=>void;mocks.check.mockImplementationOnce(()=>new Promise(r=>{finish=r}));
 const w=mount(Sheet,{props:{visible:true,managementNo:'M1',storeNo:'S1'}});await flushPromises();await w.find('[data-testid="brand-option"]').trigger('tap');
 await w.setProps({storeNo:'S2'});await flushPromises();finish({allowed:true,checkToken:'old',storeVersion:1});await flushPromises();
 await w.find('[data-testid="brand-confirm"]').trigger('tap');expect(mocks.submit).not.toHaveBeenCalled();w.unmount();
});

it('uses the financial coordinator and freezes the same command after a lost response',async()=>{
 mocks.brands.mockResolvedValue({items:[{brandNo:'B1',name:'Brand',status:'ACTIVE',storedValueStatus:'ACTIVE'}],nextCursor:null});mocks.financial.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({changeNo:'F1',state:'COMPLETED',staged:true});
 const w=mount(Sheet,{props:{visible:true,managementNo:'M1',storeNo:'S1'}});await flushPromises();await w.find('[data-testid="brand-option"]').trigger('tap');await flushPromises();await w.find('[data-testid="brand-confirm"]').trigger('tap');await flushPromises();await w.find('[data-testid="brand-confirm"]').trigger('tap');await flushPromises();expect(mocks.check).not.toHaveBeenCalled();expect(mocks.submit).not.toHaveBeenCalled();expect(mocks.financial).toHaveBeenCalledTimes(2);expect(mocks.financial.mock.calls[1]).toEqual(mocks.financial.mock.calls[0]);expect(w.emitted('changed')).toHaveLength(1);w.unmount();
});

it('revalidates a definite pre-registration rejection without permanently locking the brand choice',async()=>{
 mocks.brands.mockResolvedValue({items:[{brandNo:'B1',name:'Brand',status:'ACTIVE',storedValueStatus:'ACTIVE'}],nextCursor:null});mocks.original.mockResolvedValue({original:null});mocks.financial.mockRejectedValueOnce(new ApiError(10409,'Conflict')).mockResolvedValue({changeNo:'F1',state:'COMPLETED',staged:true});
 const w=mount(Sheet,{props:{visible:true,managementNo:'M1',storeNo:'S1'}});await flushPromises();await w.find('[data-testid="brand-option"]').trigger('tap');await flushPromises();await w.find('[data-testid="brand-confirm"]').trigger('tap');await flushPromises();mocks.operating.mockResolvedValue({revision:13});await w.find('[data-testid="brand-option"]').trigger('tap');await flushPromises();await w.find('[data-testid="brand-confirm"]').trigger('tap');await flushPromises();expect(mocks.financial.mock.calls[1]![1].expectedStoreRevision).toBe(13);expect(mocks.original).toHaveBeenCalledOnce();w.unmount();
});
