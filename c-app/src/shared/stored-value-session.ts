import { useUserStore } from "@/stores/user";
import { ROUTES } from "@shared/utils/constants";
let pendingLoginPath = "";
const unauthorized = (error: unknown) => [401, 10401, 10402].includes(Number((error as { code?: number })?.code));
/** 仅储值只读请求：等待既有静默登录后最多重读一次，保留通知落点。 */
export async function readStoredValueWithSession<T>(read: () => Promise<T>, returnPath: () => string): Promise<T> {
  const user = useUserStore(); const requestToken = user.token;
  try { const result = await read(); pendingLoginPath = ""; return result; }
  catch (error) {
    if (!unauthorized(error)) throw error;
    const restored = user.token && user.token !== requestToken ? true : await user.silentLogin(true).catch(() => false);
    if (restored) {
      try { const result = await read(); pendingLoginPath = ""; return result; }
      catch (retryError) { if (!unauthorized(retryError)) throw retryError; error = retryError; }
    }
    const path = returnPath();
    if (pendingLoginPath !== path) {
      pendingLoginPath = path;
      uni.navigateTo({ url: `${ROUTES.login}?redirect=${encodeURIComponent(path)}`, fail: () => { pendingLoginPath = ""; } });
    }
    throw error;
  }
}
