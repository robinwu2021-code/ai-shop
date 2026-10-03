// 通用展示格式化。金额见 shared/money，时间见 shared/datetime。
export { money, toMajor, toMinor, currentCurrency } from "./money";
export {
  countdown,
  countdownShort,
  datetime,
  hourMinute,
  isoDate,
  monthDay,
  todayAtLocal,
} from "./datetime";

/** 克 → 展示重量（<1000g 用 g，否则用 kg） */
export function weight(gram: number): string {
  if (gram < 1000) return `${gram}g`;
  return `${(gram / 1000).toFixed(2).replace(/\.?0+$/, "")}kg`;
}

/** 米 → 「800m」/「1.2km」 */
export function distance(meter: number): string {
  if (meter < 1000) return `${Math.round(meter)}m`;
  return `${(meter / 1000).toFixed(1)}km`;
}

/** 手机号脱敏（团长看客户列表用） */
export function maskPhone(phone: string): string {
  return phone.replace(/^(\d{3})\d{4}(\d{4})$/, "$1****$2");
}

/**
 * 计量单位的展示名。
 *
 * `baseUom` **有两种来源**：进销存字典表 `inv_uom` 的码（`PIECE` / `BAG` …），
 * 或平台商品上的自由文本（商家自己填的「袋」「提」）。
 * 认得的码翻成当地语言，认不得的**原样返回** —— 别把商家自己写的单位吃掉。
 *
 * 2026-08-28 之前这里没有转换，库存明细上写的是「单位 PIECE」。
 */
export function uomLabel(uom: string | null | undefined, t: (k: string) => string): string {
  if (!uom) return "";
  const key = `stock.uom.${uom}`;
  const got = t(key);
  // i18n 取不到时各实现返回的东西不一样：有的原样返回 key，有的返回空
  return got && got !== key ? got : uom;
}
