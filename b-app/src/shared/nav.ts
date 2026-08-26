// B 端路由与底部菜单。C 端的 TABS 是消费者视角（首页/分类/购物车/我的），
// 商家看的是「工作台/订单/商品/我的」—— 两套导航没有共用的意义，各自维护。
export const ROUTES = {
  home: "/pages/home/index",
  orders: "/pages/orders/index",
  goods: "/pages/goods-list/index",
  me: "/pages/me/index",
  login: "/pages/login/index",
  apply: "/pages/apply/index",
  goodsEdit: "/pages/goods-edit/index",
  order: "/pages/order/index",
  verify: "/pages/verify/index",
  picking: "/pages/picking/index",
  delivery: "/pages/delivery/index",
  store: "/pages/store/index",
  storeNotice: "/pages/store-notice/index",
  storeScope: "/pages/store-scope/index",
  storePick: "/pages/store-pick/index",
  payment: "/pages/payment/index",
  stores: "/pages/stores/index",
  storeCategories: "/pages/store-categories/index",
  mySpecs: "/pages/my-specs/index",
  skuIdentity: "/pages/sku-identity/index",
  qualifications: "/pages/qualifications/index",
  entities: "/pages/entities/index",
  entityDetail: "/pages/entity-detail/index",
  staff: "/pages/staff/index",
  staffDetail: "/pages/staff-detail/index",
  roleDetail: "/pages/role-detail/index",
  afterSale: "/pages/after-sale/index",
  messages: "/pages/messages/index",
  reviews: "/pages/reviews/index",
  quotes: "/pages/quotes/index",
  groups: "/pages/groups/index",
  income: "/pages/income/index",
  settle: "/pages/settle/index",
  stats: "/pages/stats/index",
  crossStore: "/pages/cross-store/index",
  plan: "/pages/plan/index",
  customers: "/pages/customers/index",
  marketing: "/pages/marketing/index",

  // ── 进销存（P-18）。**库存页是这一块的枢纽** ——
  // 工作台只开一道门到它，其余五屏从它里面进。
  // 每屏各在工作台/我的上摆一个入口的话，就回到「同一件事三个门，人记不住走哪个」
  stock: "/pages/stock/index",
  stockDetail: "/pages/stock-detail/index",
  stockCheck: "/pages/stock-check/index",
  purchaseEdit: "/pages/purchase-edit/index",
  stockDocs: "/pages/stock-docs/index",
  stockOut: "/pages/stock-out/index",
  transfer: "/pages/transfer/index",
  stockReport: "/pages/stock-report/index",
  locations: "/pages/locations/index",
} as const;

/**
 * tab 页路径集合。**推送落点判断要用它**：tab 页只能 switchTab 打开，
 * 用 navigateTo 会静默失败（点了没反应），而「新订单」的落点正是 tab 页。
 */
export const TAB_ROUTES: ReadonlySet<string> = new Set([
  ROUTES.home,
  ROUTES.orders,
  ROUTES.goods,
  ROUTES.me,
]);

export const TABS = [
  { key: "home", route: ROUTES.home, icon: "home", iconOn: "homeFilled", labelKey: "tab.home" },
  { key: "orders", route: ROUTES.orders, icon: "grid", iconOn: "gridFilled", labelKey: "tab.orders" },
  { key: "goods", route: ROUTES.goods, icon: "cart", iconOn: "cartFilled", labelKey: "tab.goods" },
  { key: "me", route: ROUTES.me, icon: "user", iconOn: "userFilled", labelKey: "tab.me" },
] as const;
