package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台行业与子行业（V643，TDD-子行业与能力端验收）。子行业决定建店的经营类型，<b>不影响方案与功能</b>。
 * 与老表 {@code sys_industry}（支付侧行业，带微信 / 支付宝小微开关）无关。
 */
@Getter
@Setter
@TableName("sys_store_industry")
public class SysStoreIndustry extends BaseEntity {
    private String industryCode;
    /** 行业本身那一行为空串 */
    private String subIndustryCode;
    private String businessTypeCode;
    private String nameZh;
    private String nameTw;
    private String nameEn;
    private Integer sort;
    private Integer enabled;
}
