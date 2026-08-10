package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家违规与处置记录。
 *
 * <p><b>结论在 {@code mch_entity.breach_count}，事实在这里。</b>
 * 只有计数器的话，商家申诉时运营既说不清那个数字怎么来的，
 * 也没法在申诉成立时准确地减回去。
 */
@Getter
@Setter
@TableName("mch_violation")
public class MchViolation extends BaseEntity {

    public static final String BREACH = "BREACH";
    public static final String SUSPEND = "SUSPEND";

    private String violationNo;
    private String entityNo;

    /** FAKE_GOODS / BREACH / PRICE_FRAUD / SERVICE。**只有 BREACH 计入 breachCount**。 */
    private String type;

    /** WARN / LIMIT / SUSPEND。SUSPEND 会真的把商家推到 SUSPENDED。 */
    private String action;

    /** 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住。 */
    private String detail;

    private String operatorNo;
    private Long at;
}
