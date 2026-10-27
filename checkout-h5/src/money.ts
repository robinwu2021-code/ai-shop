/** 金额一律是最小单位（分 / 元 / 費爾斯），小数位由服务端按市场给；给不出时不显示（宁可不显示，不显示错） */

export function formatMoney(amountMinor: number, currency: string, scale: number | null | undefined,
                            locale: string): string | null {
  if (scale == null || !Number.isInteger(scale) || scale < 0 || scale > 4) return null;
  const major = amountMinor / 10 ** scale;
  // 金额本身始终从左到右（阿拉伯语页面里也不翻转数字），由调用方包 <bdi dir="ltr">
  return new Intl.NumberFormat(locale, {
    style: "currency", currency, minimumFractionDigits: scale, maximumFractionDigits: scale,
    numberingSystem: "latn",
  } as Intl.NumberFormatOptions).format(major);
}

/** 顾客输入 → 最小单位。小数位超出、非数字、≤ 0 都返回 null */
export function parseAmount(input: string, scale: number): number | null {
  const s = input.trim().replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x660)).replace(/[,，\s]/g, "");
  const re = scale === 0 ? /^\d{1,9}$/ : new RegExp(`^\\d{1,9}(\\.\\d{1,${scale}})?$`);
  if (!re.test(s)) return null;
  const [int, frac = ""] = s.split(".");
  const minor = Number(int) * 10 ** scale + Number((frac + "0".repeat(scale)).slice(0, scale) || "0");
  return minor > 0 ? minor : null;
}
