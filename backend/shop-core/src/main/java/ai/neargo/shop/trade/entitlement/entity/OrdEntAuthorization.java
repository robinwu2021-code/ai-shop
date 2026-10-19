package ai.neargo.shop.trade.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("ord_ent_authorization")
public class OrdEntAuthorization extends BaseEntity {
    private String intentKey;
    private String authorizationNo;
    private String requestKey;
    private String payloadHash;
    private String sourceKey;
    private String sourceSnapshot;
    private String orderUseSnapshot;
    private Long sourceRevision;
    private String orderNo;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String quoteRef;
    private String selectionDigest;
    private String orderSnapshotHash;
    private Long amountMinor;
    private String currency;
    private String status;
    private LocalDateTime authorizedAt;
    private LocalDateTime expiresAt;
}
