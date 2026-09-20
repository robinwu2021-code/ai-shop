package ai.neargo.shop.common;

import java.util.List;

/**
 * 「这笔单买了什么」的一句话描述。**两个地方要用同一串**：
 *
 * <ul>
 *   <li>微信支付下单的 {@code description}（≤127）——
 *       用户在微信「我-小店与卡包-小程序购物订单」里看到的商品信息就是它；</li>
 *   <li>发货信息录入的 {@code item_desc}（≤120，微信 10060008 不许为空）。</li>
 * </ul>
 *
 * <h2>为什么抽出来</h2>
 * 两处各写一遍的话，用户在「购物订单」列表里看到的商品名与点进去之后
 * 物流卡片上的商品名会<b>对不上</b> —— 而这种不一致没有任何闸门看得见，
 * 只有真用户会遇到，且他会以为点错了单。
 *
 * <p>放 {@code shop-base}：交易域（拼支付描述）与 {@code shop-app} 的 paybridge
 * （拼上报描述）都够得着，而它们互相够不着。
 */
public final class GoodsDesc {

    private GoodsDesc() {
    }

    /** 微信支付 {@code description} 的上限 */
    public static final int PAY_MAX = 127;
    /** 发货信息录入 {@code item_desc} 的上限 */
    public static final int SHIPPING_MAX = 120;

    /**
     * 第一件商品的标题，多件补「等N件」。
     *
     * <h2>为什么是「第一件 + 等N件」而不是全部拼起来</h2>
     * 全拼会在 120 字处被截断，而截断点落在第二、三件商品名的中间 ——
     * 用户看到的是一串读不通的半个词。只报第一件是<b>确定能读通</b>的那种取舍：
     * 他认得出这是哪一单，剩下的点进小程序看。
     *
     * @param titles 订单明细的商品名，<b>按下单顺序</b>。空标题会被跳过
     * @param max    上限，用 {@link #PAY_MAX} 或 {@link #SHIPPING_MAX}
     * @return 拼好的描述；<b>一个能用的标题都没有时返回空串</b> ——
     *         调用方必须把空串当缺件处理，不要兜一个「商品」之类的占位词：
     *         兜了之后微信收下，而用户在订单列表里看到的每一单都叫「商品」
     */
    public static String of(List<String> titles, int max) {
        if (titles == null || titles.isEmpty() || max <= 0) {
            return "";
        }
        String first = titles.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .findFirst().orElse("");
        if (first.isEmpty()) {
            return "";
        }
        long n = titles.stream().filter(s -> s != null && !s.isBlank()).count();
        String tail = n > 1 ? "等" + n + "件" : "";
        /*
         * 先给「等N件」留位置再截标题。反过来做（先截标题到 max 再拼尾巴）
         * 会让总长超出上限，而微信那边是直接拒还是静默截取决于字段，
         * 不该赌 —— 赌错的表现是上报失败，而失败的代价是钱结不出来。
         */
        int room = max - tail.length();
        if (room <= 0) {
            return tail.substring(0, max);
        }
        return (first.length() <= room ? first : first.substring(0, room)) + tail;
    }
}
