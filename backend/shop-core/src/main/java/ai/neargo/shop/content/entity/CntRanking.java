package ai.neargo.shop.content.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 榜单配置。
 *
 * <p><b>{@code MANUAL} 与算出来的几类校验路径完全不同</b>：前者要检查条目数与商品在售，
 * 后者根本不该带条目。混在一起校验的结果是两边都校验不干净。
 */
@Getter
@Setter
@TableName("cnt_ranking")
public class CntRanking extends BaseEntity {

    public static final String MANUAL = "MANUAL";

    private String rankNo;
    private String name;
    private String kind;
    /** 榜单容量。MANUAL 的条目数不能超过它 */
    private Integer size;
    /** JSON 数组，**仅 MANUAL 有值** */
    private String manualSkus;
    private Boolean enabled;
}
