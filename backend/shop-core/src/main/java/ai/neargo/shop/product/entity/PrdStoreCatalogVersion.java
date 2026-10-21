package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Product-owned store catalog immutable publication. */
@Getter
@Setter
@TableName("prd_store_catalog_version")
public class PrdStoreCatalogVersion extends BaseEntity {
    private String catalogVersionNo;
    private String catalogNo;
    private String entityNo;
    private String storeNo;
    private Long revisionIndex;
    private LocalDateTime effectiveAt;
    private String frozenPayload;
    private String snapshotHash;
}
