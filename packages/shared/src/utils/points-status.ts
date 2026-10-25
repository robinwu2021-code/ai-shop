/** Earning pause does not change the type lifecycle or erase existing balances. */
export function pointsTypeStatusKey(status: string | null | undefined, earningPaused = false): string {
  if (status === "ACTIVE") return earningPaused ? "points.typeStateEarningPaused" : "points.typeStateActive";
  switch (status) {
    case "DRAFT": return "points.typeStateDraft";
    case "DRAINING": return "points.typeStateDraining";
    case "RETIRED": return "points.typeStateRetired";
    default: return "points.typeStateUnknown";
  }
}
