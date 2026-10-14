package ai.neargo.shop.trade.order.service.lifecycle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.contract.PaymentResolutionView;
import ai.neargo.shop.trade.order.entity.OrdPaymentResolution;
import ai.neargo.shop.trade.order.mapper.PaymentResolutionMapper;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Own transaction has no order-row writes or locks, so a rejected projection cannot erase evidence. */
@Service
public class PaymentResolutionServiceImpl implements PaymentResolutionService {
    private final PaymentResolutionMapper resolutions;
    private final TransactionTemplate independent;

    public PaymentResolutionServiceImpl(PaymentResolutionMapper resolutions,
                                        @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.resolutions = resolutions;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void record(OrdOrder order, SettlePort.PaymentReceipt receipt, String reason) {
        if (receipt == null || !"SUCCESS".equals(receipt.status())) {
            return;
        }
        independent.executeWithoutResult(tx -> DataScopeContext.executeWithoutScope(() -> {
            var row = new OrdPaymentResolution();
            row.setPayNo(order.getOrderNo());
            row.setPaymentNo(receipt.paymentNo());
            row.setUserNo(order.getUserNo());
            row.setSourceUserNo(receipt.userNo());
            row.setSourcePayNo(receipt.orderNo());
            row.setAmountMinor(receipt.amountMinor());
            row.setCurrency(receipt.currency());
            row.setPayChannel(receipt.payChannel());
            row.setTradeNo(receipt.tradeNo());
            row.setReason(reason);
            row.setStatus(OrdPaymentResolution.MANUAL_REQUIRED);
            try {
                resolutions.insert(row);
            } catch (DuplicateKeyException duplicate) {
                // Keep the original evidence and any later resolution intact on callback replay.
                if (resolutions.selectCount(Wrappers.<OrdPaymentResolution>lambdaQuery()
                        .eq(OrdPaymentResolution::getPayNo, order.getOrderNo())
                        .eq(OrdPaymentResolution::getPaymentNo, receipt.paymentNo())) != 1) {
                    throw duplicate;
                }
            }
            return null;
        }));
    }

    @Override
    public PaymentResolutionView view(String payNo, String buyerNo) {
        var row = DataScopeContext.executeWithoutScope(() -> resolutions.selectOne(
                Wrappers.<OrdPaymentResolution>lambdaQuery().eq(OrdPaymentResolution::getPayNo, payNo)
                        .eq(OrdPaymentResolution::getUserNo, buyerNo)
                        .eq(OrdPaymentResolution::getStatus, OrdPaymentResolution.MANUAL_REQUIRED)
                        .orderByDesc(OrdPaymentResolution::getId).last("LIMIT 1")));
        return row == null ? null : new PaymentResolutionView(row.getStatus(), row.getReason(), row.getPaymentNo());
    }
}
