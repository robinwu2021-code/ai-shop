package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.RefundSplitBackPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link RefundSplitBackPort} 实现。**只读 + 一个既有链路的入口**。
 *
 * <p>写侧一行 SQL 都没有：{@link #resumeRefund} 转给
 * {@link AfterSaleService#resumeRefund}，退款收尾的每一件事都在那里。
 * 在这里自己 {@code UPDATE ord_after_sale SET status='REFUNDED'} 会漏掉
 * 子单转态与 {@code AfterSaleRefunded} 事件，而漏掉的那些不会报错。
 */
@Component
public class RefundSplitBackPortImpl implements RefundSplitBackPort {

    private final AfterSaleMapper afterSaleMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final AfterSaleService afterSaleService;
    private final MerchantQueryPort merchantPort;
    private final UserQueryPort userPort;
    private final ObjectMapper json;
    private final RefundExecutionMapper executions;

    public RefundSplitBackPortImpl(AfterSaleMapper afterSaleMapper, OrderStatusLogMapper statusLogMapper,
                                   AfterSaleService afterSaleService, MerchantQueryPort merchantPort,
                                   UserQueryPort userPort, ObjectMapper json, RefundExecutionMapper executions) {
        this.afterSaleMapper = afterSaleMapper;
        this.statusLogMapper = statusLogMapper;
        this.afterSaleService = afterSaleService;
        this.merchantPort = merchantPort;
        this.userPort = userPort;
        this.json = json;
        this.executions = executions;
    }

    @Override
    public List<PendingBack> pendingSplitBacks() {
        // An approved return alone is not ready to refund. An existing reversal execution is evidence
        // that receipt/approval already authorized cash. Keep the original after-sale data scope.
        List<OrdAfterSale> rows = afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getStatus, OrdAfterSale.REFUNDING)
                .and(w -> w.isNotNull(OrdAfterSale::getLiability).or().inSql(OrdAfterSale::getAfterSaleNo,
                        "SELECT after_sale_no FROM ord_refund_execution WHERE deleted=0 AND tenant_no='MAIN'"
                        + " AND phase='REVERSAL' AND error_code='REVERSE_UNCONFIRMED' AND status<>'SUCCEEDED'"))
                .and(w -> w.isNull(OrdAfterSale::getSplitReversed)
                        .or().eq(OrdAfterSale::getSplitReversed, false))
                .orderByAsc(OrdAfterSale::getId));
        return map(rows);
    }

    @Override
    public PendingBack find(String afterSaleNo) {
        var row = afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo));
        return row == null ? null : map(List.of(row)).get(0);
    }

    private List<PendingBack> map(List<OrdAfterSale> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        // 一屏一批就批量查，不逐条 —— 与商家名同处的 N+1 是列表页最常见的那个
        Set<String> merchantNos = rows.stream().map(OrdAfterSale::getEntityNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, MerchantQueryPort.MerchantBrief> merchants = merchantPort.findAll(merchantNos);

        Set<String> numbers = rows.stream().map(OrdAfterSale::getAfterSaleNo).collect(Collectors.toSet());
        Map<String, OrdRefundExecution> progress = executions.selectList(Wrappers.<OrdRefundExecution>lambdaQuery()
                .in(OrdRefundExecution::getAfterSaleNo, numbers)).stream()
                .collect(Collectors.toMap(OrdRefundExecution::getAfterSaleNo, e -> e));
        return rows.stream().map(as -> new PendingBack(
                as.getAfterSaleNo(), as.getSubOrderNo(), as.getOrderNo(),
                as.getEntityNo(),
                java.util.Optional.ofNullable(merchants.get(as.getEntityNo()))
                        .map(MerchantQueryPort.MerchantBrief::merchantName).orElse(null),
                userPort.find(as.getUserNo()).map(UserQueryPort.UserBrief::nickname).orElse(null),
                as.getType(), as.getStatus(),
                as.getRefundMinor() == null ? 0L : as.getRefundMinor(),
                as.getReason(),
                // **必须是列表不是 null**：运营端契约把 images 声明成必填数组，
                // 缺了会在渲染时 `.map of undefined` —— 那是整块不渲染，不是显示空白
                readImages(as.getImages()),
                as.getLiability(), verdictOf(as.getSubOrderNo()),
                as.getCreatedAt() == null ? 0L
                        : as.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli(), as.getSplitReversed(), progress(progress.get(as.getAfterSaleNo()))))
                .toList();
    }

    private ExecutionProgress progress(OrdRefundExecution e) {
        return e == null ? null : new ExecutionProgress(e.getExecutionNo(), e.getPaymentNo(), e.getStatus(),
                e.getPhase(), e.getErrorCode(), e.getResourceStatus(), e.getResourceErrorCode(),
                e.getSucceededAt(), e.getNextAttemptAt());
    }

    @Override
    public void resumeRefund(String afterSaleNo, String operatorNo) {
        afterSaleService.resumeRefund(afterSaleNo, operatorNo);
    }

    /**
     * 裁决说明取<b>平台写的最后一条</b>状态日志。
     *
     * <p>`ord_after_sale` 上没有 verdict 列（裁决说明落在时间线上），
     * 而财务执行回退时要看的正是「平台当初为什么判退」——
     * 不给它，运营只能回去翻售后单。
     */
    private String verdictOf(String subOrderNo) {
        List<OrdStatusLog> logs = statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                .eq(OrdStatusLog::getSubOrderNo, subOrderNo)
                .eq(OrdStatusLog::getOperatorType, OrdStatusLog.BY_PLATFORM)
                .orderByDesc(OrdStatusLog::getId).last("limit 1"));
        return logs.isEmpty() ? null : logs.get(0).getLabel();
    }

    private List<String> readImages(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
