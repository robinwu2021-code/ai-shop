import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
import {writeFrozenMigration} from './lib/beauty-schema-sql.mjs';

const columns = {
  "bty_source_observation": [
    "entity_no",
    "store_no",
    "source_kind",
    "source_no",
    "checkout_caller",
    "checkout_operation_no",
    "order_no",
    "buyer_no",
    "observation_epoch",
    "state",
    "source_revision",
    "source_hash",
    "verified_revision",
    "verified_hash",
    "verified_lines",
    "verified_at",
    "observed_at",
    "failure_code",
    "admission_kind",
    "customer_kind",
    "guest_no",
    "identity_revision",
    "manual_epoch",
    "manual_state",
    "manual_observed_at",
    "manual_failure_code",
    "manual_revision",
    "manual_hash",
    "manual_verified_revision",
    "manual_verified_hash",
    "manual_verified_lines",
    "manual_verified_at",
    "manual_admission_revision",
    "manual_admission_state",
    "id",
    "tenant_no",
    "version",
    "created_by",
    "created_at",
    "updated_by",
    "updated_at",
    "deleted"
  ],
  "bty_trade_binding": [
    "entity_no",
    "binding_no",
    "submission_no",
    "task_no",
    "binding_revision",
    "source_kind",
    "source_no",
    "source_line_ref",
    "source_revision",
    "qty",
    "uom",
    "source_snapshot_hash",
    "guard_no",
    "admission_kind",
    "customer_kind",
    "guest_no",
    "identity_revision",
    "buyer_no",
    "manual_source_revision",
    "manual_source_hash",
    "id",
    "tenant_no",
    "version",
    "created_by",
    "created_at",
    "updated_by",
    "updated_at",
    "deleted"
  ],
  "bty_source_allocation_guard": [
    "entity_no",
    "guard_no",
    "source_kind",
    "source_no",
    "source_line_ref",
    "source_revision",
    "source_qty",
    "allocated_qty",
    "uom",
    "source_hash",
    "admission_kind",
    "customer_kind",
    "guest_no",
    "identity_revision",
    "buyer_no",
    "manual_source_revision",
    "manual_source_hash",
    "id",
    "tenant_no",
    "version",
    "created_by",
    "created_at",
    "updated_by",
    "updated_at",
    "deleted"
  ]
};

