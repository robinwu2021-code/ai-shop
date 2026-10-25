import fs from 'node:fs';
import path from 'node:path';

// Source discovery only: never connects to a database, compiles Java or executes migrations.
const read = file => fs.readFileSync(file, 'utf8');
const walk = dir => fs.existsSync(dir) ? fs.readdirSync(dir, {withFileTypes: true})
  .flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]) : [];
const relative = (root, file) => path.relative(root, file).replaceAll('\\', '/');
const compact = text => text.replace(/\s+/g, ' ').trim();
function split(text, separator = ',', angles = false) {
  const parts = []; let start = 0, depth = 0, quote = null;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) { if (c === quote && text[i - 1] !== '\\') quote = null; continue; }
    if (c === '"' || c === "'" || c === '`') { quote = c; continue; }
    if ('([{'.includes(c) || (angles && c === '<')) depth++;
    if (')]}'.includes(c) || (angles && c === '>')) depth--;
    if (c === separator && depth === 0) { parts.push(text.slice(start, i)); start = i + 1; }
  }
  return [...parts, text.slice(start)].map(x => x.trim()).filter(Boolean);
}
function balanced(text, open) {
  let depth = 0, quote = null;
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (quote) { if (c === quote && text[i - 1] !== '\\') quote = null; continue; }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (c === '(') depth++;
    if (c === ')' && --depth === 0) return i;
  }
  throw Error('Unclosed source declaration at ' + open);
}
function column(declaration, version) {
  const m = declaration.match(/^`?([a-z][a-z0-9_]*)`?\s+([a-z]+(?:\s*\([\d, ]+\))?)/i);
  if (!m || /^(primary|unique|key|constraint|check|index|foreign)$/i.test(m[1])) return null;
  const comment = declaration.match(/COMMENT\s+'((?:[^']|'')*)'/i);
  return {name: m[1], type: m[2].toLowerCase().replaceAll(' ', ''),
    label: comment ? comment[1].replaceAll("''", "'") : m[1], nullable: !/\bNOT\s+NULL\b|\bPRIMARY\s+KEY\b/i.test(declaration),
    ref: null, introducedIn: 'V' + version, sqlDeclaration: compact(declaration)};
}
const keyColumns = value => split(value).map(x => x.trim().replaceAll('`', '').replace(/\(\d+\)$/, ''));
function migration(file, root, owner) {
  const name = path.basename(file), version = Number(name.match(/^V(\d+)__/)[1]);
  const sql = read(file).replace(/^\s*--.*$/gm, '');
  const changes = [];
  for (const statement of split(sql, ';')) {
    const create = statement.match(/CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([\w]+)`?\s*\(/i);
    if (create) {
      const start = statement.indexOf('(', create.index), end = balanced(statement, start);
      const declarations = split(statement.slice(start + 1, end));
      const fields = declarations.map(d => column(d, version)).filter(Boolean);
      const unique = declarations.flatMap(d => { const m = d.match(/^UNIQUE\s+(?:KEY|INDEX)\s+`?\w+`?\s*\((.*)\)/i); return m ? [keyColumns(m[1])] : []; });
      const primary = declarations.find(d => /^PRIMARY\s+KEY/i.test(d))?.match(/\((.*)\)/)?.[1];
      changes.push({action: 'CREATE_TABLE', table: create[1], fields, unique,
        primaryKey: primary ? keyColumns(primary) : fields.filter(f => /PRIMARY\s+KEY/i.test(f.sqlDeclaration)).map(f => f.name)});
      continue;
    }
    const alter = statement.match(/ALTER\s+TABLE\s+`?(\w+)`?\s+([\s\S]*)/i);
    if (!alter) continue;
    for (const part of split(alter[2])) {
      const added = part.match(/^(ADD|MODIFY|CHANGE)\s+(?:COLUMN\s+)?(?!UNIQUE\b|KEY\b|INDEX\b|CONSTRAINT\b)([\s\S]*)/i);
      if (added) { const field = column(added[2], version); if (field) changes.push({action: added[1].toUpperCase() === 'ADD' ? 'ADD_COLUMN' : 'MODIFY_COLUMN', table: alter[1], field}); }
      const unique = part.match(/^ADD\s+UNIQUE\s+(?:KEY|INDEX)\s+`?\w+`?\s*\((.*)\)/i);
      if (unique) changes.push({action: 'ADD_UNIQUE', table: alter[1], columns: keyColumns(unique[1])});
    }
  }
  return {owner, version, file: relative(root, file), status: owner === 'FOOD' && version <= 15 ? 'CLOUD_BASELINE_V15' : 'SOURCE_AVAILABLE_PENDING_DEPLOYMENT', changes};
}
function javaSource(file, root) {
  const source = read(file), text = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  const pkg = text.match(/\bpackage\s+([\w.]+);/)?.[1];
  const name = path.basename(file, '.java'), qualifiedName = pkg + '.' + name;
  const imports = Object.fromEntries([...text.matchAll(/\bimport\s+(?!static)([\w.]+);/g)].map(m => [m[1].split('.').at(-1), m[1]]));
  const methods = [];
  let previousMethod = Math.max(0, text.search(/\b(?:class|interface)\s/));
  const pattern = /(?:\bpublic\s+|^ {4}(?=\S))(?:(?:static|default|final|synchronized|abstract)\s+)*((?!return\b|throw\b|new\b|record\b|class\b|interface\b|enum\b)[\w.$<>?,\[\] ]+?)\s+(\w+)\s*\(/gm;
  for (const m of text.matchAll(pattern)) {
    const open = m.index + m[0].lastIndexOf('('), end = balanced(text, open);
    const tail = text.slice(end + 1).match(/^\s*(?:throws\s+[\w., ]+)?\s*([{;])/);
    if (!tail) continue;
    if (!m[0].trimStart().startsWith('public ') && !/public\s+interface\s/.test(text) && !name.endsWith('Mappers')) continue;
    const parameters = split(text.slice(open + 1, end), ',', true).map(p => {
      const clean = compact(p.replace(/@[\w.]+(?:\([^)]*\))?/g, '').replace(/\bfinal\s+/g, ''));
      const pm = clean.match(/^(.*?)\s+(\w+)$/);
      return {name: pm?.[2] || clean, type: pm?.[1] || '', declaration: compact(p),
        binding: p.match(/@(RequestBody|RequestParam|PathVariable|RequestHeader|ModelAttribute)\b/)?.[1] || 'CONTEXT_OR_DIRECT'};
    });
    const prefix = text.slice(previousMethod, m.index);
    previousMethod = end + 1;
    const annotations = [];
    for (const a of prefix.matchAll(/@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*\(/g)) {
      const at = a.index + a[0].lastIndexOf('('), last = balanced(prefix, at);
      annotations.push({kind: a[1], expression: prefix.slice(at + 1, last)});
    }
    if (/^(?:private|protected|record|class|interface|enum)\b/.test(m[1].trim())) continue;
    const securityAnnotations = [...prefix.matchAll(/@(PreAuthorize|Secured)\s*\(/g)].map(a => { const open = a.index + a[0].lastIndexOf('('); return '@' + a[1] + '(' + compact(prefix.slice(open + 1, balanced(prefix, open))) + ')'; });
    methods.push({securityAnnotations, name: m[2], returnType: compact(m[1]).replace(/^(?:(?:public|static|default|final|synchronized|abstract)\s+)+/, ''), parameters, annotations,
      declaration: compact(text.slice(m.index, end + 1)), sourceLine: source.slice(0, source.indexOf(m[0])).split('\n').length});
  }
  const records = [];
  for (const m of text.matchAll(/\brecord\s+(\w+)(?:\s*<[^>]+>)?\s*\(/g)) {
    const open = m.index + m[0].lastIndexOf('('), end = balanced(text, open);
    records.push({name: m[1], fields: split(text.slice(open + 1, end), ',', true).map(compact)});
  }
  return {qualifiedName, name, package: pkg, sourceFile: relative(root, file), source: text, imports, methods, records};
}
export function discoverFoodSource(root) {
  const foodFiles = walk(path.join(root, 'backend-plugins/shop-industry-food')).filter(f => !f.includes('/target/'));
  const backendFiles = walk(path.join(root, 'backend')).filter(f => !f.includes('/target/') && !f.includes('/node_modules/'));
  const migrations = [...foodFiles.filter(f => /\/V\d+__[^/]+\.sql$/.test(f) && f.includes('/db/industry/food/')).map(f => migration(f, root, 'FOOD')),
    ...backendFiles.filter(f => /\/V(?:577|578|579|580|581)__[^/]+\.sql$/.test(f)).map(f => migration(f, root, 'CORE')),
    ...backendFiles.filter(f => /\/V14__exact_demand_plan\.sql$/.test(f)).map(f => migration(f, root, 'INVENTORY'))]
    .sort((a, b) => a.owner.localeCompare(b.owner) || a.version - b.version || a.file.localeCompare(b.file));
  for (const owner of ['FOOD', 'CORE', 'INVENTORY']) {
    const versions = migrations.filter(m => m.owner === owner).map(m => m.version);
    if (versions.length !== new Set(versions).size) throw Error('Duplicate migration version: ' + owner);
  }
  const sharedTableNames = new Set(migrations.filter(m => m.owner !== 'FOOD').flatMap(m => m.changes.map(c => c.table)));
  const sharedEntityNames = new Set([...sharedTableNames].map(t => t.split('_').map(x => x[0].toUpperCase() + x.slice(1)).join('')));
  const sharedJava = backendFiles.filter(f => f.endsWith('.java') && f.includes('/src/main/java/') &&
    (/\/printing\//.test(f) || /Industry|InventoryProduction|InventoryDemandPlan|PricingFee/.test(path.basename(f)) || sharedEntityNames.has(path.basename(f, '.java'))) && !/\/pay\//.test(f));
  const classes = [...foodFiles.filter(f => f.endsWith('.java') && f.includes('/src/main/java/')), ...sharedJava]
    .sort().map(f => javaSource(f, root));
  const constants = new Map();
  for (const c of classes) for (const m of c.source.matchAll(/\bString\s+(\w+)\s*=\s*"([^"]*)"\s*;/g)) constants.set(c.name + '.' + m[1], m[2]);
  const resolve = (expression, cls) => {
    const value = expression.replace(/^\s*(?:value|path)\s*=\s*/, '').split(/,\s*(?:produces|consumes|method)\s*=/)[0].trim();
    return split(value, '+').map(p => /^"[^"]*"$/.test(p) ? p.slice(1, -1) : constants.get(p) ?? constants.get(cls.name + '.' + p) ?? '${' + p + '}').join('');
  };
  const endpoints = [];
  for (const c of classes.filter(c => c.name.endsWith('Controller'))) {
    const before = c.source.slice(0, c.source.search(/\b(?:public\s+)?class\s/));
    const mapping = before.match(/@RequestMapping\s*\(/);
    let prefix = '';
    if (mapping) { const open = mapping.index + mapping[0].lastIndexOf('('); prefix = resolve(before.slice(open + 1, balanced(before, open)), c); }
    for (const m of c.methods) for (const a of m.annotations) {
      const method = a.kind === 'RequestMapping' ? a.expression.match(/RequestMethod\.(\w+)/)?.[1] || 'REQUEST' : a.kind.replace('Mapping', '').toUpperCase();
      const route = prefix + resolve(a.expression, c);
      endpoints.push({method, path: route, realm: route.startsWith('/mp/') ? 'mp' : route.startsWith('/ops/') ? 'ops' : route.startsWith('/internal/') ? 'internal' : 'biz',
        controllerClass: c.qualifiedName, handler: m.name, sourceFile: c.sourceFile, sourceLine: m.sourceLine,
        parameters: m.parameters.map(p => ({...p, qualifiedType: c.imports[p.type.split(/[<.]/)[0]] || p.type})), securityAnnotations: m.securityAnnotations, responseType: m.returnType, status: 'SOURCE_PRESENT_PENDING_CURRENT_BATCH_VERIFICATION'});
    }
  }
  const tables = [];
  for (const m of migrations.filter(m => m.owner === 'FOOD')) for (const change of m.changes) {
    if (change.action !== 'CREATE_TABLE') continue;
    const entity = classes.find(c => c.source.match(new RegExp('@TableName\\s*\\(\\s*"' + change.table + '"')));
    const mappers = classes.flatMap(c => {
      if (/Mapper$/.test(c.name) && c.source.includes(change.table)) return [c.qualifiedName];
      if (!entity) return [];
      return [...c.source.matchAll(new RegExp('interface\\s+(\\w+)\\s+extends\\s+BaseMapper\\s*<\\s*' + entity.name + '\\s*>', 'g'))].map(m => c.qualifiedName + '.' + m[1]);
    });
    const serviceClasses = classes.filter(c => /ServiceImpl$/.test(c.name) && mappers.some(m => c.source.includes(m))).map(c => c.qualifiedName.replace('.service.impl.', '.service.').replace(/Impl$/, ''));
    tables.push({table: change.table, introducedIn: 'V' + m.version, migrationFile: m.file,
      entityClass: entity?.qualifiedName || null, entityFile: entity?.sourceFile || null, mapperClasses: mappers, serviceClasses,
      persistenceStyle: entity ? 'MYBATIS_ENTITY' : mappers.length ? 'EXPLICIT_SQL_NO_BASE_ENTITY_ASSUMPTION' : 'SOURCE_TABLE_MAPPING_REVIEW_REQUIRED'});
  }
  const sharedPersistence = [...sharedTableNames].sort().map(table => {
    const entity = classes.find(c => c.source.match(new RegExp('@TableName\\s*\\(\\s*"' + table + '"')));
    return {table, entityClass: entity?.qualifiedName || null, entityFile: entity?.sourceFile || null,
      mapperClasses: classes.filter(c => /Mapper$/.test(c.name) && c.source.includes(table)).map(c => c.qualifiedName),
      status:'ORIGINAL_OWNER_SOURCE_PRESENT_PENDING_CURRENT_BATCH_VERIFICATION'};
  });
  return {sharedPersistence, status: 'SOURCE_PRESENT_PENDING_CURRENT_BATCH_VERIFICATION', discovery: 'STATIC_SOURCE_ONLY',
    baseline: {foodVersion: 15, foodBusinessTableCount: 83, foodIncludingHistoryTableCount: 84, historyTable: 'fnb_flyway_history'},
    targetVersions: {food: 25, core: 581, inventory: 14}, migrations, endpoints: endpoints.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method)),
    classes: classes.map(({source, imports, methods, ...c}) => ({...c, methods: methods.map(({name, declaration, sourceLine}) => ({name, declaration, sourceLine}))})), persistence: tables};
}
const moduleFor = name => /identity|dining_device|registered_device|device_session/.test(name) ? 'MOD-07' : /kds|voice|station_runtime/.test(name) ? 'MOD-07'
  : /return_amendment/.test(name) ? 'MOD-06' : /delivery|party|amendment|fee_|scheduled_dispatch/.test(name) ? 'MOD-03' : /production/.test(name) ? 'MOD-04'
  : /organization|service_form|attendee/.test(name) ? 'MOD-11' : /close/.test(name) ? 'MOD-09' : 'MOD-01';
export function reconcileFoodSource(root, s, plan) {
  const actual = discoverFoodSource(root);
  const byName = new Map(s.tables.map(t => [t.name, t]));
  for (const migration of actual.migrations.filter(m => m.owner === 'FOOD')) for (const change of migration.changes) {
    let table = byName.get(change.table);
    if (change.action === 'CREATE_TABLE') {
      if (!table) {
        const moduleId = moduleFor(change.table), domain = moduleId === 'MOD-07' ? 'H' : moduleId === 'MOD-03' ? 'D' : moduleId === 'MOD-04' ? 'E' : moduleId === 'MOD-11' ? 'L' : moduleId === 'MOD-09' ? 'I' : 'C';
        table = {name: change.table, label: change.table, existing: false, fields: change.fields, unique: change.unique, domain,
          rules: ['按实际追加迁移与Service守卫实施；本批未编译、测试或部署。'], deployment: 'MIGRATION_V' + migration.version};
        s.tables.push(table); byName.set(table.name, table);
        s.businessModules.find(m => m.id === moduleId).tables.push(table.name);
        const mapping = actual.persistence.find(p => p.table === table.name);
        plan.tableOwners[table.name] = {moduleId, writeService: mapping?.serviceClasses[0]?.split('.').at(-1) || 'BusinessOperationsService', customMapperMethods: [],
          ...(mapping?.mapperClasses[0] ? {mapperClass: mapping.mapperClasses[0]} : {}), status: 'SOURCE_PRESENT_PENDING_CURRENT_BATCH_VERIFICATION'};
      }
      for (const field of change.fields) { const existing = table.fields.find(f => f.name === field.name); if (existing) existing.introducedIn = 'V' + migration.version; }
      table.introducedIn = 'V' + migration.version;
      table.migrationFile = migration.file;
      table.primaryKey = change.primaryKey;
      if (migration.version >= 16) {
        table.physicalFields = true;
        table.usesBaseEntity = Boolean(actual.persistence.find(p => p.table === table.name)?.entityClass);
        // Full physical declarations prevent invented BaseEntity columns for JDBC-owned tables.
        table.fields = change.fields.map(f => ({...table.fields.find(old => old.name === f.name), ...f}));
        table.unique = change.unique;
      }
    } else if (table && ['ADD_COLUMN', 'MODIFY_COLUMN'].includes(change.action)) {
      const index = table.fields.findIndex(f => f.name === change.field.name);
      if (index < 0) table.fields.push(change.field);
      else if (migration.version >= 16) {
        const old = table.fields[index];
        table.fields[index] = {...old, ...change.field, label: old.label,
          introducedIn: old.introducedIn || (change.action === 'MODIFY_COLUMN' ? table.introducedIn : change.field.introducedIn),
          ...(change.action === 'MODIFY_COLUMN' ? {modifiedIn: 'V' + migration.version} : {})};
      } else if (change.action === 'ADD_COLUMN' && !table.fields[index].introducedIn) table.fields[index].introducedIn = 'V' + migration.version;
      else if (change.action === 'MODIFY_COLUMN') table.fields[index].modifiedIn = 'V' + migration.version;
    } else if (table && change.action === 'ADD_UNIQUE' && !table.unique.some(k => JSON.stringify(k) === JSON.stringify(change.columns))) table.unique.push(change.columns);
  }
  for (const table of s.tables) {
    const mapping = actual.persistence.find(p => p.table === table.name);
    if (plan.tableOwners[table.name]?.status) {
      const owners = {
        fnb_dining_device:'DiningIdentityService', fnb_identity_authorization:'DiningIdentityService', fnb_identity_operation:'DiningIdentityService',
        fnb_check_freeze:'CheckBusinessGuardService', fnb_check_line_membership:'CheckCollaborationService',
        fnb_reservation_preorder:'ReservationPreorderService', fnb_service_slot:'ServiceSlotService', fnb_capacity_claim:'ServiceSlotService',
        fnb_service_handover:'CheckCollaborationService', fnb_service_handover_item:'CheckCollaborationService', fnb_service_responsibility:'CheckCollaborationService',
        fnb_operation_case:'BusinessOperationsService', fnb_business_command:'BusinessOperationsService',
        fnb_scheduled_dispatch:'ScheduledDiningDispatchService', fnb_queue_notice:'QueueNotificationService',
        fnb_kds_cursor:'FoodKdsService', fnb_kds_subscription:'FoodKdsService', fnb_kds_event:'FoodKdsService',
        fnb_station_runtime:'FoodStationRuntimeService', fnb_kds_station_runtime:'FoodStationRuntimeService', fnb_kds_voice_command:'FoodStationRuntimeService',
        fnb_return_amendment:'BusinessReturnService', fnb_fee_source_binding:'FoodFeeSourceService', fnb_cart_delivery:'DiningDeliveryService',
        fnb_business_organization:'ServiceFormService', fnb_service_form:'ServiceFormService', fnb_service_attendee:'ServiceFormService', fnb_service_form_batch:'ServiceFormService'
      };
      owners.fnb_fee_contract_claim = 'FoodFeeSourceService';
      if (owners[table.name]) plan.tableOwners[table.name].writeService = owners[table.name];
      const moduleId = moduleFor(table.name);
      plan.tableOwners[table.name].moduleId = moduleId;
      table.domain = {'MOD-07':'H','MOD-03':'D','MOD-04':'E','MOD-06':'G','MOD-11':'L','MOD-09':'I'}[moduleId] || 'C';
      for (const module of s.businessModules) module.tables = module.tables.filter(name => name !== table.name);
      s.businessModules.find(module => module.id === moduleId).tables.push(table.name);
    }
    if (mapping) table.sourceImplementation = mapping;
    if (Number(table.introducedIn?.slice(1)) >= 16) table.implementationStatus = 'CODED_PENDING_VERIFICATION';
  }
  for (const table of s.tables.filter(t => t.name.startsWith('rpt_'))) { table.deployment = 'PLANNED'; table.implementationStatus = 'DEFERRED_SHARED_REPORT_OWNER'; }
  const tableRank = t => t.name.startsWith('rpt_') ? 3 : t.deployment === 'PLANNED' ? 2 : 0;
  s.tables.sort((a, b) => tableRank(a) - tableRank(b));
  for (const domain of s.domains) domain.tables = s.tables.filter(t => t.domain === domain.code).map(t => t.name);
  s.catalogEnhancement.targetBusinessTableCount = s.tables.filter(t => t.name.startsWith('fnb_')).length;
  s.databaseInitialization.targetSchemaVersion = actual.targetVersions.food;
  s.databaseInitialization.cloudBaseline = actual.baseline;
  s.databaseInitialization.currentSourceTarget = {...actual.targetVersions, status: s.deliveryVerification?.status || 'NOT_COMPILED_TESTED_OR_DEPLOYED'};
  s.databaseInitialization.additionalMigrations = actual.migrations.filter(m => m.owner === 'FOOD' && m.version > 1).map(m => m.file);
  const normalize = value => value.replace(/\{[^}]+\}/g, '{}');
  for (const endpoint of actual.endpoints) endpoint.targetApiIds = s.apis.filter(a => a.method === endpoint.method && normalize(a.path) === normalize(endpoint.path)).map(a => a.id);
  for (const api of s.apis) {
    const matches = actual.endpoints.filter(e => e.targetApiIds.includes(api.id));
    api.sourceImplementation = {status: matches.length ? 'EXACT_ROUTE_SOURCE_PRESENT_PENDING_CURRENT_BATCH_VERIFICATION' : 'TARGET_WITHOUT_EXACT_ROUTE_MATCH',
      actualEntries: matches.map(e => ({controllerClass: e.controllerClass, handler: e.handler, sourceFile: e.sourceFile}))};
  }
  s.sourceImplementation = actual;
  s.sharedPersistence = actual.sharedPersistence;
  s.sharedSchemaChanges = actual.migrations.filter(m => m.owner !== 'FOOD');
  s.reviewDate = '2026-10-25';
  return actual;
}
export function renderActualFoodSource(actual, section = 'api') {
  const cell = x => String(x ?? '—').replaceAll('|', '\\|').replaceAll('\n', ' ');
  let md = '## 当前源码实现清单（待集中编译与验证）\n\n云端基线为 FOOD V15，83 张业务表加 fnb_flyway_history 共84张；当前源码目标 FOOD V25 / CORE V581 / INVENTORY V14。实际三端注册数量以本节路由为准，未精确匹配的原ops专项目标继续保留规划。实际共享内部入口按原owner列出。本节仅静态读取源码，不表示测试通过、迁移已执行或已部署。后文目标规划及历史验收记录分别保留。\n\n';
  if (section === 'api') {
  md += '| 端 | HTTP | 实际路径 | Controller方法 | 参数声明 | 返回类型 | 权限声明（源码） |\n|---|---|---|---|---|---|---|\n';
  for (const e of actual.endpoints) md += `| ${e.realm} | ${e.method} | ${cell(e.path)} | ${cell(e.controllerClass + '.' + e.handler)} | ${cell(e.parameters.map(p => p.declaration).join('; '))} | ${cell(e.responseType)} | ${cell(e.securityAnnotations.join('; '))} |\n`;
  }
  if (section === 'code' || section === 'method') {
  md += '\n### 实际类与方法\n\n| 源码 | 类 | 方法声明（源码） |\n|---|---|---|\n';
  for (const c of actual.classes) if (c.methods.length) md += `| ${cell(c.sourceFile)} | ${cell(c.qualifiedName)} | ${section === 'method' ? cell(c.methods.map(m => m.declaration).join('<br>')) : cell(c.methods.map(m => m.name).join(', '))} |\n`;
  }
  if (section === 'api') {
  md += '\n### 实际record DTO字段（原声明，含校验注解）\n\n| 源码类 | record | 字段声明 |\n|---|---|---|\n';
  for (const c of actual.classes) for (const r of c.records) md += `| ${cell(c.qualifiedName)} | ${r.name} | ${cell(r.fields.join('<br>'))} |\n`;
  }
  return md + '\n## 保留的目标规划及历史记录\n\n';
}

export function foodSourceProjection(actual, section) {
  const summary = {status: actual.status, discovery: actual.discovery,
    canonicalSource: 'food-system-design.json#/sourceImplementation', baseline: actual.baseline,
    targetVersions: actual.targetVersions};
  if (section === 'method') return {...summary, classes: actual.classes.filter(c => c.methods.length).map(({records, ...c}) => c)};
  return {...summary, endpoints: actual.endpoints, persistence: actual.persistence};
}
