package ai.neargo.shop.logistics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A reserved Kuaidi100 /poll slot, shared across orders with the same carrier and number. */
@Getter
@Setter
@TableName("lgs_kd100_sub_attempt")
public class LgsKd100SubAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String accountRef;
    private String companyCode;
    private String waybillNo;
    private String monthKey;
    private Integer attemptNo;
    private String shipmentNo;
    private String identityKey;
    private String callbackId;
    private String result;
    private String resultCode;
    private Long terminatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