const columnClause = /^(ADD|MODIFY) COLUMN `([a-z][a-z0-9_]*)` (?:VARCHAR\([1-9][0-9]{0,3}\)|CHAR\(64\)|BIGINT|JSON|DATETIME\(3\))(?: COLLATE utf8mb4_bin)? (?:NOT NULL|NULL)(?: DEFAULT '(?:LEGACY_FUNDED)')?$/;
function validateClause(table, clause) {
  if (typeof clause !== 'string' || /;|--|\/\*|\b(?:INSERT|DELETE|UPDATE|DROP|TRUNCATE|SELECT|REFERENCES)\b/i.test(clause)) {
    throw new Error('Invalid V9 local schema alteration');
  }
  const column = columnClause.exec(clause);
  if (column) {
    if (!columns[table].includes(column[2])) throw new Error('Unknown V9 column');
    return;
  }
  if (!/^ADD CONSTRAINT `ck_bty_[a-z0-9_]+` CHECK \(.+\)$/.test(clause)) {
    throw new Error('Unsupported V9 type or constraint');
  }
  const expression = clause.slice(clause.indexOf('CHECK (') + 7, -1);
  for (const [, name] of expression.matchAll(/`([^`]+)`/g)) {
    if (!columns[table].includes(name)) throw new Error('Unknown V9 check column');
  }
  const tokens = expression.replace(/`[^`]+`|'[^']*'|\b\d+\b|[\s(),<>=!+-]/g, ' ').trim().split(/\s+/);
  if (tokens.some(t => t && !['AND','OR','IS','NOT','NULL','IN','CHAR_LENGTH','JSON_TYPE','JSON_LENGTH'].includes(t))) {
    throw new Error('Unsupported V9 check expression');
  }
}
function validateTrigger(body) {
  if (!Array.isArray(body) || body.length > 16 || body.some(line => typeof line !== 'string')) {
    throw new Error('Invalid V9 domain trigger');
  }
  const sql = body.join('\n');
  if (sql.length > 24000) throw new Error('Oversized V9 domain trigger');
  const tokens = [];
  const token = /\s+|'(?:[^'\\]|\\.|'')*'|<=>|[().=+;]|`[a-z][a-z0-9_]*`|[a-zA-Z_][a-zA-Z0-9_]*|1/gy;
  let offset = 0;
  while (offset < sql.length) {
    token.lastIndex = offset;
    const match = token.exec(sql);
    if (!match) throw new Error('Unsupported V9 trigger token');
    offset = token.lastIndex;
    if (!/^\s+$/.test(match[0])) tokens.push(match[0]);
  }
  let at = 0;
  let depth = 0;
  const peek = value => tokens[at]?.toUpperCase() === value;
  const take = value => {
    if (!peek(value)) throw new Error('Unsupported V9 trigger grammar: expected ' + value);
    return tokens[at++];
  };
  const literal = () => {
    const value = tokens[at++];
    if (!value?.startsWith("'") || value.length > 256) throw new Error('Invalid V9 trigger literal');
    return value;
  };
  const identifier = () => {
    const value = tokens[at++];
    if (!value || !/^(?:[a-zA-Z_][a-zA-Z0-9_]*|`[a-z][a-z0-9_]*`)$/.test(value)) {
      throw new Error('Invalid V9 trigger identifier');
    }
    return value.replaceAll('`', '');
  };
  const scalar = aliases => {
    if (tokens[at]?.startsWith("'")) return literal();
    if (peek('1')) return take('1');
    const owner = identifier();
    take('.');
    const column = identifier();
    const table = ['OLD', 'NEW'].includes(owner.toUpperCase())
      ? 'bty_source_observation' : aliases[owner];
    if (!table || !columns[table].includes(column)) throw new Error('Unknown V9 trigger reference');
    if (peek('+')) { take('+'); take('1'); }
  };
  const atom = aliases => {
    if (++depth > 16) throw new Error('Nested V9 trigger expression');
    try {
      if (peek('(')) {
        take('('); expression(aliases); take(')');
      } else if (peek('EXISTS')) {
        take('EXISTS'); take('('); take('SELECT'); take('1'); take('FROM');
        const table = identifier();
        if (!['bty_trade_binding', 'bty_source_allocation_guard'].includes(table)) {
          throw new Error('Foreign V9 trigger source');
        }
        const alias = identifier();
        if (!['b', 'g'].includes(alias)) throw new Error('Unsupported V9 trigger alias');
        take('WHERE'); expression({...aliases, [alias]: table}); take(')');
      } else {
        scalar(aliases);
        if (peek('IS')) { take('IS'); take('NULL'); }
        else if (peek('=')) { take('='); scalar(aliases); }
        else { take('<=>'); scalar(aliases); }
      }
    } finally { depth--; }
  };
  const negative = aliases => {
    if (peek('NOT')) { take('NOT'); atom(aliases); }
    else atom(aliases);
  };
  const conjunction = aliases => {
    negative(aliases);
    while (peek('AND')) { take('AND'); negative(aliases); }
  };
  const expression = aliases => {
    conjunction(aliases);
    while (peek('OR')) { take('OR'); conjunction(aliases); }
  };
  take('BEGIN');
  for (let guard = 0; guard < 2; guard++) {
    take('IF'); expression({}); take('THEN');
    take('SIGNAL'); take('SQLSTATE');
    if (literal() !== "'45000'") throw new Error('Unsupported V9 trigger signal');
    take('SET'); take('MESSAGE_TEXT'); take('='); literal(); take(';');
    take('END'); take('IF'); take(';');
  }
  take('END');
  if (at !== tokens.length) throw new Error('Unexpected V9 trigger statement');
}

/** Only the reviewed three local source tables and domain trigger belong to this migration. */
export function renderManualSourceSchema(design) {
  const tables = ['bty_source_observation', 'bty_trade_binding', 'bty_source_allocation_guard'];
  if (design.version !== 9 || JSON.stringify(design.alterations?.map(x => x.table)) !== JSON.stringify(tables)
      || design.trigger?.name !== 'trg_bty_source_observation_domain_guard'
      || design.trigger.table !== tables[0] || design.trigger.timing !== 'BEFORE UPDATE') {
    throw new Error('V9 owns only the reviewed manual source domain');
  }
  const statements = design.alterations.map(({table, clauses}) => {
    if (!Array.isArray(clauses) || !clauses.length) throw new Error('Empty V9 alteration');
    for (const clause of clauses) validateClause(table, clause);
    return 'ALTER TABLE `' + table + '`\n    ' + clauses.join(',\n    ') + ';';
  });
  const body = design.trigger.body;
  validateTrigger(body);
  return '-- Beauty V9: separate manual source observation; V1-V8 remain frozen.\n'
    + '-- Old financial inserts keep LEGACY_FUNDED defaults; no historical source relabeling.\n\n'
    + statements.join('\n\n') + '\n\nDELIMITER $$\nCREATE TRIGGER `' + design.trigger.name
    + '` BEFORE UPDATE ON `bty_source_observation` FOR EACH ROW\n' + body.join('\n')
    + '$$\nDELIMITER ;\n';
}

if (process.argv[1] && fileURLToPath(import.meta.url) === fs.realpathSync(process.argv[1])) {
  const design = JSON.parse(fs.readFileSync(new URL(
    '../docs/technical/design/schema/beauty-manual-source-observation-schema.json', import.meta.url)));
  const sql = renderManualSourceSchema(design);
  const file = fileURLToPath(new URL('../backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/'
    + 'db/industry/beauty/V9__beauty_manual_source_observation.sql', import.meta.url));
  if (process.argv.includes('--check')) {
    if (fs.readFileSync(file, 'utf8') !== sql) throw new Error('Frozen V9 drift');
  } else writeFrozenMigration(file, sql);
  console.log('Beauty V9 manual source migration verified');
}
