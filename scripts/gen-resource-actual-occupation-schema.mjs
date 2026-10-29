#!/usr/bin/env node
// Pure forward-schema renderer; no SQL, application or tests are executed.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFrozenMigration } from './lib/beauty-schema-sql.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const truth = 'docs/technical/design/schema/resource-actual-occupation-schema.json';
const names = ['mch_resource_occupation', 'mch_resource_occupation_unit',
  'mch_resource_occupation_segment', 'mch_resource_occupation_bucket'];
const common = ['mch_booking_owner', 'mch_resource_command', 'mch_resource_bucket', 'mch_booking_hold_unit'];
const hash = text => createHash('sha256').update(text).digest('hex');
const column = c => `${c.name} ${c.type.toUpperCase()}` +
  (c.charset ? ` CHARACTER SET ${c.charset}` : '') +
  (c.collation && c.collation !== 'OWNER_TENANT' ? ` COLLATE ${c.collation}` : '') +
  (c.nullable ? ' NULL' : ' NOT NULL') + (c.name === 'id' ? ' AUTO_INCREMENT' : '') +
  (c.default === null ? '' : ` DEFAULT ${c.default}`);
const mismatch = fields => fields.map(c => `NOT (BINARY NEW.${c} <=> BINARY OLD.${c})`).join(' OR\n        ');
const signal = message => `SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '${message}';`;
const guarded = (test, message) => `    IF ${test} THEN\n        ${signal(message)}\n    END IF;`;
function guards(source) {
  const result = [];
  const add = (table, suffix, event, body) => result.push({name:`trg_${suffix}`,table,
    timing:'BEFORE',event,body:`BEGIN\n${body}\nEND`});
  for (const table of source.tables) {
    const rules = [guarded(mismatch(table.immutable), 'Immutable resource original')];
    if (table.tag === 'rco') {
      rules.push(guarded("(OLD.state='ENDED' AND NEW.state<>'ENDED') OR (OLD.state='CLEANING' AND NEW.state NOT IN ('CLEANING','ENDED'))", 'Occupation lifecycle cannot reopen'));
      rules.push(guarded('NEW.revision<OLD.revision OR NEW.revision>OLD.revision+1 OR NEW.observation_epoch<OLD.observation_epoch OR NEW.observation_epoch>OLD.observation_epoch+1 OR (NEW.state<>OLD.state AND NEW.revision<>OLD.revision+1)', 'Occupation lifecycle revision required'));
    }
    if (table.tag === 'rcs') {
      rules.push(guarded('OLD.actual_end_at IS NOT NULL AND (' + mismatch(['actual_end_at','close_command_no']) + ')', 'Segment close is permanent'));
      rules.push(guarded('OLD.cleaning_from IS NOT NULL AND (' + mismatch(['cleaning_from','cleaning_until']) + ')', 'Cleaning policy interval is immutable'));
      rules.push(guarded('OLD.cleaning_completed_at IS NOT NULL AND (' + mismatch(['cleaning_completed_at','cleaning_command_no']) + ')', 'Cleaning completion is permanent'));
    }
    if (table.tag === 'rcb') rules.push(guarded('OLD.retired_at IS NOT NULL AND (' + mismatch(['retired_at','retire_command_no']) + ')', 'Projection retirement is permanent'));
    add(table.name, `${table.tag}_immutable`, 'UPDATE', rules.join('\n'));
    add(table.name, `${table.tag}_no_delete`, 'DELETE', `    ${signal('Resource evidence cannot be deleted')}`);
  }
  for (const table of source.commonTables) {
    let active;
    let rules=[];
    if (table.name === 'mch_booking_owner') {
      active = "OLD.occupation_association_state<>'UNBOUND'";
      rules.push(guarded(active + ' AND (' + mismatch(['tenant_no','entity_no','store_no','owner_no','source_type','source_no',...table.addedColumns.map(c=>c.name)]) + ' OR NEW.deleted<>0)', 'Occupation owner association is permanent'));
    } else if (table.name === 'mch_resource_command') {
      active = "OLD.command_kind='OCCUPATION_V1'";
      rules.push(guarded(mismatch(['command_kind',...table.addedColumns.filter(c=>c.immutable).map(c=>c.name)]), 'Resource command kind and original are immutable'));
      rules.push(guarded(active+' AND ('+mismatch(['tenant_no','entity_no','store_no','command_no','owner_no','action','request_key_hash','payload_hash','frozen_payload','initiated_by'])+' OR NEW.deleted<>0)', 'Actual command original is permanent'));
      rules.push(guarded(active+" AND OLD.status<>'ACCEPTED' AND ("+mismatch(['status','result_payload','original_result_no','original_result_revision','original_result_hash','occupation_outcome','seal_no','seal_revision','seal_hash'])+')', 'Original occupation result is permanent'));
    } else if (table.name === 'mch_booking_hold_unit') {
      active='OLD.consumed_at IS NOT NULL';
      rules.push(guarded(active+' AND ('+mismatch(['tenant_no','entity_no','unit_no','hold_no','bucket_no','allocation_revision','qty','source_line_ref','consume_occupation_unit_no','consumed_at','consume_command_no'])+' OR NEW.deleted<>0 OR NEW.released_at IS NOT NULL OR NEW.release_command_no IS NOT NULL)', 'Booking consumption is permanent'));
    } else {
      continue;
    }
    const tag={mch_booking_owner:'rco_owner',mch_resource_command:'rco_command',mch_booking_hold_unit:'rco_consumed'}[table.name];
    add(table.name,`${tag}_immutable`,'UPDATE',rules.join('\n'));
    add(table.name,`${tag}_no_delete`,'DELETE',guarded(active,'Actual resource association cannot be deleted'));
  }
  return result;
}
function checksum(sql) {
  let crc=0xffffffff;
  for(const byte of Buffer.from(sql.replace(/^\uFEFF/,'').split(/\r\n|\n|\r/).join(''))) {
    crc^=byte;
    for(let bit=0;bit<8;bit++) crc=(crc>>>1)^((crc&1)?0xedb88320:0);
  }
  return (crc^0xffffffff)|0;
}
export function render(source) {
  if (source.schemaVersion!==1 || source.platformVersion!==607 || source.owner!=='shop-merchant/primary' ||
      JSON.stringify(source.tables.map(t=>t.name))!==JSON.stringify(names) ||
      JSON.stringify(source.commonTables.map(t=>t.name))!==JSON.stringify(common)) throw new Error('Resource owner identity drift');
  for (const table of [...source.tables, ...source.commonTables]) {
    const columns=new Set();
    for (const c of table.columns) {
      if (!/^[a-z][a-z0-9_]{0,63}$/.test(c.name) || columns.has(c.name) ||
          !/^(?:bigint|int|longtext|datetime\(3\)|(?:var)?char\([1-9][0-9]{0,3}\))$/.test(c.type) ||
          typeof c.nullable!=='boolean' || typeof c.immutable!=='boolean' ||
          ![null,'ascii','utf8mb4'].includes(c.charset) ||
          ![null,'OWNER_TENANT','ascii_bin','utf8mb4_bin'].includes(c.collation) ||
          c.default!==null && !/^(?:[0-9]+|'[A-Z0-9_]+')$/.test(c.default)) throw new Error('Invalid resource column');
      columns.add(c.name);
    }
    for(const [name,fields] of Object.entries({...table.unique,...table.indexes})) {
      if(!/^[a-z][a-z0-9_]{0,63}$/.test(name) || !fields.length || new Set(fields).size!==fields.length ||
          fields.some(f=>!columns.has(f))) throw new Error('Invalid resource key');
    }
    for(const [name,expr] of Object.entries(table.checks)) {
      if(!/^[a-z][a-z0-9_]{0,63}$/.test(name) || typeof expr!=='string' || /;|--|\/\*/.test(expr))
        throw new Error('Invalid resource check');
    }
  }
  const statements=[];
  for(const table of source.commonTables) {
    for(const c of table.addedColumns) statements.push(`ALTER TABLE ${table.name} ADD COLUMN ${column(c)};`);
    for(const name of table.droppedChecks) statements.push(`ALTER TABLE ${table.name} DROP CHECK ${name};`);
    for(const [name,expr] of Object.entries(table.addedChecks)) statements.push(`ALTER TABLE ${table.name} ADD CONSTRAINT ${name} CHECK (${expr});`);
    for(const [name,cols] of Object.entries(table.addedUnique)) statements.push(`ALTER TABLE ${table.name} ADD UNIQUE KEY ${name} (${cols.join(', ')});`);
  }
  const retainedStatementHashes=statements.map(s=>hash(s.slice(0,-1)));
  for(const table of source.tables) {
    const parts=table.columns.map(c=>'    '+column(c));
    parts.push(`    PRIMARY KEY (${table.primaryKey.join(', ')})`);
    for(const [name,cols] of Object.entries(table.unique)) parts.push(`    UNIQUE KEY ${name} (${cols.join(', ')})`);
    for(const [name,cols] of Object.entries(table.indexes)) parts.push(`    KEY ${name} (${cols.join(', ')})`);
    for(const [name,expr] of Object.entries(table.checks)) parts.push(`    CONSTRAINT ${name} CHECK (${expr})`);
    statements.push(`CREATE TABLE ${table.name} (\n${parts.join(',\n')}\n) ENGINE=InnoDB COMMENT='${table.comment}';`);
  }
  const triggers=guards(source);
  const sql='-- Generated by scripts/gen-resource-actual-occupation-schema.mjs; do not edit.\n'+
    '-- Permanent evidence only; no execution, stock or quantity backfill.\n\n'+statements.join('\n\n')+
    '\n\nDELIMITER $$\n'+triggers.map(t=>`CREATE TRIGGER ${t.name} ${t.timing} ${t.event} ON ${t.table}\nFOR EACH ROW\n${t.body}$$`).join('\n\n')+'\nDELIMITER ;\n';
  const descriptor=JSON.stringify({schemaVersion:1,owner:source.owner,platformVersion:607,
    migrationScript:'V607__resource_actual_occupation.sql',flywayChecksum:checksum(sql),migrationSha256:hash(sql),
    ownerIdentity:source.ownerIdentity,tables:source.tables,commonTables:source.commonTables,
    retainedStatementHashes,triggers},null,2)+'\n';
  return {sql,descriptor};
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  if(process.argv.slice(2).some(a=>a!=='--check')) throw new Error('Unknown renderer argument');
  const source=JSON.parse(readFileSync(resolve(root,truth),'utf8'));
  const output=render(source);
  for(const [path,value] of [[source.migration,output.sql],[source.resource,output.descriptor]]) {
    const target=resolve(root,path);
    if(process.argv.includes('--check')) {if(readFileSync(target,'utf8')!==value)throw new Error('Schema drift: '+path);}
    else if(path===source.migration)writeFrozenMigration(target,value);
    else {mkdirSync(dirname(target),{recursive:true});writeFileSync(target,value);}
  }
}
