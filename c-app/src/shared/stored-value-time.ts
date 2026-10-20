import { currentOffset, datetime } from "@shared/utils/datetime";
const FIXED_ZONES: Record<string, number> = { UTC: 0, "Etc/UTC": 0, "Asia/Shanghai": 480, "Asia/Dubai": 240 };
const pad = (value: number) => String(value).padStart(2, "0");
function fixedTime(instant: string, offset: number): string {
  const date = new Date(Date.parse(instant) + offset * 60_000);
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`;
}
/** 账单以门店IANA时区展示。无Intl的小程序支持现有中/海湾市场；未知时区明确退回UTC。 */
export function storedValueDateTime(instant: string, zone: string, includeZone = true): string {
  if (Object.prototype.hasOwnProperty.call(FIXED_ZONES, zone)) return `${fixedTime(instant, FIXED_ZONES[zone]!)}${includeZone ? ` (${zone})` : ""}`;
  try {
    const formatter = new Intl.DateTimeFormat("en-GB", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
    const parts = Object.fromEntries(formatter.formatToParts(new Date(instant)).map(part => [part.type, part.value]));
    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}${includeZone ? ` (${zone})` : ""}`;
  } catch {
    // 绝不把设备/当前市场时区冒充门店时区。
    return `${fixedTime(instant, 0)} (UTC)`;
  }
}
/** 目录API没有门店时区，以用户当前市场时区展示并明确标注偏移。 */
export function storedValueDirectoryTime(instant: string): string {
  const offset = currentOffset(); const absolute = Math.abs(offset);
  return `${datetime(Date.parse(instant))} (UTC${offset < 0 ? "−" : "+"}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)})`;
}
