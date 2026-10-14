package ai.neargo.shop.trade.order.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 售后行（V404）：一张售后单退的是哪几行、各几件、各多少钱。 */
@Getter
@Setter
@TableName("ord_after_sale_item")
public class OrdAfterSaleItem extends BaseEntity {

    private String afterSaleNo;
    private String subOrderNo;
    /** 订单行 ord_item.id */
    private Long itemId;
    private Integer qty;
    /** 本行本次退款额（商家核定 / 协商后按比例分回） */
    private Long refundMinor;
}
