<script setup lang="ts">
import { computed,ref,onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import { useMerchantStore } from '@/stores/merchant';
import { useStoredValueList } from '@/shared/use-stored-value-list';
import { money } from '@shared/utils/money';
const {t}=useI18n();const merchant=useMerchantStore();const status=ref('');
const {records,cursor,loading,error,load}=useStoredValueList(()=>merchant.storeNo,c=>api.mSvPlans({storeNo:merchant.storeNo,status:status.value||undefined,cursor:c}));
const statuses=computed(()=>[{key:'',label:t('storedValue.allPlans')},{key:'ACTIVE',label:t('storedValue.active')},{key:'INACTIVE',label:t('storedValue.inactive')}]);
function select(event:{detail:{value:string|number}}){status.value=statuses.value[Number(event.detail.value)]!.key;void load();}
function go(no:string){navigate(`/pages/stored-value-plan-detail/index?planNo=${encodeURIComponent(no)}`);}
onMounted(()=>void load());defineExpose({refresh:()=>load()});
function navigate(url:string){uni.navigateTo({url});}
</script>
<template>
 <view class="sv-list">
  <picker v-if="merchant.can('pay:sv:plan:read')||merchant.can('pay:sv:plan:manage')" :range="statuses" range-key="label" @change="select"><view class="sh-card sh-row sh-row--between"><text class="txt-body">{{statuses.find(x=>x.key===status)?.label}}</text><sh-icon name="chevronDown" :size="24"/></view></picker>
  <text v-if="error" class="sh-hint">{{error}}</text><button v-if="error" class="sh-btn sh-btn--soft" @tap="load()">{{t('storedValue.retry')}}</button>
  <view v-for="p in records" :key="p.planNo" class="sh-card sv-card" @tap="go(p.planNo)">
   <view class="sh-row sh-row--between"><text class="txt-strong">{{p.name}}</text><text class="sh-chip" :class="p.status==='ACTIVE'?'sh-chip--success':''">{{t(p.status==='ACTIVE'?'storedValue.active':'storedValue.inactive')}}</text></view>
   <sh-kv :label="t('storedValue.total')" between><text class="txt-price sh-num">{{money(p.totalAmountMinor,p.currency)}}</text></sh-kv>
   <text class="sh-muted">{{t('storedValue.paid')}} {{money(p.paidAmountMinor,p.currency)}} · {{t('storedValue.bonus')}} {{money(p.bonusAmountMinor,p.currency)}}</text>
  </view>
  <sh-empty v-if="!loading&&!error&&!records.length" :text="t('storedValue.empty')"/>
  <text v-if="loading" class="sh-muted">{{t('storedValue.loading')}}</text><button v-else-if="cursor" class="sh-btn sh-btn--muted" @tap="load(true)">{{t('storedValue.more')}}</button>
  <sh-actionbar v-if="merchant.can('pay:sv:plan:manage')"><button class="sh-btn" @tap="navigate('/pages/stored-value-plan-edit/index')">{{t('storedValue.newPlan')}}</button></sh-actionbar>
 </view>
</template>
<style scoped>.sv-list{display:flex;flex-direction:column;gap:var(--sh-gap-block)}</style>
