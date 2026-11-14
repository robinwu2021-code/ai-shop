package ai.neargo.shop.beauty.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable scoped reception fact; never a shared customer master. */
@Getter
@Setter
@TableName("bty_arrival_challenge")
public class BtyArrivalChallenge extends BaseEntity {
    /** entity_no; scoped internal persistence only. */
    private String entityNo;
    /** store_no; scoped internal persistence only. */
    private String storeNo;
    /** challenge_no; scoped internal persistence only. */
    private String challengeNo;
    /** code; scoped internal persistence only. */
    private String code;
    /** issued_at; scoped internal persistence only. */
    private LocalDateTime issuedAt;
    /** expires_at; scoped internal persistence only. */
    private LocalDateTime expiresAt;
    /** actor_no; scoped internal persistence only. */
    private String actorNo;
    /** actor_realm; scoped internal persistence only. */
    private String actorRealm;
    /** actor_kind; scoped internal persistence only. */
    private String actorKind;
    /** key_hash; scoped internal persistence only. */
    private String keyHash;
}
