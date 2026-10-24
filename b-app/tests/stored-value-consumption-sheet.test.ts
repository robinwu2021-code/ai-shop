// @vitest-environment happy-dom
import {beforeEach,expect,it,vi} from 'vitest';
import {mount,flushPromises} from '@vue/test-utils';
import './component-setup';
const m=vi.hoisted(()=>({goods:vi.fn(),preview:vi.fn(),query:vi.fn(),consume:vi.fn(),resolve:vi.fn(),select:vi.fn(),can:vi.fn(),scope:vi.fn()}));
vi.mock('@dcloudio/uni-app',()=>({onHide:()=>{},onShow:()=>{}}));
vi.mock('@/api',()=>({api:{mManagementContext:async()=>({actorKey:'ACTOR'}),mGoodsList:m.goods,mSvConsumptionPreview:m.preview,mSvConsumptionOrder:m.query,mSvConsume:m.consume,mSvConsumptionResolve:m.resolve,mSvSelectCustomer:m.select}}));
vi.mock('@/stores/merchant',()=>({useMerchantStore:()=>({storeNo:'S1',profile:{merchantNo:'M1'},can:m.can,ensureScope:m.scope})}));
vi.mock('vue-i18n',()=>({useI18n:()=>({t:(s:string)=>s})}));
import Sheet from '@/pages/stored-value/components/StoredValueConsumptionSheet.vue';
const service={serviceOrderNo:'ORDER1',status:'WAIT_AUTH',storeName:'Store',items:[{itemNo:'G1',title:'Care',quantity:1}],confirmation:{orderRevision:2,quoteRef:'Q1',selectionDigest:'a'.repeat(64),expiresAt:'2099-01-01T00:00:00Z'},payment:{assetKind:'STORED_VALUE',scopeType:'BRAND',issuerName:'Brand',currency:'CNY',amountMinor:5000}};
beforeEach(()=>{vi.clearAllMocks();m.can.mockReturnValue(true);m.scope.mockResolvedValue(undefined);vi.mocked(uni.getStorageSync).mockReturnValue('');Object.assign(uni,{removeStorageSync:vi.fn()});m.goods.mockResolvedValue({records:[{goodsNo:'G1',type:'SERVICE',title:'Care',skus:[{skuNo:'SKU1',spec:'Care',price:1}]}],page:1,size:20,total:1});m.preview.mockResolvedValue({service,quote:{redeemable:true,amountMinor:5000,scopeType:'BRAND',issuerName:'Brand',currency:'CNY'}});m.query.mockResolvedValueOnce({...service,status:'WAIT_SUBMIT'}).mockResolvedValue({...service,status:'COMMITTED'});m.consume.mockResolvedValue({status:'COMMITTED',operationNo:'OP1'});});
it('requires consumer confirmation and keeps the selected issuer through the original service order',async()=>{
 const w=mount(Sheet,{props:{visible:true,customer:{customerRef:'CUSTOMER',pendingScope:'PERSON',displayName:'Person',expiresAt:'2099-01-01T00:00:00Z',phoneMasked:'***',account:null},wallet:{scopeType:'BRAND',issuerRef:'BRAND_REF',name:'Brand',currency:'CNY',accountRef:'ACCOUNT_REF',account:{balanceMinor:13000,heldMinor:0,availableMinor:13000},canRedeem:true,canRecharge:true,unavailableReason:null}}});await flushPromises();
 await w.find('[data-testid="consume-increase"]').trigger('tap');
 const foot=()=>w.findAll('[role="button"]').at(-1)!;await foot().trigger('tap');await flushPromises();
 expect(m.preview.mock.calls[0][0]).toEqual({storeNo:'S1',customerRef:'CUSTOMER',issuerRef:'BRAND_REF',accountRef:'ACCOUNT_REF',items:[{skuNo:'SKU1',quantity:1}]});
 expect(m.consume).not.toHaveBeenCalled();expect(w.text()).toContain('storedValue.askCustomerConfirm');
 await foot().trigger('tap');await flushPromises();expect(m.consume).not.toHaveBeenCalled();
 await foot().trigger('tap');await flushPromises();
 expect(m.consume).toHaveBeenCalledWith({orderNo:'ORDER1',orderRevision:2,customerRef:'CUSTOMER',authorizationRef:null});expect(w.emitted('consumed')).toHaveLength(1);w.unmount();
});

