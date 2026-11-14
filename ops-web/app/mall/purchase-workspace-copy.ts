const zh = {
  title: "购买办理", assist: "运营代购", kind: "套餐类型", entity: "主体套餐", store: "门店套餐", scope: "查询对象",
  entityPlaceholder: "请输入主体编号", storePlaceholder: "请输入门店编号", query: "查询",
  hint: "选定对象后查询购买订单，核款、退款及恢复按原授权办理。", first: "请先选择查询对象。",
  locked: "当前办理尚未结束，请先完成并关闭详情后再切换。", denied: "没有购买订单查询权限。",
  order: "订单号", storeNo: "门店", offer: "套餐", amount: "金额", status: "状态", detail: "详情",
  previous: "上一页", next: "下一页", empty: "该主体没有购买订单。", error: "查询失败，请重试。", retry: "重试",
};
export const PURCHASE_WORKSPACE_COPY = {
  zh,
  en: { title: "Purchases", assist: "Assisted purchase", kind: "Plan type", entity: "Entity plans", store: "Store plans", scope: "Purchase owner",
    entityPlaceholder: "Enter entity number", storePlaceholder: "Enter store number", query: "Search",
    hint: "Select an owner to query purchases. Verification, refunds and recovery retain their existing permissions.", first: "Select a purchase owner first.",
    locked: "Finish the current action and close its details before switching.", denied: "Purchase read access is required.",
    order: "Order", storeNo: "Store", offer: "Plan", amount: "Amount", status: "Status", detail: "Details", previous: "Previous", next: "Next", empty: "No purchases for this entity.", error: "Query failed. Please retry.", retry: "Retry" },
  "zh-TW": { ...zh, title: "購買辦理", assist: "營運代購", kind: "套餐類型", entity: "主體套餐", store: "門市套餐", scope: "查詢對象", entityPlaceholder: "請輸入主體編號", storePlaceholder: "請輸入門市編號", query: "查詢", hint: "選定對象後查詢購買訂單，核款、退款及恢復依原授權辦理。", first: "請先選擇查詢對象。", locked: "目前辦理尚未結束，請先完成並關閉詳情後再切換。", denied: "沒有購買訂單查詢權限。", order: "訂單號", storeNo: "門市", offer: "套餐", amount: "金額", status: "狀態", detail: "詳情", previous: "上一頁", next: "下一頁", empty: "該主體沒有購買訂單。", error: "查詢失敗，請重試。", retry: "重試" },
} as const;
