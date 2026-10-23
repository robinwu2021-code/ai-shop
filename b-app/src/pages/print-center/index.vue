<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { onLoad, onShow, onHide, onUnload, onPullDownRefresh } from '@dcloudio/uni-app';
import { useI18n } from 'vue-i18n';
import { useMerchantStore } from '@/stores/merchant';
import { USE_MOCK } from '@/api';
import { ROUTES } from '@/shared/nav';
import { confirm, prompt } from '@ai-shop/ui/prompt';
import { callPrint, PrintCommands, SIMULATION_FIXTURES, decodeFrozen, type PrintPage, type PrintJob, type PrintRequest, type Scene, type Simulation, type ExportView, type Incident, type PrintSummary, type PrintOperation } from '@/api/print';
import { printJobActions, simulationOutcomeLabel, type JobAction } from '@/shared/print-center';
const { t } = useI18n();
const merchant = useMerchantStore();
const commands = new PrintCommands();
const orderNo = ref('');
const tab = ref('jobs');
const jobs = ref<PrintJob[]>([]), requests = ref<PrintRequest[]>([]), scenes = ref<Scene[]>([]), simulations = ref<Simulation[]>([]), exports = ref<ExportView[]>([]), incidents = ref<Incident[]>([]);
const summary = ref<PrintSummary|null>(null);
const selectedScene = ref('ORDER_PAID'), fixture = ref<typeof SIMULATION_FIXTURES[number]>('SUCCESS'), width = ref(80), reason = ref('');
const busy = ref(false), loading = ref(false), error = ref(''), latestOperation = ref<PrintOperation|null>(null), page = ref(1), total = ref(0);
let epoch = 0, timer:ReturnType<typeof setInterval>|undefined;
const tabs = computed(() => [
 { key:'jobs',permission:'read' },{ key:'requests',permission:'read' },{ key:'simulations',permission:'simulate' },{ key:'exports',permission:'export' },{ key:'incidents',permission:'resolve' },
].filter(x=>merchant.can('biz:print:'+x.permission)));
const sceneNames = computed(()=>scenes.value.map(s=>s.title));
const selectedTitle = computed(()=>scenes.value.find(s=>s.scene===selectedScene.value)?.title || selectedScene.value);
const canRead = computed(()=>merchant.can('biz:print:read')||merchant.can('biz:print:simulate')||merchant.can('biz:print:export'));
function textError(value:unknown){return value instanceof Error?value.message:String(t('printCenter.failed'));}
async function load(){
 if(USE_MOCK || !merchant.storeNo || !canRead.value)return;
 const current=++epoch, storeNo=merchant.storeNo;loading.value=true;error.value='';
 try{
  const query={storeNo,page:page.value,size:20};
  const result=tab.value==='jobs'?await callPrint<PrintPage<PrintJob>>('J06',{...query,orderNo:orderNo.value||undefined}):tab.value==='requests'?await callPrint<PrintPage<PrintRequest>>('J01',{...query,orderNo:orderNo.value||undefined}):tab.value==='simulations'?await callPrint<PrintPage<Simulation>>('S02',query):tab.value==='exports'?await callPrint<PrintPage<ExportView>>('E02',query):await callPrint<PrintPage<Incident>>('M02',query);
  if(current!==epoch || storeNo!==merchant.storeNo)return;
  total.value=result.total;
  if(tab.value==='jobs')jobs.value=result.records as PrintJob[];else if(tab.value==='requests')requests.value=result.records as PrintRequest[];else if(tab.value==='simulations')simulations.value=result.records as Simulation[];else if(tab.value==='exports')exports.value=result.records as ExportView[];else incidents.value=result.records as Incident[];
  if(orderNo.value && merchant.can('biz:print:read') && merchant.can('biz:order:view')){const value=await callPrint<PrintSummary>('J11',{storeNo},{orderNo:orderNo.value});if(current===epoch&&storeNo===merchant.storeNo)summary.value=value;}
 }catch(e){if(current===epoch)error.value=textError(e);}finally{if(current===epoch)loading.value=false;}
}
async function initialize(){
 if(USE_MOCK)return;
 await merchant.ensureScope();
 if(!tabs.value.some(x=>x.key===tab.value))tab.value=tabs.value[0]?.key||'jobs';
 if(merchant.can('biz:print:read')){try{scenes.value=await callPrint<Scene[]>('C01',{storeNo:merchant.storeNo});}catch(e){error.value=textError(e);}}
 await load();
}
async function run(action:()=>Promise<PrintOperation>){if(busy.value)return;busy.value=true;error.value='';const store=merchant.storeNo;try{const op=await action();if(store===merchant.storeNo){latestOperation.value=op;await load();}}catch(e){error.value=textError(e);}finally{busy.value=false;}}
async function simulate(){
 const fact=summary.value?.sourceFacts.find(f=>f.scene===selectedScene.value);
 await run(()=>commands.run('S01',{storeNo:merchant.storeNo,scene:selectedScene.value,variantCode:'STANDARD_'+width.value,fixtureCode:fixture.value,source:{type:fact?'FACT':'SAMPLE',no:null,factRef:fact||null,expectedRevision:null},templateSelection:null,outputOptions:{pdfEnabled:false,pdfProfile:'ORIGINAL_ROLL'}}));
 tab.value='simulations';page.value=1;await load();
}
async function submitSource(){const fact=summary.value?.sourceFacts.find(f=>f.scene==='ORDER_PAID');if(!fact)return;await run(()=>commands.run('J03',{storeNo:merchant.storeNo,sourceRef:{type:fact.sourceType,no:orderNo.value,factNo:fact.factNo,revision:fact.bizRevision,scene:fact.scene},scene:fact.scene,expectedSourceRevision:fact.bizRevision,printMode:'EXISTING_FACT',outputOptions:{pdfEnabled:true,pdfProfile:'ORIGINAL_ROLL'}}));}
async function jobAction(job:PrintJob,action:JobAction){
 if(!printJobActions(job,merchant.can).includes(action))return;
 let note=reason.value.trim();
 if(action!=='QUERY'&&!note){const input=await prompt({title:String(t('printCenter.reason')),maxlength:60});if(!input?.trim())return;note=input.trim();}
 let risk=false;
 if(action==='REPRINT'&&job.status==='UNKNOWN'){if(!merchant.can('biz:print:resolve')){error.value=String(t('printCenter.resolveRequired'));return;}risk=await confirm({title:String(t('printCenter.duplicateRisk')),hint:String(t('printCenter.duplicateRiskHint')),danger:true});if(!risk)return;}
 if(action==='CANCEL'&&!await confirm({title:String(t('printCenter.action.CANCEL')),hint:String(t('printCenter.cancelHint')),danger:true}))return;
 if(action==='CONFIRM'){
  if(!await confirm({title:String(t('printCenter.action.CONFIRM')),hint:String(t('printCenter.confirmHint')),danger:true}))return;
  const evidence=await prompt({title:String(t('printCenter.evidenceRef')),maxlength:128});if(!evidence?.trim())return;
  await run(()=>commands.run('J19',{expectedRevision:job.revision,outcome:'PRINTED',reason:note,evidenceRef:evidence.trim(),queueTerminatedConfirmed:true},{jobNo:job.jobNo}));return;
 }
 const id=action==='REPRINT'?'J13':action==='EXTRA'?'J15':action==='RETRY'?'J16':action==='CANCEL'?'J17':'J18';
 const body:Record<string,unknown>={expectedRevision:job.revision,reason:note};if(action==='REPRINT')body.riskAcknowledged=risk;if(action==='EXTRA')body.copies=1;
 await run(()=>commands.run(id,body,{jobNo:job.jobNo}));
}
async function exportSource(type:'JOB'|'SIMULATION',no:string,revision:number){await run(()=>commands.run('E01',{storeNo:merchant.storeNo,source:{type,no,expectedRevision:revision},profile:'ORIGINAL_ROLL'}));tab.value='exports';page.value=1;await load();}
async function download(item:ExportView){if(item.status!=='READY')return;error.value='';try{const grant=await callPrint<{url:string}>('E04',undefined,{exportNo:item.exportNo});const base=import.meta.env.VITE_API_BASE||'';const url=grant.url.startsWith('/')?base+grant.url:grant.url;uni.downloadFile({url,success:r=>{if(r.statusCode!==200){error.value=String(t('printCenter.downloadFailed'));return;}uni.openDocument({filePath:r.tempFilePath,fileType:'pdf',showMenu:true,fail:()=>{error.value=String(t('printCenter.downloadFailed'));}});},fail:()=>{error.value=String(t('printCenter.downloadFailed'));}});}catch(e){error.value=textError(e);}}
async function acknowledge(item:Incident){await run(()=>commands.run('M03',{expectedRevision:item.revision,reason:'ACKNOWLEDGED'},{incidentNo:item.incidentNo}));}
function navigate(url:string){uni.navigateTo({url});}
function changeScene(event:{detail:{value:string|number}}){selectedScene.value=scenes.value[Number(event.detail.value)]?.scene||'ORDER_PAID';}
function fixtureChanged(event:{detail:{value:string|number}}){fixture.value=SIMULATION_FIXTURES[Number(event.detail.value)]||'SUCCESS';}
function next(delta:number){page.value+=delta;void load();}
watch(tab,()=>{page.value=1;void load();});
watch(()=>merchant.storeNo,()=>{epoch++;jobs.value=[];requests.value=[];simulations.value=[];exports.value=[];incidents.value=[];summary.value=null;commands.clear();page.value=1;void initialize();});
onLoad(q=>{orderNo.value=String(q?.orderNo||'');});
onShow(()=>{void initialize();if(timer)clearInterval(timer);timer=setInterval(()=>{if(!busy.value&&!loading.value)void load();},15000);});
function stop(){if(timer)clearInterval(timer);timer=undefined;}
onHide(stop);onUnload(()=>{stop();epoch++;});
onPullDownRefresh(async()=>{await load();uni.stopPullDownRefresh();});
</script>
<template>
 <sh-scaffold title-key="printCenter.title" :denied="!canRead">
  <view v-if="USE_MOCK" class="sh-card">{{ t('printCenter.mockHint') }}</view>
  <template v-else>
   <view class="sh-card">
    <view class="sh-row sh-row--between"><text class="txt-strong">{{ t('printCenter.title') }}</text><button size="mini" :disabled="loading" @tap="load">{{ t('printCenter.refresh') }}</button></view>
    <view v-if="orderNo" class="txt-muted">{{ t('printCenter.order') }} {{ orderNo }}</view>
    <view v-if="summary" class="print-summary">
     <text>{{ t('printCenter.status.'+summary.generationState) }}</text>
     <text>{{ t('printCenter.summary',{expected:summary.expectedCount,confirmed:summary.confirmedCount,resolved:summary.replacementResolvedCount,unknown:summary.unknownCount}) }}</text>
     <button v-if="summary.sourceFacts.length&&merchant.can('biz:print:submit')" size="mini" :disabled="busy" @tap="submitSource">{{ t('printCenter.submit') }}</button>
    </view>
    <view class="sh-row print-nav"><button v-if="merchant.can('biz:print:device')" size="mini" @tap="navigate(ROUTES.printDevices)">{{ t('printCenter.devices') }}</button><button v-if="merchant.can('biz:print:config')" size="mini" @tap="navigate(ROUTES.printTemplates)">{{ t('printCenter.templates') }}</button></view>
   </view>
   <view v-if="merchant.can('biz:print:simulate')" class="sh-card">
    <text class="txt-strong">{{ t('printCenter.simulate') }}</text><text class="print-hint">{{ t('printCenter.simulationHint') }}</text>
    <picker :range="sceneNames" @change="changeScene"><view class="sh-cell">{{ selectedTitle }} ▾</view></picker>
    <picker :range="SIMULATION_FIXTURES.map(x=>String(t('printCenter.fixture.'+x)))" @change="fixtureChanged"><view class="sh-cell">{{ t('printCenter.fixture.'+fixture) }} ▾</view></picker>
    <view class="sh-row"><button size="mini" :class="width===58?'print-selected':''" @tap="width=58">58mm</button><button size="mini" :class="width===80?'print-selected':''" @tap="width=80">80mm</button><button size="mini" :disabled="busy" @tap="simulate">{{ t('printCenter.runSimulation') }}</button></view>
   </view>
   <view v-if="error" class="sh-card print-error" role="alert">{{ error }}</view>
   <view v-if="latestOperation" class="sh-card txt-muted">{{ t('printCenter.operation') }} {{ latestOperation.operationNo }} · {{ t('printCenter.status.'+latestOperation.status) }}<text class="print-hint">{{ t('printCenter.operationHint') }}</text></view>
   <view class="sh-row print-tabs"><button v-for="item in tabs" :key="item.key" size="mini" :class="tab===item.key?'print-selected':''" @tap="tab=item.key">{{ t('printCenter.tabs.'+item.key) }}</button></view>
   <template v-if="tab==='jobs'">
    <input v-model="reason" class="sh-card" :placeholder="String(t('printCenter.reason'))" :maxlength="60" />
    <view v-for="job in jobs" :key="job.jobNo" class="sh-card">
     <view class="sh-row sh-row--between"><text class="txt-strong">{{ job.jobNo }}</text><text>{{ t('printCenter.status.'+job.status) }}</text></view>
     <text class="print-hint">{{ job.scene }} · {{ job.jobKind }} · {{ job.printerNo }}</text>
     <text v-if="job.status==='UNKNOWN'" class="print-error print-hint">{{ t('printCenter.unknownHint') }}</text>
     <text v-if="job.resolutionStatus==='RESOLVED'" class="print-hint">{{ t('printCenter.replacedHint') }}</text>
     <view class="sh-row print-actions"><button v-for="action in printJobActions(job,merchant.can)" :key="action" size="mini" :disabled="busy" @tap="jobAction(job,action)">{{ t('printCenter.action.'+action) }}</button><button v-if="merchant.can('biz:print:export')" size="mini" :disabled="busy" @tap="exportSource('JOB',job.jobNo,job.revision)">{{ t('printCenter.exportPdf') }}</button></view>
    </view>
    <view v-if="!jobs.length&&!loading" class="sh-card txt-muted">{{ t('printCenter.empty') }}</view>
   </template>
   <template v-if="tab==='requests'"><view v-for="item in requests" :key="item.requestNo" class="sh-card"><text>{{ item.requestNo }} · {{ t('printCenter.status.'+item.status) }}</text><text v-if="item.blockCode" class="print-hint print-error">{{ t('printCenter.blockedHint') }} {{ item.blockCode }}</text><button v-if="merchant.can('biz:print:resolve')&&['PENDING','BLOCKED'].includes(item.status)" size="mini" :disabled="busy" @tap="run(()=>commands.run('J04',{expectedRevision:item.revision,reason:'CONFIGURATION_FIXED'},{requestNo:item.requestNo}))">{{ t('printCenter.retryGeneration') }}</button></view></template>
   <template v-if="tab==='simulations'"><view v-for="item in simulations" :key="item.simulationNo" class="sh-card"><text class="txt-strong">{{ t(simulationOutcomeLabel(item.status)) }}</text><text class="print-hint">{{ item.simulationNo }} · {{ t('printCenter.fixture.'+item.fixtureCode) }}</text><text class="print-hint">{{ t('printCenter.simulationHint') }}</text><button v-if="merchant.can('biz:print:export')" size="mini" :disabled="busy" @tap="exportSource('SIMULATION',item.simulationNo,item.revision)">{{ t('printCenter.exportPdf') }}</button></view></template>
   <template v-if="tab==='exports'"><view v-for="item in exports" :key="item.exportNo" class="sh-card"><text class="txt-strong">{{ item.exportNo }} · {{ t('printCenter.status.'+item.status) }}</text><text class="print-hint">{{ item.pageCount }} {{ t('printCenter.pages') }} · {{ t('printCenter.pdfHint') }}</text><button v-if="item.status==='READY'" size="mini" @tap="download(item)">{{ t('printCenter.download') }}</button></view></template>
   <template v-if="tab==='incidents'"><view v-for="item in incidents" :key="item.incidentNo" class="sh-card"><text>{{ item.targetNo }} · {{ item.incidentType }} · {{ t('printCenter.status.'+item.status) }}</text><button v-if="item.status==='OPEN'" size="mini" :disabled="busy" @tap="acknowledge(item)">{{ t('printCenter.acknowledge') }}</button></view></template>
   <view class="sh-row print-pager"><button size="mini" :disabled="page<=1||loading" @tap="next(-1)">{{ t('printCenter.previous') }}</button><text>{{ page }} / {{ Math.max(1,Math.ceil(total/20)) }}</text><button size="mini" :disabled="page*20>=total||loading" @tap="next(1)">{{ t('printCenter.next') }}</button></view>
  </template>
 </sh-scaffold>
</template>
<style scoped>
.print-hint{display:block;color:var(--sh-text-secondary,#666);font-size:24rpx;margin-top:12rpx;overflow-wrap:anywhere}.print-error{color:var(--sh-danger,#c0392b)}.print-summary{display:flex;flex-direction:column;gap:12rpx;margin-top:16rpx}.print-nav,.print-tabs,.print-actions{flex-wrap:wrap;gap:12rpx;margin-top:16rpx}.print-tabs{padding:12rpx}.print-pager{justify-content:space-between;margin:24rpx}.print-actions button,.print-nav button{margin:0}
</style>

<style scoped>.print-selected{background:#1677ff;color:white;}</style>
