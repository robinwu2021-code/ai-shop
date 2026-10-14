package ai.neargo.shop.content.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 运营素材。
 *
 * <p><b>投给谁和素材本身是一件事</b>：{@code scope} 指定了社区或商家时，
 * {@code scopeRefs} 不能为空 —— 一份「限定投放」但没有投放对象的素材，
 * 保存成功了却谁都看不到。
 */
@Getter
@Setter
@TableName("cnt_material")
public class CntMaterial extends BaseEntity {

    public static final String ALL = "ALL";
    public static final String COMMUNITY = "COMMUNITY";
    public static final String MERCHANT = "MERCHANT";

    private String materialNo;
    private String title;
    private String kind;
    private String content;
    private String scope;
    /** JSON 数组 */
    private String scopeRefs;
    /** JSON 数组，空 = 不限语言 */
    private String langs;
    /** 未发布的素材商家看不到 */
    private Boolean published;
    private Integer downloads;
}
