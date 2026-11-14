package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Keeps the FnbIdentityAuthorization business boundary scoped to original restaurant facts and
 * lifecycle authority.
 */
@Getter
@Setter
@TableName("fnb_identity_authorization")
public class FnbIdentityAuthorization extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String authorizationNo;
    private String sessionNo;
    private String kind;
    private String deviceNo;
    private Long deviceGeneration;
    private String displayName;
    private String issuedBy;
    private String status;
}
