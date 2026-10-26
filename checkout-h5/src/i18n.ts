/**
 * 文案（F35：zh-TW / en / ar，另加 zh-CN —— 大陆市场的顾客同样扫这个码）。四份键集必须一致（i18n.test.ts）。
 * 语言：浏览器首选语言能对上就用它；对不上按市场（AE → ar，TW → zh-TW，CN → zh-CN），再不行 en。
 */
export type Lang = "zh-CN" | "zh-TW" | "en" | "ar";

const zhCN = {
  "title.pay": "付款",
  "title.code": "付款给商家",
  "title.result": "付款结果",
  "payee": "收款方",
  "method": "支付方式",
  "pay.confirm": "确认付款 {amount}",
  "pay.opening": "正在打开支付…",
  "pay.check": "我已付款，查看结果",
  "pay.noMethod": "这家店暂时不能在线收款",
  "pay.unsupported": "这种支付方式不能在当前页面打开",
  "pay.validUntil": "链接 {time} 前有效",
  "pay.failed": "付款没有发起成功，请重试",
  "code.amount": "付款金额",
  "code.single": "单笔最多 {amount}",
  "code.next": "下一步",
  "code.invalid": "请输入正确的金额",
  "code.overLimit": "超过单笔上限 {amount}，请分开付款",
  "code.disabled": "这个收款码已停用",
  "result.succeeded": "付款成功",
  "result.processing": "正在确认付款结果…",
  "result.failed": "付款未完成",
  "result.retry": "重新付款",
  "result.unknown": "付款结果请回到付款页面查看",
  "result.done": "完成",
  "expired.title": "链接已失效",
  "expired.body": "收款链接已失效，请向商家重新获取。",
  "error.network": "网络不太好，请稍后重试",
  "error.amountUnavailable": "金额暂时无法显示，请联系商家",
  "method.WECHAT_PAY": "微信支付",
  "method.ALIPAY": "支付宝",
  "method.TEST_PAY": "测试支付",
  "method.other": "在线支付",
};
type Dict = typeof zhCN;

const zhTW: Dict = {
  "title.pay": "付款",
  "title.code": "付款給商家",
  "title.result": "付款結果",
  "payee": "收款方",
  "method": "支付方式",
  "pay.confirm": "確認付款 {amount}",
  "pay.opening": "正在開啟支付…",
  "pay.check": "我已付款，查看結果",
  "pay.noMethod": "這家店暫時不能線上收款",
  "pay.unsupported": "這種支付方式不能在目前頁面開啟",
  "pay.validUntil": "連結 {time} 前有效",
  "pay.failed": "付款沒有發起成功，請重試",
  "code.amount": "付款金額",
  "code.single": "單筆最多 {amount}",
  "code.next": "下一步",
  "code.invalid": "請輸入正確的金額",
  "code.overLimit": "超過單筆上限 {amount}，請分開付款",
  "code.disabled": "這個收款碼已停用",
  "result.succeeded": "付款成功",
  "result.processing": "正在確認付款結果…",
  "result.failed": "付款未完成",
  "result.retry": "重新付款",
  "result.unknown": "付款結果請回到付款頁面查看",
  "result.done": "完成",
  "expired.title": "連結已失效",
  "expired.body": "收款連結已失效，請向商家重新取得。",
  "error.network": "網路不太穩定，請稍後重試",
  "error.amountUnavailable": "金額暫時無法顯示，請聯絡商家",
  "method.WECHAT_PAY": "微信支付",
  "method.ALIPAY": "支付寶",
  "method.TEST_PAY": "測試支付",
  "method.other": "線上支付",
};

