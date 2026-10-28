/** Replay narrowly supported built-in role permission updates, in Flyway order. */
export function appendRolePermissions(roles, migrations) {
  for (const sql of migrations) {
    for (const statement of sql.split(';')) {
      if (!/UPDATE\s+mch_role\s+SET/i.test(statement)) continue;
      const where = statement.split(/\bWHERE\b/i)[1] ?? '';
      if (!/entity_no\s*=\s*'\*'/.test(where) || !/builtin\s*=\s*1/.test(where)) continue;
      const role = where.match(/role_code\s*=\s*'(\w+)'/)?.[1];
      if (!role || !roles[role]) throw new Error('Unknown built-in role migration');
      const permission = statement.match(/perms\s*=\s*JSON_ARRAY_APPEND\(perms\s*,\s*'\$'\s*,\s*'([^']+)'\)/i)?.[1];
      if (!permission) throw new Error('Unsupported built-in permission update: '+statement.trim());
      roles[role].add(permission);
    }
  }
  return roles;
}
