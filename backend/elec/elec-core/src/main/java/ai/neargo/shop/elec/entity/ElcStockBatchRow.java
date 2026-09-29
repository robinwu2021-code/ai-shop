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

    private String cells;
}
