import test from 'node:test';
import assert from 'node:assert/strict';
import { appendRolePermissions } from '../lib/biz-role-migrations.mjs';
const add = (where) => `UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:print:read') WHERE ${where};`;
test('builtin append is idempotent and cannot grant to custom merchant roles', () => {
 const roles={MANAGER:new Set(['biz:order:view'])};
 appendRolePermissions(roles,[add("entity_no='*' AND builtin=1 AND role_code='MANAGER'"),add("entity_no='*' AND builtin=1 AND role_code='MANAGER'"),add("entity_no='E1' AND role_code='MANAGER'")]);
 assert.deepEqual([...roles.MANAGER], ['biz:order:view','biz:print:read']);
});
test('unknown permission syntax fails closed instead of ignoring migration drift',()=>{
 assert.throws(()=>appendRolePermissions({MANAGER:new Set()},["UPDATE mch_role SET perms='[]' WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER';"]),/Unsupported/);
});
