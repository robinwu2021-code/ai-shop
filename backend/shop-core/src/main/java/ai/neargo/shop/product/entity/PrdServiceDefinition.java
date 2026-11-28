package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 服务定义的一行（{@code prd_service_definition}，TDD-美业域迁移新库 §2.8）：商品默认行（{@code skuNo=""}）
 * 或某个 SKU 的覆盖行。一个 {@code definitionVersion} 是一组行；行写入后不改，发布总是写下一个版本。
 *
 * <p>{@code steps} / {@code notes} 是 JSON 文本；覆盖行为空表示沿用默认行。
 */
@Getter
@Setter
@TableName("prd_service_definition")
public class PrdServiceDefinition extends BaseEntity {
    private String entityNo;
    private String storeNo;
    private String goodsNo;
    private String skuNo;
    private Integer definitionVersion;
    private Integer durationMin;
    private Integer bufferBeforeMin;
    private Integer bufferAfterMin;
    private String steps;
    private String notes;
    private Integer schemaVersion;
    private String snapshotHash;
    private String status;
    private String sourceRef;
    private String publishedBy;
    /** 发布时间（UTC）。 */
    private LocalDateTime publishedAt;

    public PrdServiceDefinition() {
        setTenantNo("MAIN");
    }
}
