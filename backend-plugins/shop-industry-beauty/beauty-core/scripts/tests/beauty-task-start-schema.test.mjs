// SOURCE ONLY / NOT_RUN. Rendering consistency is independent of MySQL execution or owner effects.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const root=new URL('../../',import.meta.url);
test('new descriptor binds exact forward migration while the V13 descriptor stays historical',()=>{
 const descriptor=JSON.parse(readFileSync(new URL('src/main/resources/META-INF/shop/beauty-task-start-schema.json',root)));
 const sql=readFileSync(new URL(`src/main/resources/db/industry/beauty/${descriptor.migrationScript}`,root));
 assert.equal(createHash('sha256').update(sql).digest('hex'),descriptor.migrationSha256);
 assert.equal(descriptor.history.length,15);
 assert.deepEqual(descriptor.columns.map(c=>c.name),['execution_no','action_observation_json']);
 const v13=JSON.parse(readFileSync(new URL('src/main/resources/META-INF/shop/beauty-task-action-schema.json',root)));
 assert.equal(v13.platformVersion,13); assert.ok(v13.check.includes("='TASK_ACTION_V1'"));
 assert.ok(descriptor.checks.ck_bty_task_action.includes(v13.check));
 assert.ok(!descriptor.triggers[0].body.includes('CANCELLED_RELEASED'));
});
