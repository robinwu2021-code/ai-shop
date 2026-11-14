<script setup lang="ts">
import { computed,ref,onMounted,watch } from 'vue';import { useI18n } from 'vue-i18n';import { api } from '@/api';import { useMerchantStore } from '@/stores/merchant';import { useStoredValueList } from '@/shared/use-stored-value-list';import { dateFilter } from '@/shared/stored-value-form';import { money } from '@shared/utils/money';import { datetime } from '@shared/utils/datetime';import type { SvRecordsQuery,SvWallet } from '@/api/stored-value-types';
const props=defineProps<{accountRef?:string;compact?:boolean;wallet?:SvWallet;customerRef?:string}>();const {t}=useI18n();const merchant=useMerchantStore();const type=ref<SvRecordsQuery['type']>('ALL');const dates=ref<{startDate?:string;endDate?:string}>({});const open=ref(false);const start=ref('');const end=ref('');const dateError=ref('');
const {records,cursor,loading,error,load}=useStoredValueList(()=>merchant.storeNo,c=>props.wallet?.accountRef&&props.customerRef?api.mSvWalletRecords(merchant.storeNo,props.customerRef,{issuerRef:props.wallet.issuerRef,accountRef:props.wallet.accountRef,type:type.value,...dates.value,cursor:c,size:props.compact?3:20}):api.mSvRecords({storeNo:merchant.storeNo,type:type.value,...dates.value,accountRef:props.accountRef,cursor:c,size:props.compact?3:20}));
const displayed=computed(()=>props.compact?records.value.slice(0,3):records.value);
const tabs=computed(()=>[{key:'ALL',label:t('storedValue.all')},{key:'RECHARGE',label:t('storedValue.recharge')},{key:'REDEMPTION',label:t('storedValue.redemption')}]);
function select(k:string){type.value=k as SvRecordsQuery['type'];void load();}
function time(){start.value=dates.value.startDate||'';end.value=dates.value.endDate||'';dateError.value='';open.value=true;}
function apply(){try{dates.value=dateFilter(start.value,end.value);dateError.value='';open.value=false;void load();}catch{dateError.value=t('storedValue.invalidDate');}}
function clear(){dates.value={};open.value=false;void load();}
watch(()=>[props.accountRef,props.wallet?.accountRef,props.customerRef],()=>void load());onMounted(()=>void load());defineExpose({refresh:()=>load()});
function navigate(url:string){uni.navigateTo({url});}
</script>
<template><view class="sv-list">
 <view v-if="!compact" class="sh-row sh-row--between"><sh-tabs :items="tabs" :active="type" @change="select"/><button class="sh-link sh-hit" @tap="time">{{dates.startDate||dates.endDate?`${dates.startDate?.slice(5)||'…'}–${dates.endDate?.slice(5)||'…'}`:t('storedValue.time')}}</button></view>
 <text v-if="error" class="sh-hint">{{error}}</text><button v-if="error" class="sh-btn sh-btn--soft" @tap="load()">{{t('storedValue.retry')}}</button>
 <view v-for="r in displayed" :key="r.recordNo" class="sh-card sv-card" @tap="navigate(`/pages/stored-value-record/index?recordNo=${encodeURIComponent(r.recordNo)}`)">
  <view class="sh-row sh-row--between"><text class="txt-strong">{{t(r.type==='RECHARGE'?'storedValue.recharge':'storedValue.redemption')}} · {{r.title}}</text><text class="txt-strong sh-num">{{r.signedAmountMinor>0?'+':''}}{{money(r.signedAmountMinor,r.currency)}}</text></view>
  <text class="sh-muted">{{r.maskedName}} · {{r.storeName}}</text><view class="sh-row sh-row--between"><text class="sh-muted">{{datetime(Date.parse(r.occurredAt))}}</text><text class="sh-muted sh-num">{{t('storedValue.balance')}} {{money(r.balanceAfterMinor,r.currency)}}</text></view>
 </view>
 <sh-empty v-if="!loading&&!error&&!records.length" :text="t('storedValue.empty')"/><text v-if="loading" class="sh-muted">{{t('storedValue.loading')}}</text><button v-else-if="cursor&&!compact" class="sh-btn sh-btn--muted" @tap="load(true)">{{t('storedValue.more')}}</button>
 <sh-sheet :visible="open" :title="t('storedValue.time')" fit @close="open=false">
  <view class="field"><text class="field__label">{{t('storedValue.start')}}</text><picker mode="date" :value="start" @change="start=$event.detail.value"><view class="field__input">{{start||t('storedValue.select')}}</view></picker></view>
  <view class="field"><text class="field__label">{{t('storedValue.end')}}</text><picker mode="date" :value="end" @change="end=$event.detail.value"><view class="field__input">{{end||t('storedValue.select')}}</view></picker></view>
  <text v-if="dateError" class="sh-hint">{{dateError}}</text><template #foot><view class="sh-row"><button class="sh-btn sh-btn--soft" @tap="clear">{{t('storedValue.unlimited')}}</button><button class="sh-btn" @tap="apply">{{t('storedValue.confirm')}}</button></view></template>
 </sh-sheet>
</view></template>
<style scoped>.sv-list{display:flex;flex-direction:column;gap:var(--sh-gap-block)}</style>
