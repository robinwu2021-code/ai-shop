package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Existing FOOD checkout source metadata; core/PAY remains the authoritative financial ledger. */
@Getter
@Setter
@TableName("fnb_settlement")
public class FnbSettlement extends BaseEntity {
    private LocalDateTime nextRecoveryAt;
    private String entityNo;
    private String settlementNo;
    private String storeNo;
    private String currency;
    private String status;
    private String scopeHash;
    private String quoteRef;
    private Long dueMinor;
    private String coreCashierRef;
    private LocalDateTime expiresAt;
    private String requestKey;
    private String requestHash;
    private String requestSnapshot;
    private String buyerNo;
}
