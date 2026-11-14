package ai.neargo.inventory.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 盘点 SN 实扫一条。同一盘点单里同一 SN 只有一条：重扫不重计。 */
@Getter
@Setter
@TableName("inv_count_scan")
public class InvCountScan extends InvEntity {
    private String ownerId;
    private String countNo;
    private String serialNo;
    private String serialId;
    private String itemId;
    /** IN_SCOPE / OUT_OF_SCOPE / NOT_IN_STOCK / UNKNOWN */
    private String verdict;
}
