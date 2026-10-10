/**
 * 快递公司 —— **存的、发的都是微信的 `delivery_id`**。
 *
 * ## 这不是 `Carrier`
 *
 * 仓库里另有一张 `ful_carrier`（运营配置，`/biz/fulfillment/carriers` 下发），
 * 那是**调拨**用的承运方档案，带账号、API key、SLA。两者编码碰巧重合，
 * 但它是运营随手能改的自由文本 —— 填了 `SFEXPRESS` 微信就不认，
 * 而表现是快递单**静默报不上去**、货款冻在微信那边。
 * 对微信上报这一侧，必须以微信的表为准。
 *
 * ## 为什么只有十几家，而不是微信的全量 1509 家
 *
 * 全量里绝大多数是跨境小承运商，我们的商家用不到；
 * 而发货界面上真正要的是十几个能一眼点中的按钮，不是一个 1509 项的搜索框。
 *
 * ## 与后端那份的关系
 *
 * 后端 `ExpressCompanies.java` 是校验方（`ship()` 认不得的码直接拒）。
 * 两份必须逐项一致 —— 端上能选而后端拒，商家会看到一个莫名其妙的「参数错误」。
 * `express-companies.test.ts` 对账，少一条、多一条、名字不同都会红。
 *
 * ## 每个码都核验过
 *
 * 2026-09-20 用微信 `get_delivery_list` 的全量表逐个查过。易错的几个：
 * 韵达是 `YD` 不是 `YUNDA`（不存在）；邮政包裹是 `YZPY` 不是 `POSTB`（不存在）；
 * 顺丰要 `SF`（速运）不是 `NSF`（新顺丰）；百世快递是 `HTKY`，`BTWL` 是百世快运。
 */
export interface ExpressCompany {
  /** 微信 delivery_id，原样上报 */
  readonly code: string;
  /** 展示名，与微信的 delivery_name 一致 —— 不一致的话商家选的和微信显示的对不上 */
  readonly name: string;
}

/** 顺序就是发货界面的显示顺序：按国内件实际用量从高到低 */
export const EXPRESS_COMPANIES: readonly ExpressCompany[] = [
  { code: "SF", name: "顺丰速运" },
  { code: "ZTO", name: "中通快递" },
  { code: "YTO", name: "圆通速递" },
  { code: "YD", name: "韵达速递" },
  { code: "STO", name: "申通快递" },
  { code: "JTSD", name: "极兔速递" },
  { code: "JD", name: "京东快递" },
  { code: "YZPY", name: "邮政快递包裹" },
  { code: "EMS", name: "EMS" },
  { code: "DBL", name: "德邦快递" },
  { code: "HTKY", name: "百世快递" },
  { code: "FWX", name: "丰网速运" },
  { code: "UC", name: "优速快递" },
  { code: "ZJS", name: "宅急送" },
] as const;
