package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 退款时的结算侧编排（5.3）：按售后单判重 → 口径算份额 → 分账前扣减 / 分账后按额回退。
 *
 * <p>自身不写规则：份额归 {@link SettlementRefundPolicy}，两种落法归
 * {@link PreSplitAdjuster} / {@link PostSplitReverser}。这里只判「这张结算单处在哪一边」。
 */
@Component
public class SettlementRefundCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SettlementRefundCoordinator.class);

    /** 分账指令发出过（含确认到账）—— 钱已经动过，要经通道回退 */
    private static final Set<String> SPLIT_DONE = Set.of(StlBill.SPLIT, StlBill.SPLIT_CONFIRMED);

    private final BillMapper billMapper;
    private final BillRefundJournal journal;
    private final SettlementRefundPolicy policy;
    private final PreSplitAdjuster preSplit;
    private final PostSplitReverser postSplit;

    public SettlementRefundCoordinator(BillMapper billMapper, BillRefundJournal journal, SettlementRefundPolicy policy,
                                   PreSplitAdjuster preSplit, PostSplitReverser postSplit) {
        this.billMapper = billMapper;
        this.journal = journal;
        this.policy = policy;
        this.preSplit = preSplit;
        this.postSplit = postSplit;
    }

    /** @return false = 回退没做成，调用方<b>必须停止退款</b>（钱还在商家那边） */
    @Transactional("payTxManager")
    public boolean reverse(SplitReversal cmd) {
        StlBill bill = DataScopeContext.executeWithoutScope(() -> billMapper.selectOne(
                Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo, cmd.subOrderNo()).last("LIMIT 1")));
        if (bill == null || StlBill.REVERSED.equals(bill.getStatus()) || journal.recorded(cmd.afterSaleNo())) {
            // 没有结算单（未支付即取消）/ 已全部回退 / 本售后单已处理过（重放）：都视为成功
            return true;
        }
        if (StlBill.SPLITTING.equals(bill.getStatus())) {
            log.warn("[settle-refund] 结算单 {} 分账进行中，售后 {} 稍后重试", bill.getSettleNo(), cmd.afterSaleNo());
            return false;
        }
        BillRefundShare share = policy.share(bill, cmd.goodsMinor(), cmd.freightMinor(), cmd.goodsPaidMinor());
        if (splitDone(bill)) {
            return postSplit.reverse(bill, share, cmd.afterSaleNo());
        }
        preSplit.adjust(bill, share, cmd.afterSaleNo());
        return true;
    }

    /** 转人工的单要看分账发过没有：发过（splitAt 有值）就是钱已动过，同样经通道回退。 */
    private static boolean splitDone(StlBill bill) {
        return SPLIT_DONE.contains(bill.getStatus())
                || (StlBill.MANUAL.equals(bill.getStatus()) && bill.getSplitAt() != null);
    }
}
