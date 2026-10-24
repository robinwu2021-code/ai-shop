<script setup lang="ts">
import {ref,watch,onMounted,onUnmounted} from 'vue';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {useMerchantStore} from '@/stores/merchant';
import {useStoredValueList} from '@/shared/use-stored-value-list';
import {money} from '@shared/utils/money';
import {datetime} from '@shared/utils/datetime';
import type {SvCandidate,SvSelectionSource} from '@/api/stored-value-types';
const pending=ref(false),typing=ref(false),identityConflicts=ref(0);
const props=defineProps<{planNo?:string}>();
const {t}=useI18n();const merchant=useMerchantStore();const keyword=ref(''),query=ref(''),validation=ref('');
interface CustomerRow {key:string;source:SvSelectionSource;name:string;phone:string;account:SvCandidate['account'];lastRechargeAt?:string|null;sourceStoreName?:string|null}
const {records,cursor,loading,error,load,invalidate}=useStoredValueList<CustomerRow>(()=>merchant.storeNo,async c=>{
 if(query.value||merchant.can('biz:customer')){
  const page=await api.mScopeCandidates(merchant.storeNo,{...(query.value?{keyword:query.value}:{}),cursor:c});identityConflicts.value=page.identityConflictCount;
  return {records:page.items.flatMap<CustomerRow>(a=>a.memberNo?[{key:a.memberNo,source:{type:'MEMBER',memberNo:a.memberNo,memberScope:'OPERATING'} as SvSelectionSource,name:a.displayName,phone:a.phoneMasked,account:a.account,sourceStoreName:a.sourceStoreName,lastRechargeAt:a.lastRechargeAt}]:a.account?[{key:a.account.accountRef,source:{type:'ACCOUNT',accountRef:a.account.accountRef} as SvSelectionSource,name:a.displayName,phone:a.phoneMasked,account:a.account,lastRechargeAt:a.lastRechargeAt}]:[]),nextCursor:page.nextCursor,hasMore:!!page.nextCursor};
 }
 identityConflicts.value=0;
 if(!merchant.can('pay:sv:account:read'))return {records:[],nextCursor:null,hasMore:false};
 const page=await api.mSvAccounts({storeNo:merchant.storeNo,cursor:c});
 return {...page,records:page.records.map(a=>({key:a.accountRef,source:{type:'ACCOUNT',accountRef:a.accountRef} as SvSelectionSource,name:a.maskedName,phone:a.maskedPhone,account:a,lastRechargeAt:a.lastRechargeAt}))};
});
function search(){
 clearTimeout(suggestTimer);typing.value=false;const phone=String(keyword.value).trim();validation.value='';
 if(phone&&(phone.length<(/^[0-9]+$/.test(phone)?3:2)||phone.length>100)){validation.value=t('storedValue.suggestHint');return;}
 query.value=phone;void load();
}
function resume(){uni.navigateTo({url:'/pages/stored-value-recharge/index'});}
function detail(a:CustomerRow){
 const identity=a.source.type==='MEMBER'?`${a.source.memberScope==='OPERATING'?'scopeMemberNo':'memberNo'}=${encodeURIComponent(a.source.memberNo)}`:`accountRef=${encodeURIComponent(a.source.accountRef)}`;
 uni.navigateTo({url:`/pages/stored-value-account/index?${identity}&storeNo=${encodeURIComponent(merchant.storeNo)}${props.planNo?`&planNo=${encodeURIComponent(props.planNo)}`:''}`});
}
let suggestTimer:ReturnType<typeof setTimeout>|undefined;
watch(keyword,value=>{
 clearTimeout(suggestTimer);typing.value=false;invalidate();const text=String(value).trim();
 if(!text){query.value='';validation.value='';void load();return;}
 if(text.length>=(/^[0-9]+$/.test(text)?3:2)){typing.value=true;suggestTimer=setTimeout(search,350);}
});
onUnmounted(()=>clearTimeout(suggestTimer));
async function checkPending(){const actor=await api.mManagementContext();const scope=`${merchant.profile?.merchantNo}:${merchant.storeNo}`;pending.value=!!uni.getStorageSync(`sv.offline.v3:${scope}:${actor.actorKey}`);}
function refresh(){void checkPending().catch(()=>{pending.value=false});return load();}
watch(()=>merchant.storeNo,()=>{keyword.value='';query.value='';validation.value='';identityConflicts.value=0;clearTimeout(suggestTimer);void checkPending().catch(()=>{pending.value=false});},{flush:'sync'});
onMounted(()=>void refresh());defineExpose({refresh});
</script>
<template><view class="sv-list">
 <view v-if="merchant.can('biz:customer')&&merchant.can('pay:sv:recharge:create')" class="sh-card sh-row"><input v-model="keyword" class="field__input sh-num sh-fill" type="text" maxlength="100" :placeholder="t('storedValue.memberSuggest')" confirm-type="search" @confirm="search"/><view role="button" class="sh-btn sh-btn--soft sh-btn--sm" @tap="search">{{t('common.search')}}</view></view>
 <view v-if="pending&&merchant.can('pay:sv:recharge:create')" role="button" class="sh-link sh-hit" @tap="resume">{{t('storedValue.resumePending')}}</view>
 <text v-if="identityConflicts" class="sh-hint">{{t('storedValue.identityConflict')}}</text>
 <text v-if="validation||error" class="sh-hint">{{validation||error}}</text><view v-if="error" role="button" class="sh-btn sh-btn--soft" @tap="load()">{{t('storedValue.retry')}}</view>
 <view v-for="a in records" :key="a.key" data-testid="sv-customer" role="button" class="sh-card sv-card" @tap="detail(a)">
  <view class="sh-row sh-row--between"><text class="txt-strong">{{a.name}}</text><sh-go :text="t('storedValue.detail')"/></view><text class="sh-muted">{{a.phone}}</text>
  <text v-if="a.sourceStoreName" class="sh-muted">{{t('storedValue.sourceStore')}} · {{a.sourceStoreName}}</text>
  <sh-kv v-if="a.account" :label="t('storedValue.balance')" between><text class="txt-price sh-num">{{money(a.account.availableMinor,a.account.currency)}}</text></sh-kv>
  <text v-if="a.lastRechargeAt" class="sh-muted">{{t('storedValue.lastRecharge')}} {{datetime(Date.parse(a.lastRechargeAt))}}</text>
 </view>
 <sh-empty v-if="!loading&&!typing&&!error&&!records.length" :text="t(query?'storedValue.noMember':'storedValue.empty')"/><text v-if="loading||typing" class="sh-muted">{{t('storedValue.loading')}}</text>
 <view v-else-if="cursor" role="button" class="sh-btn sh-btn--muted" @tap="load(true)">{{t('storedValue.more')}}</view>
</view></template>
<style scoped>.sv-list{display:flex;flex-direction:column;gap:var(--sh-gap-block)}</style>
