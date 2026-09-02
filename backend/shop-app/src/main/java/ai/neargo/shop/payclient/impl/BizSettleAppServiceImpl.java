package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.dto.PurchaseInvoiceVO;
import ai.neargo.shop.pay.dto.RateCardVO;
import ai.neargo.shop.pay.dto.SettleBillVO;
import ai.neargo.shop.pay.dto.StatementVO;
import ai.neargo.shop.payclient.BizSettleAppService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BizSettleAppServiceImpl implements BizSettleAppService {

    private final SettleService settleService;
    private final SettleBatchService batchService;
    private final ai.neargo.shop.pay.service.WithdrawService withdrawService;

    public BizSettleAppServiceImpl(SettleService settleService, SettleBatchService batchService,
                                   ai.neargo.shop.pay.service.WithdrawService withdrawService) {
        this.withdrawService = withdrawService;
        this.settleService = settleService;
        this.batchService = batchService;
    }

    /**
     * 门店收窄 —— <b>这一份是唯一的一份</b>。
     *
     * <p>与订单页用同一个 {@code allowedStoresOrAll()}，不另写一套：
     * 钱的作用域比订单更不能出错，而两套实现迟早有一套忘了跟上授权模型的变化。
     *
     * <p><b>存量流水没有 {@code store_no}</b>，按当前门店筛会把它们全部滤掉 ——
     * 所以只在真的有多家店时才收窄，单店商家永远看到全部（与搬家前逐字一致）。
     */
    private static Collection<String> storeScope(Boolean allStores) {
        var ctx = BizContext.current();
        if (Boolean.TRUE.equals(allStores)) {
            return ctx.allowedStoresOrAll();
        }
        String current = ctx.currentStoreNo();
        return List.of(current == null ? "" : current);
    }

    @Override
    public List<SettleBillVO> bills(Boolean allStores) {
        return settleService.merchantBills(BizContext.requireMerchantNo(), storeScope(allStores));
    }

    @Override
    public SettleService.IncomeSummaryVO income(Boolean allStores) {
        return settleService.incomeSummary(BizContext.requireMerchantNo(), storeScope(allStores));
    }

    @Override
    public List<SettleBatchService.BatchVO> batches() {
        return batchService.merchantBatches(BizContext.requireMerchantNo());
    }

    @Override
    public SettleBillVO bill(String settleNo) {
        return settleService.merchantBill(BizContext.requireMerchantNo(), settleNo);
    }

    @Override
    public RateCardVO rateCard() {
        return settleService.rateCard();
    }

    @Override
    public Map<String, String> invoiceTitle() {
        return settleService.platformInvoiceTitle();
    }

    @Override
    public ai.neargo.shop.pay.SettleService.PendingInvoiceVO pendingInvoice() {
        return settleService.pendingInvoice(BizContext.requireMerchantNo());
    }

    @Override
    public PurchaseInvoiceVO submitInvoice(SettleService.SubmitInvoiceCommand command) {
        return settleService.submitInvoice(BizContext.requireMerchantNo(), command);
    }

    @Override
    public List<PurchaseInvoiceVO> myInvoices() {
        return settleService.myInvoices(BizContext.requireMerchantNo());
    }

    @Override
    public StatementVO statement(String period) {
        return settleService.statement(BizContext.requireMerchantNo(), period);
    }

    @Override
    public WithdrawPageVO myWithdraws() {
        String me = BizContext.requireMerchantNo();
        /*
         * 复用运营端的 list 再按自己筛，而不是新写一个查询。
         *
         * <b>提现单没有门店维度</b>：钱结到主体，不结到店。
         * 所以这里不走 storeScope —— 走了的话多店商家会看不到自己的提现单。
         */
        var all = withdrawService.list(null, me, 1, 200).records().stream()
                .filter(w -> me.equals(w.merchantNo()))
                .toList();
        return new WithdrawPageVO(withdrawService.withdrawableMinor(me),
                ai.neargo.shop.pay.entity.StlWithdraw.MIN_AMOUNT_MINOR, all);
    }

    @Override
    public ai.neargo.shop.pay.dto.FinanceVOs.WithdrawVO applyWithdraw(long amountMinor) {
        String me = BizContext.requireMerchantNo();
        // 操作人记主体号：B 端的提现申请是「这家商家提的」，
        // 具体是店里哪个员工点的按钮由审计日志另行记录，不进资金单据
        return withdrawService.apply(me, amountMinor, me);
    }
}
