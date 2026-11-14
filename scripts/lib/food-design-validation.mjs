// Structural design guard; this does not prove business implementation or SQL concurrency safety.
export function validateFoodDesign(s) {
  const must = (ok, message) => { if (!ok) throw new Error(message); };
  const unique = (items, label) => must(new Set(items).size === items.length, `Duplicate ${label}`);
  unique(s.tables.map(t => t.name), 'table');
  unique(s.apis.map(a => a.id), 'API ID');
  unique(s.apis.map(a => a.method + ' ' + a.path.replace(/\{[^}]+\}/g, '{}')), 'route');
  const tables = new Map(s.tables.map(t => [t.name, t]));
  const common = s.commonFields.map(f => f[0]);
  for (const t of s.tables) {
    unique(t.fields.map(f => f.name), `${t.name} field`);
    const names = new Set([...common, ...t.fields.map(f => f.name)]);
    for (const key of t.unique) for (const name of key) must(names.has(name), `Unknown key ${t.name}.${name}`);
    for (const f of t.fields) if (f.ref) {
      const [table, column] = f.ref.split('.');
      if (tables.has(table)) must([...common, ...tables.get(table).fields.map(f => f.name)].includes(column), `Unknown reference ${f.ref}`);
    }
  }
  const expected = {
    fnb_menu_store: ['menu_no', 'store_no'],
    fnb_menu_item_sku: ['menu_item_no', 'sku_no'],
    fnb_dish_option_group: ['goods_no', 'group_no'],
    fnb_sku_station: ['store_no', 'sku_no', 'station_no', 'task_role'],
  };
  for (const [name, key] of Object.entries(expected)) {
    must(tables.get(name)?.unique.some(k => JSON.stringify(k) === JSON.stringify(key)), `Cardinality key missing ${name}`);
    must(!tables.get(name).unique.some(k => k.length < key.length && k.every(n => key.includes(n))), `Parent-only unique key ${name}`);
  }
  for (const name of ['fnb_kitchen_task', 'fnb_settlement_item']) {
    const t = tables.get(name);
    const fields = new Map(t.fields.map(f => [f.name, f]));
    for (const key of t.unique) must(key.every(n => !fields.get(n)?.nullable), `Nullable dedup key ${name}`);
  }
  const primitives = new Set(['string', 'int32', 'int64', 'decimal', 'boolean', 'datetime', 'date', 'object']);
  const validateFields = (value, label) => {
    const parts = value ? value.split(';') : [];
    unique(parts.map(v => v.split(':')[0].replace(/\?$/, '')), `${label} parameter`);
    for (const part of parts) {
      const [name, type, ...desc] = part.split(':');
      must(name && type && desc.join(':'), `Incomplete parameter ${label}.${name}`);
      const base = type.replace(/\[\]/g, '');
      must(primitives.has(base) || Object.hasOwn(s.dtos, base), `Unknown DTO ${label}.${base}`);
    }
  };
  for (const [name, fields] of Object.entries(s.dtos)) validateFields(fields, name);
  for (const a of s.apis) {
    validateFields(a.input, a.id + ' input'); validateFields(a.output, a.id + ' output');
    const path = [...a.path.matchAll(/\{([^}]+)\}/g)].map(m => m[1]);
    const body = (a.input || '').split(';').map(v => v.split(':')[0].replace(/\?$/, ''));
    must(path.every(n => !body.includes(n)), `Path/body duplicate ${a.id}`);
  }
  const ids = new Set(s.apis.map(a => a.id));
  const requirements = s.domains.flatMap(d => d.requirements);
  unique(requirements, 'domain requirement');
  for (const d of s.domains) {
    must(JSON.stringify(d.tables) === JSON.stringify(s.tables.filter(t => t.domain === d.code).map(t => t.name)), `Stale domain tables ${d.code}`);
    must(JSON.stringify(d.apis) === JSON.stringify(s.apis.filter(a => a.domain === d.code).map(a => a.id)), `Stale domain APIs ${d.code}`);
  }
  const moduleRequirements = s.businessModules.flatMap(m => m.requirements);
  unique(moduleRequirements, 'module requirement');
  must(requirements.length === moduleRequirements.length && requirements.every(r => moduleRequirements.includes(r)), 'Incomplete module coverage');
  unique(s.requirementAlignment.map(r => r.requirementId), 'alignment requirement');
  must(s.requirementAlignment.length === requirements.length, 'Incomplete requirement alignment');
  for (const row of s.requirementAlignment) {
    const module = s.businessModules.find(m => m.id === row.moduleId);
    must(module?.requirements.includes(row.requirementId), `Wrong requirement owner ${row.requirementId}`);
    must(['DEFINED', 'CORE_CONTRACT', 'DESIGN_GAP'].includes(row.status), `Unknown alignment status ${row.requirementId}`);
    must(row.note, `Missing alignment conclusion ${row.requirementId}`);
    for (const api of row.apiIds) must(ids.has(api), `Unknown alignment API ${api}`);
    for (const table of row.tables) must(tables.has(table), `Unknown alignment table ${table}`);
  }
  if (s.catalogEnhancement) {
    const e = s.catalogEnhancement;
    const hasKey = (name, key) => tables.get(name)?.unique.some(k => k.join(',') === key);
    const reviewedKeys = {
      fnb_menu_context: ['context_no', 'menu_no,order_source,fulfillment,channel_scope_key'],
      fnb_external_mapping: ['mapping_no', 'provider,store_no,channel_scope_key,object_type,external_no,mapping_revision_no'],
    };
    for (const [name, keys] of Object.entries(reviewedKeys))
      must(tables.get(name)?.unique.length === keys.length && keys.every(k => hasKey(name, k)),
        `Unreviewed channel scope or identity constraint ${name}`);
    must(hasKey('fnb_recipe_line', 'recipe_no,line_no')
      && !hasKey('fnb_recipe_line', 'recipe_no,item_ref'), 'Recipe line identity must preserve repeated materials');
    must(tables.get('fnb_modifier')?.fields.find(f => f.name === 'kind')?.label === '类型 ADDON / COMBO；免费口味复用SPEC_VALUE', 'Unreviewed modifier contract label');
    must(hasKey('fnb_menu_context', 'menu_no,order_source,fulfillment,channel_scope_key'), 'Missing channel scope cardinality');
    must(tables.get('fnb_menu_context').fields.some(f => f.name === 'channel_scope_key' && !f.nullable), 'Nullable channel scope');
    must(hasKey('fnb_external_mapping', 'provider,store_no,channel_scope_key,object_type,external_no,mapping_revision_no'), 'Missing immutable channel mapping scope');
    must(e.plannedTables.length === 9 && new Set(e.plannedTables).size === 9, 'Incomplete planned enhancement tables');
    const migrated=new Set(e.migrationV4Tables||[]);
    must(migrated.size===2 && migrated.has('fnb_recipe') && migrated.has('fnb_recipe_line'), 'Invalid recipe V4 scope');
    const channels=new Set(e.migrationV7Tables||[]);
    must(channels.size===1 && channels.has('fnb_menu_channel_binding'), 'Invalid menu V7 scope');
    const composed=new Set(e.migrationV6Tables||[]);
    must(composed.size===1 && composed.has('fnb_menu_item_option_group'), 'Invalid menu V6 scope');
    const adjusted=new Set(e.migrationV5Tables||[]);
    must(adjusted.size===1 && adjusted.has('fnb_recipe_adjustment'), 'Invalid recipe V5 scope');
    const production=new Set(e.migrationV9Tables||[]);
    must(production.size===2 && production.has('fnb_production_consumption')
      && production.has('fnb_production_consumption_line'), 'Invalid production V9 scope');
    for (const name of e.plannedTables)
      must(tables.get(name)?.deployment === (migrated.has(name)?'MIGRATION_V4':adjusted.has(name)?'MIGRATION_V5':composed.has(name)?'MIGRATION_V6':channels.has(name)?'MIGRATION_V7':production.has(name)?'MIGRATION_V9':'PLANNED'), `Unverified deployment ${name}`);
    must(s.tables.filter(t => t.name.startsWith('fnb_')).length === e.targetBusinessTableCount, 'Stale target table count');
    for (const name of ['fnb_recipe_line', 'fnb_recipe_adjustment'])
      must(tables.get(name)?.fields.some(f => f.name === 'qty' && f.type === 'decimal(18,6)'), 'Recipe quantity precision differs');
    const ready = ['pricing', 'inventory'].every(name => e.contracts?.[name]?.implementation === 'VERIFIED');
    must(!e.runtimeEnabled || ready, 'Enhancement runtime closed: authoritative contracts not ready');
  }
  return true;
}
