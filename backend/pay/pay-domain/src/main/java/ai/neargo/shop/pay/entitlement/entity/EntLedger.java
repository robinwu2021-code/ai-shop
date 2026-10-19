package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Immutable source identity and owner-side balances are kept in the pay transaction. */
@Getter
@Setter
@TableName("ent_ledger")
public class EntLedger extends BaseEntity {
    private String entryNo;
    private String instanceNo;
    private String bizType;
    private String bizKey;
    private String bucketKey;
    private String originEntryNo;
    private Long assetSeq;
    private Integer unitsDelta;
    private Integer reservedDelta;
    private Integer afterUnits;
    private Integer afterReserved;
    private LocalDateTime occurredAt;
}
