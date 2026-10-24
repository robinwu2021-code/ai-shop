// Recipient tracking platform boundary: native phone identity and H5 URL Link.
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { ROUTES } from "@shared/utils/constants";
import type { TrackView } from "@shared/types";

export function usesNativeRecipientIdentity(): boolean {
  let native = false;
  // #ifdef MP-WEIXIN
  native = true;
  // #endif
  // #ifndef MP-WEIXIN
  native = false;
  // #endif
  return native;
}

export async function loadRecipientTracking(token: string): Promise<{ redirected: boolean; view: TrackView | null }> {
  if (usesNativeRecipientIdentity()) {
    if (!useUserStore().isPhoneLogin) {
      uni.redirectTo({ url: `${ROUTES.login}?redirect=${encodeURIComponent(ROUTES.track + "?t=" + token)}` });
      return { redirected: true, view: null };
    }
    return { redirected: false, view: token ? await api.trackMine(token) : null };
  }
  return { redirected: false, view: token ? await api.track(token) : null };
}

export function supportsRecipientMiniLink(): boolean {
  let supported = false;
  // #ifdef H5
  supported = true;
  // #endif
  return supported;
}

export function openRecipientMiniLink(url: string): void {
  // #ifdef H5
  if (url) window.location.href = url;
  // #endif
}
