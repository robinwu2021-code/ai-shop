/*
 * 收款链接页的繁体中文（台湾）词条：b-app 整体尚无繁体，先只在本页提供。
 * 应用语言为中文（zh-*）且系统语言为繁体（zh-TW / zh-HK / zh-MO / zh-Hant）时返回繁体；否则返回 null，由应用 i18n 处理。
 */
const ZH_TW: Record<string, string> = {
  title: "收款連結", amount: "收款金額", amountPlaceholder: "輸入金額", subject: "用途", subjectPlaceholder: "顧客付款時看得到（選填）",
  validity: "有效期", v15: "15 分鐘", v60: "1 小時", v1440: "24 小時", v10080: "7 天",
  hint: "產生後把連結或 QR Code 傳給顧客，顧客開啟即可依此金額付款；入帳以收款明細為準。",
  generate: "產生收款連結", copy: "複製連結", copied: "已複製", share: "分享", expiresAt: "{time} 前有效",
  again: "再產生一條", refresh: "查看付款狀態", st_PAID: "已付款", st_open: "待付款", st_closed: "已失效",
  invalidAmount: "請輸入正確的金額",
};

export function isTraditional(appLocale: string, systemLanguage: string): boolean {
  return /^zh/i.test(appLocale) && /^zh[-_]?(hant|tw|hk|mo)\b|^zh[-_]hant/i.test(systemLanguage.replace(/_/g, "-"));
}

export function linkText(appLocale: string, systemLanguage: string, key: string, named?: Record<string, unknown>): string | null {
  if (!isTraditional(appLocale, systemLanguage)) return null;
  const s = ZH_TW[key];
  if (s == null) return null;
  return s.replace(/\{(\w+)\}/g, (_, k) => (named && named[k] != null ? String(named[k]) : `{${k}}`));
}

export const PAY_LINK_ZH_TW_KEYS = Object.keys(ZH_TW);
