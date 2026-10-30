import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, delimiter} from 'node:path';
import {fileURLToPath} from 'node:url';

const runner = fileURLToPath(new URL('../verify-beauty-persistence.mjs', import.meta.url));
const shim = String.raw`
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
const command = path.basename(process.argv[1]);
const root = process.env.RUNNER_FAKE_ROOT;
fs.appendFileSync(path.join(root, 'calls'), JSON.stringify({command, args}) + '\n');
if (command === 'limactl') {
  console.log('MemAvailable:       524288 kB');
} else if (command === 'mvn') {
  process.exit(process.env.RUNNER_MAIN_FAIL === '1' ? 43 : 0);
} else if (command === 'docker') {
  const name = fs.existsSync(path.join(root, 'own-name'))
    ? fs.readFileSync(path.join(root, 'own-name'), 'utf8') : undefined;
  if (args[0] === 'run') {
    const own = args[args.indexOf('--name') + 1];
    fs.writeFileSync(path.join(root, 'own-name'), own);
    fs.writeFileSync(path.join(root, own), 'running');
    if (process.env.RUNNER_MODE === 'timeout') {
      setTimeout(() => process.exit(44), 20000);
    } else if (process.env.RUNNER_MODE === 'run-error') {
      console.error('PRIMARY_RUN_FAILURE');
      process.exit(41);
    } else {
      console.log('fake-created-id');
    }
  } else if (args[0] === 'rm') {
    if (args.at(-1) !== name) throw new Error('Foreign container cleanup attempted');
    fs.rmSync(path.join(root, name), {force: true});
    if (process.env.RUNNER_CLEANUP_FAIL === '1') {
      console.error('SECONDARY_CLEANUP_FAILURE');
      process.exit(42);
    }
  } else if (args[0] === 'exec' && args.includes('SELECT 1')) {
    console.log('1');
  } else if (args[0] === 'port') {
    console.log('127.0.0.1:33306');
  } else {
    if (process.env.RUNNER_DIAGNOSTIC_FAIL === '1') {
      console.error('SECONDARY_DIAGNOSTIC_FAILURE');
      process.exit(45);
    }
    console.log('fake-own-diagnostic');
  }
} else {
  throw new Error('Unexpected subprocess');
}
`;

function exercise({mode = 'run-error', lowMemory = false, cleanupFail = false,
  diagnosticFail = false, mainFail = false}, verify) {
  const root = mkdtempSync(join(tmpdir(), 'beauty-runner-behavior-'));
  const bin = join(root, 'bin');
  mkdirSync(bin);
  for (const command of ['docker', 'limactl', 'mvn']) {
    writeFileSync(join(bin, command), '#!' + process.execPath + '\n' + shim, {mode: 0o755});
  }
  writeFileSync(join(root, 'foreign-running'), 'untouched');
  try {
    const result = spawnSync(process.execPath, [runner], {
      encoding: 'utf8', timeout: 25000,
      env: {...process.env, PATH: bin + delimiter + bin, LIMA_HOME: root,
        BEAUTY_TEST_LOW_MEMORY: lowMemory ? '1' : '0', BEAUTY_TEST_DISK: '1',
        RUNNER_FAKE_ROOT: root, RUNNER_MODE: mode,
        RUNNER_CLEANUP_FAIL: cleanupFail ? '1' : '0',
        RUNNER_DIAGNOSTIC_FAIL: diagnosticFail ? '1' : '0',
        RUNNER_MAIN_FAIL: mainFail ? '1' : '0'},
    });
    assert.equal(result.error, undefined, 'Runner subprocess must terminate itself');
    const calls = readFileSync(join(root, 'calls'), 'utf8').trim().split('\n').map(JSON.parse);
    const own = readFileSync(join(root, 'own-name'), 'utf8');
    assert.match(own, /^codex-beauty-jdbc-[0-9a-f-]{36}$/);
    assert.equal(existsSync(join(root, own)), false, 'Created own container must be removed after run failure');
    assert.equal(readFileSync(join(root, 'foreign-running'), 'utf8'), 'untouched');
    const removals = calls.filter(c => c.command === 'docker' && c.args[0] === 'rm');
    assert.deepEqual(removals.map(c => c.args), [['rm', '--force', '--volumes', own]]);
    for (const {command, args} of calls) {
      if (command !== 'docker' || args[0] === 'run') continue;
      if (args[0] === 'events') assert(args.includes('container=' + own));
      else assert(args.includes(own), 'Every Docker command must target only own UUID');
    }
    verify(result, calls);
  } finally {
    rmSync(root, {recursive: true, force: true});
  }
}

test('default runner cleans daemon-created container when run CLI reports error', () => {
  exercise({}, (result, calls) => {
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /PRIMARY_RUN_FAILURE/);
    assert.equal(calls.some(c => c.command === 'mvn'), false);
  });
});

test('low-memory runner cleans daemon-created container after actual CLI timeout', () => {
  exercise({mode: 'timeout', lowMemory: true}, (result, calls) => {
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /ETIMEDOUT/);
    assert.equal(calls.some(c => c.command === 'mvn'), false);
    assert(calls.some(c => c.command === 'docker' && c.args[0] === 'events'));
  });
});

test('startup error survives failed own diagnostics and cleanup', () => {
  exercise({lowMemory: true, cleanupFail: true, diagnosticFail: true}, result => {
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /PRIMARY_RUN_FAILURE/);
    assert.doesNotMatch(result.stderr, /Error: Command failed: docker rm/);
    assert.match(result.stdout, /SECONDARY_CLEANUP_FAILURE/);
    assert.match(result.stdout, /SECONDARY_DIAGNOSTIC_FAILURE/);
  });
});

test('default runner preserves startup error when own cleanup also reports error', () => {
  exercise({cleanupFail: true}, result => {
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /PRIMARY_RUN_FAILURE/);
    assert.doesNotMatch(result.stderr, /Error: Command failed: docker rm/);
  });
});

test('default runner cleans after Maven failure and preserves the body error', () => {
  exercise({mode: 'success', mainFail: true, cleanupFail: true}, (result, calls) => {
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Beauty disposable JDBC integration tests failed/);
    assert.doesNotMatch(result.stderr, /Error: Command failed: docker rm/);
    assert.equal(calls.filter(c => c.command === 'mvn').length, 1);
  });
});

test('cleanup failure still fails runner when startup and Maven succeed', () => {
  exercise({mode: 'success', cleanupFail: true}, (result, calls) => {
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /SECONDARY_CLEANUP_FAILURE/);
    assert.equal(calls.filter(c => c.command === 'mvn').length, 1);
  });
});
