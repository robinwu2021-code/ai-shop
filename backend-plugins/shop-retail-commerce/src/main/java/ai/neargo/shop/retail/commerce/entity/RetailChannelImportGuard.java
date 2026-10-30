package ai.neargo.shop.retail.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * A real upstream cancellation fences imports even when no retail source or core preparation exists
 * yet.
 */
@Getter
@Setter
@TableName("retail_channel_import_guard")
public class RetailChannelImportGuard extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String guardNo;
    private String importNo;
    private String originalDocumentHash;
    private String status;
    private String withdrawalSnapshot;
    private String cancellationReceiptRef;
}
