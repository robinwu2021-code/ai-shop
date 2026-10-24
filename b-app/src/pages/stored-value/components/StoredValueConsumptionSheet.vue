<script setup lang="ts">
import {computed,ref,watch,onUnmounted} from 'vue';
import {onHide,onShow} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {selectStoredValueMember} from '@/api/member-scopes';
import {useMerchantStore} from '@/stores/merchant';
import {idempotencyKey} from '@shared/net/http-client';
import {money} from '@shared/utils/money';
import type {Goods} from '@shared/types';
import type {SvSelectedCustomer,SvSelectionSource,SvWallet,SvConsumptionCreate,SvConsumptionPreview,SvServiceView} from '@/api/stored-value-types';
const props=defineProps<{visible:boolean;customer:SvSelectedCustomer;wallet:SvWallet;source?:SvSelectionSource}>();
const emit=defineEmits<{close:[];consumed:[]}>();
const {t}=useI18n();const merchant=useMerchantStore();
type Original={key:string;body:SvConsumptionCreate;preview:SvConsumptionPreview|null;serviceOrderNo?:string;unknown?:boolean};
const goods=ref<Goods[]>([]),counts=ref<Record<string,number>>({}),original=ref<Original|null>(null),order=ref<SvServiceView|null>(null);
const loading=ref(false),busy=ref(false),error=ref(''),page=ref(1),more=ref(false);
let generation=0,store='',storage='',foreground=true,timer:ReturnType<typeof setTimeout>|undefined;
const terminal=computed(()=>!!order.value&&['COMMITTED','CANCELLED','RELEASED','EXPIRED'].includes(order.value.status));
const lines=computed(()=>Object.entries(counts.value).filter(([,quantity])=>quantity>0).map(([skuNo,quantity])=>({skuNo,quantity})));
const canSubmit=computed(()=>order.value?.status==='WAIT_SUBMIT'&&!!order.value.confirmation);
function live(token:number){return props.visible&&foreground&&token===generation&&store===merchant.storeNo;}
function persist(){if(original.value)uni.setStorageSync(storage,JSON.stringify(original.value));}
function accept(value:SvServiceView){order.value=value;if(terminal.value){uni.removeStorageSync(storage);if(value.status==='COMMITTED')emit('consumed');}}
function schedule(){clearTimeout(timer);if(props.visible&&foreground&&(original.value?.preview||original.value?.serviceOrderNo)&&!terminal.value)timer=setTimeout(()=>void refresh(),3000);}
async function products(append=false){
 if(loading.value||busy.value)return;const token=generation;loading.value=true;error.value='';
 try{const next=append?page.value+1:1;const list=await api.mGoodsList({status:'ON_SALE',page:next,size:20});if(!live(token))return;
  goods.value=append?[...goods.value,...list.records.filter(g=>g.type==='SERVICE')]:list.records.filter(g=>g.type==='SERVICE');page.value=next;more.value=list.page*list.size<list.total;
 }catch(e){if(live(token))error.value=(e as Error).message;}finally{if(token===generation)loading.value=false;}
}
async function load(){
 const token=++generation;clearTimeout(timer);busy.value=false;store=merchant.storeNo;goods.value=[];counts.value={};original.value=null;order.value=null;error.value='';loading.value=true;
 try{
  const actor=await api.mManagementContext();if(!live(token))return;
  storage=['sv.consume.v1',actor.actorKey,merchant.profile?.merchantNo,store,props.customer.pendingScope||props.customer.memberNo,props.wallet.scopeType].map(v=>encodeURIComponent(String(v||''))).join(':');
  const saved=uni.getStorageSync(storage);if(saved){const p=JSON.parse(String(saved)) as Original;if(p.body.storeNo!==store)throw Error(t('storedValue.leaveStore'));original.value=p;}
 }catch(e){if(live(token))error.value=(e as Error).message;return;}finally{if(token===generation)loading.value=false;}
 if(!live(token))return;
 if(original.value?.preview||original.value?.serviceOrderNo){await refresh();}else if(original.value){await preview();}else await products();
}
function quantity(skuNo:string,delta:number){if(busy.value||original.value)return;const next=Math.max(0,Math.min(100,(counts.value[skuNo]||0)+delta));counts.value={...counts.value,[skuNo]:next};}
async function currentReference(){
 if(Date.parse(props.customer.expiresAt)>Date.now())return props.customer.customerRef;
 if(!props.source)throw Error(t('storedValue.referenceExpired'));
 const fresh=await selectStoredValueMember(store,props.source);
 if(!fresh.pendingScope||fresh.pendingScope!==props.customer.pendingScope)throw Error(t('storedValue.identityConflict'));
 return fresh.customerRef;
}
async function preview(){
 if(busy.value||loading.value||store!==merchant.storeNo||!props.wallet.canRedeem||!props.wallet.accountRef)return;
 if(!original.value&&!lines.value.length)return;const token=generation;busy.value=true;error.value='';
 try{
  if(!original.value){const reference=await currentReference();if(!live(token))return;original.value={key:idempotencyKey(),body:{storeNo:store,customerRef:reference,issuerRef:props.wallet.issuerRef,accountRef:props.wallet.accountRef,items:lines.value},preview:null};persist();}
  const p=original.value;const value=await api.mSvConsumptionPreview(p.body,p.key);if(!live(token))return;p.preview=value;persist();accept(value.service);
  if(!value.quote.redeemable)error.value=t('storedValue.insufficientWallet');schedule();
 }catch(e){
  if(live(token)){
   error.value=(e as Error).message;const pending=original.value;
   if(pending){
    const definite=typeof(e as {code?:number}).code==='number'&&(e as {code:number}).code>0;
    if(!definite)pending.unknown=true;
    persist();
    try{const found=await api.mSvConsumptionResolve(pending.body,pending.key);if(!live(token))return;
     if(found.found&&found.service){pending.serviceOrderNo=found.service.serviceOrderNo;persist();accept(found.service);schedule();}
     else if(definite&&!pending.unknown){uni.removeStorageSync(storage);original.value=null;}
    }catch{pending.unknown=true;persist();}
   }
  }
 }finally{if(token===generation)busy.value=false;}
}
async function refresh(){
 const p=original.value;const no=p?.serviceOrderNo||p?.preview?.service.serviceOrderNo;if(!p||!no||busy.value||store!==merchant.storeNo)return;const token=generation;busy.value=true;
 try{let value=await api.mSvConsumptionOrder(no,store);
  if(live(token)&&value.status==='PROCESSING'&&value.confirmation){
   // The original command is already staged. Retry that command, never create another quote/use.
   await api.mSvConsume({orderNo:value.serviceOrderNo,orderRevision:value.confirmation.orderRevision,customerRef:p.body.customerRef,authorizationRef:null});
   if(!live(token))return;value=await api.mSvConsumptionOrder(value.serviceOrderNo,store);
  }
  if(live(token)){accept(value);schedule();}}
 catch(e){if(live(token)){error.value=(e as Error).message;schedule();}}finally{if(token===generation)busy.value=false;}
}
async function submit(){
 if(busy.value||!canSubmit.value||!order.value?.confirmation||store!==merchant.storeNo)return;const token=generation;busy.value=true;error.value='';
 try{const reference=await currentReference();if(!live(token))return;await api.mSvConsume({orderNo:order.value.serviceOrderNo,orderRevision:order.value.confirmation.orderRevision,customerRef:reference,authorizationRef:null});if(live(token)){const value=await api.mSvConsumptionOrder(order.value.serviceOrderNo,store);if(live(token)){accept(value);schedule();}}}
 catch(e){if(live(token))error.value=(e as Error).message;}finally{if(token===generation)busy.value=false;}
}
async function cancel(){
 if(!order.value||busy.value||terminal.value||store!==merchant.storeNo)return;const token=generation;busy.value=true;error.value='';
 try{await api.mSvConsumptionCancel(order.value.serviceOrderNo,store);if(live(token)){const value=await api.mSvConsumptionOrder(order.value.serviceOrderNo,store);if(live(token)){accept(value);schedule();}}}
 catch(e){if(live(token))error.value=(e as Error).message;}finally{if(token===generation)busy.value=false;}
}
function close(){if(!busy.value)emit('close');}
watch(()=>[props.visible,merchant.storeNo,props.customer.customerRef,props.wallet.issuerRef],()=>{if(props.visible)void load();else{generation++;clearTimeout(timer);}},{immediate:true});
onHide(()=>{foreground=false;clearTimeout(timer);});onShow(()=>{const returning=!foreground;foreground=true;if(returning&&props.visible)void load();});onUnmounted(()=>{generation++;clearTimeout(timer);});
</script>
<template><sh-sheet :visible="visible" :title="t('storedValue.redemption')" @close="close">
 <view class="sv-consume">
  <view class="sh-row sh-row--between"><text class="txt-strong">{{customer.displayName}}</text><text>{{t(wallet.scopeType==='BRAND'?'issuer.brandScope':'issuer.storeScope')}}</text></view>
  <text class="sh-muted">{{wallet.name}}</text>
  <sh-empty v-if="loading" pending compact/>
  <template v-if="!original">
   <view v-for="good in goods" :key="good.goodsNo" class="sh-card">
    <text class="txt-strong">{{good.title}}</text>
    <view v-for="sku in good.skus" :key="sku.skuNo" class="sh-cell sh-row sh-row--between">
     <view class="sh-fill"><text>{{sku.spec}}</text><text class="sh-num">{{money(sku.price,wallet.currency)}}</text></view>
     <view class="sh-row"><view role="button" class="sh-link sh-hit" @tap="quantity(sku.skuNo,-1)">−</view><text class="sh-num">{{counts[sku.skuNo]||0}}</text><view role="button" class="sh-link sh-hit" @tap="quantity(sku.skuNo,1)">+</view></view>
    </view>
   </view>
   <sh-empty v-if="!loading&&!goods.length" :text="t('storedValue.noServices')" compact/>
   <view v-if="more&&!loading" class="sh-btn sh-btn--soft" @tap="products(true)">{{t('storedValue.more')}}</view>
  </template>
  <template v-if="order">
   <view class="sh-card"><text class="txt-strong">{{t('storedValue.consumeState.'+order.status)}}</text><sh-kv v-for="(item,i) in order.items" :key="i" :label="item.title" between>× {{item.quantity}}</sh-kv><sh-kv v-if="order.payment" :label="t('storedValue.consumeAmount')" between>{{money(order.payment.amountMinor,order.payment.currency)}}</sh-kv></view>
   <text v-if="order.status==='WAIT_AUTH'&&order.confirmation" class="sh-hint">{{t('storedValue.askCustomerConfirm')}}</text>
   <view v-if="!terminal" class="sh-link sh-hit" @tap="cancel">{{t('storedValue.cancelConsumption')}}</view>
  </template>
  <text v-if="error" class="sh-hint">{{error}}</text>
 </view>
 <template #foot><view role="button" class="sh-btn" :class="{'is-disabled':busy||loading}" @tap="terminal?close():canSubmit?submit():(original?.preview||original?.serviceOrderNo)?refresh():preview()">{{t(terminal?'storedValue.finish':canSubmit?'storedValue.completeRedemption':(original?.preview||original?.serviceOrderNo)?'storedValue.refresh':original?'storedValue.resume':'storedValue.previewConsumption')}}</view></template>
</sh-sheet></template>
<style scoped>.sv-consume{display:flex;flex-direction:column;gap:var(--sh-gap-block);min-height:32vh}</style>
