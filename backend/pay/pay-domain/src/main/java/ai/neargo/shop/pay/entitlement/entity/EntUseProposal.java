package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Pay-owned durable use fact; never written through a core transaction. */
@Getter
@Setter
@TableName("ent_use_proposal")
public class EntUseProposal extends BaseEntity {
    private String proposalNo;
    private String proposalKind;
    private String userNo;
    private String entityNo;
    private String payloadSnapshot;
    private String snapshotHash;
    private LocalDateTime expiresAt;
}
