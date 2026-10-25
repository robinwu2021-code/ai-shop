package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_agent")
public class PrnAgent extends PrnRow {
    @TableField(value = "agent_no", updateStrategy = FieldStrategy.ALWAYS)
    private String agentNo;

    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "name", updateStrategy = FieldStrategy.ALWAYS)
    private String name;

    @TableField(value = "platform", updateStrategy = FieldStrategy.ALWAYS)
    private String platform;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "enrollment_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String enrollmentHash;

    @TableField(value = "enrollment_expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime enrollmentExpiresAt;

    @TableField(value = "credential_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String credentialHash;

    @TableField(value = "credential_version", updateStrategy = FieldStrategy.ALWAYS)
    private Integer credentialVersion;

    @TableField(value = "session_epoch", updateStrategy = FieldStrategy.ALWAYS)
    private Long sessionEpoch;

    @TableField(value = "software_version", updateStrategy = FieldStrategy.ALWAYS)
    private String softwareVersion;

    @TableField(value = "capabilities", updateStrategy = FieldStrategy.ALWAYS)
    private String capabilities;

    @TableField(value = "last_seen_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastSeenAt;

    @TableField(value = "revoked_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime revokedAt;
}
