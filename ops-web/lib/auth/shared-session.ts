import { ApiError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth";
import { useServerMenu } from "@/lib/stores/server-menu";
import { verifySharedOpsToken } from "@/lib/api/https/dashboard";
import { readSharedToken } from "./ops-bridge";

let pending: Promise<void> | null = null;
/** Keep the shell hidden until the shared token has been checked by the backend. */
export function receiveSharedSession(clearQueries: () => void): Promise<void> {
  if (pending) return pending;
  let token: string | null = null;
  try { token = readSharedToken(sessionStorage); } catch { /* disabled storage */ }
  if (!token) return Promise.resolve();
  const candidate = token;
  // Keep existing storage intact while the shell is gated. A network failure is
  // not evidence that the current session has expired.
  pending = (async () => {
    try {
      const identity = await verifySharedOpsToken(candidate);
      // An explicit logout or another login during the request must win.
      if (readSharedToken(sessionStorage) !== candidate) return;
      clearQueries();
      useServerMenu.getState().clear();
      useAuth.getState().login(identity);
      await useServerMenu.getState().load();
    } catch (error) {
      if (readSharedToken(sessionStorage) !== candidate) return;
      if (error instanceof ApiError && (error.code === 401 || error.code === 403)) {
        useAuth.getState().logout();
        useServerMenu.getState().clear();
        clearQueries();
      } else {
        throw error;
      }
    } finally { pending = null; }
  })();
  return pending;
}
