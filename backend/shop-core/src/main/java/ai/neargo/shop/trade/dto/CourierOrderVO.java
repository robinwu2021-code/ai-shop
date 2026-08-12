package ai.neargo.shop.trade.dto;

/**
 * 配送员看到的订单 —— {@link OrderVO} 的<b>裁剪档</b>。
 *
 * <h2>为什么是另一个类型，而不是把 OrderVO 的字段藏起来</h2>
 * 需求（三端角色权限功能对齐清单 §4.4）原话：<b>不用条件序列化藏字段</b> ——
 * 「那种藏法迟早被某个 DTO 复用漏出去」。
 *
 * 藏字段的失败方式是安静的：某天有人给 {@code OrderVO} 加一个
 * {@code buyerPhone}，忘了同步那份「配送员不能看」的字段名单，
 * 于是它就下发出去了，<b>没有任何报错</b>。换成独立的 record 之后，
 * 新字段默认不在这里 —— 要给配送员看必须显式加一行，而那一行会被 review 看见。
 *
 * <h2>裁掉了什么，为什么</h2>
 * <ul>
 *   <li><b>金额</b>（{@code amount} 八个字段）—— 他送的是货不是钱。
 *       配送员知道每单值多少，是把「这单该不该多留意」变成了他的判断</li>
 *   <li><b>核销码</b>（{@code verifyCode}）—— 取货码是<b>凭证</b>。
 *       他没有 {@code biz:verify} 核销不了，但凭证本身不该散出去</li>
 *   <li><b>买家与商品明细</b> —— 自送只需要知道送几件到哪里</li>
 * </ul>
 *
 * <h2>⚠️ 收货地址还没有</h2>
 * 需求写的是「待自送的单 + <b>地址</b>」，而地址这一列<b>后端从来没下发过</b>：
 * {@code ord_sub_order} 上只有 {@code address_id}，{@link OrderVO} 里连字段都没有，
 * b-app 的配送页也没渲染过它。所以本次只完成「裁剪」这一半，
 * <b>「补地址」是一件独立的、当前对所有角色都缺失的能力</b>，另开任务。
 *
 * @param orderNo     子单号。<b>字段名随 {@link OrderVO} 的订单视角</b>（那里的
 *                    {@code orderNo} 装的也是子单号）—— 换个名字的话，端上「标记送达」
 *                    要按返回类型分别取 {@code orderNo} 与 {@code subOrderNo} 两个字段，
 *                    而那是纯粹自找的分支
 * @param status      展示状态，端上按它筛「待自送」
 * @param fulfillment 履约方式。配送员只该看到自送单，但这一层不做过滤（过滤是查询的事），
 *                    字段留着让端上能判
 * @param itemQty     总件数。<b>只有件数没有品名</b> —— 他要知道搬几件，不需要知道搬的是什么
 * @param createdAt   下单时间，用来排先后
 */
public record CourierOrderVO(String orderNo,
                             String status,
                             String fulfillment,
                             int itemQty,
                             long createdAt) {

    /**
     * 从完整视图裁下来。
     *
     * <p>在<b>出口处</b>裁而不是在查询里裁：查询只有一份，两个档共用同一条 SQL 与同一份
     * 数据范围收窄，不会出现「配送员那条查询忘了带门店条件」这种两份实现才会有的错。
     */
    public static CourierOrderVO of(OrderVO o) {
        int qty = o.items() == null ? 0
                : o.items().stream().mapToInt(OrderVO.ItemVO::qty).sum();
        return new CourierOrderVO(o.orderNo(), o.status(), o.fulfillment(), qty, o.createdAt());
    }
}
