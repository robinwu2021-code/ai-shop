import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseScopeRegistrations } from '../lib/data-scope-registrations.mjs';

test('literal-loop registrations retain every table and the real ownership anchor',()=>{
 const source='for(String table:new String[]{"prn_job","prn_attempt"}) { registry.register(table,Map.of(ScopeDim.MERCHANT,"entity_no")); }';
 assert.deepEqual([...parseScopeRegistrations(source)], [['prn_job',{MERCHANT:'entity_no'}],['prn_attempt',{MERCHANT:'entity_no'}]]);
 assert.throws(()=>parseScopeRegistrations('registry.register(dynamic, anchors);'),/Unparsed/);
 assert.throws(()=>parseScopeRegistrations(source+' registry.register(dynamic, anchors);'),/Unparsed/);
});
