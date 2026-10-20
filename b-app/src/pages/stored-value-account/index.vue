<script setup lang="ts">
import { ref,watch } from 'vue';import { onLoad,onShow } from '@dcloudio/uni-app';import { useI18n } from 'vue-i18n';import { api } from '@/api';import { useMerchantStore } from '@/stores/merchant';import { money } from '@shared/utils/money';import StoredValueRecordList from '../stored-value/components/StoredValueRecordList.vue';import type { SvAccount } from '@/api/stored-value-types';
const {t}=useI18n();const merchant=useMerchantStore();const account=ref<SvAccount|null>(null);const all=ref(false);const failed=ref(false);const error=ref('');let store='',refNo='',seq=0;
async function load(){const token=++seq;try{if(!store){await merchant.ensureStores();store=merchant.storeNo;}const a=await api.mSvAccount(refNo,store);if(token===seq&&store===merchant.storeNo){account.value=a;failed.value=false;}}catch(e){if(token===seq){failed.value=true;error.value=(e as Error).message;}}}
watch(()=>merchant.storeNo,()=>{seq++;account.value=null;failed.value=true;error.value=t('storedValue.leaveStore');});onLoad(q=>{refNo=q?.accountRef||'';void load();});onShow(()=>{if(store)void load();});
function navigate(url:string){uni.navigateTo({url});}
</script>
<template><sh-scaffold :title-suffix="merchant.multiStore ? merchant.currentStore?.name : ''" title-key="storedValue.accountDetail" :denied="!merchant.can('pay:sv:account:read')" :failed="failed" :pending="!account&&!failed" @retry="load">
 <view v-if="account" class="sh-card sv-card"><text class="txt-title">{{account.maskedName}}</text><text class="sh-muted">{{account.maskedPhone}}</text><sh-kv :label="t('storedValue.balance')" between><text class="txt-price sh-num">{{money(account.availableMinor,account.currency)}}</text></sh-kv></view>
 <view v-if="account" class="sh-row sh-row--between"><text class="txt-strong">{{t('storedValue.recent')}}</text><button class="sh-link sh-hit" @tap="all=!all">{{t(all?'storedValue.recent':'storedValue.allRecords')}}</button></view><StoredValueRecordList v-if="account" :key="account.accountRef" :account-ref="account.accountRef" :compact="!all"/>
 <text v-if="error" class="sh-hint">{{error}}</text><sh-actionbar v-if="account&&merchant.can('pay:sv:recharge:create')"><button class="sh-btn" @tap="navigate('/pages/stored-value-recharge/index')">{{t('storedValue.rechargeTitle')}}</button></sh-actionbar>
</sh-scaffold></template>
