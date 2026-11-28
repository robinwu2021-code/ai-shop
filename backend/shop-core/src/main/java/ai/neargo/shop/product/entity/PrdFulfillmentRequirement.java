package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 交付要求（{@code prd_fulfillment_requirement}）：一个商品需要哪类角色、资源或工位，各几个、什么顺序。
 *
 * <p>服务的要求挂服务定义版本 {@code definitionVersion}（{@code skuNo} 为空属于默认行，有值属于该 SKU 的覆盖行），
 * {@code revisionNo} 留空；非服务的要求挂商品修订号 {@code revisionNo}，{@code definitionVersion} 留空。
 */
@Getter
@Setter
@TableName("prd_fulfillment_requirement")
public class PrdFulfillmentRequirement extends BaseEntity {
    /** 要求类型：执行角色。 */
    public static final String TYPE_ROLE = "ROLE";
    /** 要求类型：资源（床位、房间、设备）。 */
    public static final String TYPE_RESOURCE = "RESOURCE";

    private String entityNo;
    private String requirementNo;
    private String goodsNo;
    private String skuNo;
    private String storeNo;
    private Integer revisionNo;
    private Integer definitionVersion;
    private String requirementType;
    private String roleCode;
    private String resourceType;
    private String stationNo;
    private String taskRole;
    private String skillGoodsNo;
    private Integer qty;
    private Integer sequenceNo;
    private Integer offsetMin;
    private Integer durationMin;
    private String selectionRule;

    public PrdFulfillmentRequirement() {
        setTenantNo("MAIN");
    }
}
