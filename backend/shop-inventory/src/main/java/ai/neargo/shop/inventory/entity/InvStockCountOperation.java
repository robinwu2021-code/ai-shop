package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Idempotency marker stored in the same inventory database transaction as a one-line count adjustment. */
@Getter
@Setter
@TableName("inv_stock_count_operation")
public class InvStockCountOperation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ownerId;
    private String operationNo;
    private String requestHash;
    private String countNo;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
