// 消息与客服 mock（P-14）。
import type { FaqEntry, MsgTemplate, NotifyQuota, Ticket } from "@/lib/types";

/*
 * 模板 mock。**通道值与后端种子（V141）同一套** —— SMS/MAIL/WXSUB/PUSH/INAPP。
 * 此前这里写的是 V20 建表注释里的旧叫法（SUBSCRIBE/PUSH/INBOX），
 * 与真实数据对不上：真后端下模板列表的通道列会是空白，而 mock 下看着正常。
 *
 * 占位用英文键（{code} 而不是 {社区}）：预览与模板参数输入框都按占位名取值，
 * 中文占位名在 URL/JSON 里传起来只会添乱。
 */
export const msgTemplates: MsgTemplate[] = [
  { templateNo: "TPL_SMS_OTP", name: "验证码", channel: "SMS", lang: "zh-CN", content: "【数智邻购】您的验证码是 {code}，5 分钟内有效，请勿泄露。", providerTemplateId: "SMS_474945291", enabled: true, sentCount: 3820 },
  { templateNo: "TPL_MAIL_TEST", name: "通道联通测试", channel: "MAIL", lang: "zh-CN", content: "{subject}\n\n{body}", enabled: true, sentCount: 12 },
  { templateNo: "TPL_MAIL_OPS_INIT_PWD", name: "运营账号开通", channel: "MAIL", lang: "zh-CN", content: "你好 {realName}，\n\n你的运营端账号已开通。\n登录名：{username}\n初始密码：{password}\n\n首次登录会要求你立即修改密码。请勿转发本邮件。", enabled: true, sentCount: 3 },
  { templateNo: "TPL_MAIL_OPS_RESET_PWD", name: "运营密码重置", channel: "MAIL", lang: "zh-CN", content: "你好 {realName}，\n\n有人为你的运营端账号申请了密码重置。\n重置码（{ttlMinutes} 分钟内有效，只能用一次）：\n\n    {token}\n\n如果不是你本人操作，忽略本邮件即可。", enabled: true, sentCount: 1 },
{ templateNo: "TPL_MAIL_OPS_RESET_PWD", name: "Ops password reset", channel: "MAIL", lang: "en", content: "Hi {realName},\n\nSomeone requested a password reset.\nReset code (valid {ttlMinutes} minutes, single use):\n\n    {token}\n", enabled: true, sentCount: 1 },
  { templateNo: "TPL_WX_ARRIVED", name: "到货通知", channel: "WXSUB", lang: "zh-CN", content: "您有 {number1} 件包裹已到自提点 · {thing2}", enabled: true, sentCount: 640 },
  // 与后端 V141 种子对齐。**缺了这条的话，抽屉里只剩一条模板 → 选择器不出现**，
  // 而「两条微信模板都能测」正是 G2 要保证的事，在 mock 下就演示不出来
  { templateNo: "TPL_WX_REFUNDED", name: "退款通知", channel: "WXSUB", lang: "zh-CN", content: "退款 {amount1} 已处理 · {thing2}", enabled: true, sentCount: 120 },
  { templateNo: "TPL_PUSH_TEST", name: "通用推送", channel: "PUSH", lang: "zh-CN", content: "{subject}\n{body}", enabled: true, sentCount: 210 },
  { templateNo: "TPL_INAPP_TEST", name: "站内信", channel: "INAPP", lang: "zh-CN", content: "{subject}\n{body}", enabled: false, sentCount: 12 },
];

export const notifyQuota: NotifyQuota = {
  dailyPerUser: 3,
  minIntervalHours: 6,
  updatedAt: "2026-07-15T02:00:00Z",
  updatedBy: "admin",
};

export const tickets: Ticket[] = [
  { ticketNo: "TK9001", title: "取货码显示已核销但没拿到货", userNickname: "海棠", orderNo: "SO2026080506", status: "PENDING", proxyActions: [], createdAt: "2026-08-06T01:10:00Z" },
  { ticketNo: "TK9002", title: "想改配送地址", userNickname: "阿May", orderNo: "SO2026080504", status: "ASSIGNED", assignee: "cs02", proxyActions: ["cs02 代客修改收货地址：阳光里 6-2 → 阳光里 8-1"], createdAt: "2026-08-05T10:00:00Z" },
  { ticketNo: "TK9003", title: "咨询自提点营业时间", userNickname: "小满", status: "RESOLVED", assignee: "cs02", proxyActions: [], createdAt: "2026-08-04T03:00:00Z" },
  { ticketNo: "TK9004", title: "重复扣款", userNickname: "老周", orderNo: "SO2026080503", status: "CLOSED", assignee: "cs02", proxyActions: ["cs02 代客发起退款：¥18.60"], createdAt: "2026-08-02T06:00:00Z" },
];

export const faqs: FaqEntry[] = [
  { faqNo: "FQ9001", question: "自提码在哪里看？", answer: "在「我的 - 订单」里点开订单详情，页面顶部就是取货码。", category: "取货", published: true, views: 2841 },
  { faqNo: "FQ9002", question: "生鲜称重差价怎么算？", answer: "按实际称重多退少补，差价在取货后 24 小时内自动结算。", category: "生鲜", published: true, views: 1620 },
  { faqNo: "FQ9003", question: "逾期未取会怎样？", answer: "按平台逾期规则处理：默认顺延到下一批，超过顺延上限后按作废退款。", category: "取货", published: true, views: 980 },
  { faqNo: "FQ9004", question: "如何申请开店？", answer: "", category: "商家", published: false, views: 0 },
];

/**
 * 平台默认语言（G2e）。**可变对象而不是常量**：mock 要能被 saveDefaultLang 改，
 * 否则「保存后仍显示旧值」这类缺陷在本地看不出来。
 */
export const defaultLang = { value: "zh-CN" };
