package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Original cancellation can arrive before trade identities; this row blocks a late admission
 * without inventing an order.
 */
@Getter
@Setter
@TableName("ord_channel_admission_guard")
public class OrdChannelAdmissionGuard extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String sourceSystem;
    private String sourceNo;
    private Long sourceVersion;
    private String sourceHash;
    private String importRef;
    private String status;
    private String withdrawalSnapshot;
    private String cancellationReceiptRef;
}
