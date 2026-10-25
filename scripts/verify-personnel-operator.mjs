import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {randomBytes, randomUUID, createHash} from 'node:crypto';
import {readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, readdirSync, realpathSync, rmdirSync} from 'node:fs';
import {join, relative, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const suite = 'PersonnelOperatorFactsIT,PersonnelOperatorProtocolTest,RemotePersonnelOperatorFactsPortTest,BizRoleSeedTest,BoundInternalProtocolTest';
export const frozenMigrations = [
  ['V532__shared_personnel_foundation.sql', 8333, '299c7ed3dc22f7b5a0510949956e685c702d74b88a606b5a92004ca1cb7d579b'],
  ['V533__shared_resource_identity.sql', 2540, '2b41485b9b032ffa00b74b5cec31bfaf774a9024f465e3e2d5ec149759c43aff']
];
/** No arbitrary suite, class wildcard or fallback may provision native resources. */
export function phaseSelection(phase) {
  assert(['red', 'green', 'ablation', 'restored'].includes(phase), 'invalid Task62 phase');
  return suite;
}
/** Fresh exact method evidence cannot be replaced by a skipped or similarly named testcase. */
export function inspectReport(xml, requested = [], allowFailure = false) {
  const tag = xml.match(/<testsuite\b[^>]*>/)?.[0]; assert(tag, 'missing Task62 XML suite');
  const counts = Object.fromEntries([...tag.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)]
    .map(([, k, v]) => [k, Number(v)]));
  assert(counts.tests > 0 && counts.skipped === 0, 'Task62 XML needs nonzero unskipped tests');
  if (!allowFailure) assert(counts.failures === 0 && counts.errors === 0, 'Task62 failed XML');
  const methods = [...xml.matchAll(/<testcase\b[^>]*\bname="([^"]+)"/g)].map(m => m[1]);
  assert(requested.every(m => methods.includes(m)), 'missing exact Task62 XML method');
  return counts;
}
/** A later testcase failure must never be attributed to the selected successful testcase. */
export function requireAssertionFailure(xml, method) {
  const cases = [...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
  const selected = cases.filter(m => m[1].match(/\bname="([^"]+)"/)?.[1] === method);
  assert.equal(selected.length, 1, 'missing exact Task62 failure testcase');
  assert(/<failure\b/.test(selected[0][2] ?? ''), 'Task62 selected testcase needs its own assertion failure');
}
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const pause = ms => new Promise(r => setTimeout(r, ms));
const mapper = join(root, 'backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java');
const upper = 'AND (a.valid_to IS NULL OR #{at}<a.valid_to)';
const reports = [
  ['backend/shop-base', 'ai.neargo.shop.svc.BoundInternalProtocolTest', ['boundEnumsUseExactNamesAndRejectUnknownOrNumericValues']],
  ['backend/shop-app', 'ai.neargo.shop.scenario.PersonnelOperatorFactsIT', ['indefiniteAssignmentReturnsLinked', 'finiteAssignmentBoundaryIsHalfOpen', 'expiredAssignmentAndStoppedPersonReturnUnlinked']],
  ['backend/shop-app', 'ai.neargo.shop.scenario.PersonnelOperatorProtocolTest', ['tokenThenRouteThenCurrentScopeAndFinallyContext']],
  ['backend/shop-app', 'ai.neargo.shop.arch.BizRoleSeedTest', []],
  ['backend-plugins/shop-industry-beauty/beauty-channel', 'ai.neargo.shop.beauty.port.RemotePersonnelOperatorFactsPortTest', ['explicitNullValidToRoundTripsAsLinked', 'missingValidToAndInvalidIntervalObservationsAreUnavailable']]
];
/** Maven module paths are relative but must canonically target the sole current worktree. */
export function aggregatePom(directory) {
  const targets = ['backend', 'backend-plugins/shop-industry-beauty'].map(p => realpathSync(join(root, p)));
  const modules = targets.map(target => {
    const module = relative(realpathSync(directory), target);
    assert.equal(realpathSync(resolve(directory, module)), target, 'wrong Task62 reactor source');
    return '<module>' + module + '</module>';
  });
  return '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    + '<groupId>local.task62</groupId><artifactId>personnel-operator-reactor</artifactId><version>1</version>'
    + '<packaging>pom</packaging><modules>' + modules.join('') + '</modules></project>';
}
function provenance() {
  const sources = [
    'backend/shop-base/src/main/java/ai/neargo/shop/spi/personnel/PersonnelOperatorFactsPort.java',
    'backend/shop-base/src/main/java/ai/neargo/shop/spi/personnel/PersonnelOperatorInternalApi.java',
    'backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalBindings.java',
    'backend/shop-base/src/main/java/ai/neargo/shop/svc/InternalReplyDeserializer.java',
    'backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java',
    'backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/PersonnelOperatorFactsPortImpl.java',
    'backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java',
    'backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/OperatorPersonnelFactRow.java',
    'backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalPersonnelOperatorController.java',
    'backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/port/RemotePersonnelOperatorFactsPort.java',
    'backend-plugins/shop-industry-beauty/beauty-channel/src/main/java/ai/neargo/shop/beauty/config/BeautyMerchantClientConfiguration.java',
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
  const temporary = '/tmp/codex-personnel-operator-' + uuid;
  const evidence = join(root, '.superpowers/sdd/beauty-backend/task-62-evidence', phase + '-' + uuid);
  mkdirSync(join(temporary, 'reactor'), {recursive: true}); mkdirSync(evidence, {recursive: true});
  const password = randomBytes(24).toString('base64url');
  const environment = {...process.env, JAVA_HOME: '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home',
    MYSQL_ROOT_PASSWORD: password, MYSQL_PWD: password, PERSONNEL_OPERATOR_TEST_PASSWORD: password};
  const sanitize = value => String(value).replaceAll(password, '[REDACTED]');
  const name = 'codex-personnel-operator-' + uuid;
  const docker = args => {
    try { return execFileSync('docker', ['--context', 'colima-hxkey-tests', ...args], {
      encoding:'utf8', env:environment, timeout:45000, stdio:['ignore','pipe','pipe']}).trim(); }
    catch(e) { throw new Error('Task62 own resource failed: ' + sanitize(e.stderr ?? e.message)); }
  };
  const lock = '/tmp/codex-beauty-bm04-native.lock';
  let owned = false, original, originalProvenance, changed = false, locked = false;
  const commandLog = [];
  const maven = async (args, label) => {
    const command = ['-o', '-Dmaven.repo.local=/tmp/codex-beauty-bm01-m2', '-f', join(temporary, 'reactor/pom.xml'),
      '-pl', ':shop-app,:beauty-channel', '-am', ...args];
    const child = spawn('mvn', command, {cwd:root, env:environment, stdio:['ignore','pipe','pipe']});
    const chunks = []; for (const stream of [child.stdout, child.stderr]) stream.on('data', b => chunks.push(b));
    let hard; const timer = setTimeout(() => {child.kill('SIGTERM'); hard = setTimeout(() => child.kill('SIGKILL'),5000);},600000);
    let status;
    try { status = await new Promise((resolve,reject) => {child.once('error',reject); child.once('exit',resolve);}); }
    finally {clearTimeout(timer);clearTimeout(hard);writeFileSync(join(evidence,label+'.log'),sanitize(Buffer.concat(chunks)));}
    commandLog.push({command:'mvn',args:command,status});
    writeFileSync(join(evidence,'commands.json'),JSON.stringify(commandLog,null,2));
    return status;
  };
  try {
    mkdirSync(lock); locked = true;
    const pins = frozenMigrations.map(([file,size,digest]) => {
      const bytes = readFileSync(join(root,'backend/shop-app/src/main/resources/db/migration',file));
      assert.equal(bytes.length,size); assert.equal(hash(bytes),digest); return {file,size,sha256:digest};
    });
    writeFileSync(join(evidence,'frozen-migrations.json'),JSON.stringify(pins,null,2));
    const memory = execFileSync('limactl',['shell','--instance','colima-hxkey-tests','cat','/proc/meminfo'],
      {encoding:'utf8',env:{...environment,LIMA_HOME:'/Users/robin/.colima/_lima'},timeout:15000});
    const available = Number(memory.match(/^MemAvailable:\s+(\d+)\s+kB$/m)?.[1]);
    assert(available >= 524288,'Task62 requires MemAvailable >=512MiB');
    const disk = execFileSync('limactl',['shell','--instance','colima-hxkey-tests','df','-Pk','/'],
      {encoding:'utf8',env:{...environment,LIMA_HOME:'/Users/robin/.colima/_lima'},timeout:15000});
    const diskAvailable = Number(disk.trim().split('\n').at(-1).trim().split(/\s+/)[3]);
    assert(diskAvailable >= 1048576,'Task62 requires disk available >=1GiB');
    docker(['image','inspect','mysql:9.7.2']);
    writeFileSync(join(evidence,'resource.json'),JSON.stringify({uuid,name,availableKiB:available,diskAvailableKiB:diskAvailable,
      mysql:'mysql:9.7.2',loopbackOnly:true},null,2));
    const pom = aggregatePom(join(temporary, 'reactor'));
    writeFileSync(join(temporary,'reactor/pom.xml'),pom);writeFileSync(join(evidence,'aggregate-pom.xml'),pom);
    if (phase === 'ablation') {
      original = readFileSync(mapper);const source = original.toString();
      originalProvenance = provenance();
      writeFileSync(join(evidence,'before-ablation-provenance.json'),JSON.stringify(originalProvenance,null,2));
      assert.equal(source.split(upper).length,2,'exact single assignment upper-bound conjunct required');
      writeFileSync(join(evidence,'mapper-before.sha256'),hash(original));
      writeFileSync(mapper,source.replace(upper,''));changed = true;
      writeFileSync(join(evidence,'mapper-ablated.sha256'),hash(readFileSync(mapper)));
    }
    for (const [module,cls] of reports) rmSync(join(root,module,'target/surefire-reports/TEST-'+cls+'.xml'),{force:true});
    owned = true;
    docker(['run','--rm','--pull','never','--detach','--name',name,'--label','codex.task62.uuid='+uuid,
      '--publish','127.0.0.1::3306','--memory','768m','--pids-limit','256','--cpus','1',
      '--env','MYSQL_ROOT_PASSWORD','--env','MYSQL_ROOT_HOST=%','mysql:9.7.2',
      '--datadir=/var/lib/personnel-operator-test','--innodb-buffer-pool-size=64M','--performance-schema=OFF']);
    let ready=false;
    for(let i=0;i<150;i++) {
      try {docker(['exec','--env','MYSQL_PWD',name,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','-e','SELECT 1']);ready=true;break;}
      catch {await pause(300);}
    }
    assert(ready,'Task62 owned MySQL readiness bounded out');
    const address=docker(['port',name,'3306/tcp']);assert.match(address,/^127\.0\.0\.1:[0-9]+$/);
    const jdbc='jdbc:mysql://'+address+'/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC';
    const status=await maven(['--fail-at-end','test','-Dtest='+selected,'-Dsurefire.failIfNoSpecifiedTests=false',
      '-Dpersonnel.operator.test.admin-url='+jdbc, '-Dpersonnel.operator.test.source-root='+realpathSync(root)],phase);
    writeFileSync(join(evidence,'phase-provenance.json'),JSON.stringify(provenance(),null,2));
    writeFileSync(join(evidence,'mapper-phase.java'),readFileSync(mapper));
    const summary=[];
    for(const [module,cls,methods] of reports) {
      const path=join(root,module,'target/surefire-reports/TEST-'+cls+'.xml');assert(existsSync(path),'missing fresh report '+cls);
      const xml=sanitize(readFileSync(path,'utf8'));writeFileSync(join(evidence,cls+'.xml'),xml);
      summary.push({class:cls,...inspectReport(xml,methods,phase==='red'||phase==='ablation'),sha256:hash(xml)});
    }
    const origins=[];
    for(const [module] of reports) {
      const directory=join(root,module,'target/surefire-reports');
      for(const file of readdirSync(directory).filter(n => reports.some(([,cls]) => n===cls+'.txt'))) {
        origins.push({module,file,sha256:hash(readFileSync(join(directory,file)))});
      }
    }
    writeFileSync(join(evidence,'summary.json'),JSON.stringify({phase,status,summary,origins},null,2));
    if(phase==='red'||phase==='ablation') {
      assert(status!==0 && summary.some(c=>c.failures>0),'Task62 RED needs real assertion failure');
      if(phase==='ablation') {
        const xml=readFileSync(join(evidence,'ai.neargo.shop.scenario.PersonnelOperatorFactsIT.xml'),'utf8');
        requireAssertionFailure(xml, 'expiredAssignmentAndStoppedPersonReturnUnlinked');
      }
    } else assert.equal(status,0,'Task62 native suite failed');
    console.log('Task62 '+phase+' evidence: '+evidence+' '+JSON.stringify(summary));
  } finally {
    try {
      if(changed) {
        writeFileSync(mapper,original);assert.equal(hash(readFileSync(mapper)),hash(original));
        writeFileSync(join(evidence,'mapper-restored.sha256'),hash(readFileSync(mapper)));
        assert.equal(await maven(['test-compile','-DskipTests'],'finally-restored-compile'),0,'restored source compile failed');
        const restoredProvenance = provenance();
        writeFileSync(join(evidence,'finally-restored-provenance.json'),JSON.stringify(restoredProvenance,null,2));
        assert.deepEqual(restoredProvenance, originalProvenance, 'finally source/class bytes must restore exactly');
      }
    } finally {
      try {
        if(owned) {
          const label=docker(['inspect','--format','{{index .Config.Labels "codex.task62.uuid"}}',name]);
          assert.equal(label,uuid,'never remove foreign resource');docker(['rm','--force','--volumes',name]);
        }
        writeFileSync(join(evidence,'cleanup.json'),JSON.stringify({uuid,name,ownedContainerRemoved:owned,
          temporaryRemoved:true,mapperRestored:!changed||hash(readFileSync(mapper))===hash(original)},null,2));
      } finally {
        rmSync(temporary,{recursive:true,force:true});
        if(locked) rmdirSync(lock);
      }
    }
  }
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  assert(process.argv.length===3&&/^--phase=/.test(process.argv[2]),'invalid Task62 phase arguments');
  const phase=process.argv[2].slice(8);phaseSelection(phase);await run(phase);
}
