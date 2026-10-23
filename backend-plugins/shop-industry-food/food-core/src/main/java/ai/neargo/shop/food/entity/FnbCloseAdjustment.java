package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Existing V1 closing evidence; signed rows are never overwritten by the closing service. */
@Getter
@Setter
@TableName("fnb_close_adjustment")
public class FnbCloseAdjustment extends BaseEntity {
    private String entityNo;
    private String adjustmentNo;
    private String originalCloseNo;
    private String currentDayNo;
    private String coreReference;
    private String reason;
    private String approvalRef;
}
