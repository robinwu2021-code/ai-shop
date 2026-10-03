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
    /** 门店强制下线：作用在 {@link #storeNo} 那家店上，不动主体。 */
    public static final String STORE_OFFLINE = "STORE_OFFLINE";

    private String violationNo;
    private String entityNo;

    /** 门店级处置时的门店号，空 = 主体级处置（V96）。 */
    private String storeNo;

    /** FAKE_GOODS / BREACH / PRICE_FRAUD / SERVICE。**只有 BREACH 计入 breachCount**。 */
    private String type;

    /** WARN / LIMIT / SUSPEND / STORE_OFFLINE。SUSPEND 推主体到 SUSPENDED；STORE_OFFLINE 推门店到 SUSPENDED。 */
    private String action;

    /** 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住。 */
    private String detail;

    private String operatorNo;
    private Long at;
}
