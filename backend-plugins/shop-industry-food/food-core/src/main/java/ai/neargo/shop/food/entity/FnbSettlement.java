package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Existing FOOD checkout source metadata; core/PAY remains the authoritative financial ledger. */
@Getter
@Setter
@TableName("fnb_settlement")
public class FnbSettlement extends BaseEntity {
    /** Recovery lease; only the recovery scanner moves it, entity write-back never does (C2). */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime nextRecoveryAt;
    /** 结算来源：空或 H5 = 顾客自助先付；COUNTER = 柜台后付结账（V40）。 */
    private String kind;
    /** 柜台结账：核心实收的实收合计与找零（V40）；确认前与其他结算单为空。 */
    private Long receivedMinor;
    private Long changeMinor;
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
