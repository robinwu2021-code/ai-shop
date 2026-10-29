package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable hash-only credentials allow multiple application nodes without persisting bearer secrets. */
@Getter
@Setter
@TableName("mbr_identity_ticket")
public class MbrIdentityTicket extends BaseEntity {
    private String ticketHash;
    private String purpose;
    private String userNo;
    private String customerNo;
    private String entityNo;
    private String storeNo;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String sourceTicketHash;
    private String operatorScopeType;
    private String issuerNo;
    private Long brandScopeRevision;
    private String scopeMemberNo;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
}
