import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const jar = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-server/target/beauty-server-0.1.0-SNAPSHOT.jar', import.meta.url));
const java = process.env.JAVA_HOME ? process.env.JAVA_HOME + '/bin/java' : 'java';
const server = spawn(java, ['-jar', jar, '--shop.beauty.enabled=false', '--spring.datasource.url=',
  '--server.address=127.0.0.1', '--server.port=0'], {stdio: ['ignore', 'pipe', 'pipe']});
let output = '';
let exited = false;
let launchError;
const finished = new Promise(resolve => server.on('exit', () => {exited = true; resolve();}));
server.on('error', error => {launchError = error; exited = true;});
server.stdout.on('data', data => {output = (output + data).slice(-100000);});
server.stderr.on('data', data => {output = (output + data).slice(-100000);});
try {
  const deadline = Date.now() + 40000;
  let port;
  while (Date.now() < deadline && !exited) {
    const match = output.match(/Tomcat started on port ([0-9]+)/);
    if (match && output.includes('Started BeautyApplication')) {port = match[1]; break;}
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert(!launchError, launchError?.message);
  assert(port, 'Beauty disabled process failed to start: ' + output.slice(-4000));
  const readiness = await fetch(`http://127.0.0.1:${port}/actuator/health/readiness`, {signal: AbortSignal.timeout(5000)});
  assert.equal(readiness.status, 503);
  assert.equal((await readiness.json()).status, 'OUT_OF_SERVICE');
  const liveness = await fetch(`http://127.0.0.1:${port}/actuator/health/liveness`, {signal: AbortSignal.timeout(5000)});
  assert.equal(liveness.status, 200);
  console.log('PASS executable beauty-server: disabled startup without database, liveness 200, readiness 503.');
} finally {
  if (!exited) {
    server.kill('SIGTERM');
    const timer = setTimeout(() => server.kill('SIGKILL'), 10000);
    await finished;
    clearTimeout(timer);
  }
}
