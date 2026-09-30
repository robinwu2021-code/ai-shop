package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_stock_batch_row")
public class ElcStockBatchRow extends ElcEntity {

    private String batchNo;

    private Integer rowIdx;

    /** 原样一行，JSON 数组 */
    private String cells;

    /** 这一行的问题，JSON：[{"c":"QTY_INVALID","l":"ERROR","col":3,"v":"约2千"}] */
    private String issues;

    /** 这一行最重的级别：ERROR / WARN */
    private String issueLevel;
}
