package ai.neargo.shop.printing.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Shared printing persistence; business identifiers alone cross the API boundary. */
@Getter
@Setter
@TableName("prn_device")
public class PrnDevice extends BaseEntity {
    private String entityNo;
    private String deviceNo;
    private String storeNo;
    private String vendor;
    private String connectionType;
    private String credentialRef;
    private String bridgeActorNo;
    private String status;
    private String capabilities;
    private LocalDateTime lastSeenAt;
    private Integer online;
    private Long authorizationVersion;
    private String revokeReason;
    private String revokedBy;
    private String revokeKey;
    private String revocationHistory;
}
