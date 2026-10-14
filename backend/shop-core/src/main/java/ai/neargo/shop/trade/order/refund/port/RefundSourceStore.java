package ai.neargo.shop.trade.order.refund.port;

import ai.neargo.shop.trade.entity.OrdItem;

import java.util.List;

/** 算可退额要读的源数据：子单的行、主单的支付渠道。只读。 */
public interface RefundSourceStore {

    List<OrdItem> items(String subOrderNo);

    /** 主单支付渠道；查不到为 null（按不支持部分退处理） */
    String payChannel(String orderNo);
}
