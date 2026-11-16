"use client";
import { useAuth } from "./auth";

// Keep credentials out of query keys/devtools. A new authenticated session gets a new
// local generation even when the same operator signs in again in this browser.
let observedSession: string | undefined;
let generation = 0;
export function useReportingIdentity(): readonly [string, string, number] {
  const tenant = useAuth(s => s.tenantNo), username = useAuth(s => s.username);
  const token = useAuth(s => s.token), merchant = useAuth(s => s.merchantNo);
  const community = useAuth(s => s.communityNo), pickup = useAuth(s => s.pickupNo);
  const perms = useAuth(s => s.perms);
  const session = JSON.stringify([tenant, username, token, merchant, community, pickup, [...(perms ?? [])].sort()]);
  if (session !== observedSession) { observedSession = session; generation += 1; }
  return [tenant, username, generation];
}
