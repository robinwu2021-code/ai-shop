// 消息与客服文案（矩阵 P-14.1 / P-14.2）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {

  channelSubscribe: "订阅消息",
  channelPush: "App 推送",
  channelInbox: "站内信",

  toastTemplateSaved: "已更新模板状态",
  /** `{name}` 是任务名 */
  toastSent: "「{name}」已发送",
  toastQuotaSaved: "频控已保存",
  toastAssigned: "已分派",
  toastProxyLogged: "代客操作已留痕",
  toastTicketClosed: "工单已关闭",
  toastSaved: "已保存",
  toastPublished: "已更新上架状态",

  colTemplateNo: "模板号",
  colName: "名称",
  colChannel: "渠道",
  colContent: "正文",
  colSent30d: "30 天发送",
  colEnabled: "启用",
  /** `{name}` 是模板名 */
  ariaEnable: "{name} 启用",

  colTaskNo: "任务号",
  colAudience: "人群",
  colReach: "预估触达",
  reachEmpty: "0（人群为空）",
  colScheduledAt: "计划时间",
  colStatus: "状态",
  colActions: "操作",
  btnSendNow: "立即发送",

  colTicketNo: "工单号",
  colTitle: "标题",
  colUser: "用户",
  colOrder: "关联订单",
  colAssignee: "处理人",
  unassigned: "未分派",
  colProxy: "代客操作",
  /** `{n}` 是条数 */
  proxyCount: "{n} 条",
  actionHandle: "处理",
  actionView: "查看",

  colFaqNo: "编号",
  colQuestion: "问题",
  colCategory: "分类",
  colAnswer: "答案",
  answerMissing: "未填写",
  colViews: "浏览",
  colPublished: "上架",
  /** `{q}` 是问题 */
  ariaPublish: "{q} 上架",
  actionEdit: "编辑",

  quotaTitle: "触达频控",
  quotaReadOnlyWhat: "触达频控配置",
  quotaNotice: "两个上限都必须大于 0 —— 填 0 等于没有频控，但界面上看着像配了，比不配更危险。",
  fieldDaily: "单用户单日上限",
  fieldInterval: "同模板最小间隔（小时）",
  save: "保存",

  sectionTasks: "推送任务",
  emptyTasks: "还没有推送任务。推送要先选人群再选模板，预估触达数为 0 时发不出去。",
  sectionTemplates: "消息模板",
  emptyTemplates: "还没有消息模板。订阅消息模板需先在微信后台报备。",

  ticketReadOnlyWhat: "工单分派与代客操作",
  searchTickets: "搜索工单号 / 标题 / 用户 / 订单",
  filterStatus: "按状态筛选",
  filterStatusAll: "全部状态",
  emptyTickets: "没有符合条件的工单。清空筛选，或换个状态 —— 待分派的工单最该先看。",

  faqNotice: "答案为空的条目「不能上架」：用户点进去看到一片空白，比没有这条更糟。",
  addFaqLabel: "新增条目",
  emptyFaq: "帮助中心还是空的。用户最常问的三件事：取货码在哪、称重差价怎么算、逾期未取怎么办。",

  btnCloseTicket: "关闭工单",
  fieldAssignee: "处理人",
  assigneePlaceholder: "员工登录名，如 cs02",
  btnAssign: "分派",
  fieldProxy: "记录代客操作",
  proxyPlaceholder: "替用户做了什么，如「代客修改收货地址：A → B」「代客发起退款：¥18.60」",
  btnLogProxy: "留痕",
  proxyHint: "代客操作是替用户改数据或退款，没有留痕就查不出是谁做的（矩阵 P-14.2.3）。",
  fieldProxyLog: "代客操作记录",
  none: "无",

  faqDrawerEdit: "编辑条目",
  faqDrawerNew: "新增条目",
  fieldQuestion: "问题",
  fieldCategory: "分类",
  categoryPlaceholder: "如：取货 / 生鲜 / 商家",
  fieldAnswer: "答案",
  answerPlaceholder: "用用户的话写，别抄规则条款",
  answerHint: "答案为空可以先存草稿，但不能上架。",
};

