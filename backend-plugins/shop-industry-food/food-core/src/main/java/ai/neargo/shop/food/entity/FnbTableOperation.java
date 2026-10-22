package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Restaurant reception fact; never owns retail money or inventory. */
@Getter
@Setter
@TableName("fnb_table_operation")
public class FnbTableOperation extends BaseEntity {
    private String entityNo;
    private String operationNo;
    private String storeNo;
    private String kind;
    private String checkNo;
    private String targetCheckNo;
    private String requestKey;
    private String requestHash;
    private String expectedVersions;
    private String targetScope;
    private String resourceOperationRef;
    private String status;
    private String reason;
}
