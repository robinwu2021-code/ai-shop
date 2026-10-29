import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {randomBytes, randomUUID, createHash} from 'node:crypto';
import {readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, readdirSync, realpathSync, rmdirSync} from 'node:fs';
import {join, relative, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const suite = 'ManualServiceAdmissionIT,ManualStockDispositionIT,ManualServiceAdmissionProtocolTest,ManualServiceFinancialIsolationIT,RemoteManualServiceAdmissionPortTest,BizRoleSeedTest,BoundInternalProtocolTest,BoundDelegatedCallsTest,CheckoutCashPermissionFlowTest#ownerWildcardResolvesActualAccountButDelegationIsNotCurrentCashAuthorization';
const r1FinancialMethods = [
  'importedCashRecoveryRejectsManualBeforePayOrResourceEffects',
  'importedStockObligationRejectsManualBeforeOriginalReleaseOrRestore',
  'fundedRefundRecoveryStillDispatchesAndReleasesOriginalOutsideCoreLocks'
];
const r1RemoteMethod = 'wrongDigestOrMalformedResultIsUnavailable';
const r2FinancialMethod = 'fullPointsRefundTerminalStillRecoversPreparedOriginalStock';
export const migrationPaths = [
  'backend/shop-app/src/main/resources/db/migration/V601__manual_service_admission.sql',
  'backend/shop-inventory/src/main/resources/db/inventory/V16__original_stock_disposition.sql'
];
/** No arbitrary suite, class wildcard or fallback may provision native resources. */
export function phaseSelection(phase) {
  assert(['red', 'green', 'ablation', 'restored', 'r1-red', 'r1-green', 'r2-red', 'r2-green'].includes(phase), 'invalid Task64 phase');
  if (phase.startsWith('r2-')) {
    return 'ManualServiceFinancialIsolationIT#' + r2FinancialMethod + (phase === 'r2-green'
      ? '+importedStockObligationRejectsManualBeforeOriginalReleaseOrRestore' : '');
  }
  if (phase.startsWith('r1-')) {
    const methods = phase === 'r1-green' ? [...r1FinancialMethods, 'manualOrderCannotReachPayOrRefund'] : r1FinancialMethods;
    return 'ManualServiceFinancialIsolationIT#' + methods.join('+') + ',RemoteManualServiceAdmissionPortTest#' + r1RemoteMethod;
  }
  return ['ablation','restored'].includes(phase)
    ? 'ManualServiceFinancialIsolationIT#manualPaidIsExcludedBeforeSettlementPageLimit' : suite;
}
/** Fresh exact method evidence cannot be replaced by a skipped or similarly named testcase. */
export function inspectReport(xml, requested = [], allowFailure = false) {
  const tag = xml.match(/<testsuite\b[^>]*>/)?.[0]; assert(tag, 'missing Task64 XML suite');
  const counts = Object.fromEntries([...tag.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)]
    .map(([, k, v]) => [k, Number(v)]));
  assert(counts.tests > 0 && counts.skipped === 0, 'Task64 XML needs nonzero unskipped tests');
  assert.equal(counts.errors,0,'Task64 setup/test errors are not behavioral RED');
  if (!allowFailure) assert(counts.failures === 0, 'Task64 failed XML');
  const methods = [...xml.matchAll(/<testcase\b[^>]*\bname="([^"]+)"/g)].map(m => m[1]);
  assert(requested.every(m => methods.includes(m)), 'missing exact Task64 XML method');
  return counts;
}
/** A later testcase failure must never be attributed to the selected successful testcase. */
export function requireAssertionFailure(xml, method) {
  const cases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
  const selected = cases.filter(m => m[1].match(/\bname="([^"]+)"/)?.[1] === method);
  assert.equal(selected.length, 1, 'missing exact Task64 failure testcase');
  assert(/<failure\b/.test(selected[0][2] ?? ''), 'Task64 selected testcase needs its own assertion failure');
}
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const pause = ms => new Promise(r => setTimeout(r, ms));
const admission = join(root, 'backend/shop-core/src/main/java/ai/neargo/shop/trade/port/SettleSourcePortImpl.java');
const strictGate = '.eq(OrdOrder::getAdmissionKind, "LEGACY_FUNDED")';
export const reports = [
  ['backend/shop-base-auth','ai.neargo.shop.auth.BoundDelegatedCallsTest',[
    'tokenPrecedesDelegationAndEveryExitRestoresPreviousContext','blankTokenConstructionIsSafeAndInvocationRejectsBeforeDigest',
    'verifiedPurposeAndPayloadCannotBeReusedAndNestedFailureRestoresOriginal']],
  ['backend/shop-app','ai.neargo.shop.scenario.CheckoutCashPermissionFlowTest',[
    'ownerWildcardResolvesActualAccountButDelegationIsNotCurrentCashAuthorization']],
  ['backend/shop-base','ai.neargo.shop.svc.BoundInternalProtocolTest',['boundEnumsUseExactNamesAndRejectUnknownOrNumericValues']],
  ['backend/shop-app','ai.neargo.shop.scenario.ManualServiceAdmissionIT',[
    'currentBuyerPurchaseAndCashierReceiptCreateOneManualSource','manualQuoteAndPlacementNeedNoPayConfiguration',
    'cashierCannotConfirmOrPlaceForBuyer','staleOrChangedQuoteCannotBePurchased','cashPermissionAndExactAmountAreRequired',
    'lostReplyReplaysOriginalDeclaration','stockUnknownKeepsReceiptAndBlocksAdmission',
    'unchangedPendingOriginalDoesNotStarveNextBoundedRecoverySlice',
    'cancelAndManualReceiptHaveOneWinner','manualSourceDoesNotRewriteFinancialObservation']],
  ['backend/shop-app','ai.neargo.shop.scenario.ManualStockDispositionIT',[
    'inventoryV2EnqueueStaysPendingUntilActualAllocation','inventoryV2FailedOrExpiredAllocationNeverAdmits',
    'platformNormalReturnWithMissingOrReleasedLocksNeverAdmits','alreadyConfirmedOriginalReplaysWithoutSecondDeduction',
    'manifestMismatchOrPartialCoverageFailsClosed','frozenAuthoritySurvivesConfigurationChange']],
  ['backend/shop-app','ai.neargo.shop.scenario.ManualServiceAdmissionProtocolTest',[
    'currentScopeAndOriginalRequestAreBound','defaultDisabledOpsHaveNoPermissiveProvider']],
  ['backend/shop-app','ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT',[
    'manualOrderCannotReachPayOrRefund','manualPaidIsExcludedBeforeSettlementPageLimit',
    'manualSourceIsNeverSettlementReady','unexpectedPaySuccessCannotRepairManualOrder']],
  ['backend/shop-app','ai.neargo.shop.arch.BizRoleSeedTest',[]],
  ['backend-plugins/shop-industry-beauty/beauty-channel','ai.neargo.shop.beauty.port.RemoteManualServiceAdmissionPortTest',[
    'boundManualFactsRoundTrip','wrongDigestOrMalformedResultIsUnavailable','lostReplyUsesOriginalQuery']]
];
/** Maven module paths are relative but must canonically target the sole current worktree. */
export function aggregatePom(directory) {
  const targets = ['backend', 'backend-plugins/shop-industry-beauty'].map(p => realpathSync(join(root, p)));
  const modules = targets.map(target => {
    const module = relative(realpathSync(directory), target);
    assert.equal(realpathSync(resolve(directory, module)), target, 'wrong Task64 reactor source');
    return '<module>' + module + '</module>';
  });
  return '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    + '<groupId>local.task64</groupId><artifactId>manual-service-admission-reactor</artifactId><version>1</version>'
    + '<packaging>pom</packaging><modules>' + modules.join('') + '</modules></project>';
}
function provenance() {
  const sources = [
    'backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BoundActionContext.java',
    'backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BoundDelegatedCalls.java',
    'backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StockDispositionPort.java',
    'backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StockDispositionContract.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/product/port/StockPortImpl.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/product/service/impl/StockDispositionServiceImpl.java',
    'backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/ReservationServiceImpl.java',
    'backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/service/impl/ReservationDispositionServiceImpl.java',
    'backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryMirrorConsumer.java',
    'backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/InventoryStockPort.java',
    'backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/DualWriteStockPort.java',
    'backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/OriginalStockDispositionPort.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/ManualServiceAdmissionServiceImpl.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/port/SettleSourcePortImpl.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/BasicRefundExecutionServiceImpl.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/RefundExecutionStore.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/PointsRefundResourceServiceImpl.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/RefundResourceServiceImpl.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/RefundExecutionMapper.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/mapper/RefundObligationMapper.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/AfterSaleServiceImpl.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/transition/TradeStatusWriter.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/machine/order/OrderMachine.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/machine/aftersale/AfterSaleMachine.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/policy/RefundClosurePolicy.java',
    'backend/shop-core/src/main/java/ai/neargo/shop/trade/order/refund/service/impl/RefundObligationServiceImpl.java',
    'backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemoteManualServiceAdmissionPort.java',
    ...reports.map(([module, cls]) => module+'/src/test/java/'+cls.replaceAll('.', '/')+'.java')
  ];
  const entries = [];
  for (const source of sources) {
    const production = source.includes('/src/main/java/');
    const [module, java] = source.split(production ? '/src/main/java/' : '/src/test/java/');
    const name = java.slice(0,-5), directory = join(root,module,production ? 'target/classes' : 'target/test-classes',name.slice(0,name.lastIndexOf('/')));
    const stem = name.slice(name.lastIndexOf('/')+1);
    for (const path of [join(root,source), ...readdirSync(directory).filter(file => file===stem+'.class'
      || file.startsWith(stem+'$')&&file.endsWith('.class')).map(file => join(directory,file))]) {
      entries.push({path:realpathSync(path),sha256:hash(readFileSync(path)),bytes:readFileSync(path).length});
    }
  }
  return entries;
}
async function run(phase) {
  const selected = phaseSelection(phase), uuid = randomUUID();
  const selectedReports = phase.startsWith('r2-')
    ? reports.filter(([,cls]) => cls.endsWith('.ManualServiceFinancialIsolationIT'))
      .map(([module,cls]) => [module,cls,phase === 'r2-green'
        ? [r2FinancialMethod,'importedStockObligationRejectsManualBeforeOriginalReleaseOrRestore'] : [r2FinancialMethod]])
    : phase.startsWith('r1-')
    ? reports.filter(([,cls]) => cls.endsWith('.ManualServiceFinancialIsolationIT') || cls.endsWith('.RemoteManualServiceAdmissionPortTest'))
      .map(([module,cls]) => [module,cls,cls.endsWith('.ManualServiceFinancialIsolationIT')
        ? (phase === 'r1-green' ? [...r1FinancialMethods, 'manualOrderCannotReachPayOrRefund'] : r1FinancialMethods) : [r1RemoteMethod]])
    : ['ablation','restored'].includes(phase)
    ? reports.filter(([,cls])=>cls.endsWith('.ManualServiceFinancialIsolationIT'))
      .map(([module,cls])=>[module,cls,['manualPaidIsExcludedBeforeSettlementPageLimit']]) : reports;
  const temporary = '/tmp/codex-manual-service-admission-' + uuid;
  const evidence = join(root, '.superpowers/sdd/beauty-backend/task-64-evidence', phase + '-' + uuid);
  mkdirSync(join(temporary, 'reactor'), {recursive: true}); mkdirSync(evidence, {recursive: true});
  const password = randomBytes(24).toString('base64url');
  const environment = {...process.env, JAVA_HOME: '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
    MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password, MANUAL_SERVICE_TEST_PASSWORD: password};
  const sanitize = value => String(value).replaceAll(password, '[REDACTED]');
  const name = 'codex-manual-service-admission-' + uuid;
  const docker = args => {
    try { return execFileSync('docker', ['--context', 'colima-hxkey-tests', ...args], {
      encoding:'utf8', env:environment, timeout:45000, stdio:['ignore','pipe','pipe']}).trim(); }
    catch(e) { throw new Error('Task64 own resource failed: ' + sanitize(e.stderr ?? e.message)); }
  };
  const lock = '/tmp/codex-beauty-bm04-native.lock';
  let owned = false, original, originalProvenance, changed = false, locked = false;
  let interrupted=false, activeMaven;
  const onSignal=()=>{interrupted=true;activeMaven?.kill('SIGTERM');};
  process.on('SIGINT',onSignal);process.on('SIGTERM',onSignal);
  const commandLog = [];
  const maven = async (args, label) => {
    const command = ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', join(temporary, 'reactor/pom.xml'),
      '-pl', ':shop-app,:shop-inventory,:beauty-channel', '-am', ...args];
    const child = spawn('mvn', command, {cwd:root, env:environment, stdio:['ignore','pipe','pipe']});
    activeMaven=child;
    const chunks = []; for (const stream of [child.stdout, child.stderr]) stream.on('data', b => chunks.push(b));
    let hard; const timer = setTimeout(() => {child.kill('SIGTERM'); hard = setTimeout(() => child.kill('SIGKILL'),5000);},600000);
    let status;
    try { status = await new Promise((resolve,reject) => {child.once('error',reject); child.once('exit',resolve);}); }
    finally {activeMaven=undefined;clearTimeout(timer);clearTimeout(hard);writeFileSync(join(evidence,label+'.log'),sanitize(Buffer.concat(chunks)));}
    commandLog.push({command:'mvn',args:command,status});
    writeFileSync(join(evidence,'commands.json'),JSON.stringify(commandLog,null,2));
    return status;
  };
  try {
    mkdirSync(lock); locked = true;
    const gate=JSON.parse(readFileSync(join(root,'.superpowers/sdd/beauty-backend/task-64-native-root-gate.json'),'utf8'));
    assert.equal(gate.approved,true,'Task64 requires explicit root native GO');
    assert((gate.allowedPhases??[gate.phase]).includes(phase),'Task64 requires matching root-approved phase');
    for (const path of migrationPaths) assert(gate.files.some(pin=>pin.path===path),'missing exact migration gate');
    for (const pin of gate.files) {
      const bytes=readFileSync(join(root,pin.path));
      assert.equal(bytes.length,pin.bytes,'Task64 root-gated byte mismatch '+pin.path);
      assert.equal(hash(bytes),pin.sha256,'Task64 root-gated hash mismatch '+pin.path);
    }
    writeFileSync(join(evidence,'root-gate.json'),JSON.stringify(gate,null,2));
    const memory = execFileSync('limactl',['shell','--instance','colima-hxkey-tests','cat','/proc/meminfo'],
      {encoding:'utf8',env:{...environment,LIMA_HOME:'/Users/robin/.colima/_lima'},timeout:15000});
    const available = Number(memory.match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]);
    assert(available >= 524288,'Task64 requires MemAvailable >=512MiB');
    const disk = execFileSync('limactl',['shell','--instance','colima-hxkey-tests','df','-Pk','/'],
      {encoding:'utf8',env:{...environment,LIMA_HOME:'/Users/robin/.colima/_lima'},timeout:15000});
    const diskAvailable = Number(disk.trim().split('\n').at(-1).trim().split(/\s+/)[3]);
    assert(diskAvailable >= 1048576,'Task64 requires disk available >=1GiB');
    docker(['image','inspect','mysql:9.7.2']);
    writeFileSync(join(evidence,'resource.json'),JSON.stringify({uuid,name,availableKiB:available,diskAvailableKiB:diskAvailable,
      mysql:'mysql:9.7.2',loopbackOnly:true},null,2));
    const pom = aggregatePom(join(temporary, 'reactor'));
    writeFileSync(join(temporary,'reactor/pom.xml'),pom);writeFileSync(join(evidence,'aggregate-pom.xml'),pom);
    if (phase === 'ablation') {
      original = readFileSync(admission);const source = original.toString();
      originalProvenance = provenance();
      writeFileSync(join(evidence,'admission-before.java'),original);
      writeFileSync(join(evidence,'before-ablation-provenance.json'),JSON.stringify(originalProvenance,null,2));
      assert.equal(source.split(strictGate).length,2,'exact single before-limit manual settlement exclusion required');
      writeFileSync(join(evidence,'admission-before.sha256'),hash(original));
      writeFileSync(admission,source.replace(strictGate, ''));changed = true;
      writeFileSync(join(evidence,'admission-ablated.sha256'),hash(readFileSync(admission)));
      writeFileSync(join(evidence,'admission-ablated.java'),readFileSync(admission));
    }
    for (const [module,cls] of selectedReports) rmSync(join(root,module,'target/surefire-reports/TEST-'+cls+'.xml'),{force:true});
    owned = true;
    docker(['run','--rm','--pull','never','--detach','--name',name,'--label','codex.task64.uuid='+uuid,
      '--publish','127.0.0.1::3306','--memory','768m','--pids-limit','256','--cpus','1',
      '--env','MYSQL_ROOT_PASSWORD','--env','MYSQL_ROOT_HOST=%','mysql:9.7.2',
      '--datadir=/var/lib/manual-service-admission-test','--innodb-buffer-pool-size=64M','--performance-schema=OFF']);
    let ready=false;
    for(let i=0;i<150;i++) {
      try {docker(['exec','--env','MYSQL_PWD',name,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','-e','SELECT 1']);ready=true;break;}
      catch {await pause(300);}
      assert(!interrupted,'Task64 interrupted during owned readiness');
    }
    assert(ready,'Task64 owned MySQL readiness bounded out');
    const address=docker(['port',name,'3306/tcp']);assert.match(address,/^127\.0\.0\.1:[0-9]+$/);
    const platform='manual_'+uuid.replaceAll('-',''), inventory='manual_inv_'+uuid.replaceAll('-','');
    const mysql=(input,schema)=>{
      try {return execFileSync('docker',['--context','colima-hxkey-tests','exec','-i','--env','MYSQL_PWD',name,
        'mysql','--protocol=TCP','-h127.0.0.1','-uroot',...(schema?[schema]:[])],
        {encoding:'utf8',input,env:environment,timeout:45000,stdio:['pipe','pipe','pipe']});}
      catch(error){throw new Error('Task64 schema setup failed: '+sanitize(error.stderr??error.message));}
    };
    mysql('CREATE DATABASE '+platform+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
      +'CREATE DATABASE '+inventory+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;');
    const fixtureRoot='.superpowers/sdd/beauty-backend/task-64-evidence/pre-native/';
    for(const [schema,file] of [[platform,'platform-v538.sql'],[inventory,'inventory-v13.sql']]) {
      const path=fixtureRoot+file;
      assert(gate.files.some(pin=>pin.path===path),'missing reviewed structural fixture');
      mysql(readFileSync(join(root,path),'utf8'),schema);
    }
    for(const migration of ['V599__checkout_source_foundation.sql','V600__checkout_payment_operation.sql',
      'V601__manual_service_admission.sql']) mysql(readFileSync(join(root,
        'backend/shop-app/src/main/resources/db/migration',migration),'utf8'),platform);
    mysql(readFileSync(join(root,migrationPaths[1]),'utf8'),inventory);
    const jdbc=schema=>'jdbc:mysql://'+address+'/'+schema+'?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
    Object.assign(environment,{
      CHECKOUT_NATIVE_MYSQL_URL:jdbc(platform),CHECKOUT_NATIVE_MYSQL_USER:'root',CHECKOUT_NATIVE_MYSQL_PASSWORD:password,
      MANUAL_INVENTORY_MYSQL_URL:jdbc(inventory),SPRING_DATASOURCE_URL:jdbc(platform),SPRING_DATASOURCE_USERNAME:'root',
      SPRING_DATASOURCE_PASSWORD:password,SPRING_DATASOURCE_DRIVER_CLASS_NAME:'com.mysql.cj.jdbc.Driver',
      SPRING_SQL_INIT_MODE:'never'});
    assert(!interrupted,'Task64 interrupted before test execution');
    const adminUrl=jdbc('mysql');
    const status=await maven(['--fail-at-end','test','-Dtest='+selected,'-Dsurefire.failIfNoSpecifiedTests=false','-Dcheckout.test.native=true',
      '-Dmanual.service.test.admin-url='+adminUrl, '-Dmanual.service.test.source-root='+realpathSync(root)],phase);
    assert(!interrupted,'Task64 test execution interrupted');
    writeFileSync(join(evidence,'phase-provenance.json'),JSON.stringify(provenance(),null,2));
    writeFileSync(join(evidence,'admission-phase.java'),readFileSync(admission));
    // Preserve every fresh report before classifying any setup or behavioral failure.
    for(const [module,cls] of selectedReports) {
      const path=join(root,module,'target/surefire-reports/TEST-'+cls+'.xml');
      if(existsSync(path)) {
        const xml=sanitize(readFileSync(path,'utf8'));
        writeFileSync(path,xml);writeFileSync(join(evidence,cls+'.xml'),xml);
      }
    }
    const summary=[];
    for(const [module,cls,methods] of selectedReports) {
      const path=join(root,module,'target/surefire-reports/TEST-'+cls+'.xml');assert(existsSync(path),'missing fresh report '+cls);
      const xml=sanitize(readFileSync(path,'utf8'));writeFileSync(path,xml);writeFileSync(join(evidence,cls+'.xml'),xml);
      summary.push({class:cls,...inspectReport(xml,methods,phase==='red'||phase==='r1-red'||phase==='r2-red'||phase==='ablation'),sha256:hash(xml)});
      if (phase.startsWith('r1-') || phase.startsWith('r2-')) assert.equal(summary.at(-1).tests, methods.length, 'exact R1 finite method count required');
    }
    const origins=[];
    for(const [module] of selectedReports) {
      const directory=join(root,module,'target/surefire-reports');
      for(const file of readdirSync(directory).filter(n => selectedReports.some(([,cls]) => n===cls+'.txt'))) {
        origins.push({module,file,sha256:hash(readFileSync(join(directory,file)))});
      }
    }
    writeFileSync(join(evidence,'phase-source-class-manifest.json'),JSON.stringify(provenance(),null,2));
    writeFileSync(join(evidence,'summary.json'),JSON.stringify({phase,status,summary,origins},null,2));
    if(phase==='red'||phase==='r1-red'||phase==='r2-red'||phase==='ablation') {
      assert(status!==0 && summary.some(c=>c.failures>0),'Task64 RED needs real assertion failure');
      if(phase==='r2-red') {
        requireAssertionFailure(readFileSync(join(evidence,'ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT.xml'),'utf8'),r2FinancialMethod);
      }
      if(phase==='r1-red') {
        const financial = readFileSync(join(evidence,'ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT.xml'),'utf8');
        for (const method of r1FinancialMethods.slice(0,2)) requireAssertionFailure(financial, method);
        requireAssertionFailure(readFileSync(join(evidence,'ai.neargo.shop.beauty.port.RemoteManualServiceAdmissionPortTest.xml'),'utf8'),r1RemoteMethod);
      }
      if(phase==='red') {
        const xml=readFileSync(join(evidence,'ai.neargo.shop.scenario.ManualServiceAdmissionIT.xml'),'utf8');
        requireAssertionFailure(xml,'currentBuyerPurchaseAndCashierReceiptCreateOneManualSource');
      }
      if(phase==='ablation') {
        const xml=readFileSync(join(evidence,'ai.neargo.shop.scenario.ManualServiceFinancialIsolationIT.xml'),'utf8');
        requireAssertionFailure(xml, 'manualPaidIsExcludedBeforeSettlementPageLimit');
      }
    } else assert.equal(status,0,'Task64 native suite failed');
    console.log('Task64 '+phase+' evidence: '+evidence+' '+JSON.stringify(summary));
  } catch(error) {
    writeFileSync(join(evidence,'attempt-failure.json'),JSON.stringify({phase,message:sanitize(error.message),
      classification:'diagnostic unless exact behavioral assertion XML is verified'},null,2));
    throw error;
  } finally {
    try {
      if(changed) {
        writeFileSync(admission,original);assert.equal(hash(readFileSync(admission)),hash(original));
        writeFileSync(join(evidence,'admission-restored.sha256'),hash(readFileSync(admission)));
        assert.equal(await maven(['test-compile','-DskipTests'],'finally-restored-compile'),0,'restored source compile failed');
        const restoredProvenance = provenance();
        writeFileSync(join(evidence,'finally-restored-provenance.json'),JSON.stringify(restoredProvenance,null,2));
        assert.deepEqual(restoredProvenance, originalProvenance, 'finally source/class bytes must restore exactly');
      }
    } finally {
      try {
        if(owned) {
          const present=docker(['ps','-aq','--filter','name=^/'+name+'$']);
          if(present) {
            const label=docker(['inspect','--format','{{index .Config.Labels "codex.task64.uuid"}}',name]);
            assert.equal(label,uuid,'never remove foreign resource');
            docker(['rm','--force','--volumes',name]);
          }
        }
        writeFileSync(join(evidence,'cleanup.json'),JSON.stringify({uuid,name,ownedContainerRemoved:owned,
          temporaryRemoved:true,admissionRestored:!changed||hash(readFileSync(admission))===hash(original)},null,2));
      } finally {
        rmSync(temporary,{recursive:true,force:true});
        if(locked) rmdirSync(lock);
        process.off('SIGINT',onSignal);process.off('SIGTERM',onSignal);
      }
    }
  }
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  assert(process.argv.length===3&&/^--phase=/.test(process.argv[2]),'invalid Task64 phase arguments');
  const phase=process.argv[2].slice(8);phaseSelection(phase);await run(phase);
}
