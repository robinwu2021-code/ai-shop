package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_checkout_confirmation")
public class OrdCheckoutConfirmation extends BaseEntity {
    private String confirmationNo;
    private String caller;
    private String operationNo;
    private String quoteNo;
    private Long quoteRevision;
    private String quoteHash;
    private String buyerNo;
    private String requestKeyHash;
    private String requestHash;
    private Long amountMinor;
    private String currency;
    private String purpose;
    private LocalDateTime expiresAt;
    private String status;
    private LocalDateTime confirmedAt;
    private LocalDateTime consumedAt;
    private String orderNo;
}
