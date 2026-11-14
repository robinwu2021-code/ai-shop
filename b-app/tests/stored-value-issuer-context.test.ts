// @vitest-environment happy-dom
import {beforeEach,expect,it,vi} from 'vitest';
import {createPinia,setActivePinia} from 'pinia';
import {mount,flushPromises} from '@vue/test-utils';
import './component-setup';
const m=vi.hoisted(()=>({load:(_:Record<string,string>)=>{},issuer:vi.fn(),entities:vi.fn(),configure:vi.fn(),impact:vi.fn(),version:vi.fn(),store:vi.fn(),get:vi.fn(),post:vi.fn(),prepare:vi.fn(),context:vi.fn(),resources:vi.fn(),grant:vi.fn(),brand:vi.fn()}));
vi.mock('@dcloudio/uni-app',()=>({onLoad:(f:typeof m.load)=>{m.load=f;}}));
vi.mock('@/api',()=>({api:{mBrand:m.brand,mSvBrandCapability:async()=>({protocol:1,ready:false,blockers:[]}),mSvIssuer:m.issuer,mSvIssuerEntities:m.entities,mSvConfigureIssuer:m.configure,mSvIssuerImpact:m.impact,mSvIssuerVersion:m.version,mSvStoreIssuers:m.store,mSvPrepareIssuer:m.prepare,mManagementContext:m.context,mManagementResources:m.resources,mManagementGrant:m.grant}}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>({storeNo:'S1'})}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
vi.mock('@shared/net/http-client',async()=>{const actual=await vi.importActual('@shared/net/http-client');return {...actual,http:{get:m.get,post:m.post}};});
import Page from '@/pages/stored-value-issuer/index.vue';
import {issuerHttpApi} from '@/api/stored-value-issuer-http';
const config={name:'Brand',settlementEntityNo:'E1',collectionMode:'CENTRALIZED',clearingMode:'INTERNAL_RECON',debitAllocationMode:'LEGACY_SNAPSHOT',currency:'CNY',marketZone:'Asia/Shanghai',routes:[{paymentMerchantNo:null,entityNo:'E1',storeNo:null,channel:'MERCHANT_OFFLINE',scene:'OFFLINE'}],expectedRevision:0};
beforeEach(()=>{setActivePinia(createPinia());vi.clearAllMocks();Object.assign(uni,{removeStorageSync:vi.fn()});vi.mocked(uni.getStorageSync).mockReturnValue('');m.brand.mockResolvedValue({status:'ACTIVE'});m.issuer.mockResolvedValue({issuer:null});m.entities.mockResolvedValue([{entityNo:'E1',name:'Entity',accepted:true,canAccept:false}]);m.store.mockResolvedValue({storeNo:'S1',legacyMode:true,issuers:[]});});
it('uses store and brand endpoints separately and scopes original lookup to the outer store',async()=>{
 await issuerHttpApi.mSvIssuer('BRAND','B1','M1');expect(m.get).toHaveBeenLastCalledWith('/biz/management/brands/B1/stored-value/issuer',{managementNo:'M1'});
 await issuerHttpApi.mSvIssuer('STORE','S1','M1');expect(m.get).toHaveBeenLastCalledWith('/biz/management/stores/S1/stored-value/issuer',{managementNo:'M1'});
 await issuerHttpApi.mSvIssuerRecharge('R1','S1');expect(m.get).toHaveBeenLastCalledWith('/biz/stores/S1/stored-value/issuer-recharges/R1');
});
it('opens settlement choice in a bottom sheet and retries the exact unknown save command',async()=>{
 m.configure.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({...config,issuerNo:'I1',issuerVersionNo:'IV1',scopeType:'BRAND',scopeNo:'B1',status:'DRAFT',revision:0});
 const wrapper=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();
 expect(wrapper.find('select').exists()).toBe(false);
 await wrapper.find('[data-testid="issuer-entity"]').trigger('tap');await flushPromises();
 await wrapper.find('[data-testid="issuer-entity-choice"]').trigger('tap');await flushPromises();
 await wrapper.find('[data-testid="issuer-save"]').trigger('tap');await flushPromises();
 expect(wrapper.find('input').attributes('disabled')).toBeDefined();
 await wrapper.find('[data-testid="issuer-save"]').trigger('tap');await flushPromises();
 expect(m.configure).toHaveBeenCalledTimes(2);expect(m.configure.mock.calls[1]).toEqual(m.configure.mock.calls[0]);
 expect(m.configure.mock.calls[0][0]).toBe('BRAND');expect(m.configure.mock.calls[0][2].configuration.routes).toEqual(config.routes);
 expect(wrapper.text()).toContain('issuer.brandNotReady');wrapper.unmount();
});
it('local store view has no brand selector or financial activation action',async()=>{
 const wrapper=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({s:'S1',local:'1'});await flushPromises();
 expect(m.store).toHaveBeenCalledWith('S1');expect(m.issuer).not.toHaveBeenCalled();
 expect(wrapper.find('[data-testid="issuer-save"]').exists()).toBe(false);expect(wrapper.text()).toContain('issuer.storeScope');wrapper.unmount();
});
it('blocked configuration never sends a version write',async()=>{
 m.issuer.mockResolvedValue({issuer:{...config,issuerNo:'I1',issuerVersionNo:'IV1',scopeType:'BRAND',scopeNo:'B1',status:'DRAFT',revision:0}});
 m.impact.mockResolvedValue({allowed:false,blockers:['LIABILITY_RULES_LOCKED'],affectedAccountCount:1,hasPending:false});
 const wrapper=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();
 await wrapper.find('[data-testid="issuer-save"]').trigger('tap');await flushPromises();
 expect(m.version).not.toHaveBeenCalled();expect(m.configure).not.toHaveBeenCalled();expect(wrapper.text()).toContain('issuer.blockers.LIABILITY_RULES_LOCKED');wrapper.unmount();
});

it('requires an explicit owner action before preparing store settings',async()=>{
 m.issuer.mockRejectedValueOnce(Object.assign(new Error('forbidden'),{code:10403})).mockResolvedValue({issuer:null});m.prepare.mockResolvedValue({});
 const wrapper=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',s:'S1',prepare:'1'});await flushPromises();
 expect(m.prepare).not.toHaveBeenCalled();
 await wrapper.find('[data-testid="issuer-prepare"]').trigger('tap');await flushPromises();
 expect(m.prepare).toHaveBeenCalledWith('S1','M1');expect(wrapper.find('[data-testid="issuer-save"]').exists()).toBe(true);wrapper.unmount();
});
it('explicit settlement authorization precedes selection and financial configuration',async()=>{
 m.entities.mockResolvedValue([{entityNo:'E1',name:'Entity',accepted:false,canAccept:true}]);m.context.mockResolvedValue({actorKey:'USER:U1'});m.resources.mockResolvedValue({items:[],nextCursor:null});m.grant.mockResolvedValue({});
 const wrapper=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();
 expect(m.grant).not.toHaveBeenCalled();
 await wrapper.find('[data-testid="issuer-entity"]').trigger('tap');await wrapper.find('[data-testid="issuer-entity-choice"]').trigger('tap');await flushPromises();
 expect(m.grant).toHaveBeenCalledWith('M1',{actorNo:'USER:U1',resourceType:'ENTITY',resourceNo:'E1',permissionCode:'sv.issuer.settlement.authorize',delegatable:false});expect(m.configure).not.toHaveBeenCalled();wrapper.unmount();
});

it('keeps an inactive brand issuer readable and blocks configuration and activation actions',async()=>{
 m.brand.mockResolvedValue({status:'INACTIVE'});m.issuer.mockResolvedValue({issuer:{...config,issuerNo:'I1',status:'DRAFT',revision:0}});
 const w=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();
 expect(w.text()).toContain('issuer.brandInactive');expect(w.text()).toContain('Brand');expect(w.find('[data-testid="issuer-save"]').exists()).toBe(false);expect(w.find('[data-testid="brand-activate"]').exists()).toBe(false);expect(m.entities).not.toHaveBeenCalled();expect(m.configure).not.toHaveBeenCalled();w.unmount();
});
it('shows an unconfigured inactive brand as empty without inventing a draft or offering writes',async()=>{
 m.brand.mockResolvedValue({status:'INACTIVE'});const w=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();expect(w.text()).toContain('issuer.noConfiguration');expect(w.text()).not.toContain('issuer.states.DRAFT');expect(w.find('[data-testid="issuer-save"]').exists()).toBe(false);w.unmount();
});
it('does not require unrelated brand profile rights to read an issuer',async()=>{
 m.brand.mockRejectedValue(Object.assign(new Error('forbidden'),{code:10403}));m.entities.mockRejectedValue(Object.assign(new Error('forbidden'),{code:10403}));m.issuer.mockResolvedValue({issuer:{...config,issuerNo:'I1',status:'ACTIVE',revision:0}});const w=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();expect(w.text()).toContain('Brand');expect(w.find('.sh-failed').exists()).toBe(false);expect(w.find('[data-testid="issuer-save"]').exists()).toBe(false);w.unmount();
});
it('distinguishes denied access from a transport error and preserves its actual reason',async()=>{
 m.issuer.mockRejectedValueOnce(Object.assign(new Error('No issuer permission'),{code:10403}));let w=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();expect(w.find('.sh-denied').exists()).toBe(true);expect(w.text()).toContain('No issuer permission');w.unmount();
 m.issuer.mockRejectedValueOnce(new Error('Connection unavailable'));w=mount(Page,{global:{mocks:{$t:(key:string)=>key}}});m.load({m:'M1',b:'B1'});await flushPromises();expect(w.find('.sh-failed').exists()).toBe(true);expect(w.text()).toContain('Connection unavailable');w.unmount();
});
