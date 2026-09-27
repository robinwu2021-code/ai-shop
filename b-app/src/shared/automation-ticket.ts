// 从 App 启动参数里取密钥票据（ADR-027，TDD-密钥票据免登录 §4.4）。
//
// 模拟器上由 scripts/automation/emulator-login.sh 用 adb 带进来。uni 的
// `plus.runtime.arguments` 在不同启动方式下给的形态不一样：JSON 字符串、查询串、
// 或者直接就是票据本身 —— 三种都认，认不出来就当没有（**不猜**：猜错的代价是拿一段垃圾去换会话）。
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

/** App 运行时才有启动参数；H5、小程序一律 null（它们没有这条路径）。 */
export function readAutomationTicket(): string | null {
  // #ifdef APP-PLUS
  try {
    return parseAutomationTicket(plus.runtime.arguments);
  } catch {
    return null;
  }
  // #endif
  // eslint-disable-next-line no-unreachable
  return null;
}
