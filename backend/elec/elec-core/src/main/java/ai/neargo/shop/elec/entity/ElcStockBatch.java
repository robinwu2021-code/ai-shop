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

    private String currency;

    private String headers;

    private String columnMap;

    /** 阶梯价列 JSON：[[列序号, 从多少起], …] */
    private String tierCols;

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
