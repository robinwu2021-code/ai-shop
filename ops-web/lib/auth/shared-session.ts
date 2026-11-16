import { ApiError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth";
import { useServerMenu } from "@/lib/stores/server-menu";
import { verifySharedOpsToken } from "@/lib/api/https/dashboard";
import { currentSharedToken, migrateLegacySession } from "./ops-bridge";

let pending: Promise<void> | null = null;

/**
 * 启动时对齐两端共用的会话（TDD-ops-统一登录与系统切换 §3.2）。
 *
 * - 共用键里没有令牌：本页的缓存一律作废（另一端已退出，或从未登录）。
 * - 令牌与本页缓存相同：照常使用。
 * - 令牌不同（另一端换了人或刚登录）：先经后端核验，通过才采用，角色与权限以后端为准。
 * Keep the shell hidden until this resolves.
 */
export function receiveSharedSession(clearQueries: () => void): Promise<void> {
  if (pending) return pending;
  migrateLegacySession();
  const token = currentSharedToken();
  const cached = useAuth.getState().token;
  if (!token) {
    if (cached) {
      useAuth.getState().logout();
      useServerMenu.getState().clear();
      clearQueries();
    }
    return Promise.resolve();
  }
  if (token === cached) return Promise.resolve();
  const candidate = token;
  // Keep existing storage intact while the shell is gated. A network failure is
  // not evidence that the current session has expired.
  pending = (async () => {
    try {
      const identity = await verifySharedOpsToken(candidate);
      // An explicit logout or another login during the request must win.
      if (currentSharedToken() !== candidate) return;
      clearQueries();
      useServerMenu.getState().clear();
      useAuth.getState().login(identity);
      await useServerMenu.getState().load();
    } catch (error) {
      if (currentSharedToken() !== candidate) return;
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

/**
 * 另一个标签页（或另一端）改了共用会话：退出 → 本页回登录页；换了人 → 整页重新加载，
 * 重新走 {@link receiveSharedSession}。两种情况都先清掉查询缓存，不让上一个人的数据留在屏幕上。
 */
export function onSharedSessionChange(event: StorageEvent, clearQueries: () => void, toLogin: () => void, reload: () => void): void {
  if (event.key !== null && event.key !== "neargo-ops-auth") return;
  const next = currentSharedToken();
  const mine = useAuth.getState().token;
  if (next === mine) return;
  clearQueries();
  useServerMenu.getState().clear();
  if (!next) {
    if (mine) useAuth.setState({ token: "", username: "", perms: [] });
    toLogin();
  } else {
    reload();
  }
}
