package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentLedgerServiceImpl implements PaymentLedgerService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLedgerServiceImpl.class);

    private final SettleMappers.PaymentMapper paymentMapper;

    public PaymentLedgerServiceImpl(SettleMappers.PaymentMapper paymentMapper) {
        this.paymentMapper = paymentMapper;
    }

    @Override
    @Transactional("payTxManager")
    public void open(SettlePort.PaymentOpen cmd) {
        StlPayment existing = byOutTradeNo(cmd.outTradeNo());
        if (existing != null) {
            return;   // 幂等：用户在收银台反复点「去支付」，多一行就多一笔「掉单」
        }
        StlPayment p = new StlPayment();
        p.setPaymentNo(BizKey.next(BizKey.PAYMENT));
        p.setDirection(StlPayment.PAY);
        p.setStatus(StlPayment.PENDING);
        p.setOutTradeNo(cmd.outTradeNo());
        p.setOrderNo(cmd.orderNo());
        p.setUserNo(cmd.userNo());
        p.setEntityNo(cmd.entityNo());
        p.setPayChannel(cmd.payChannel());
        p.setAmountMinor(cmd.amountMinor());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.insert(p));
    }

    @Override
    @Transactional("payTxManager")
    public void settle(SettlePort.PaymentSettled cmd) {
        StlPayment existing = byOutTradeNo(cmd.outTradeNo());
        if (existing == null) {
            /*
             * 没有起点行。**正常链路不会走到这里** —— 端上必须先调
             * /mp/order/{no}/pay 拿到支付参数才付得成，而那一步会落 PENDING 行。
             * 走到这里的是存量单：本功能（2026-09-01）上线之前发起、之后才回调的。
             *
             * <b>不硬补一行</b>：stl_payment.user_no 是 NOT NULL，而这里拿不到付款人 ——
             * 编一个值进去，这行流水就永远指向一个不存在的用户，
             * 而它会被对账、退款追溯当成真数据用。**宁可缺一行，不要一行假的。**
             *
             * 也不抛错：抛了通道会一直重推，而订单状态那边照样要推进 ——
             * 这笔钱确实收到了。缺的这一行是存量的历史问题，随窗口过去自己消失。
             */
            log.warn("[payment-ledger] {} 没有发起行，跳过记账 —— "
                    + "存量单（本功能上线前发起）。订单状态不受影响", cmd.outTradeNo());
            return;
        }
        if (StlPayment.SUCCESS.equals(existing.getStatus())) {
            /*
             * 幂等：通道会重推。**不覆盖已有的成功时刻** ——
             * 覆盖的话对账查到的成功时刻会随每次重推往后跳，
             * 而那个时刻是「钱什么时候到的」的唯一依据。
             */
            return;
        }
        StlPayment patch = new StlPayment();
        patch.setId(existing.getId());
        patch.setStatus(StlPayment.SUCCESS);
        patch.setTradeNo(cmd.tradeNo());
        patch.setSucceededAt(cmd.succeededAt());
        /*
         * 金额以**通道回执**为准，不是以发起时的应付为准。
         * 两者不一致时留下的是通道说的那个数 —— 对账要对的就是它，
         * 而「我方以为该收多少」在发起行里已经记过了。
         */
        patch.setAmountMinor(cmd.amountMinor());
        DataScopeContext.executeWithoutScope(() -> paymentMapper.updateById(patch));
    }

    private StlPayment byOutTradeNo(String outTradeNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.PAY)
                        .eq(StlPayment::getOutTradeNo, outTradeNo)
                        .last("LIMIT 1")));
    }
}
