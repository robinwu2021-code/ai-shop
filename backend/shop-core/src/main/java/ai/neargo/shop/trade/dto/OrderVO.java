package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 订单（对齐 c-app {@code Order} 的平铺模型 + Q6 双视角）。
 *
 * <p><b>同一个结构承担两种视角</b>：
 * <ul>
 *   <li><b>订单视角</b>（{@code orderNo} = 子单号）：单商家，有 {@code fulfillment} /
 *       {@code verifyCode} / {@code timeline}。订单列表、详情、售后、评价、核销都用它。</li>
 *   <li><b>支付视角</b>（{@code orderNo} = 主单号）：合计金额 + {@code subOrders}，
 *       **不给 {@code fulfillment}** —— 跨商家可能各不相同，给一个单值就是错的。只有收银台用。</li>
 * </ul>
 *
 * <p>为什么不拆成两个类：c-app 的 `Order` 类型只有一个，拆两个会逼前端在每个页面判断「这是哪种」。
 * 用可空字段表达差异，端上按字段有无渲染即可。
 */
public record OrderVO(String orderNo,
                      /** 支付单号（主单）。两种视角都带，收银台靠它跳转 */
                      String payOrderNo,
                      String status,
                      /** 支付视角为 null */
                      String fulfillment,
                      String merchantNo,
                      String merchantName,
                      List<ItemVO> items,
                      Amount amount,
                      /** 自提码/核销码/兑换码三态共用；支付成功后才有 */
                      String verifyCode,
                      String pickupNo,
                      String pickupName,
                      /** 支付截止时间（原 expireAt，随前端命名） */
                      Long payDeadlineAt,
                      long createdAt,
                      Long paidAt,
                      /**
                       * 快递单号（EXPRESS 履约）。
                       *
                       * <p><b>买家和商家都要看得到</b>：没有单号的「已发货」对买家没有任何用处 ——
                       * 他既查不到物流，也无法判断该不该继续等；商家这边则无法核对自己填了什么。
                       * 此前这一列在库里有、在 VO 里没有，于是发货这件事对两边都不可见。
                       */
                      String expressNo,
                      String trafficSource,
                      /**
                       * 收件人（V69 快照）。<b>自提单为 null</b>。
                       *
                       * <p>此前这一列在库里有 {@code address_id}、在 VO 里连字段都没有 ——
                       * 于是「商家自送」这条链路上，<b>所有角色（含店主）都不知道送到哪里</b>，
                       * 而页面上只有一个「已送达」按钮。
                       */
                      Receiver receiver,
                      List<TimelineNode> timeline,
                      /** **仅支付视角**：一次支付覆盖的各商家订单 */
                      List<OrderVO> subOrders,
                      /**
                       * 下单人昵称。**商家侧才有**（B12：认得出是谁，不给联系方式）。
                       *
                       * <p>端上的契约一直有这个字段，后端此前不下发 —— 于是
                       * B 端售后页每一行的买家都是「—」：店主要处理一张退货单，
                       * 却看不到是谁申请的，只能回订单列表里对号入座。
                       */
                      String buyerNickname) {

    /**
     * 金额值对象（字段名随 c-app）。把 8 个金额收在一起，
     * 比在订单上平铺 8 个 `xxxAmount` 更难写错 —— 传参时少一个就编译不过。
     */
    public record Amount(long goodsMinor,
                         long freightMinor,
                         long discountMinor,
                         long payableMinor,
                         long paidMinor,
                         long pointsDeductMinor,
                         int pointsUsed,
                         int pointsEarn,
                         String currency) {

        /**
         * 不带积分的老工厂。<b>它把积分三个字段写死成 0</b> ——
         * 于是「库里有 points_deduct_minor、VO 里有 pointsDeductMinor」的同时，
         * 端上拿到的永远是 0，而两侧代码单独看都对。
         *
         * <p>保留它是因为大量调用方确实没有积分（预览、子单视图），
         * 但**有积分的路径必须用下面那个** —— 少传一次就是一次「金额对不上」。
         */
        public static Amount of(long goods, long freight, long discount, long paid, String currency) {
            return of(goods, freight, discount, paid, 0L, 0, currency);
        }

        /**
         * @param pointsDeduct 积分抵扣金额（分）。**payable 要把它减掉** ——
         *                     不减的话结算页显示的应付比实际扣款高，用户会以为多扣了钱
         * @param pointsUsed   用掉的积分数
         */
        public static Amount of(long goods, long freight, long discount, long paid,
                                long pointsDeduct, int pointsUsed, String currency) {
            return new Amount(goods, freight, discount,
                    goods + freight - discount - pointsDeduct, paid,
                    pointsDeduct, pointsUsed, 0, currency);
        }
    }

    public record ItemVO(String goodsNo,
                         String merchantNo,
                         String skuNo,
                         String title,
                         String cover,
                         String spec,
                         long price,
                         int qty,
                         long amount,
                         String type,
                         /**
                          * 赠品行（买赠活动送的），价格为 0。
                          *
                          * <p>端上的 `OrderItem.isGift` 一直有这个字段，后端此前不下发 ——
                          * 于是买赠订单里会出现一条「¥0.00 ×4」的行，而 C 端认不出它是赠品，
                          * 既显示不了「赠」标，也无法与「商家把价格填成 0」区分开。
                          */
                         boolean isGift) {
    }

    /**
     * 收件人。
     *
     * @param phone <b>脱敏与否由调用方决定</b>（见 {@code MerchantOrderServiceImpl}）：
     *              商家自送给完整号 —— 送不到门口就得打电话，给后四位等于让人站在楼下干瞪眼；
     *              其余履约方式给后四位（B12：商家不需要能打给每一个买家）。
     *              <b>这条规则写在装配的地方，不写在这里</b> ——
     *              record 只是形状，谁能看到多少是那一处的判断
     */
    public record Receiver(String name, String phone, String address) {
    }

    public record TimelineNode(String status, String label, long at) {
    }
}
