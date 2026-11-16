import {expect,it} from 'vitest';
import {NAV,visibleLeaves} from './nav';
import {readFileSync} from 'node:fs';
const section=NAV.find(s=>s.key==='merchant')!;
it('has one application entry alongside the independent self-operation and payment entries',()=>{
 expect(section.children!.filter(l=>l.group==='入驻').map(l=>l.label)).toEqual(['入驻管理','支付开通']);
 expect(section.children!.filter(l=>l.href==='/merchants')).toHaveLength(1);
});
it('keeps review and assisted-entry permissions independent under one destination',()=>{
 for(const p of ['merchant:apply:audit','merchant:apply:onbehalf','merchant:selfop:create'])expect(visibleLeaves(section,[p]).some(l=>l.href==='/merchants')).toBe(true);
 expect(visibleLeaves(section,['merchant:merchant:read']).some(l=>l.href==='/merchants')).toBe(false);
 expect(visibleLeaves(section,['merchant:apply:onbehalf']).some(l=>l.href.includes('self-operated'))).toBe(false);
});
it('preserves both menu authorization points without granting or revoking roles',()=>{
 const sql=readFileSync('../backend/shop-app/src/main/resources/db/migration/V667__ops_unified_admission_management.sql','utf8');
 expect(sql).toContain("'OPS_MERCHANT','OPS_MERCHANT__TAB_ON_BEHALF'");expect(sql).not.toMatch(/(?:INSERT|DELETE|UPDATE)\s+sys_role/i);expect(sql).not.toMatch(/SET\s+perm_code/i);
});
