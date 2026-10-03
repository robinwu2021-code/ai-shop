package ai.neargo.shop.risk.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 风险事件（P-16.2.1–3）。**三类同表用 {@code type} 区分**。
 *
 * <p>拆成三张表会让「这个主体同时命中几类」看不出来 —— 而那恰恰是最该优先处理的一批。
 *
 * <p><b>刻意不存风险分值</b>：分值口径要等有真实样本后由风控定。
 * 现在编一个看起来很准的分数，只会让人照着它做决定。
 */
@Getter
@Setter
@TableName("risk_event")
public class RiskEvent extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String DISMISSED = "DISMISSED";

    private String riskEventNo;
    private String type;
    private String subjectType;

    /** 主体标识（userNo / entityNo / 设备号或 IP），**不是昵称**。 */
    private String subject;

    private String subjectName;

    /** 逗号分隔的中文短语。给人看的证据摘要，不是枚举。 */
    private String signals;

    /** 逗号分隔的证据单号。 */
    private String refs;

    private Integer hitCount;
    private String status;
    private String verdict;
    private String decidedBy;
    private LocalDateTime decidedAt;

    /**
     * 待处置期间 = {@code type|subject}；处置之后改写成自己的单号。
     *
     * <p>唯一索引因此同时兑现两件事：**同一主体同类风险在处置完成前只有一张待办**
     * （刷单的人一晚上下 200 单，逐单开事件会让队列直接失去可用性），
     * 以及处置完之后再命中还能重新开单。
     */
    private String dedupKey;

    /** 待处置期间的去重键。 */
    public static String openKey(String type, String subject) {
        return type + "|" + subject;
    }
}
