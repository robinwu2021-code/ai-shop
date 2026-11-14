package ai.neargo.shop.member.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Only the member owner persists guest credentials; encrypted fields never form a public customer fact. */
@Getter
@Setter
@TableName("mbr_guest_registration")
public class MbrGuestRegistration extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String guestNo;
    private String actorNo;
    private String actorRealm;
    private String actorKind;
    private String keyHash;
    private String requestHash;
    private String contactCipher;
    private String displayName;
    private String claimTokenHash;
    private String claimTokenCipher;
    private String state;
    private String claimedUserNo;
    private LocalDateTime claimedAt;
    private String claimKeyHash;
    private String claimRequestHash;
    private String claimActorNo;
    private String claimActorRealm;
    private String claimActorKind;
}
