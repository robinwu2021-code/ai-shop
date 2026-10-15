import { reactive } from "vue";
import { useUserStore } from "@/stores/user";

export type PhoneLoginPhase = "preparing" | "ready" | "authorizing" | "submitting" | "authenticated" | "failed";
export const phoneLoginState = reactive({ phase: "ready" as PhoneLoginPhase });
let inFlight: Promise<{ userChanged: boolean }> | null = null;

export async function preparePhoneLogin() {
  if (useUserStore().isLogin) return true;
  phoneLoginState.phase = "preparing";
  const ok = await useUserStore().silentLogin();
  phoneLoginState.phase = ok ? "ready" : "failed";
  return ok;
}

export function submitPhoneAuthorization(code: string) {
  if (inFlight) return inFlight;
  phoneLoginState.phase = "submitting";
  inFlight = (async () => {
    try {
      if (!code || !(await preparePhoneLogin())) throw new Error("登录准备失败，请重试");
      phoneLoginState.phase = "submitting";
      const result = await useUserStore().loginByWxPhone(code);
      phoneLoginState.phase = "authenticated";
      return result;
    } catch (error) {
      phoneLoginState.phase = "failed";
      throw error;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}
