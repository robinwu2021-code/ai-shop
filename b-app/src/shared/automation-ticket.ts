// 从 App 启动参数里取密钥票据（ADR-027，TDD-密钥票据免登录 §4.4）。
//
// 模拟器上由 scripts/automation/emulator-login.sh 用 adb 写进 App 私有目录的一个文件。
// 内容的形态宽容：票据本身、JSON、查询串三种都认，认不出来就当没有
// （**不猜**：猜错的代价是拿一段垃圾去换会话）。
//
// 票据本身没有私钥签不出来，所以「App 会读启动参数」不是一扇门；它只是把本机脚本签好的票据送到登录那一步。

const TICKET_RE = /^v1\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

export function parseAutomationTicket(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const s = raw.trim();
  if (!s) return null;
  if (TICKET_RE.test(s)) return s;
  if (s.startsWith("{")) {
    try {
      const v = (JSON.parse(s) as Record<string, unknown>).automationTicket;
      return typeof v === "string" && TICKET_RE.test(v.trim()) ? v.trim() : null;
    } catch {
      return null;
    }
  }
  const m = /(?:^|[?&])automationTicket=([^&]+)/.exec(s);
  if (m) {
    const v = decodeURIComponent(m[1] ?? "").trim();
    return TICKET_RE.test(v) ? v : null;
  }
  return null;
}

/**
 * 模拟器上交票据的文件（相对 `_doc`，即 /sdcard/Android/data/<包名>/apps/<appid>/doc/）。
 *
 * <p>为什么走文件不走启动参数：离线 SDK 5.24 里 `plus.runtime.arguments` 只从 uni 小程序模式的
 * Intent extra 取值，普通 App 冷启动带 extra 它不读（2026-09-27 实测，见 emulator-login.sh）；
 * 走 URL scheme 又要改仓库外的离线工程、并让正式包多一个深链入口。
 * 这个目录在新版 Android 上只有本 App 与 adb 写得进去，别的 App 放不了东西。
 */
export const AUTOMATION_TICKET_FILE = "_doc/automation-ticket.txt";

/** 读出来就删：票据一次性，文件留着只会让下一次启动拿旧票据去换、白记一次失败审计。 */
export function readAutomationTicket(): Promise<string | null> {
  // #ifdef APP-PLUS
  return new Promise((resolve) => {
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const io = (plus as any).io;
      io.resolveLocalFileSystemURL(
        AUTOMATION_TICKET_FILE,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (entry: any) => {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          entry.file((file: any) => {
            const reader = new io.FileReader();
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            reader.onloadend = (e: any) => {
              entry.remove(() => undefined, () => undefined);
              resolve(parseAutomationTicket(e?.target?.result));
            };
            reader.onerror = () => resolve(null);
            reader.readAsText(file, "utf-8");
          }, () => resolve(null));
        },
        () => resolve(null),
      );
    } catch {
      resolve(null);
    }
  });
  // #endif
  // eslint-disable-next-line no-unreachable
  return Promise.resolve(null);
}
