/** A code identifies a ledger; it must never substitute for the user-facing name. */
export function requirePointsNames<T extends { name?: string | null }>(rows: T[], message = "POINTS_NAME_MISSING"): T[] {
  if (rows.some(row => typeof row.name !== "string" || !row.name.trim())) throw new Error(message);
  return rows.map(row => ({ ...row, name: row.name!.trim() }));
}
