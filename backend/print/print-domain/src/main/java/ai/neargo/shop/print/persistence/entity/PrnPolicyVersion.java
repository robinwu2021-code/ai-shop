package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_policy_version")
public class PrnPolicyVersion extends PrnRow {
    @TableField(value = "policy_version_no", updateStrategy = FieldStrategy.ALWAYS)
    private String policyVersionNo;

    @TableField(value = "policy_no", updateStrategy = FieldStrategy.ALWAYS)
    private String policyNo;

    @TableField(value = "version_number", updateStrategy = FieldStrategy.ALWAYS)
    private Integer versionNumber;

    @TableField(value = "effective_from", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime effectiveFrom;

    @TableField(value = "policy_snapshot", updateStrategy = FieldStrategy.ALWAYS)
    private String policySnapshot;

    @TableField(value = "content_hash", updateStrategy = FieldStrategy.ALWAYS)
    private String contentHash;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private String createdBy;
}
