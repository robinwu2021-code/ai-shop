package ai.neargo.shop.settle.mapper;

import ai.neargo.shop.settle.entity.PtsUserAccount;
import ai.neargo.shop.settle.entity.PtsUserLedger;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlPayment;
import ai.neargo.shop.settle.entity.StlPointsPool;
import ai.neargo.shop.settle.entity.StlSplitLog;
import ai.neargo.shop.settle.entity.StlPurchaseInvoice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** settle 域的 Mapper 集合。 */
public final class SettleMappers {

    private SettleMappers() {
    }

    public interface SettleBatchMapper
            extends BaseMapper<ai.neargo.shop.settle.entity.StlSettleBatch> {
    }

    public interface BillMapper extends BaseMapper<StlBill> {
    }

    /** 采购进项票（自营）。发票代码+号码联合唯一，挡住同一张票冲两个周期的账。 */
    public interface PurchaseInvoiceMapper extends BaseMapper<StlPurchaseInvoice> {
    }

    /** 商家提现单（P-12.2.1）。**只记账不打款**，见 {@code StlWithdraw} 类注释。 */
    public interface WithdrawMapper
            extends BaseMapper<ai.neargo.shop.settle.entity.StlWithdraw> {
    }

    /**
     * 商家结算发票申请（P-12.2.4）。**与进项票、C 端销项票是三张不同方向的票**，
     * 别按 invoice_no 跨表 join —— 见 {@code StlSettleInvoice} 类注释。
     */
    public interface SettleInvoiceMapper
            extends BaseMapper<ai.neargo.shop.settle.entity.StlSettleInvoice> {
    }

    public interface FeeRuleMapper
            extends BaseMapper<ai.neargo.shop.settle.entity.StlFeeRule> {
    }

    public interface SplitLogMapper extends BaseMapper<StlSplitLog> {
    }

    public interface ReconDiffMapper extends BaseMapper<ai.neargo.shop.settle.entity.StlReconDiff> {
    }

    public interface PaymentMapper extends BaseMapper<StlPayment> {
    }

    public interface PointsPoolMapper extends BaseMapper<StlPointsPool> {
    }

    public interface PointsAccountMapper extends BaseMapper<PtsUserAccount> {

        /**
         * 扣减可用余额。<b>一条 UPDATE 带条件，不能先查后写</b> ——
         * 两个请求同时下单时，先查后写会让同一笔分被花两次，
         * 而账面上看不出异常（余额只是少扣了一次）。
         *
         * <p>{@code balance >= #{points}} 就是那道闸，与
         * {@code CouponMappers.tryReceive} 挡超发是同一手法：
         * <b>影响行数为 0 即余额不足</b>，由调用方决定是拒绝还是降级为不抵扣。
         *
         * <p>顺带推后到期日（滚动到期：任何积分变动都续期），
         * 与 {@code last_active_at} 一起改 —— 分开写会出现
         * 「到期日推后了，但看不出是哪次活动推的」。
         */
        @Update("""
                UPDATE pts_user_account
                   SET balance = balance - #{points},
                       total_use = total_use + #{points},
                       last_active_at = #{now}, expire_at = #{expireAt},
                       updated_at = NOW(), version = version + 1
                 WHERE user_no = #{userNo} AND market = #{market} AND deleted = 0
                   AND balance >= #{points}""")
        int deduct(@Param("userNo") String userNo, @Param("market") String market,
                   @Param("points") long points, @Param("now") long now,
                   @Param("expireAt") long expireAt);

        /**
         * 退回积分：加回可用余额，并把已用总额减回去。
         *
         * <p>不校验余额 —— 退回只会让余额变多。
         * 幂等由调用方保证（USE 流水的 {@code status} 只能从 PENDING 变一次）。
         */
        @Update("""
                UPDATE pts_user_account
                   SET balance = balance + #{points},
                       total_use = total_use - #{points},
                       last_active_at = #{now}, expire_at = #{expireAt},
                       updated_at = NOW(), version = version + 1
                 WHERE user_no = #{userNo} AND market = #{market} AND deleted = 0""")
        int refund(@Param("userNo") String userNo, @Param("market") String market,
                   @Param("points") long points, @Param("now") long now,
                   @Param("expireAt") long expireAt);

        /**
         * 发放到<b>待生效</b>余额。
         *
         * <p>不进 {@code balance}：售后期内退款要把分收回，
         * 而已经花出去的分收不回来。转正由独立任务负责。
         */
        @Update("""
                UPDATE pts_user_account
                   SET pending_balance = pending_balance + #{points},
                       total_earn = total_earn + #{points},
                       last_active_at = #{now}, expire_at = #{expireAt},
                       updated_at = NOW(), version = version + 1
                 WHERE user_no = #{userNo} AND market = #{market} AND deleted = 0""")
        int grantPending(@Param("userNo") String userNo, @Param("market") String market,
                         @Param("points") long points, @Param("now") long now,
                         @Param("expireAt") long expireAt);

        /**
         * 待生效转正：把 {@code points} 从 pending 挪进 balance。
         *
         * <p><b>是「挪」不是「加」</b> —— 两列同时改，且 pending 不能被扣成负数。
         * 分成两条语句写的话，中间崩一次就会出现「加了 balance 没减 pending」，
         * 而那笔多出来的余额没有任何流水解释得了。
         */
        @Update("""
                UPDATE pts_user_account
                   SET balance = balance + #{points},
                       pending_balance = pending_balance - #{points},
                       last_active_at = #{now}, updated_at = NOW(), version = version + 1
                 WHERE user_no = #{userNo} AND market = #{market}
                   AND pending_balance >= #{points} AND deleted = 0""")
        int activatePending(@Param("userNo") String userNo, @Param("market") String market,
                            @Param("points") long points, @Param("now") long now);
    }

    public interface PointsLedgerMapper extends BaseMapper<PtsUserLedger> {
    }
}
