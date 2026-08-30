package ai.neargo.shop.settle.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.settle.dto.FinanceVOs.SettleInvoiceVO;
import ai.neargo.shop.settle.entity.StlSettleInvoice;
import ai.neargo.shop.settle.mapper.SettleMappers.SettleInvoiceMapper;
import ai.neargo.shop.settle.service.SettleInvoiceService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link SettleInvoiceService} 实现。 */
@Service
public class SettleInvoiceServiceImpl implements SettleInvoiceService {

    private final SettleInvoiceMapper mapper;

    public SettleInvoiceServiceImpl(SettleInvoiceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PageData<SettleInvoiceVO> list(String status, String keyword, long page, long size) {
        long p = Math.max(page, 1);
        long s = Math.max(size, 1);
        var w = Wrappers.<StlSettleInvoice>lambdaQuery()
                .eq(status != null && !status.isBlank(), StlSettleInvoice::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like(StlSettleInvoice::getInvoiceNo, k)
                    .or().like(StlSettleInvoice::getMerchantName, k)
                    .or().like(StlSettleInvoice::getTitle, k));
        }
        // 先到先开：手工开票时顺序就是公平性
        w.orderByAsc(StlSettleInvoice::getId);
        // 运营端全量开票队列 —— 不绕过（stl_settle_invoice 已登记 MERCHANT → entity_no）
        var pg = mapper.selectPage(new Page<>(p, s), w);
        return PageData.of(pg.getRecords().stream().map(SettleInvoiceServiceImpl::toVO).toList(),
                pg.getTotal(), p, s);
    }

    @Override
    @Transactional
    public SettleInvoiceVO issue(String invoiceNo, String serialNo, String operatorNo) {
        StlSettleInvoice iv = require(invoiceNo);
        // 重复开票就是重复虚开 —— 不做幂等早退，要让点第二次的人看见「已处理」
        if (!StlSettleInvoice.PENDING.equals(iv.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (serialNo == null || serialNo.isBlank()) {
            // 没有流水号的「已开票」等于没开：商家拿不到凭证，事后也查不到开没开
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (StlSettleInvoice.COMPANY.equals(iv.getTitleType())
                && (iv.getTaxNo() == null || iv.getTaxNo().isBlank())) {
            throw BizException.of(ErrorCode.INVOICE_TAX_NO_REQUIRED);
        }
        // 超出已结算金额的部分没有真实交易对应，就是虚开
        if (WithdrawServiceImpl.nz(iv.getAmountMinor())
                > WithdrawServiceImpl.nz(iv.getSettledAmountMinor())) {
            throw BizException.of(ErrorCode.INVOICE_OVER_SETTLED);
        }

        iv.setStatus(StlSettleInvoice.ISSUED);
        iv.setSerialNo(serialNo.trim());
        iv.setDecidedAt(System.currentTimeMillis());
        iv.setDecidedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(iv));
        return toVO(iv);
    }

    @Override
    @Transactional
    public SettleInvoiceVO reject(String invoiceNo, String reason, String operatorNo) {
        StlSettleInvoice iv = require(invoiceNo);
        if (!StlSettleInvoice.PENDING.equals(iv.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (reason == null || reason.isBlank()) {
            // 驳回原因原样回商家 B 端。不写等于让人猜是抬头错了还是金额不符
            throw BizException.of(ErrorCode.REASON_REQUIRED);
        }
        iv.setStatus(StlSettleInvoice.REJECTED);
        iv.setRemark(reason.trim());
        iv.setDecidedAt(System.currentTimeMillis());
        iv.setDecidedBy(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(iv));
        return toVO(iv);
    }

    private StlSettleInvoice require(String invoiceNo) {
        StlSettleInvoice iv = DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<StlSettleInvoice>lambdaQuery()
                        .eq(StlSettleInvoice::getInvoiceNo, invoiceNo).last("limit 1")));
        if (iv == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return iv;
    }

    private static SettleInvoiceVO toVO(StlSettleInvoice iv) {
        return new SettleInvoiceVO(iv.getInvoiceNo(), iv.getEntityNo(), iv.getMerchantName(),
                iv.getPeriod(), WithdrawServiceImpl.nz(iv.getAmountMinor()),
                WithdrawServiceImpl.nz(iv.getSettledAmountMinor()),
                iv.getTitleType(), iv.getTitle(), iv.getTaxNo(), iv.getStatus(), iv.getSerialNo(),
                WithdrawServiceImpl.iso(iv.getAppliedAt()),
                WithdrawServiceImpl.iso(iv.getDecidedAt()), iv.getRemark());
    }
}
