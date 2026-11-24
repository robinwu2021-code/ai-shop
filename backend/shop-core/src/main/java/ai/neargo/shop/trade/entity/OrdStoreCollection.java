package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 挂在门店零售单上的收款码 / 付款链接收款（V695，TDD-统一订单与开票资料 AC7 / AC16）。
 * 支付单 {@code biz_no} = 子单号，收款成功时落一行；金额是支付域确认的实收，不是端上报的。一张支付单只一行。
 */
@Getter
@Setter
@TableName("ord_store_collection")
public class OrdStoreCollection extends BaseEntity {
    private String payOrderNo;
    private String orderNo;
    private String subOrderNo;
    private String entityNo;
    private String storeNo;
    private String currency;
    private Long paidMinor;
    private String payChannel;
}