const props=()=>({visible:true,customer:{customerRef:'CUSTOMER',pendingScope:'PERSON',displayName:'Person',expiresAt:'2099-01-01T00:00:00Z',phoneMasked:'***',account:null},wallet:{scopeType:'BRAND' as const,issuerRef:'BRAND_REF',name:'Brand',currency:'CNY',accountRef:'ACCOUNT_REF',account:{balanceMinor:13000,heldMinor:0,availableMinor:13000},canRedeem:true,canRecharge:true,unavailableReason:null}});
async function open(){const w=mount(Sheet,{props:props()});await flushPromises();await w.find('[data-testid="consume-increase"]').trigger('tap');return w;}
async function foot(w:ReturnType<typeof mount>){await w.findAll('[role="button"]').at(-1)!.trigger('tap');await flushPromises();}
it('refreshes an expired reference only for the same canonical person',async()=>{
 const p=props();p.customer.expiresAt='2000-01-01T00:00:00Z';m.select.mockResolvedValue({...p.customer,customerRef:'FRESH',expiresAt:'2099-01-01T00:00:00Z'});
 const w=mount(Sheet,{props:{...p,source:{type:'MEMBER',memberNo:'MEMBER'}}});await flushPromises();await w.find('[data-testid="consume-increase"]').trigger('tap');await foot(w);
 expect(m.preview.mock.calls[0][0].customerRef).toBe('FRESH');w.unmount();
});
it('resumes the staged original if a committed response was lost',async()=>{
 const w=await open();await foot(w);m.query.mockReset().mockResolvedValueOnce({...service,status:'PROCESSING'}).mockResolvedValue({...service,status:'COMMITTED'});await foot(w);
 expect(m.consume).toHaveBeenCalledExactlyOnceWith({orderNo:'ORDER1',orderRevision:2,customerRef:'CUSTOMER',authorizationRef:null});expect(m.preview).toHaveBeenCalledTimes(1);expect(w.emitted('consumed')).toHaveLength(1);w.unmount();
});
it('finds and retains an original when the preview response is lost',async()=>{
 m.preview.mockRejectedValue(new Error('network'));m.resolve.mockResolvedValue({found:true,service});const w=await open();await foot(w);
 expect(m.resolve).toHaveBeenCalledWith(m.preview.mock.calls[0][0],m.preview.mock.calls[0][1]);expect(w.text()).toContain('storedValue.askCustomerConfirm');expect(w.text()).not.toContain('CareCare');w.unmount();
});
it('does not forget an unknown original after a later definitive refusal',async()=>{
 m.preview.mockRejectedValueOnce(new Error('network')).mockRejectedValue(Object.assign(new Error('refused'),{code:40040}));m.resolve.mockResolvedValue({found:false});const w=await open();await foot(w);await foot(w);
 expect(m.preview).toHaveBeenCalledTimes(2);expect(m.preview.mock.calls[1]).toEqual(m.preview.mock.calls[0]);expect(uni.removeStorageSync).not.toHaveBeenCalled();w.unmount();
});
it('unlocks selections on a definitive rejection with no original',async()=>{
 m.preview.mockRejectedValue(Object.assign(new Error('refused'),{code:40040}));m.resolve.mockResolvedValue({found:false});const w=await open();await foot(w);
 expect(uni.removeStorageSync).toHaveBeenCalled();expect(w.find('[data-testid="consume-increase"]').exists()).toBe(true);w.unmount();
});

it('resumes the exact preview if Core created the original before quote binding failed',async()=>{
 m.preview.mockRejectedValueOnce(new Error('quote lookup lost')).mockResolvedValue({service,quote:{redeemable:true,amountMinor:5000,currency:'CNY'}});
 const unbound={...service,confirmation:null,payment:null};m.resolve.mockResolvedValue({found:true,service:unbound});m.query.mockReset().mockResolvedValue(unbound);
 const w=await open();await foot(w);expect(m.preview).toHaveBeenCalledTimes(1);await foot(w);
 expect(m.preview).toHaveBeenCalledTimes(2);expect(m.preview.mock.calls[1]).toEqual(m.preview.mock.calls[0]);expect(w.text()).toContain('storedValue.askCustomerConfirm');expect(m.consume).not.toHaveBeenCalled();w.unmount();
});

it('does not read the catalog or create a consumption without permission',async()=>{
 m.can.mockImplementation(code=>code!=='pay:sv:consume:create');
 const w=mount(Sheet,{props:props()});await flushPromises();
 expect(m.scope).toHaveBeenCalled();expect(m.goods).not.toHaveBeenCalled();
 await foot(w);expect(m.preview).not.toHaveBeenCalled();w.unmount();
});
it('keeps catalog reads gated separately from stored-value consumption',async()=>{
 m.can.mockImplementation(code=>code!=='biz:stock');
 const w=mount(Sheet,{props:props()});await flushPromises();
 expect(m.goods).not.toHaveBeenCalled();await foot(w);expect(m.preview).not.toHaveBeenCalled();w.unmount();
});
