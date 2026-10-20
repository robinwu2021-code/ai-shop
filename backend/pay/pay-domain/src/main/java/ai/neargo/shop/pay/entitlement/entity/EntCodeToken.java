package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** No raw credential or reversible ciphertext is persisted. */
@Getter
@Setter
@TableName("ent_code_token")
public class EntCodeToken extends BaseEntity {
    private String tokenHash;
    private String instanceNo;
    private String userNo;
    private String entityNo;
    private String requestKeyHash;
    private String payloadHash;
    private String nonce;
    private LocalDateTime expiresAt;
    private String status;
    private String consumedOperationNo;
    private String consumedStoreNo;
    private String consumedActorNo;
    private LocalDateTime consumedAt;
}
