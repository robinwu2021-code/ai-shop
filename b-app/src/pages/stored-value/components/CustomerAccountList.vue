<script setup lang="ts">
import { ref,onMounted } from 'vue';import { useI18n } from 'vue-i18n';import { api } from '@/api';import { useMerchantStore } from '@/stores/merchant';import { useStoredValueList } from '@/shared/use-stored-value-list';import { money } from '@shared/utils/money';import { datetime } from '@shared/utils/datetime';
const {t}=useI18n();const merchant=useMerchantStore();const keyword=ref('');
const {records,cursor,loading,error,load}=useStoredValueList(()=>merchant.storeNo,c=>api.mSvAccounts({storeNo:merchant.storeNo,keyword:keyword.value.trim()||undefined,cursor:c}));
onMounted(()=>void load());defineExpose({refresh:()=>load()});
function navigate(url:string){uni.navigateTo({url});}
</script>
<template><view class="sv-list">
 <view class="sh-card sh-row"><input v-model="keyword" class="field__input" :placeholder="t('storedValue.search')" confirm-type="search" @confirm="load()"/><button class="sh-link sh-hit" @tap="load()">{{t('common.search')}}</button></view>
 <text v-if="error" class="sh-hint">{{error}}</text><button v-if="error" class="sh-btn sh-btn--soft" @tap="load()">{{t('storedValue.retry')}}</button>
 <view v-for="a in records" :key="a.accountRef" class="sh-card sv-card" @tap="navigate(`/pages/stored-value-account/index?accountRef=${encodeURIComponent(a.accountRef)}`)">
  <view class="sh-row sh-row--between"><text class="txt-strong">{{a.maskedName}}</text><sh-go :text="t('storedValue.detail')"/></view><text class="sh-muted">{{a.maskedPhone}}</text>
  <sh-kv :label="t('storedValue.balance')" between><text class="txt-price sh-num">{{money(a.availableMinor,a.currency)}}</text></sh-kv>
  <text v-if="a.lastOccurredAt" class="sh-muted">{{t('storedValue.lastActivity')}} {{datetime(Date.parse(a.lastOccurredAt))}}</text>
 </view>
 <view v-if="merchant.can('pay:sv:recharge:create')" role="button" class="sh-btn" @tap="navigate('/pages/stored-value-recharge/index')">{{t('storedValue.rechargeTitle')}}</view>
 <sh-empty v-if="!loading&&!error&&!records.length" :text="t('storedValue.empty')"/><text v-if="loading" class="sh-muted">{{t('storedValue.loading')}}</text>
 <button v-else-if="cursor" class="sh-btn sh-btn--muted" @tap="load(true)">{{t('storedValue.more')}}</button>
</view></template>
<style scoped>.sv-list{display:flex;flex-direction:column;gap:var(--sh-gap-block)}</style>
