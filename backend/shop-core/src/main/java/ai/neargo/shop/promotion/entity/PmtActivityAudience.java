package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 活动受众。<b>一行都没有 = 对所有人生效</b>。
 *
 * <p>这条默认值不是省事：老活动没有受众概念，迁过来之后行为必须逐分不变。
 * 「空 = 谁都不给」的设计会让所有存量活动在上线那一刻集体失效，
 * 而症状是「活动还在、就是不减钱」。
 */
@Getter
@Setter
@TableName("pmt_activity_audience")
public class PmtActivityAudience extends BaseEntity {

    public static final String TAG = "TAG";
    public static final String LEVEL = "LEVEL";
    public static final String SOURCE = "SOURCE";
    public static final String SEGMENT = "SEGMENT";
    /** 非本店会员 —— 拉新活动要的正是「还不是我的会员的人」 */
    public static final String NON_MEMBER = "NON_MEMBER";

    private String activityNo;
    private String entityNo;
    private String audienceType;
    private String audienceValue;
}
