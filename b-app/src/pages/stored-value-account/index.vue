<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {onLoad,onShow} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {selectStoredValueMember} from '@/api/member-scopes';
import {useMerchantStore} from '@/stores/merchant';
import {money} from '@shared/utils/money';
import StoredValueRechargeSheet from '../stored-value/components/StoredValueRechargeSheet.vue';
import StoredValueRecordList from '../stored-value/components/StoredValueRecordList.vue';
import type {SvAccount,SvSelectedCustomer,SvSelectionSource} from '@/api/stored-value-types';
const {t}=useI18n();const merchant=useMerchantStore();
const account=ref<SvAccount|null>(null),customer=ref<SvSelectedCustomer|null>(null),rechargeVisible=ref(false),all=ref(false),failed=ref(false),error=ref('');
const recordList=ref<{refresh:()=>unknown}|null>(null);
let store='',refNo='',memberNo='',scopeMemberNo='',originStore='',planNo='',seq=0;
const source=computed<SvSelectionSource>(()=>scopeMemberNo?{type:'MEMBER',memberNo:scopeMemberNo,memberScope:'OPERATING'}:memberNo?{type:'MEMBER',memberNo}:{type:'ACCOUNT',accountRef:refNo});
const denied=computed(()=>(memberNo||scopeMemberNo)?(!merchant.can('biz:customer')||!merchant.can('pay:sv:recharge:create')):!merchant.can('pay:sv:account:read'));
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
  }else a=await api.mSvAccount(refNo,store);
  if(token===seq&&store===merchant.storeNo){account.value=a;customer.value=c;failed.value=false;void recordList.value?.refresh();}
 }catch(e){if(token===seq){failed.value=true;error.value=(e as Error).message;}}
}
watch(()=>merchant.storeNo,()=>{seq++;account.value=null;customer.value=null;rechargeVisible.value=false;failed.value=true;error.value=t('storedValue.leaveStore');});
onLoad(q=>{refNo=q?.accountRef||'';memberNo=q?.memberNo||'';scopeMemberNo=q?.scopeMemberNo||'';originStore=q?.storeNo||'';planNo=q?.planNo||'';void load();});
onShow(()=>{if(store)void load();});
</script>
<template><sh-scaffold :title-suffix="merchant.multiStore?merchant.currentStore?.name:''" title-key="storedValue.accountDetail" :denied="denied" :failed="failed" :pending="!account&&!customer&&!failed" @retry="load">
 <view v-if="account||customer" class="sh-card sv-card"><text class="txt-title">{{customer?.displayName||account?.maskedName}}</text><text class="sh-muted">{{customer?.phoneMasked||account?.maskedPhone}}</text><sh-kv v-if="account" :label="t('storedValue.balance')" between><text class="txt-price sh-num">{{money(account.availableMinor,account.currency)}}</text></sh-kv></view>
 <view v-if="account" class="sh-row sh-row--between"><text class="txt-strong">{{t('storedValue.recent')}}</text><view role="button" class="sh-link sh-hit" @tap="all=!all">{{t(all?'storedValue.recent':'storedValue.allRecords')}}</view></view>
 <sh-kv v-if="customer?.sourceStoreName" :label="t('storedValue.sourceStore')">{{customer.sourceStoreName}}</sh-kv>
 <StoredValueRecordList v-if="account" :key="account.accountRef" ref="recordList" :account-ref="account.accountRef" :compact="!all"/>
 <sh-empty :pending="!account&&!customer&&!failed" v-else-if="customer" :text="t('storedValue.empty')" compact/>
 <text v-if="error" class="sh-hint">{{error}}</text>
 <sh-actionbar v-if="(account||customer)&&merchant.can('pay:sv:recharge:create')"><view data-testid="detail-recharge" role="button" class="sh-btn sh-fill" @tap="rechargeVisible=true">{{t('storedValue.recharge')}}</view></sh-actionbar>
 <StoredValueRechargeSheet v-if="account||customer" :visible="rechargeVisible" :source="source" :plan-no="planNo" @close="rechargeVisible=false" @credited="load"/>
</sh-scaffold></template>
