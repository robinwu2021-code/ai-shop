package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.dto.FinanceVOs.RefundSplitBackVO;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.service.RefundSplitBackService;
import ai.neargo.shop.spi.trade.RefundSplitBackPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link RefundSplitBackService} 实现。「先回退、后退款」的顺序由售后续跑（AfterSaleRefundExecutor）保证。 */
@Service
public class RefundSplitBackServiceImpl implements RefundSplitBackService {

    private final RefundSplitBackPort backPort;
    private final BillMapper billMapper;

    public RefundSplitBackServiceImpl(RefundSplitBackPort backPort,
                                      BillMapper billMapper) {
        this.backPort = backPort;
        this.billMapper = billMapper;
    }

    @Override
    public List<RefundSplitBackVO> pending() {
        List<RefundSplitBackPort.PendingBack> cases = backPort.pendingSplitBacks();
        if (cases.isEmpty()) {
            return List.of();
        }
        /*
         * 与结算单求交集：已经 REVERSED 的单没有可回退的分账，留在队列里只会被反复点。
         * 没有结算单的子单**也留下** —— reverseSplit 对它是无害的（直接判成功），
         * 而把它踢出队列等于让那笔售后永远没有出口。
         */
        Set<String> subOrderNos = cases.stream()
                .map(RefundSplitBackPort.PendingBack::subOrderNo).collect(Collectors.toSet());
        Map<String, String> billStatus = DataScopeContext.executeWithoutScope(() ->
                        billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                                .in(StlBill::getSubOrderNo, subOrderNos))).stream()
                .collect(Collectors.toMap(StlBill::getSubOrderNo, StlBill::getStatus,
                        (a, b) -> a));

        return cases.stream()
                .filter(c -> !StlBill.REVERSED.equals(billStatus.get(c.subOrderNo())))
                .map(RefundSplitBackServiceImpl::toVO)
                .toList();
    }

    @Override
    @Transactional("payTxManager")
    public RefundSplitBackVO execute(String afterSaleNo, String operatorNo) {
        RefundSplitBackPort.PendingBack target = backPort.pendingSplitBacks().stream()
                .filter(c -> c.afterSaleNo().equals(afterSaleNo))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));

        /*
         * 回退分账与退款都在售后续跑里（第 5 步起按退款金额回退，键 REV-售后单号）。
         * 此前这里先调整单回退 reverseSplit(子单号) —— 部分退款会因此把整单分账退回，商家少收。
         * 回退失败时续跑抛 SPLIT_EXPIRED，与此前在这里抛的是同一个码。
         */
        backPort.resumeRefund(afterSaleNo, operatorNo);

        // 回读一次而不是就地改 VO：状态要以链路真正落到的为准，不是以「应该是」为准
        return backPort.pendingSplitBacks().stream()
                .filter(c -> c.afterSaleNo().equals(afterSaleNo))
                .findFirst()
                .map(RefundSplitBackServiceImpl::toVO)
                .orElseGet(() -> done(target));
    }

    /** 退款走完之后它就不在待办里了 —— 这才是正常结局。 */
    private static RefundSplitBackVO done(RefundSplitBackPort.PendingBack c) {
        return new RefundSplitBackVO(c.afterSaleNo(), c.subOrderNo(), c.orderNo(),
                c.merchantNo(), c.merchantName(), c.buyerNickname(), c.type(), "REFUNDED",
                c.refundMinor(), c.reason(), c.images(), c.liability(), c.verdict(),
                null, false, WithdrawServiceImpl.iso(c.createdAt()));
    }

    private static RefundSplitBackVO toVO(RefundSplitBackPort.PendingBack c) {
        return new RefundSplitBackVO(c.afterSaleNo(), c.subOrderNo(), c.orderNo(),
                c.merchantNo(), c.merchantName(), c.buyerNickname(), c.type(), c.status(),
                c.refundMinor(), c.reason(), c.images(), c.liability(), c.verdict(),
                // share 恒为 null：赔付出资比例口径未定（M4），ord_after_sale 上没有这一列。
                // 接口不假装它已经判过 —— 运营端会显示红字「未判定」
                null, true, WithdrawServiceImpl.iso(c.createdAt()));
    }
}
