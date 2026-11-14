import { readFileSync } from 'node:fs';
import { resolve, sep } from 'node:path';

/** Validate evidence before a non-ORM adapter can satisfy table ownership coverage. */
export function persistenceOwners(root, registry) {
  if (!registry || Array.isArray(registry) || typeof registry !== 'object') throw new Error('Malformed persistence registry');
  const owners = new Set();
  const source = (path) => {
    if (typeof path !== 'string' || !path.includes('/src/main/') || !path.endsWith('.java')) throw new Error('Invalid owner source');
    const absolute = resolve(root, path);
    if (!absolute.startsWith(resolve(root) + sep)) throw new Error('Owner outside repository');
    return readFileSync(absolute, 'utf8').replace(/\s+/g, '');
  };
  for (const [table, entry] of Object.entries(registry)) {
    if (!/^[a-z][a-z0-9_]+$/.test(table) || !entry || typeof entry !== 'object') throw new Error('Malformed table owner');
    const owner = source(entry.owner), query = source(entry.querySource), model = source(entry.modelSource);
    if (!/(JdbcTemplate|JdbcClient|@Mapper)/.test(owner)) throw new Error(`${table}: no JDBC/MyBatis SQL adapter`);
    if (entry.querySource !== entry.owner) {
      const type = entry.owner.split('/').pop().replace('.java', '');
      if (!query.includes(type)) throw new Error(`${table}: query is not wired to owner`);
    }
    if (entry.dynamic) {
      const { prefix, suffix } = entry.dynamic;
      if (!['sys', 'inv'].includes(prefix) || !['_outbox_stream', '_outbox_delivery', '_outbox_operation'].includes(suffix)
          || table !== prefix + suffix || !owner.includes(`("${prefix}")`)
          || !owner.includes(`source.prefix+"${suffix}"`)) throw new Error(`${table}: invalid source-bound table composition`);
    } else {
      const queryText = readFileSync(resolve(root, entry.querySource), "utf8");
      const literals = [...queryText.matchAll(/"(?:\\.|[^"\\])*"/g)].map(m => m[0]);
      if (!literals.some(literal => new RegExp(`\\b${table}\\b`).test(literal))) {
        throw new Error(`${table}: missing table reference`);
      }
    }
    if (entry.rowShape === 'MAPPED_ROW') {
      if (entry.modelSymbol !== 'Map<String,Object>' || !model.includes('Map<String,Object>')) throw new Error(`${table}: missing mapped row representation`);
    } else if (['RECORD', 'ENUM'].includes(entry.rowShape)) {
      if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(entry.modelSymbol)
          || !model.includes(`${entry.rowShape.toLowerCase()}${entry.modelSymbol}`)) throw new Error(`${table}: missing concrete model`);
    } else throw new Error(`${table}: unknown row shape`);
    owners.add(table);
  }
  return owners;
}
