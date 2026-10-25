<script setup lang="ts">
import {computed,ref} from 'vue';
import {onLoad} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {idempotencyKey} from '@shared/net/http-client';
import {CURRENCIES} from '@shared/utils/constants';
import {currentCurrency,money} from '@shared/utils/money';
import {definiteGovernanceRejection} from '@/shared/stored-value-governance-errors';
import {fixedMinor} from '@/shared/stored-value-form';
import type {SvPolicy,SvPolicyRules,SvPolicyTarget,SvPolicyTargetOption} from '@/api/stored-value-governance';
const {t}=useI18n();const currency=currentCurrency(),scale=CURRENCIES[currency].minorUnits;
const management=ref(''),items=ref<SvPolicy[]>([]),targets=ref<SvPolicyTargetOption[]>([]),target=ref<SvPolicyTargetOption|null>(null);
const editing=ref(false),choosing=ref(false),loading=ref(true),busy=ref(false),failed=ref(false),error=ref(''),selected=ref<SvPolicy|null>(null);
const allow=ref(true),approval=ref(true),ratio=ref(''),paid=ref(''),bonus=ref(''),days=ref(''),outstanding=ref('');
type Pending={no:string|null;body:{managementNo:string;targetType:SvPolicyTarget;targetNo:string;currency:typeof currency;rules:SvPolicyRules};revision:number;key:string};
const pending=ref<Pending|null>(null);const locked=computed(()=>busy.value||!!pending.value);const storage=computed(()=>`sv-policy-pending:${management.value}:${currency}`);
function form(p:SvPolicy|null){selected.value=p;allow.value=p?.rules.allowIssue??true;approval.value=p?.rules.requireApproval??true;ratio.value=p?String(p.rules.maxBonusBps/100):'';paid.value=p?String(p.rules.maxPaidMinor/10**scale):'';bonus.value=p?String(p.rules.maxBonusMinor/10**scale):'';days.value=p?String(p.rules.maxActiveDays):'';outstanding.value=p?String(p.rules.maxOutstandingMinor/10**scale):'';error.value='';editing.value=true;}
function edit(p:SvPolicy){if(!targets.value.some(x=>x.type===p.targetType&&x.no===p.targetNo&&x.canManage))return;target.value=targets.value.find(x=>x.type===p.targetType&&x.no===p.targetNo)||{type:p.targetType,no:p.targetNo,name:t(`svGov.targets.${p.targetType}`),canManage:false};form(p)}
async function load(){loading.value=true;failed.value=false;try{items.value=await api.mSvPolicies({managementNo:management.value,currency});targets.value=await api.mSvPolicyTargets(management.value);
 const saved=uni.getStorageSync(storage.value);if(saved){pending.value=JSON.parse(String(saved)) as Pending;const p=pending.value;target.value=targets.value.find(x=>x.type===p.body.targetType&&x.no===p.body.targetNo)||null;form(items.value.find(x=>x.policyNo===p.no)||null);const r=p.body.rules;allow.value=r.allowIssue;approval.value=r.requireApproval;ratio.value=String(r.maxBonusBps/100);paid.value=String(r.maxPaidMinor/10**scale);bonus.value=String(r.maxBonusMinor/10**scale);days.value=String(r.maxActiveDays);outstanding.value=String(r.maxOutstandingMinor/10**scale);}
 }catch(e){failed.value=true;error.value=(e as Error).message}finally{loading.value=false}}
