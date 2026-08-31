package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.RefundSplitBackPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
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
    private final StatusLogMapper statusLogMapper;
    private final AfterSaleService afterSaleService;
    private final MerchantQueryPort merchantPort;
    private final UserQueryPort userPort;
    private final ObjectMapper json;

    public RefundSplitBackPortImpl(AfterSaleMapper afterSaleMapper, StatusLogMapper statusLogMapper,
                                   AfterSaleService afterSaleService, MerchantQueryPort merchantPort,
                                   UserQueryPort userPort, ObjectMapper json) {
        this.afterSaleMapper = afterSaleMapper;
        this.statusLogMapper = statusLogMapper;
        this.afterSaleService = afterSaleService;
        this.merchantPort = merchantPort;
        this.userPort = userPort;
        this.json = json;
    }

    @Override
    public List<PendingBack> pendingSplitBacks() {
        /*
         * 三个条件，缺一个队列就是错的：
         *
         * ① status = REFUNDING —— 钱还没退
         * ② liability 非空 —— **这条是关键的判别器**。REFUNDING 有两个来源：
         *    平台裁决支持退款（arbitrate 必填责任方），以及退货退款商家已同意
         *    （等买家寄回，approve 不写责任方）。后者的钱**本来就不该现在退** ——
         *    货还没回来。只按状态取，财务会在货没收到时就把钱退出去。
         * ③ split_reversed 不为真 —— 已回退过的单再点一次不是它需要的动作
         *
         * **不绕过**（2026-08-31，ord_after_sale 登记数据域时一并接上）：
         * 这是财务的分账回退队列，与提现审批同一个形状 —— 下一步动作是把钱退回去，
         * 配了商家域的财务不该看到别家的单。
         *
         * 上一版这里写着「售后单的属主是买家，运营看的是全量」—— 前半句是对的
         * （所以 SELF 锚点是 user_no），但后半句不成立：**运营的可见范围由数据域决定，
         * 不由这张表的属主决定**。两件事被那一句话合成了一件。
         */
        List<OrdAfterSale> rows = afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getStatus, OrdAfterSale.REFUNDING)
                .isNotNull(OrdAfterSale::getLiability)
                .and(w -> w.isNull(OrdAfterSale::getSplitReversed)
                        .or().eq(OrdAfterSale::getSplitReversed, false))
                .orderByAsc(OrdAfterSale::getId));
        if (rows.isEmpty()) {
            return List.of();
        }

        // 一屏一批就批量查，不逐条 —— 与商家名同处的 N+1 是列表页最常见的那个
        Set<String> merchantNos = rows.stream().map(OrdAfterSale::getEntityNo)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<String, MerchantQueryPort.MerchantBrief> merchants = merchantPort.findAll(merchantNos);

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
                                .toInstant().toEpochMilli()))
                .toList();
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
