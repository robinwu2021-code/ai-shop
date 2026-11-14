// 平台商城页面共用的小工具：金额展示、时间展示、按字节认图片类型、读本地图片字节、错误原因码转文案。
import type { MallMoney } from "@/api/mall";

/** 没有小数位的币种；其余按 2 位 */
const ZERO_DECIMAL = new Set(["JPY", "KRW", "VND"]);

/**
 * `{currency, minor}` → 「TWD 6,990.00」。
 *
 * `minor` 是整数**字符串**，这里只做字符串切分，不转浮点 —— 商城金额要与后端逐位一致。
 * 共享的 `money()` 只认 CNY/USD/AED，台湾门店的 TWD 进去会出错，所以单写一份。
 */
export function mallMoney(m?: MallMoney | null): string {
  if (!m || !/^\d+$/.test(m.minor)) return "—";
  const d = ZERO_DECIMAL.has(m.currency) ? 0 : 2;
  const s = m.minor.padStart(d + 1, "0");
  const int = s.slice(0, s.length - d).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${m.currency} ${int}${d ? "." + s.slice(-d) : ""}`;
}

/** ISO 时间 → 本机「YYYY-MM-DD HH:mm」 */
export function mallTime(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * 按文件头认图片：PNG `89 50 4E 47`，JPEG `FF D8 FF`；别的一律 null。
 *
 * 后端会按字节核对声明的类型（ImageProbe），声明与内容对不上就拒 ——
 * H5 选出来的是 blob 地址没有扩展名，凭文件名猜不可靠，所以只看字节。
 */
export function sniffImageType(bytes: ArrayBuffer): "image/png" | "image/jpeg" | null {
  const b = new Uint8Array(bytes.slice(0, 4));
  if (b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return "image/png";
  if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  return null;
}

/** 凭证大小上限，与后端一致（10MB） */
export const EVIDENCE_MAX_BYTES = 10 * 1024 * 1024;

/** 把 `uni.chooseImage` 给的临时路径读成字节。小程序 / App 用文件系统；H5 的路径是 blob 地址，用 fetch */
export function readImageBytes(path: string): Promise<ArrayBuffer> {
  const fs = typeof uni.getFileSystemManager === "function" ? uni.getFileSystemManager() : null;
  if (fs) {
    return new Promise((resolve, reject) => {
      fs.readFile({
        filePath: path,
        success: (res) => resolve(res.data as ArrayBuffer),
        fail: (err) => reject(new Error(err.errMsg || "READ_FAILED")),
      });
    });
  }
  return fetch(path).then((r) => r.arrayBuffer());
}

/** 硬件要收货地址：订单里有任何一个 HARDWARE 组件 */
export function hasHardware(components?: { kind?: string }[]): boolean {
  return (components ?? []).some((c) => c.kind === "HARDWARE");
}

/** 币种 → 收货地址默认国家/地区码 */
export function countryOf(currency?: string): string {
  return currency === "CNY" ? "CN" : currency === "AED" ? "AE" : "TW";
}

/**
 * 后端拒绝时 msg 就是机器原因码（如 OFFLINE_UNAVAILABLE）。有对应文案就说人话，
 * 没有就给通用句并带上原因码 —— 运营和客服要靠这个码查。
 */
export function mallErrorText(
  e: unknown,
  t: (key: string, values?: Record<string, unknown>) => string,
  te: (key: string) => boolean,
): string {
  const code = e instanceof Error ? e.message : "";
  if (/^[A-Z][A-Z0-9_]+$/.test(code) && te(`bizMall.err.${code}`)) return t(`bizMall.err.${code}`);
  return code ? t("bizMall.err.generic", { code }) : t("bizMall.err.network");
}
