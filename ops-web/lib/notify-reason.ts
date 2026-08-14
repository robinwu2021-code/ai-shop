// 发送失败的可读归因。
//
// **为什么不放在页面组件里**：这里的中文是用来**匹配后端错误原文**的模式，
// 不是显示给人看的文案 —— 放在 .tsx 里会被 page-copy 守卫当成裸中文拦下，
// 而把它们搬进 copy.ts 更荒唐：那是文案表，不是匹配规则表。
//
// **为什么需要归因**：通道原文是给排查用的（`个推拒绝：20001 离线推送配额已用尽`），
// 但看这张表的人第一时间要的不是原文，而是「该找谁」——
// 是我配错了？是配额用完了？还是这个用户压根不可达？
// 归因只回答这一个问题，**原文照常展示**，两者都不能省。

/** 归因类别。文案在各页 copy.ts 里，这里只给类别 —— 逻辑不碰 i18n。 */
export type NotifyFailReason = "CRED" | "QUOTA" | "TARGET" | "NETWORK";

const PATTERNS: [NotifyFailReason, RegExp][] = [
  // 凭据类：token 失效、签名错、appid/secret 缺失。微信 40001/42001、个推 10001/10003
  ["CRED", /token|sign|secret|appid|凭据|鉴权|auth|4000[13]|42001|1000[13]/i],
  // 配额与限流：免费档的日配额（ADR-018）、短信余额、微信 43101（未订阅或额度用尽）
  ["QUOTA", /quota|配额|限流|throttl|limit|余额|balance|43101|20001/i],
  // 收件人不可达：cid 失效（卸载/换机）、未授权
  ["TARGET", /cid|未订阅|not\s*exist|unsubscrib|收件人|invalid.*(cid|token)|无效的?用户/i],
  // 网络类：可重试
  ["NETWORK", /网络|timeout|timed\s*out|connect|network|中断|unreachable/i],
];

/**
 * 从通道错误原文推断归因。
 *
 * <p><b>匹配不上就返回 null</b>，页面只展示原文 ——
 * 编一个归因比不给归因更误导：它会把人引去检查一个没问题的地方。
 */
export function notifyFailReason(error?: string | null): NotifyFailReason | null {
  if (!error) return null;
  for (const [reason, re] of PATTERNS) {
    if (re.test(error)) return reason;
  }
  return null;
}
