package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Payment-owned catalog row; published snapshots are never rewritten or deleted. */
@Getter
@Setter
@TableName("ent_template")
public class EntTemplate extends BaseEntity {
    private String templateNo;
    private String entityNo;
    private String title;
    private String kind;
    private String funder;
    private String status;
    private String currentVersionNo;
    private String draftSnapshot;
    private LocalDateTime archivedAt;
}
