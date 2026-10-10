package ai.neargo.shop.report.dto;

import java.time.LocalDate;

/**
 * 商品日汇总的一行，与 {@code rpt_daily_goods} 一一对应。
 *
 * <p><b>{@code qty} 不含赠品</b>：赠品行价格为 0（买赠活动送的），混进来的话
 * 「送出去 100 件」会被读成「卖了 100 件」。两者各一列，每个数只说一件事。
 *
 * <p><b>没有逐商品的退货数</b>：售后单只到子单级，没有行级信息 —— 见 V2 的注释。
 *
 * <p><b>{@code title} / {@code spec} 是写入时的快照</b>，不是当前值 ——
 * 报表库不 JOIN 商品表，而那张报表描述的是那一天。
 */
public record DailyGoodsRow(
        LocalDate statDate,
        String entityNo,
        String storeNo,
        String goodsNo,
        String title,
        String spec,
        String categoryNo,
        int qty,
        long amountMinor,
        int giftQty) {
}
