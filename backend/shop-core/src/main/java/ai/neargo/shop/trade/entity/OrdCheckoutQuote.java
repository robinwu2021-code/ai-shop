package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_checkout_quote")
public class OrdCheckoutQuote extends BaseEntity {
    private String quoteNo;
    private String operationNo;
    private String caller;
    private Long revision;
    private String quoteHash;
    private String pricingJson;
    private LocalDateTime expiresAt;
    private String status;
}
