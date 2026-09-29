package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_stock_batch")
public class ElcStockBatch extends ElcMutableEntity {

    public static final String STATUS_PARSED = "PARSED";
    public static final String STATUS_APPLIED = "APPLIED";

    private String batchNo;

    private String supplierNo;

    private String fileName;

    private String mode;

    private Boolean taxIncluded;

    private String headers;

    private String columnMap;

    private Integer rowTotal;

    private Integer rowValid;

    private Integer rowInvalid;

    private Integer toInsert;

    private Integer toUpdate;

    private Integer toDelist;

    private Integer unchanged;

    private String status;

    private java.time.LocalDateTime appliedAt;
}
