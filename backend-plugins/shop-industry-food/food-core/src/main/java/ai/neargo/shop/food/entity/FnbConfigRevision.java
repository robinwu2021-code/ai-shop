package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Only the append/select Mapper can persist these immutable business facts. */
@Getter
@Setter
@TableName("fnb_config_revision")
public class FnbConfigRevision extends BaseEntity {
    private String entityNo;
    private String revisionNo;
    private String configKind;
    private String configNo;
    private Long revision;
    private Integer contentSchema;
    private String definitionSnapshot;
    private String contentHash;
    private LocalDateTime effectiveAt;
    private String status;
    private String previousRevisionNo;
    private String scopeStoreNos;
}
