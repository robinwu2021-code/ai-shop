package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** A recommendation is configuration; its existence never enables an execution capability. */
@Getter
@Setter
@TableName("pmt_preset_template")
public class PmtPresetTemplate extends BaseEntity {
    private String templateCode;
    private Integer templateVersion;
    private String catalogKind;
    private String category;
    private String name;
    private String industryTags;
    private String draftSchema;
    private String requiredCapabilities;
    private Integer enabled;
}