function rules():SvPolicyRules {const bps=fixedMinor(ratio.value,2);const n=Number(days.value);if(!Number.isSafeInteger(n)||n<=0||bps>10000)throw Error(t('svGov.invalidRules'));const r={allowIssue:allow.value,requireApproval:approval.value,maxBonusBps:bps,maxPaidMinor:fixedMinor(paid.value,scale),maxBonusMinor:fixedMinor(bonus.value,scale),maxActiveDays:n,maxOutstandingMinor:fixedMinor(outstanding.value,scale)};if(r.maxPaidMinor<=0||r.maxOutstandingMinor<=0)throw Error(t('svGov.invalidRules'));return r}
async function save(){if(busy.value||!target.value)return;error.value='';try{if(!pending.value){const p:Pending={no:selected.value?.policyNo||null,revision:selected.value?.revision||0,key:idempotencyKey(),body:{managementNo:management.value,targetType:target.value.type,targetNo:target.value.no,currency,rules:rules()}};uni.setStorageSync(storage.value,JSON.stringify(p));pending.value=p;}busy.value=true;const p=pending.value;await (p.no?api.mSvPolicyEdit(p.no,{managementNo:p.body.managementNo,edit:{rules:p.body.rules,expectedRevision:p.revision}},p.key):api.mSvPolicyCreate(p.body,p.key));uni.removeStorageSync(storage.value);pending.value=null;editing.value=false;await load()}catch(e){error.value=(e as Error).message;if(definiteGovernanceRejection(e)){uni.removeStorageSync(storage.value);pending.value=null;}}finally{busy.value=false}}
onLoad(q=>{management.value=q?.m||'';void load()});
</script>
<template><sh-scaffold title-key="svGov.policies" :pending="loading" :failed="failed" @retry="load">
 <view v-for="p in items" :key="p.policyNo" data-testid="policy-card" class="sh-card sh-hit" @tap="edit(p)"><view class="sh-row sh-row--between"><text class="txt-title">{{targets.find(x=>x.type===p.targetType&&x.no===p.targetNo)?.name||t(`svGov.targets.${p.targetType}`)}}</text><sh-go/></view><sh-kv :label="t('svGov.ratio')" between>{{p.rules.maxBonusBps/100}}%</sh-kv><sh-kv :label="t('svGov.outstanding')" between>{{money(p.rules.maxOutstandingMinor,currency)}}</sh-kv><text class="txt-sub sh-muted">{{t(p.rules.allowIssue?'svGov.allow':'svGov.disabled')}} · {{t(p.rules.requireApproval?'svGov.approvalRequired':'svGov.noApproval')}}</text></view>
 <sh-empty v-if="!loading&&!items.length" :text="t('svGov.noPolicies')"/>
 <sh-actionbar v-if="targets.some(x=>x.canManage)"><view role="button" class="sh-btn" @tap="!locked&&(choosing=true)">{{t('svGov.addPolicy')}}</view></sh-actionbar>
 <sh-sheet :visible="choosing" :title="t('svGov.chooseScope')" @close="choosing=false"><view v-for="x in targets.filter(x=>x.canManage)" :key="x.type+x.no" class="sh-cell sh-row sh-row--between" @tap="target=x;choosing=false;form(items.find(p=>p.targetType===x.type&&p.targetNo===x.no)||null)"><text>{{x.name}}</text><text class="txt-sub sh-muted">{{t(`svGov.targets.${x.type}`)}}</text></view></sh-sheet>
 <sh-sheet :visible="editing" :title="target?.name||t('svGov.policies')" @close="!locked&&(editing=false)"><view class="sh-card">
 <sh-kv :label="t('svGov.allow')" between><sh-check :model-value="allow" :disabled="locked" @update:model-value="!locked&&(allow=$event)"/></sh-kv>
 <sh-kv :label="t('svGov.approvalRequired')" between><sh-check :model-value="approval" :disabled="locked" @update:model-value="!locked&&(approval=$event)"/></sh-kv>
 <view class="field"><text class="field__label">{{t('svGov.ratio')}} (%)</text><input maxlength="6" v-model="ratio" class="field__input" type="digit" :disabled="locked"/></view>
 <view class="field"><text class="field__label">{{t('svGov.paidCap')}} · {{currency}}</text><input maxlength="10" v-model="paid" class="field__input" type="digit" :disabled="locked"/></view>
 <view class="field"><text class="field__label">{{t('svGov.bonusCap')}} · {{currency}}</text><input maxlength="10" v-model="bonus" class="field__input" type="digit" :disabled="locked"/></view>
 <view class="field"><text class="field__label">{{t('svGov.daysCap')}}</text><input maxlength="4" v-model="days" class="field__input" type="number" :disabled="locked"/></view>
 <view class="field"><text class="field__label">{{t('svGov.outstanding')}} · {{currency}}</text><input maxlength="10" v-model="outstanding" class="field__input" type="digit" :disabled="locked"/></view>
 </view><text v-if="pending" class="sh-hint">{{t('svGov.unknown')}}</text><text v-if="error" class="sh-hint">{{error}}</text><view data-testid="policy-save" class="sh-btn" role="button" :class="{'is-disabled':busy}" @tap="save">{{t(pending?'svGov.retry':'svGov.save')}}</view></sh-sheet>
 </sh-scaffold></template>
