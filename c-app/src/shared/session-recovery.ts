import { useUserStore } from "@/stores/user";
import type { UnauthorizedContext } from "@shared/net/http-client";
let recovery: Promise<boolean> | null = null;

/** Expiry restores a technical session in place; phone access remains owned by a real user gesture. */
export function recoverSession(context: UnauthorizedContext) {
  const user = useUserStore();
  if (context.requestToken !== user.token) return Promise.resolve(!!user.token);
  if (recovery) return recovery;
  user.clearSession();
  recovery = user.silentLogin(true).catch(() => false).finally(() => { recovery = null; });
  return recovery;
}
