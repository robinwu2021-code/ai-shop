<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {onLoad,onShow} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {selectStoredValueMember} from '@/api/member-scopes';
import {useMerchantStore} from '@/stores/merchant';
import {money} from '@shared/utils/money';
import StoredValueConsumptionSheet from '../stored-value/components/StoredValueConsumptionSheet.vue';
import StoredValueRechargeSheet from '../stored-value/components/StoredValueRechargeSheet.vue';
import StoredValueRecordList from '../stored-value/components/StoredValueRecordList.vue';
import type {SvAccount,SvSelectedCustomer,SvSelectionSource,SvWallet} from '@/api/stored-value-types';
const {t}=useI18n();const merchant=useMerchantStore();
const account=ref<SvAccount|null>(null),customer=ref<SvSelectedCustomer|null>(null),rechargeVisible=ref(false),consumeVisible=ref(false),all=ref(false),failed=ref(false),error=ref('');
const wallets=ref<SvWallet[]>([]),activeScope=ref<'STORE'|'BRAND'>('STORE');
const activeWallet=computed(()=>wallets.value.find(w=>w.scopeType===activeScope.value&&w.accountRef));
function consumed(){consumeVisible.value=false;void load();}
const recordList=ref<{refresh:()=>unknown}|null>(null);
let store='',refNo='',memberNo='',scopeMemberNo='',originStore='',planNo='',seq=0;
const source=computed<SvSelectionSource>(()=>scopeMemberNo?{type:'MEMBER',memberNo:scopeMemberNo,memberScope:'OPERATING'}:memberNo?{type:'MEMBER',memberNo}:{type:'ACCOUNT',accountRef:refNo});
const denied=computed(()=>(memberNo||scopeMemberNo)?(!merchant.can('biz:customer')||!merchant.can('pay:sv:recharge:create')&&!merchant.can('pay:sv:account:read')):!merchant.can('pay:sv:account:read'));
async function load(){
 const token=++seq;error.value='';
 try{
  if(!store){await merchant.ensureStores();store=originStore||merchant.storeNo;}
  if(store!==merchant.storeNo)throw Error(t('storedValue.leaveStore'));
  if(!memberNo&&!scopeMemberNo&&!refNo)throw Error(t('storedValue.chooseMember'));
  let a:SvAccount|null=null,c:SvSelectedCustomer|null=null;
  if(memberNo||scopeMemberNo){
   c=await selectStoredValueMember(store,source.value);
   if(c.account&&merchant.can('pay:sv:account:read'))a=await api.mSvAccount(c.account.accountRef,store);
  }else{a=await api.mSvAccount(refNo,store);if(merchant.can('biz:customer')&&merchant.can('pay:sv:recharge:create'))c=await selectStoredValueMember(store,source.value);}
  let choices:SvWallet[]=[];
  if(c&&merchant.can('pay:sv:account:read')){
   try{choices=await api.mSvWallets(store,c.customerRef);}catch(e){if((e as {code?:number}).code!==40040)throw e;}
  }
  if(token===seq&&store===merchant.storeNo){account.value=a;customer.value=c;wallets.value=choices;
   if(!choices.some(w=>w.scopeType===activeScope.value&&w.accountRef))activeScope.value=choices.find(w=>w.accountRef)?.scopeType||'STORE';failed.value=false;void recordList.value?.refresh();}
 }catch(e){if(token===seq){failed.value=true;error.value=(e as Error).message;}}
}
watch(()=>merchant.storeNo,()=>{seq++;account.value=null;customer.value=null;wallets.value=[];rechargeVisible.value=false;consumeVisible.value=false;failed.value=true;error.value=t('storedValue.leaveStore');});
onLoad(q=>{refNo=q?.accountRef||'';memberNo=q?.memberNo||'';scopeMemberNo=q?.scopeMemberNo||'';originStore=q?.storeNo||'';planNo=q?.planNo||'';void load();});
onShow(()=>{if(store)void load();});
</script>
<template><sh-scaffold :title-suffix="merchant.multiStore?merchant.currentStore?.name:''" title-key="storedValue.accountDetail" :denied="denied" :failed="failed" :pending="!account&&!customer&&!failed" @retry="load">
 <view v-if="account||customer" class="sh-card sv-card"><text class="txt-title">{{customer?.displayName||account?.maskedName}}</text><text class="sh-muted">{{customer?.phoneMasked||account?.maskedPhone}}</text><sh-kv v-if="account&&!wallets.length" :label="t('storedValue.balance')" between><text class="txt-price sh-num">{{money(account.availableMinor,account.currency)}}</text></sh-kv></view>
 <view v-for="wallet in wallets" :key="wallet.scopeType" role="radio" :aria-checked="activeScope===wallet.scopeType" class="sh-card sv-card" :class="{'sv-wallet--selected':activeScope===wallet.scopeType}" @tap="activeScope=wallet.scopeType">
  <view class="sh-row sh-row--between"><text class="txt-strong">{{t(wallet.scopeType==='BRAND'?'issuer.brandScope':'issuer.storeScope')}}</text><text class="sh-muted">{{wallet.name}}</text></view>
  <sh-kv v-if="wallet.account" :label="t('storedValue.balance')" between><text class="txt-price sh-num">{{money(wallet.account.availableMinor,wallet.currency)}}</text></sh-kv>
  <text v-else class="sh-muted">{{t('storedValue.noWallet')}}</text>
 </view>
 <view v-if="account||activeWallet" class="sh-row sh-row--between"><text class="txt-strong">{{t('storedValue.recent')}}</text><view role="button" class="sh-link sh-hit" @tap="all=!all">{{t(all?'storedValue.recent':'storedValue.allRecords')}}</view></view>
 <sh-kv v-if="customer?.sourceStoreName" :label="t('storedValue.sourceStore')">{{customer.sourceStoreName}}</sh-kv>
 <StoredValueRecordList v-if="activeWallet||account&&!wallets.length" :key="activeWallet?.accountRef||account?.accountRef" ref="recordList" :account-ref="account?.accountRef" :customer-ref="customer?.customerRef" :wallet="activeWallet" :compact="!all"/>
 <sh-empty v-else-if="customer" :text="t('storedValue.empty')" compact/>
 <text v-if="error" class="sh-hint">{{error}}</text>
 <sh-actionbar v-if="(account||customer)&&(merchant.can('pay:sv:recharge:create')||merchant.can('pay:sv:consume:create'))"><view class="sh-row"><view v-if="customer&&activeWallet?.canRedeem&&merchant.can('pay:sv:consume:create')" role="button" class="sh-btn sh-btn--soft sh-fill" @tap="consumeVisible=true">{{t('storedValue.redemption')}}</view><view v-if="merchant.can('pay:sv:recharge:create')" data-testid="detail-recharge" role="button" class="sh-btn sh-fill" @tap="rechargeVisible=true">{{t('storedValue.recharge')}}</view></view></sh-actionbar>
 <StoredValueConsumptionSheet v-if="customer&&activeWallet" :visible="consumeVisible" :customer="customer" :wallet="activeWallet" :source="source" @close="consumeVisible=false" @consumed="consumed"/>
 <StoredValueRechargeSheet v-if="account||customer" :visible="rechargeVisible" :source="source" :plan-no="planNo" @close="rechargeVisible=false" @credited="load"/>
</sh-scaffold></template>

<style scoped>.sv-wallet--selected{background:var(--sh-primary-tint)}</style>