const en: typeof zh = {

  channelSubscribe: "Subscription message",
  channelPush: "App push",
  channelInbox: "In-app inbox",

  toastTemplateSaved: "Template status updated",
  toastSent: "“{name}” sent",
  toastQuotaSaved: "Frequency caps saved",
  toastAssigned: "Assigned",
  toastProxyLogged: "Action-on-behalf recorded",
  toastTicketClosed: "Ticket closed",
  toastSaved: "Saved",
  toastPublished: "Listing status updated",

  colTemplateNo: "Template no.",
  colName: "Name",
  colChannel: "Channel",
  colContent: "Body",
  colSent30d: "Sent (30 days)",
  colEnabled: "Enabled",
  ariaEnable: "Enable {name}",

  colTaskNo: "Task no.",
  colAudience: "Audience",
  colReach: "Estimated reach",
  reachEmpty: "0 (audience is empty)",
  colScheduledAt: "Scheduled for",
  colStatus: "Status",
  colActions: "Actions",
  btnSendNow: "Send now",

  colTicketNo: "Ticket no.",
  colTitle: "Subject",
  colUser: "Customer",
  colOrder: "Order",
  colAssignee: "Assignee",
  unassigned: "Unassigned",
  colProxy: "On behalf",
  proxyCount: "{n}",
  actionHandle: "Handle",
  actionView: "View",

  colFaqNo: "No.",
  colQuestion: "Question",
  colCategory: "Category",
  colAnswer: "Answer",
  answerMissing: "Not written",
  colViews: "Views",
  colPublished: "Listed",
  ariaPublish: "List {q}",
  actionEdit: "Edit",

  quotaTitle: "Outreach frequency caps",
  quotaReadOnlyWhat: "frequency cap settings",
  quotaNotice:
    "Both caps must be above 0 — setting 0 means no cap at all while the screen still looks configured, which is worse than not configuring it.",
  fieldDaily: "Max per customer per day",
  fieldInterval: "Minimum gap for the same template (hours)",
  save: "Save",

  sectionTasks: "Push tasks",
  emptyTasks: "No push tasks yet. A push needs an audience and a template, and cannot be sent when the estimated reach is 0.",
  sectionTemplates: "Message templates",
  emptyTemplates: "No message templates yet. Subscription-message templates must be filed with WeChat first.",

  ticketReadOnlyWhat: "ticket assignment & actions on behalf",
  searchTickets: "Search ticket no. / subject / customer / order",
  filterStatus: "Filter by status",
  filterStatusAll: "All statuses",
  emptyTickets: "No tickets match these filters. Clear them, or try another status — unassigned tickets deserve attention first.",

  faqNotice: "An entry with no answer cannot be listed: a customer tapping through to a blank page is worse than the entry not existing.",
  addFaqLabel: "New entry",
  emptyFaq:
    "The help center is empty. The three things customers ask most: where the pickup code is, how weight differences are settled, and what happens if they miss the pickup window.",

  btnCloseTicket: "Close ticket",
  fieldAssignee: "Assignee",
  assigneePlaceholder: "Staff username, e.g. cs02",
  btnAssign: "Assign",
  fieldProxy: "Record an action on behalf",
  proxyPlaceholder: "What you did for the customer, e.g. “changed delivery address: A → B”, “raised a refund: ¥18.60”",
  btnLogProxy: "Record",
  proxyHint:
    "Acting on behalf means changing a customer's data or issuing a refund — without a record there is no way to tell who did it (matrix P-14.2.3).",
  fieldProxyLog: "Actions on behalf",
  none: "None",

  faqDrawerEdit: "Edit entry",
  faqDrawerNew: "New entry",
  fieldQuestion: "Question",
  fieldCategory: "Category",
  categoryPlaceholder: "e.g. Pickup / Fresh produce / Merchants",
  fieldAnswer: "Answer",
  answerPlaceholder: "Write it the way a customer would say it — do not paste the policy text",
  answerHint: "An entry with no answer can be saved as a draft, but cannot be listed.",
};

export const MESSAGES_COPY: PageCopy<typeof zh> = { zh, en };
