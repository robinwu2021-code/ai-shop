package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("ord_checkout_confirmation")
public class OrdCheckoutConfirmation extends BaseEntity {
    /** Historical customer identity never changes when the guest later claims an account. */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String customerKind = "USER";

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String guestNo;

    @TableField(insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.NEVER)
    private Long identityRevision;

    private String entityNo;
    private String storeNo;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String admissionKind = "LEGACY_FUNDED";

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String purchaseAuthority = "CONSUMER_SESSION";

    private String authorityNamespaceHash;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String accountNo;
    private LocalDateTime acceptedAt;

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
