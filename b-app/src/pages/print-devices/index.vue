<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import { useI18n } from 'vue-i18n';
import { useMerchantStore } from '@/stores/merchant';
import { USE_MOCK } from '@/api';
import { confirm, prompt } from '@ai-shop/ui/prompt';
import { callPrint, PrintCommands, operationResult, type PrintPage, type Printer, type PrinterDetail, type Agent, type AgentDetail, type DiscoveredPrinter } from '@/api/print';
const { t }=useI18n(), merchant=useMerchantStore(), commands=new PrintCommands();
const printers=ref<Printer[]>([]), agents=ref<Agent[]>([]), discovered=ref<DiscoveredPrinter[]>([]);
const error=ref(''), busy=ref(false), loading=ref(false), pairing=ref('');
const name=ref(''), connectionType=ref('CLOUD'), width=ref(80), vendorUser=ref(''), vendorKey=ref(''), serial=ref(''), agentNo=ref(''), physical=ref(''), queue=ref('');
const agentName=ref('');
let epoch=0;
const discoveredNames=computed(()=>discovered.value.map(d=>d.queueName));
function fail(e:unknown){error.value=e instanceof Error?e.message:String(t('printCenter.failed'));}
async function load(){if(USE_MOCK)return;loading.value=true;await merchant.ensureScope();const version=++epoch,storeNo=merchant.storeNo;try{const [p,a]=await Promise.all([callPrint<PrintPage<Printer>>('P02',{storeNo,page:1,size:100}),callPrint<PrintPage<Agent>>('A01',{storeNo,page:1,size:100})]);if(version!==epoch||storeNo!==merchant.storeNo)return;printers.value=p.records;agents.value=a.records;}catch(e){fail(e);}finally{if(version===epoch)loading.value=false;}}
async function run(action:()=>Promise<unknown>){if(busy.value)return;busy.value=true;error.value='';try{await action();await load();}catch(e){fail(e);}finally{busy.value=false;}}
async function chooseAgent(event:{detail:{value:string|number}}){agentNo.value=agents.value[Number(event.detail.value)]?.agentNo||'';physical.value='';queue.value='';try{const detail=await callPrint<AgentDetail>('A03',undefined,{agentNo:agentNo.value});discovered.value=detail.discoveredPrinters;}catch(e){fail(e);}}
function chooseQueue(event:{detail:{value:string|number}}){const value=discovered.value[Number(event.detail.value)];physical.value=value?.physicalRegistrationRef||'';queue.value=value?.queueName||'';}
async function create(){await run(async()=>{const connection=connectionType.value==='CLOUD'?{type:'CLOUD',adapterCode:'FEIE',vendorAccountRef:vendorUser.value.trim(),deviceSn:serial.value.trim(),credentials:{user:vendorUser.value.trim(),ukey:vendorKey.value}}:{type:'LOCAL',adapterCode:'LOCAL_SYSTEM_QUEUE',agentNo:agentNo.value,physicalRegistrationRef:physical.value,config:{queueName:queue.value}};await commands.run('P03',{storeNo:merchant.storeNo,name:name.value.trim(),paperWidthMm:width.value,connection});name.value='';vendorKey.value='';});}
async function setStatus(item:Printer){const status=item.status==='ACTIVE'?'PAUSED':'ACTIVE';await run(()=>commands.run('P07',{expectedRevision:item.revision,status,reason:'MERCHANT_REQUEST'},{printerNo:item.printerNo}));}
async function test(item:Printer){if(!await confirm({title:String(t('printCenter.testPrint')),hint:String(t('printCenter.testHint'))}))return;await run(()=>commands.run('P10',{expectedRevision:item.revision,testPattern:'STANDARD'},{printerNo:item.printerNo}));}
async function probe(item:Printer){await run(()=>commands.run('P09',{expectedRevision:item.revision},{printerNo:item.printerNo}));}
async function rename(item:Printer){const value=await prompt({title:String(t('printCenter.name')),value:item.name,maxlength:64});if(!value?.trim())return;await run(()=>commands.run('P05',{expectedRevision:item.revision,name:value.trim(),paperWidthMm:item.paperWidthMm},{printerNo:item.printerNo}));}
async function rotate(item:Printer){const user=await prompt({title:String(t('printCenter.vendorUser'))});if(!user?.trim())return;const secret=await prompt({title:String(t('printCenter.vendorKey')),password:true});if(!secret)return;await run(()=>commands.run('P08',{expectedRevision:item.revision,credentials:{user:user.trim(),ukey:secret}},{printerNo:item.printerNo}));}
async function clearGate(item:Printer){const detail=await callPrint<PrinterDetail>('P04',undefined,{printerNo:item.printerNo});if(!detail.activeJobNo)return;const job=await callPrint<{currentAttemptNo:string}>('J07',undefined,{jobNo:detail.activeJobNo});if(!await confirm({title:String(t('printCenter.clearGate')),hint:String(t('printCenter.clearGateHint')),danger:true}))return;const evidence=await prompt({title:String(t('printCenter.evidenceRef')),maxlength:128});if(!evidence?.trim())return;await run(()=>commands.run('J21',{expectedRevision:detail.runtimeRevision,activeAttemptNo:job.currentAttemptNo,action:'CONFIRM_QUEUE_TERMINATED',reason:'PHYSICAL_QUEUE_VERIFIED',evidenceRef:evidence.trim(),oldExecutorStopped:true},{printerNo:item.printerNo}));}
async function createAgent(){await run(()=>commands.run('A02',{storeNo:merchant.storeNo,name:agentName.value.trim(),platform:'WINDOWS'}));}
async function enroll(item:Agent){const key=await prompt({title:String(t('printCenter.publicKey')),maxlength:4096});if(!key?.trim())return;await run(async()=>{const result=await commands.run('A04',{expectedRevision:item.revision,clientPublicKey:key.trim()},{agentNo:item.agentNo});pairing.value=operationResult<{sealedEnrollment:string}>(result).sealedEnrollment;uni.setClipboardData({data:pairing.value});});}
async function revoke(item:Agent){if(!await confirm({title:String(t('printCenter.revokeAgent')),hint:String(t('printCenter.revokeHint')),danger:true}))return;await run(()=>commands.run('A05',{expectedRevision:item.revision,reason:'MERCHANT_REVOKED'},{agentNo:item.agentNo}));}
watch(()=>merchant.storeNo,()=>{epoch++;printers.value=[];agents.value=[];discovered.value=[];pairing.value='';vendorKey.value='';commands.clear();void load();});
onShow(()=>{void load();});onPullDownRefresh(async()=>{await load();uni.stopPullDownRefresh();});
</script>
<template>
 <sh-scaffold title-key="printCenter.devices" :denied="!merchant.can('biz:print:device')">
  <view v-if="USE_MOCK" class="sh-card">{{ t('printCenter.mockHint') }}</view>
  <template v-else>
   <view v-if="error" class="sh-card print-error" role="alert">{{ error }}</view>
   <view class="sh-card"><text class="txt-strong">{{ t('printCenter.addPrinter') }}</text>
    <input v-model="name" :placeholder="String(t('printCenter.name'))" :maxlength="64" />
    <view class="sh-row"><button size="mini" :class="connectionType==='CLOUD'?'print-selected':''" @tap="connectionType='CLOUD'">{{ t('printCenter.cloud') }}</button><button size="mini" :class="connectionType==='LOCAL'?'print-selected':''" @tap="connectionType='LOCAL'">{{ t('printCenter.local') }}</button></view>
    <view class="sh-row"><button size="mini" :class="width===58?'print-selected':''" @tap="width=58">58mm</button><button size="mini" :class="width===80?'print-selected':''" @tap="width=80">80mm</button></view>
    <template v-if="connectionType==='CLOUD'"><input v-model="vendorUser" :placeholder="String(t('printCenter.vendorUser'))" /><input v-model="vendorKey" password :placeholder="String(t('printCenter.vendorKey'))" /><input v-model="serial" :placeholder="String(t('printCenter.serial'))" /></template>
    <template v-else><picker :range="agents.map(a=>a.name+' · '+a.agentNo)" @change="chooseAgent"><view class="sh-cell">{{ agentNo||t('printCenter.selectAgent') }} ▾</view></picker><picker :range="discoveredNames" @change="chooseQueue"><view class="sh-cell">{{ queue||t('printCenter.selectQueue') }} ▾</view></picker><text class="print-hint">{{ t('printCenter.discoveryHint') }}</text></template>
    <button :disabled="busy||!name.trim()||(connectionType==='LOCAL'&&!physical)" @tap="create">{{ t('printCenter.addPrinter') }}</button><text class="print-hint">{{ t('printCenter.newPrinterPaused') }}</text>
   </view>
   <view v-for="item in printers" :key="item.printerNo" class="sh-card"><view class="sh-row sh-row--between"><text class="txt-strong">{{ item.name }}</text><text>{{ t('printCenter.status.'+item.status) }}</text></view><text class="print-hint">{{ item.connectionType }} · {{ item.paperWidthMm }}mm · {{ t('printCenter.status.'+item.healthStatus) }}</text><view class="sh-row print-actions"><button size="mini" :disabled="busy" @tap="setStatus(item)">{{ t(item.status==='ACTIVE'?'printCenter.pause':'printCenter.enable') }}</button><button size="mini" :disabled="busy" @tap="rename(item)">{{ t('printCenter.rename') }}</button><button size="mini" :disabled="busy" @tap="probe(item)">{{ t('printCenter.probe') }}</button><button size="mini" :disabled="busy||item.status!=='ACTIVE'" @tap="test(item)">{{ t('printCenter.testPrint') }}</button><button v-if="item.connectionType==='CLOUD'&&merchant.can('biz:print:resolve')" size="mini" :disabled="busy" @tap="rotate(item)">{{ t('printCenter.rotateCredential') }}</button><button v-if="merchant.can('biz:print:resolve')" size="mini" :disabled="busy" @tap="clearGate(item)">{{ t('printCenter.clearGate') }}</button></view></view>
   <view class="sh-card"><text class="txt-strong">{{ t('printCenter.agents') }}</text><input v-model="agentName" :placeholder="String(t('printCenter.name'))" :maxlength="64" /><button :disabled="busy||!agentName.trim()" @tap="createAgent">{{ t('printCenter.addAgent') }}</button></view>
   <view v-for="item in agents" :key="item.agentNo" class="sh-card"><text>{{ item.name }} · {{ item.agentNo }} · {{ t('printCenter.status.'+item.status) }}</text><view class="sh-row print-actions"><button v-if="item.status!=='REVOKED'" size="mini" :disabled="busy" @tap="enroll(item)">{{ t('printCenter.pairAgent') }}</button><button v-if="item.status!=='REVOKED'&&merchant.can('biz:print:resolve')" size="mini" :disabled="busy" @tap="revoke(item)">{{ t('printCenter.revokeAgent') }}</button></view></view>
   <view v-if="pairing" class="sh-card"><text>{{ t('printCenter.sealedPairingCopied') }}</text></view>
  </template>
 </sh-scaffold>
</template>
<style scoped>
input{padding:20rpx 0;border-bottom:1px solid var(--sh-border,#eee)}.print-hint{display:block;font-size:24rpx;color:var(--sh-text-secondary,#666);margin-top:12rpx;overflow-wrap:anywhere}.print-actions{flex-wrap:wrap;gap:12rpx;margin-top:16rpx}.print-actions button{margin:0}.print-error{color:var(--sh-danger,#c0392b)}
</style>

<style scoped>.print-selected{background:#1677ff;color:white;}</style>
