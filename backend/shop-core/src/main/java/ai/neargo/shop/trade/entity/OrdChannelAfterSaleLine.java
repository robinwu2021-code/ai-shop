package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("ord_channel_after_sale_line")
public class OrdChannelAfterSaleLine extends BaseEntity {
    private String decisionNo;
    private String entityNo;
    private String storeNo;
    private String admissionNo;
    private String sourceLineRef;
    private String componentRef;
    private BigDecimal physicalQuantity;
    private Long refundCeilingMinor;
}
