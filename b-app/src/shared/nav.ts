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
  payment: "/pages/payment/index",
  stores: "/pages/stores/index",
  staff: "/pages/staff/index",
  afterSale: "/pages/after-sale/index",
  reviews: "/pages/reviews/index",
  quotes: "/pages/quotes/index",
  groups: "/pages/groups/index",
  settle: "/pages/settle/index",
  stats: "/pages/stats/index",
  customers: "/pages/customers/index",
  marketing: "/pages/marketing/index",
} as const;

export const TABS = [
  { key: "home", route: ROUTES.home, icon: "home", iconOn: "homeFilled", labelKey: "tab.home" },
  { key: "orders", route: ROUTES.orders, icon: "grid", iconOn: "gridFilled", labelKey: "tab.orders" },
  { key: "goods", route: ROUTES.goods, icon: "cart", iconOn: "cartFilled", labelKey: "tab.goods" },
  { key: "me", route: ROUTES.me, icon: "user", iconOn: "userFilled", labelKey: "tab.me" },
] as const;
