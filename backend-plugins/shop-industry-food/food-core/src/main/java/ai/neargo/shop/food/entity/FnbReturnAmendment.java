package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("fnb_return_amendment")
public class FnbReturnAmendment extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String approvalNo;
    private String returnNo;
    private String actorNo;
    private String operationKey;
    private String requestHash;
    private String sourceHash;
    private String requestSnapshot;
    private String commandSnapshot;
    private String receiptSnapshot;
    private String status;
    private String replacementCartNo;
    private String replacementBatchNo;
    private String replacementOrderNo;
    private String replacementKey;
    private String replacementHash;
}
