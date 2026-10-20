/** Temporary URL bridge while points pages move out of the tabbed workspace. */
export function pointsLegacyRedirect(tab: string | null): string | null {
  if (tab === "overview") return "/points";
  if (tab === "configuration") return "/points/types";
  return null;
}
