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
