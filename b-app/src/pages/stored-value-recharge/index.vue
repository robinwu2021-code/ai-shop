<script setup lang="ts">
import {ref,watch} from 'vue';
import {onLoad} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {useMerchantStore} from '@/stores/merchant';
import {scanCode} from '@shared/ports/scan';
import {money} from '@shared/utils/money';
import StoredValueRechargeSheet from '../stored-value/components/StoredValueRechargeSheet.vue';
import LegacyRecharge from './LegacyRecharge.vue';
import type {SvCandidate,SvSelectedCustomer,SvSelectionSource} from '@/api/stored-value-types';
const merchant=useMerchantStore();const {t}=useI18n();
const phone=ref(''),records=ref<SvCandidate[]>([]),busy=ref(false),error=ref(''),searched=ref(false),sheet=ref(false),legacy=ref(false);
const source=ref<SvSelectionSource>(),identified=ref<SvSelectedCustomer>(),planNo=ref('');let generation=0,store='';
async function search(){
 if(busy.value)return;if(!/^1[3-9]\d{9}$/.test(phone.value.trim())){error.value=t('storedValue.memberPhone');return;}
 const token=++generation;const current=merchant.storeNo;busy.value=true;error.value='';records.value=[];searched.value=false;
 try{const page=await api.mSvCandidates({storeNo:current,phone:phone.value.trim()});if(token===generation&&current===merchant.storeNo){records.value=page.records;searched.value=true;}}
 catch(e){if(token===generation)error.value=(e as Error).message;}
 finally{if(token===generation)busy.value=false;}
}
function choose(c:SvCandidate){if(!c.canRecharge){error.value=t('storedValue.memberBlocked');return;}source.value={type:'MEMBER',memberNo:c.memberNo};identified.value=undefined;sheet.value=true;}
function add(){uni.navigateTo({url:`/pages/member-add/index?from=stored-value&storeNo=${encodeURIComponent(merchant.storeNo)}`,events:{'member-enrolled':(m:{memberNo:string})=>{if(store!==merchant.storeNo)return;source.value={type:'MEMBER',memberNo:m.memberNo};identified.value=undefined;sheet.value=true;}}});}
async function scan(){
 if(busy.value)return;busy.value=true;error.value='';const token=generation,current=merchant.storeNo;
 try{const code=await scanCode();if(!code)return;const c=await api.mResolveMemberCode({storeNo:current,memberCode:code});if(token!==generation||current!==merchant.storeNo)return;
  identified.value={customerRef:c.customerRef,expiresAt:c.expiresAt,displayName:c.maskedName,phoneMasked:'',account:null};source.value=undefined;sheet.value=true;
 }catch(e){if(token===generation)error.value=(e as Error).message;}finally{if(token===generation)busy.value=false;}
}
function done(){if(phone.value)void search();}
watch(()=>merchant.storeNo,()=>{generation++;busy.value=false;records.value=[];searched.value=false;sheet.value=false;source.value=undefined;identified.value=undefined;store=merchant.storeNo;error.value=t('storedValue.leaveStore');});
onLoad(async q=>{
 await merchant.ensureStores();store=merchant.storeNo;planNo.value=q?.planNo||'';
 const saved=uni.getStorageSync(`sv.recharge:${merchant.profile?.merchantNo}:${store}`);
 if(saved){legacy.value=true;return;}
 if(q?.memberNo&&(!q.storeNo||q.storeNo===store)){source.value={type:'MEMBER',memberNo:q.memberNo};sheet.value=true;}
 else if(uni.getStorageSync(`sv.offline:${merchant.profile?.merchantNo}:${store}`))sheet.value=true;
});
</script>
<template>
 <LegacyRecharge v-if="legacy"/>
 <sh-scaffold v-else :title-suffix="merchant.multiStore?merchant.currentStore?.name:''" title-key="storedValue.rechargeTitle" :denied="!merchant.can('biz:customer')||!merchant.can('pay:sv:recharge:create')">
  <view class="sh-card sv-card"><input v-model="phone" class="field__input sh-num" type="number" maxlength="11" :placeholder="t('storedValue.memberPhone')" @confirm="search"/>
   <view class="sh-row" data-testid="member-actions"><view role="button" class="sh-btn sh-btn--soft sh-fill" :class="{'is-disabled':busy}" @tap="search">{{t('storedValue.findMember')}}</view><view role="button" class="sh-btn sh-btn--soft sh-fill" :class="{'is-disabled':busy}" @tap="scan">{{t('storedValue.scan')}}</view></view>
  </view>
  <text v-if="error" class="sh-hint">{{error}}</text>
  <view v-for="c in records" :key="c.memberNo" role="button" class="sh-card sv-card" @tap="choose(c)">
   <view class="sh-row sh-row--between"><text class="txt-strong">{{c.displayName}}</text><sh-go :text="t('storedValue.recharge')"/></view><text class="sh-muted sh-num">{{c.phoneMasked}}</text>
   <text class="sh-muted">{{c.account?`${t('storedValue.balance')} ${money(c.account.availableMinor,c.account.currency)}`:t('storedValue.notFunded')}}</text>
  </view>
  <sh-empty v-if="busy" pending compact/>
  <sh-empty v-else-if="searched&&!records.length" :text="t('storedValue.noMember')" compact/>
  <view role="button" class="sh-link sh-hit" @tap="add">＋ {{t('memberAdd.title')}}</view>
  <StoredValueRechargeSheet :visible="sheet" :source="source" :identified="identified" :plan-no="planNo" @close="sheet=false" @credited="done"/>
 </sh-scaffold>
</template>
