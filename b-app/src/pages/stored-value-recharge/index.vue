<script setup lang="ts">
import {ref} from 'vue';
import {onLoad} from '@dcloudio/uni-app';
import {useMerchantStore} from '@/stores/merchant';
import StoredValueRechargeSheet from '../stored-value/components/StoredValueRechargeSheet.vue';
import LegacyRecharge from './LegacyRecharge.vue';
const merchant=useMerchantStore();const legacy=ref(false),recovering=ref(false);
function directory(){uni.redirectTo({url:'/pages/stored-value/index?tab=customers'});}
// Compatibility route: existing commands must remain recoverable after removing duplicate search UI.
onLoad(async q=>{
 await merchant.ensureStores();const scope=`${merchant.profile?.merchantNo}:${merchant.storeNo}`;
 if(uni.getStorageSync(`sv.recharge:${scope}`)){legacy.value=true;return;}
 if(uni.getStorageSync(`sv.offline:${scope}`)||uni.getStorageSync(`sv.offline.v2:${scope}`)){recovering.value=true;return;}
 const plan=q?.planNo?`&planNo=${encodeURIComponent(q.planNo)}`:'';
 if(q?.memberNo){uni.redirectTo({url:`/pages/stored-value-account/index?memberNo=${encodeURIComponent(q.memberNo)}&storeNo=${encodeURIComponent(q.storeNo||merchant.storeNo)}${plan}`});return;}
 uni.redirectTo({url:`/pages/stored-value/index?tab=customers${plan}`});
});
</script>
<template>
 <LegacyRecharge v-if="legacy"/>
 <sh-scaffold v-else title-key="storedValue.customers" :pending="!recovering">
  <StoredValueRechargeSheet :visible="recovering" @close="directory"/>
 </sh-scaffold>
</template>