const en: Dict = {
  "title.pay": "Pay",
  "title.code": "Pay the merchant",
  "title.result": "Payment result",
  "payee": "Payee",
  "method": "Payment method",
  "pay.confirm": "Pay {amount}",
  "pay.opening": "Opening payment…",
  "pay.check": "I have paid, check the result",
  "pay.noMethod": "This store cannot take online payments right now",
  "pay.unsupported": "This payment method cannot be opened on this page",
  "pay.validUntil": "Link valid until {time}",
  "pay.failed": "The payment could not be started. Please try again",
  "code.amount": "Amount",
  "code.single": "Up to {amount} per payment",
  "code.next": "Next",
  "code.invalid": "Please enter a valid amount",
  "code.overLimit": "Over the {amount} limit per payment. Please pay in parts",
  "code.disabled": "This payment code is no longer active",
  "result.succeeded": "Payment successful",
  "result.processing": "Confirming your payment…",
  "result.failed": "Payment not completed",
  "result.retry": "Pay again",
  "result.unknown": "Please go back to the payment page to see the result",
  "result.done": "Done",
  "expired.title": "Link expired",
  "expired.body": "This payment link is no longer valid. Please ask the merchant for a new one.",
  "error.network": "Network problem. Please try again later",
  "error.amountUnavailable": "The amount cannot be shown. Please contact the merchant",
  "method.WECHAT_PAY": "WeChat Pay",
  "method.ALIPAY": "Alipay",
  "method.TEST_PAY": "Test payment",
  "method.other": "Online payment",
};

const ar: Dict = {
  "title.pay": "الدفع",
  "title.code": "الدفع للتاجر",
  "title.result": "نتيجة الدفع",
  "payee": "المستفيد",
  "method": "طريقة الدفع",
  "pay.confirm": "ادفع {amount}",
  "pay.opening": "جارٍ فتح الدفع…",
  "pay.check": "لقد دفعت، اعرض النتيجة",
  "pay.noMethod": "لا يمكن لهذا المتجر قبول الدفع الإلكتروني حاليًا",
  "pay.unsupported": "لا يمكن فتح طريقة الدفع هذه في هذه الصفحة",
  "pay.validUntil": "الرابط صالح حتى {time}",
  "pay.failed": "تعذّر بدء الدفع، يرجى المحاولة مرة أخرى",
  "code.amount": "المبلغ",
  "code.single": "حتى {amount} لكل عملية دفع",
  "code.next": "التالي",
  "code.invalid": "يرجى إدخال مبلغ صحيح",
  "code.overLimit": "المبلغ يتجاوز حد {amount} لكل عملية، يرجى الدفع على دفعات",
  "code.disabled": "رمز الدفع هذا لم يعد نشطًا",
  "result.succeeded": "تم الدفع بنجاح",
  "result.processing": "جارٍ تأكيد الدفع…",
  "result.failed": "لم يكتمل الدفع",
  "result.retry": "ادفع مرة أخرى",
  "result.unknown": "يرجى العودة إلى صفحة الدفع لمعرفة النتيجة",
  "result.done": "تم",
  "expired.title": "انتهت صلاحية الرابط",
  "expired.body": "رابط الدفع هذا لم يعد صالحًا، يرجى طلب رابط جديد من التاجر.",
  "error.network": "مشكلة في الشبكة، يرجى المحاولة لاحقًا",
  "error.amountUnavailable": "لا يمكن عرض المبلغ، يرجى التواصل مع التاجر",
  "method.WECHAT_PAY": "WeChat Pay",
  "method.ALIPAY": "Alipay",
  "method.TEST_PAY": "دفع تجريبي",
  "method.other": "الدفع الإلكتروني",
};

export type Key = keyof Dict;
export const DICTS: Record<Lang, Dict> = { "zh-CN": zhCN, "zh-TW": zhTW, en, ar };

const MARKET_LANG: Record<string, Lang> = { CN: "zh-CN", TW: "zh-TW", AE: "ar" };

export function pickLang(browser: readonly string[], market?: string | null): Lang {
  for (const raw of browser) {
    const l = raw.toLowerCase();
    if (l.startsWith("ar")) return "ar";
    if (l === "zh-tw" || l === "zh-hk" || l.startsWith("zh-hant")) return "zh-TW";
    if (l.startsWith("zh")) return "zh-CN";
    if (l.startsWith("en")) return "en";
  }
  return (market && MARKET_LANG[market]) || "en";
}

export function isRtl(lang: Lang): boolean {
  return lang === "ar";
}

export function t(lang: Lang, key: Key, vars: Record<string, string> = {}): string {
  return DICTS[lang][key].replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? "");
}

export function methodLabel(lang: Lang, method: string): string {
  const key = ("method." + method) as Key;
  return key in DICTS[lang] ? t(lang, key) : t(lang, "method.other");
}
