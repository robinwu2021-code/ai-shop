package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Original source-key cancellation is durable even when no restaurant party/cart/batch or core
 * reference exists.
 */
@Getter
@Setter
@TableName("fnb_channel_import_guard")
public class FnbChannelImportGuard extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String guardNo;
    private String importNo;
    private String originalDocumentHash;
    private String status;
    private String withdrawalSnapshot;
    private String coreCancellationSnapshot;
    private String cancellationReceiptRef;
}
