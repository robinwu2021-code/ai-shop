package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prn_store_stream")
public class PrnStoreStream extends PrnRow {
    @TableField(value = "store_no", updateStrategy = FieldStrategy.ALWAYS)
    private String storeNo;

    @TableField(value = "last_sequence", updateStrategy = FieldStrategy.ALWAYS)
    private Long lastSequence;

    @TableField(value = "retained_from_sequence", updateStrategy = FieldStrategy.ALWAYS)
    private Long retainedFromSequence;
}
