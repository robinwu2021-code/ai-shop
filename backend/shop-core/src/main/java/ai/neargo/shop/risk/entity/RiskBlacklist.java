package ai.neargo.shop.risk.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 黑名单与解禁申诉（P-16.2.4）。
 *
 * <p><b>{@code untilAt} 必填</b>：无期限拉黑没有申诉出口，那是产品事故不是风控严格。
 *
 * <p>申诉通过 = {@code active} 置 false，**记录保留**。留痕不是删除 ——
 * 「这个人被拉黑过又申诉成功过」是下一次判断的重要输入。
 */
@Getter
@Setter
@TableName("risk_blacklist")
public class RiskBlacklist extends BaseEntity {

    public static final String APPEAL_NONE = "NONE";
    public static final String APPEAL_PENDING = "PENDING";
    public static final String APPEAL_UPHELD = "UPHELD";
    public static final String APPEAL_REJECTED = "REJECTED";

    private String blackNo;
    private String subjectType;
    private String subject;
    private String subjectName;
    private String reason;

    /** 列名是 {@code until_at} —— {@code UNTIL} 在 MySQL 里是保留字。 */
    private LocalDateTime untilAt;

    private String appealStatus;
    private String appealReason;
    private String appealVerdict;

    /**
     * 人工置位：申诉通过时置 false。<b>到期不会把它改成 false</b> ——
     * 没有任何定时任务在扫这张表，也刻意不加一个（见 {@link #inForce()}）。
     */
    private Boolean active;

    /**
     * <b>「生效中」的唯一口径</b>：人工没解除，且还没到期。
     *
     * <p>为什么不写一个定时任务把过期行的 {@code active} 刷成 0：那样「生效中」
     * 就有了两个真源（列的值 / 时间的事实），而它们之间隔着一次调度 ——
     * 到期后到任务跑之前的那段时间里，判定说放行、列表说生效中，两边都「没报错」。
     *
     * <p>这个方法此前不存在，代价是三处各判各的：{@code RiskEventPortImpl.blocked}
     * 带上了到期判断，重复拉黑的查重与列表的 {@code active} 没带 —— 于是
     * <b>拉黑一到期，那个人既不再被拦，也再也拉黑不上</b>（查重永远撞到那条老记录），
     * 而运营列表上它还写着「生效中」。
     */
    public boolean inForce() {
        return Boolean.TRUE.equals(active) && untilAt != null && untilAt.isAfter(LocalDateTime.now());
    }
}
