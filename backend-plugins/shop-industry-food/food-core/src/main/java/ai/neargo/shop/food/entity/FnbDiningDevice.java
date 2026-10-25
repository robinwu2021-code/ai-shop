package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Keeps the FnbDiningDevice business boundary scoped to original restaurant facts and lifecycle
 * authority.
 */
@Getter
@Setter
@TableName("fnb_dining_device")
public class FnbDiningDevice extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String deviceNo;
    private String displayName;
    private String status;
    private Long generation;
    private byte[] credentialHash;
}
