package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户积分账户：**派生缓存**，真源是 {@link PtsUserLedger}。按 (userNo, market) 唯一。
 *
 * <p>放在 settle 域而不是 user 域：积分的核心不变量是
 * 「池子余额 == 流通中积分 × 汇率」，它要跨账户、流水、资金池三张表算。
 * 分到两个模块的话，这条恒等式就没有一个模块能独立校验。
 */
@Getter
@Setter
@TableName("pts_user_account")
public class PtsUserAccount extends BaseEntity {

    private String userNo;

    /** 可用余额。**只放能花的分** —— 未过售后期的在 pendingBalance。 */
    private Long balance;

    /**
     * 待生效积分：已发放但未过售后期，<b>不计入 balance</b>。
     *
     * <p>两个数必须分开展示（「可用 400 / 待生效 100」）。合成一个的话，
     * 用户看到「我有 500 分」却只能用 400，没有任何办法解释这个差额。
     */
    private Long pendingBalance;

    private Long totalEarn;

    private Long totalUse;

    /** 市场隔离键：积分不跨市场流通。 */
    private String market;

    /**
     * 账户到期时刻：<b>任何积分变动都会把它推后</b>（滚动续期，V30）。
     *
     * <p>到期则该市场下积分全部清零。不续期的话模型会静默退化成固定期限，
     * 而这个退化<b>没有任何症状</b>。
     */
    private Long expireAt;

    /** 最近一次积分变动时刻。单独存一列是为了让「为什么是这个到期日」一眼可查。 */
    private Long lastActiveAt;

    /**
     * 到期提醒发出时刻，<b>幂等位</b>：任务重跑不能把同一个用户提醒两遍。
     *
     * <p>滚动到期是**一次性全部清零**，冲击远大于零星过期；而到期日因人而异，
     * 用户没有任何办法自己算出来。不提醒的话这个模型对用户是敌意的。
     */
    private Long expireNotifiedAt;
}
