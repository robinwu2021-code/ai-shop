// 元器件小程序词条。只放两类：组件库要的通用词，与页面标题（sh-scaffold 的 title-key）。
// 页面标题必须与 pages.json 的 navigationBarTitleText 逐字一致 —— gen-ui-catalog 会比对。
export default {
  common: {
    loadFailed: "没能加载出来",
    loadFailedTip: "多半是网络不通。检查网络后重试。",
    retry: "重试",
    confirm: "确定",
    cancel: "取消",
    done: "完成",
    noPermTitle: "没有权限",
    noPermHint: "这一页你看不了",
  },
  theme: {
    skinPure: "纯白底 · 只换主色与字色",
    skinFull: "整套配色 · 背景与字色一起换",
    title: "外观",
    mode: "明暗",
    language: "语言",
  },
  market: { CN: "中国 · 人民币", label: "地区与货币" },
  login: {
    resend: "{s}s 后重发",
  },
  title: {
    home: "元器件",
    search: "搜索结果",
    part: "料号详情",
    lookup: "批量查",
    rfqCreate: "询价",
    rfqs: "我的询价",
    rfq: "询价详情",
    supplierJoin: "成为供应商",
    supplier: "供应商工作台",
    stockUpload: "上传库存",
    stockPreview: "上架前确认",
    stocks: "我的库存",
    stockBatches: "上传记录",
    supplierProfile: "供应商资料",
    dispatches: "求购",
    dispatch: "求购详情",
    login: "登录",
  },
};
