package ai.neargo.shop.logistics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** One independent external subscription for one immutable shipment identity. */
@Getter
@Setter
@TableName("lgs_channel_subscription")
public class LgsChannelSubscription {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String shipmentNo;
    private String identityKey;
    private String sourceShipmentNo;
    private Long sourceShipmentVersion;
    private String carrier;
    private String waybillNo;
    private String channel;
    private String state;
    private String ref;
    private String error;
    private Integer attempts;
    private Long lastAttemptAt;
    private Long lastQueryAt;
    private Long nextRetryAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
