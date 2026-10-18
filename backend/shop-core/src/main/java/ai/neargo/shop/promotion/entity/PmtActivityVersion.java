package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Core-owned activity facts; pay assets remain outside this persistence boundary. */
@Getter
@Setter
@TableName("pmt_activity_version")
public class PmtActivityVersion extends BaseEntity {
    private String versionNo;
    private String activityNo;
    private Integer versionIndex;
    private Integer schemaVersion;
    private String currency;
    private String scheduleType;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String marketZone;
    private String ruleSnapshot;
    private String ruleHash;
    private String publishedBy;
    private LocalDateTime publishedAt;
}
