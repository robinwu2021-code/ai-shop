<script setup lang="ts">
import {ref,watch,onMounted} from 'vue';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {useMerchantStore} from '@/stores/merchant';
import {useStoredValueList} from '@/shared/use-stored-value-list';
import {money} from '@shared/utils/money';
import {datetime} from '@shared/utils/datetime';
import type {SvCandidate,SvSelectionSource} from '@/api/stored-value-types';
const pending=ref(false);
const props=defineProps<{planNo?:string}>();
const {t}=useI18n();const merchant=useMerchantStore();const keyword=ref(''),query=ref(''),validation=ref('');
interface CustomerRow {key:string;source:SvSelectionSource;name:string;phone:string;account:SvCandidate['account'];lastRechargeAt?:string|null}
const {records,cursor,loading,error,load}=useStoredValueList<CustomerRow>(()=>merchant.storeNo,async c=>{
 if(query.value){
  const page=await api.mSvCandidates({storeNo:merchant.storeNo,phone:query.value});
  return {records:page.records.map(a=>({key:a.memberNo,source:{type:'MEMBER',memberNo:a.memberNo} as SvSelectionSource,name:a.displayName,phone:a.phoneMasked,account:a.account})),nextCursor:null,hasMore:false};
 }
 if(!merchant.can('pay:sv:account:read'))return {records:[],nextCursor:null,hasMore:false};
 const page=await api.mSvAccounts({storeNo:merchant.storeNo,cursor:c});
 return {...page,records:page.records.map(a=>({key:a.accountRef,source:{type:'ACCOUNT',accountRef:a.accountRef} as SvSelectionSource,name:a.maskedName,phone:a.maskedPhone,account:a,lastRechargeAt:a.lastRechargeAt}))};
});
function search(){
 const phone=String(keyword.value).trim();validation.value='';
 if(phone&&!/^1[3-9]\d{9}$/.test(phone)){validation.value=t('storedValue.memberPhone');return;}
 query.value=phone;void load();
}
function resume(){uni.navigateTo({url:'/pages/stored-value-recharge/index'});}
function detail(a:CustomerRow){
 const identity=a.source.type==='MEMBER'?`memberNo=${encodeURIComponent(a.source.memberNo)}`:`accountRef=${encodeURIComponent(a.source.accountRef)}`;
 uni.navigateTo({url:`/pages/stored-value-account/index?${identity}&storeNo=${encodeURIComponent(merchant.storeNo)}${props.planNo?`&planNo=${encodeURIComponent(props.planNo)}`:''}`});
}
watch(keyword,value=>{if(!String(value).trim()&&query.value){query.value='';validation.value='';void load();}});
function checkPending(){const scope=`${merchant.profile?.merchantNo}:${merchant.storeNo}`;pending.value=!!(uni.getStorageSync(`sv.recharge:${scope}`)||uni.getStorageSync(`sv.offline:${scope}`));}
function refresh(){checkPending();return load();}
watch(()=>merchant.storeNo,()=>{keyword.value='';query.value='';validation.value='';checkPending();},{flush:'sync'});
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template><view class="sv-list">
 <view v-if="merchant.can('biz:customer')&&merchant.can('pay:sv:recharge:create')" class="sh-card sh-row"><input v-model="keyword" class="field__input sh-num sh-fill" type="number" maxlength="11" :placeholder="t('storedValue.memberPhone')" confirm-type="search" @confirm="search"/><view role="button" class="sh-link sh-hit" @tap="search">{{t('common.search')}}</view></view>
 <view v-if="pending&&merchant.can('pay:sv:recharge:create')" role="button" class="sh-link sh-hit" @tap="resume">{{t('storedValue.resumePending')}}</view>
 <text v-if="validation||error" class="sh-hint">{{validation||error}}</text><view v-if="error" role="button" class="sh-btn sh-btn--soft" @tap="load()">{{t('storedValue.retry')}}</view>
 <view v-for="a in records" :key="a.key" data-testid="sv-customer" role="button" class="sh-card sv-card" @tap="detail(a)">
  <view class="sh-row sh-row--between"><text class="txt-strong">{{a.name}}</text><sh-go :text="t('storedValue.detail')"/></view><text class="sh-muted">{{a.phone}}</text>
  <sh-kv v-if="a.account" :label="t('storedValue.balance')" between><text class="txt-price sh-num">{{money(a.account.availableMinor,a.account.currency)}}</text></sh-kv>
  <text v-if="a.lastRechargeAt" class="sh-muted">{{t('storedValue.lastRecharge')}} {{datetime(Date.parse(a.lastRechargeAt))}}</text>
 </view>
 <sh-empty v-if="!loading&&!error&&!records.length" :text="t(query?'storedValue.noMember':'storedValue.empty')"/><text v-if="loading" class="sh-muted">{{t('storedValue.loading')}}</text>
 <view v-else-if="cursor" role="button" class="sh-btn sh-btn--muted" @tap="load(true)">{{t('storedValue.more')}}</view>
</view></template>
<style scoped>.sv-list{display:flex;flex-direction:column;gap:var(--sh-gap-block)}</style>
