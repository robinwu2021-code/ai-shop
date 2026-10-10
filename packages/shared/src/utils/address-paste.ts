import { isCompleteRegion, joinRegion, regionStart, splitRegion } from "./region";

/**
 * 从**粘贴的一段文字**里认出收货信息。
 *
 * <p>用户手上多半已经有这么一串（微信里别人发的、订单里复制的）：
 *
 * <pre>张三 13800138000 浙江省杭州市西湖区阳光里小区3幢2单元601</pre>
 *
 * <p><b>它只省打字，不给坐标。</b>粘贴出来的地址与从微信地址簿导入的一样 ——
 * 只有字，没有经纬度。所以它**不是选点页的替代**：商家的自送半径、骑手导航
 * 仍然要靠那一次地图选点。两者是配合关系，界面上也要这么说。
 *
 * <p><b>认不出来就留空，绝不猜。</b>一个猜错的收货人姓名会静默寄给别人，
 * 而空着的格子用户自己会填 —— 后者只是麻烦，前者是事故。
 */
export interface PastedAddress {
  name: string;
  phone: string;
  region: string;
  province: string;
  city: string;
  district: string;
  /** 地址主体。**不含门牌**时门牌那一格留空，不硬切 */
  detail: string;
  houseNo: string;
}

/** 常见的字段标签。带标签的那种最好认，先把标签本身去掉 */
const LABELS = /(收货人|收件人|联系人|姓名|电话|手机号?|联系电话|收货地址|详细地址|地址)\s*[：:]\s*/g;

/** 全角数字 → 半角。有人从表格里复制过来就是全角的 */
const FULL_WIDTH_DIGITS = /[０-９]/g;

/**
 * 手机号：允许数字之间夹空格或横杠（`138 0013 8000`、`138-0013-8000`）。
 * **号段照样要查** —— 只认 11 位数字会把 `00000000000` 收进来。
 */
const PHONE = /1[3-9](?:[\s-]*\d){9}/;

/**
 * 门牌只在**强信号**上切：`幢 / 栋 / 单元 / 室`。
 *
 * <p>刻意不认 `号` 与 `楼`：`文一西路100号` 里的「100号」是路名门牌、
 * 是地址主体的一部分，切在那里会把地址拦腰截断 ——
 * 而用户很可能不会注意到，直到快递员打电话。
 */
const HOUSE_NO = /\d+\s*(?:幢|栋|单元|室)/;

/** 姓名最多几个字。搜索窗口越大，认错的可能越大 */
const MAX_NAME_LEN = 6;

function normalize(raw: string): string {
  return raw
    .replace(FULL_WIDTH_DIGITS, (d) => String.fromCharCode(d.charCodeAt(0) - 0xfee0))
    .replace(LABELS, " ")
    // 中英文标点一律当分隔符；换行同理
    .replace(/[，,。、；;|()（）【】\[\]\n\r\t]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * 认出这一段。**一个字段都没认出来时返回 null**，让调用方能说「没认出来」——
 * 返回一个全空的对象会让「认出来了但都是空」和「没认出来」变成同一件事。
 */
export function parsePastedAddress(raw: string): PastedAddress | null {
  const text = normalize(raw ?? "");
  if (!text) return null;

  const hit = PHONE.exec(text);
  const phone = hit ? hit[0].replace(/[\s-]/g, "") : "";
  const rest = (hit ? text.slice(0, hit.index) + " " + text.slice(hit.index + hit[0].length) : text)
    .replace(/\s+/g, " ")
    .trim();

  /*
   * **姓名靠「地址从哪儿开始」反推。**
   *
   * `regionStart` 按省级单位的规范名精确定位，而不是靠后缀猜 ——
   * 猜的话「张三浙江省杭州市…」会被拆出 province=「张三浙江省」，
   * 而那一串（姓名与地址之间没有分隔符）正是最常见的输入形状。
   *
   * 找不到省名时**姓名留空**，不拿第一个词顶上：
   * 把「阳光里小区」当成收货人，比空着糟得多。
   */
  const at = regionStart(rest, MAX_NAME_LEN);
  const name = at > 0 ? rest.slice(0, at).trim() : "";
  const addr = at >= 0 ? rest.slice(at) : rest;

  const parts = splitRegion(addr);
  const body = parts.rest.trim();

  let detail = body;
  let houseNo = "";
  const h = HOUSE_NO.exec(body);
  if (h && h.index > 0) {
    detail = body.slice(0, h.index).trim();
    houseNo = body.slice(h.index).trim();
  }

  const out: PastedAddress = {
    name,
    phone,
    region: isCompleteRegion(parts) ? joinRegion(parts) : "",
    province: parts.province,
    city: parts.city,
    district: parts.district,
    detail,
    houseNo,
  };
  /*
   * **判据是「手机号或省市区」，光有一段文字不算。**
   *
   * 只看 detail 的话，随便粘一句「你好啊」也会被当成认出来了一个地址 ——
   * 而那之后端上会把它填进表单，用户得先删掉才能继续。
   */
  return phone || out.region ? out : null;
}
