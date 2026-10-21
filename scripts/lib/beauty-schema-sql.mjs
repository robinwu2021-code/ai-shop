import fs from 'node:fs';
import path from 'node:path';

const identifier = value => {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9_]*$/.test(value) || value.length > 64) {
    throw new Error(`Invalid SQL identifier: ${value}`);
  }
  return `\`${value}\``;
};
const literal = value => `'${String(value).replaceAll("'", "''").replaceAll('\\', '\\\\')}'`;
const sqlType = value => {
  if (!/^(?:bigint|int|tinyint|text|longtext|json|date|datetime\(3\)|(?:var)?char\([1-9][0-9]*\)|decimal\(18,6\))$/.test(value)) {
    throw new Error(`Unsupported SQL type: ${value}`);
  }
  return value.toUpperCase();
};

export function renderBeautySchema(design) {
  const common = design.common_fields.map(([name, type, description]) => ({name, type, description, nullable: ['created_by', 'updated_by'].includes(name)}));
  const names = new Set();
  for (const table of design.tables) {
    identifier(table.name);
    if (names.has(table.name)) throw new Error(`Duplicate table: ${table.name}`);
    names.add(table.name);
    const columns = new Set();
    for (const field of [...common, ...table.fields]) {
      identifier(field.name); sqlType(field.type);
      if (columns.has(field.name)) throw new Error(`Duplicate column: ${table.name}.${field.name}`);
      columns.add(field.name);
    }
    for (const key of [...table.unique, ...table.indexes]) {
      if (!key.length || new Set(key).size !== key.length) throw new Error(`Invalid key: ${table.name}`);
      for (const column of key) {
        identifier(column);
        if (!columns.has(column)) throw new Error(`Unknown key column: ${table.name}.${column}`);
      }
    }
  }
  const tables = design.tables.filter(t => t.name.startsWith('bty_') && t.stage === 'P1');
  if (tables.length !== 16 || tables.some(t => t.mode !== 'NEW')) throw new Error('P1 must contain sixteen new beauty-owned tables');
  const guard = tables.find(t => t.name === 'bty_source_allocation_guard');
  for (const name of ['source_kind', 'source_no', 'source_line_ref']) {
    if (!guard?.fields.some(f => f.name === name && f.nullable === false)) throw new Error(`Nullable or missing source identity: ${name}`);
  }
  const body = tables.map(table => {
    const columns = [...common, ...table.fields];
    const parts = columns.map(field => {
      let definition = `${identifier(field.name)} ${sqlType(field.type)}`;
      if (/^(?:var)?char/.test(field.type)) definition += ' COLLATE utf8mb4_bin';
      definition += field.nullable ? ' NULL' : ' NOT NULL';
      if (field.name === 'id') definition += ' AUTO_INCREMENT';
      if (field.name === 'tenant_no') definition += " DEFAULT 'MAIN'";
      if (['version', 'deleted'].includes(field.name)) definition += ' DEFAULT 0';
      definition += ` COMMENT ${literal(field.description)}`;
      return definition;
    });
    parts.push('PRIMARY KEY (`id`)');
    for (const [keys, prefix] of [[table.unique, 'uk'], [table.indexes, 'idx']]) {
      keys.forEach((key, i) => {
        const scoped = [...new Set(['tenant_no', 'entity_no', ...key])];
        parts.push(`${prefix === 'uk' ? 'UNIQUE KEY' : 'KEY'} ${identifier(`${prefix}_${table.name}_${i + 1}`)} (${scoped.map(identifier).join(', ')})`);
      });
    }
    const has = name => columns.some(f => f.name === name);
    const checks = ['`version` >= 0', '`deleted` IN (0, 1)'];
    for (const name of ['qty', 'duration_min', 'duration_min_snapshot', 'revision_index', 'schema_version']) {
      if (has(name)) checks.push(`${identifier(name)} > 0`);
    }
    for (const name of ['done_qty', 'voided_qty', 'source_qty', 'allocated_qty', 'buffer_before_min', 'buffer_after_min', 'booking_ahead_days', 'sequence_no', 'offset_min', 'scope_revision', 'binding_revision', 'appointment_revision', 'source_revision']) {
      if (has(name)) checks.push(`${identifier(name)} >= 0`);
    }
    if (has('done_qty')) checks.push('`done_qty` + `voided_qty` <= `qty`');
    if (has('allocated_qty')) checks.push('`allocated_qty` <= `source_qty`');
    if (has('end_at') && has('start_at')) checks.push('`end_at` > `start_at`');
    if (has('ended_at')) checks.push('`ended_at` IS NULL OR `ended_at` >= `started_at`');
    if (has('finished_at')) checks.push('`finished_at` IS NULL OR (`started_at` IS NOT NULL AND `finished_at` >= `started_at`)');
    if (has('scope_frozen')) checks.push('`scope_frozen` IN (0, 1)');
    checks.forEach((check, i) => parts.push(`CONSTRAINT ${identifier(`ck_${table.name}_${i + 1}`)} CHECK (${check})`));
    return `CREATE TABLE ${identifier(table.name)} (\n    ${parts.join(',\n    ')}\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT=${literal(table.description)};`;
  });
  return '-- Beauty P1 private schema. Shared personnel/resources remain in merchant.\n-- Frozen migration: append a new version for subsequent changes.\n\n' + body.join('\n\n') + '\n';
}

export function writeFrozenMigration(file, sql) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  try { fs.writeFileSync(file, sql, {flag: 'wx'}); }
  catch (error) {
    if (error.code !== 'EEXIST') throw error;
    if (fs.readFileSync(file, 'utf8') !== sql) throw new Error(`Frozen migration changed: ${file}; append a new migration`);
  }
}
