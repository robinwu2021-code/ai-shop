package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdInvoiceRequest;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.InvoiceRequestMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.service.InvoiceRequestService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.List;

/** {@link InvoiceRequestService} 的实现。手工开票版，口径见接口注释。 */
@Service
public class InvoiceRequestServiceImpl implements InvoiceRequestService {

    private final InvoiceRequestMapper mapper;
    private final OrderMapper orderMapper;

    public InvoiceRequestServiceImpl(InvoiceRequestMapper mapper, OrderMapper orderMapper) {
        this.mapper = mapper;
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional
    public InvoiceRequestVO apply(ApplyCommand cmd) {
        String userNo = SecurityUtils.currentUserNo();
        // 属主鉴权放在查询条件里，而不是查出来再判 —— 防 IDOR 的第一层
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, cmd.orderNo())
                .eq(OrdOrder::getUserNo, userNo)
                .last("limit 1"));
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (OrdOrder.WAIT_PAY.equals(order.getStatus())
                || OrdOrder.CANCELLED.equals(order.getStatus())
                || OrdOrder.CLOSED.equals(order.getStatus())) {
            // 没成交就没有可开的票。这里拦住，比让运营在开票时才发现要好
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (blank(cmd.title()) || blank(cmd.email())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (OrdInvoiceRequest.TITLE_COMPANY.equals(cmd.titleType()) && blank(cmd.taxNo())) {
            // 单位抬头缺税号，票开出来对方入不了账 —— 等于白开一张
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        OrdInvoiceRequest existing = ofOrderRaw(cmd.orderNo());
        if (existing != null && !OrdInvoiceRequest.REJECTED.equals(existing.getStatus())) {
            // 已申请或已开具：重复申请 = 一笔交易两张票，那是税务问题不是体验问题
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (existing != null) {
            /*
             * 被驳回过：**改这一条，不插新的**。
             * 插新的话表上会留下同一订单的多条记录，而运营看到两条时
             * 分不清该开哪一张 —— 唯一索引挡的也正是这个。
             */
            existing.setTitleType(cmd.titleType());
            existing.setTitle(cmd.title());
            existing.setTaxNo(cmd.taxNo());
            existing.setEmail(cmd.email());
            existing.setStatus(OrdInvoiceRequest.REQUESTED);
            existing.setRejectReason(null);
            mapper.updateById(existing);
            return toVO(existing);
        }

        OrdInvoiceRequest r = new OrdInvoiceRequest();
        r.setRequestNo(BizKey.next(BizKey.INVOICE_REQUEST));
        r.setOrderNo(cmd.orderNo());
        r.setUserNo(userNo);
        r.setTitleType(blank(cmd.titleType()) ? OrdInvoiceRequest.TITLE_PERSONAL : cmd.titleType());
        r.setTitle(cmd.title());
        r.setTaxNo(cmd.taxNo());
        r.setEmail(cmd.email());
        // 金额落快照：后续退款会改订单金额，而已开的票不会跟着变
        r.setAmountMinor(order.getPayAmount() == null ? 0L : order.getPayAmount());
        r.setStatus(OrdInvoiceRequest.REQUESTED);
        mapper.insert(r);
        return toVO(r);
    }

    @Override
    public List<InvoiceRequestVO> mine() {
        return mapper.selectList(Wrappers.<OrdInvoiceRequest>lambdaQuery()
                        .eq(OrdInvoiceRequest::getUserNo, SecurityUtils.currentUserNo())
                        .orderByDesc(OrdInvoiceRequest::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public InvoiceRequestVO ofOrder(String orderNo) {
        OrdInvoiceRequest r = mapper.selectOne(Wrappers.<OrdInvoiceRequest>lambdaQuery()
                .eq(OrdInvoiceRequest::getOrderNo, orderNo)
                .eq(OrdInvoiceRequest::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        return r == null ? null : toVO(r);
    }

    @Override
    public List<InvoiceRequestVO> list(String status, int page, int size) {
        var w = Wrappers.<OrdInvoiceRequest>lambdaQuery()
                .eq(status != null && !status.isBlank(), OrdInvoiceRequest::getStatus, status)
                // 先到先开：手工处理时顺序就是公平性
                .orderByAsc(OrdInvoiceRequest::getId);
        return DataScopeContext.executeWithoutScope(() ->
                        mapper.selectPage(new Page<>(Math.max(page, 1), Math.max(size, 1)), w))
                .getRecords().stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public InvoiceRequestVO markIssued(String requestNo, String invoiceNo, String operatorNo) {
        if (blank(invoiceNo)) {
            // 没有票号的「已开具」等于没开：消费者拿不到凭证，事后也查不到开没开
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdInvoiceRequest r = requireRequest(requestNo);
        if (OrdInvoiceRequest.ISSUED.equals(r.getStatus())) {
            return toVO(r);   // 幂等
        }
        r.setStatus(OrdInvoiceRequest.ISSUED);
        r.setInvoiceNo(invoiceNo);
        r.setIssuedAt(System.currentTimeMillis());
        r.setOperatorNo(operatorNo);
        r.setRejectReason(null);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(r));
        return toVO(r);
    }

    @Override
    @Transactional
    public InvoiceRequestVO reject(String requestNo, String reason, String operatorNo) {
        if (blank(reason)) {
            // 不写原因的驳回等于让消费者再猜一遍抬头哪里错了
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdInvoiceRequest r = requireRequest(requestNo);
        if (OrdInvoiceRequest.ISSUED.equals(r.getStatus())) {
            // 开都开了不能再驳：票已经在对方手里，改状态改不回那张票
            throw BizException.of(ErrorCode.CONFLICT);
        }
        r.setStatus(OrdInvoiceRequest.REJECTED);
        r.setRejectReason(reason);
        r.setOperatorNo(operatorNo);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(r));
        return toVO(r);
    }

    // ---------------------------------------------------------------- 内部

    private OrdInvoiceRequest ofOrderRaw(String orderNo) {
        return mapper.selectOne(Wrappers.<OrdInvoiceRequest>lambdaQuery()
                .eq(OrdInvoiceRequest::getOrderNo, orderNo).last("limit 1"));
    }

    private OrdInvoiceRequest requireRequest(String requestNo) {
        OrdInvoiceRequest r = DataScopeContext.executeWithoutScope(() ->
                mapper.selectOne(Wrappers.<OrdInvoiceRequest>lambdaQuery()
                        .eq(OrdInvoiceRequest::getRequestNo, requestNo).last("limit 1")));
        if (r == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return r;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private InvoiceRequestVO toVO(OrdInvoiceRequest r) {
        return new InvoiceRequestVO(r.getRequestNo(), r.getOrderNo(), r.getTitleType(),
                r.getTitle(), r.getTaxNo(), r.getEmail(),
                r.getAmountMinor() == null ? 0L : r.getAmountMinor(),
                r.getStatus(), r.getInvoiceNo(), r.getIssuedAt(), r.getRejectReason(),
                r.getCreatedAt() == null ? null
                        : r.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}
