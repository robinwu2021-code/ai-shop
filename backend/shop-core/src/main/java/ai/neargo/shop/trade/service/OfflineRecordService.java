package ai.neargo.shop.trade.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * App 外收款记录（TDD-商户App外部记账）：商家在 App 外收到的钱，在 App 里记一笔账、留痕。
 *
 * <p><b>为什么是 trade 域一张独立的账，而不是任何一种已有单据</b>：挂到线下卖出、订单线下实收或支付单上，
 * 都会把那种单据的后果带进来（减库存、推订单状态、进支付台账与通道对账）。这里只记钱、只给商家看，
 * 带了订单号也只是引用。
 *
 * <p>作用域一律由调用方从 {@code BizContext} 取（主体、当前门店、操作人），请求里不收。
 */
public interface OfflineRecordService {

    /** 记一笔；同一幂等键同体重送返回第一次那条，同键不同体 10409。 */
    OfflineRecordView record(Scope scope, RecordCmd cmd);

    /** 本店记录，按实收时间倒序、游标翻页。 */
    RecordPage list(Scope scope, ListQuery query);

    /** 本店区间内未作废记录的笔数与金额（已拍板 D6-B：单列，不并入渠道营业额）。 */
    Summary summary(Scope scope, LocalDate from, LocalDate to);

    /** 本店带某个业务单号的全部记录（含已作废），按实收时间正序；最多 200 条。 */
    List<OfflineRecordView> ofBizNo(Scope scope, String bizNo);

    /** 更正金额、方式、实收时间；原因必填，旧值进留痕。 */
    OfflineRecordView correct(Scope scope, String recordNo, CorrectCmd cmd);

    /** 作废：状态改 VOIDED，行不删。 */
    OfflineRecordView voidRecord(Scope scope, String recordNo, VoidCmd cmd);

    /** 服务端认定的作用域：主体、当前门店、操作人。 */
    record Scope(String entityNo, String storeNo, String actorNo) {}

    /**
     * @param receivedAt 实际收款时刻；{@code null} = 服务器当前时间
     */
    record RecordCmd(
            String idempotencyKey,
            long amountMinor,
            String currency,
            String method,
            String orderNo,
            String bizNo,
            String note,
            Instant receivedAt) {}

    /**
     * @param receivedAt 新的实收时刻；{@code null} = 不改（已拍板 D10-B）
     */
    record CorrectCmd(
            long amountMinor,
            String currency,
            String method,
            Instant receivedAt,
            String reason,
            long expectedVersion) {}

    record VoidCmd(String reason, long expectedVersion) {}

    /**
     * @param from    含；{@code null} = 门店市场的今天
     * @param to      含；{@code null} = 门店市场的今天
     * @param orderNo 精确匹配订单号（已拍板 D3-D：订单详情据此判断「已有外部記錄」）
     */
    record ListQuery(
            LocalDate from,
            LocalDate to,
            List<String> methods,
            List<String> statuses,
            String q,
            String orderNo,
            String cursor,
            int size) {}

    record RecordPage(List<OfflineRecordView> items, String nextCursor) {}

    record Summary(String from, String to, String currency, long count, long amountMinor) {}

    /** 四个接口共用的记录行；时间一律 ISO 8601 带门店市场的偏移。 */
    record OfflineRecordView(
            String recordNo,
            long amountMinor,
            String currency,
            String method,
            String orderNo,
            String bizNo,
            String note,
            String receivedAt,
            String recordedBy,
            String recordedByName,
            String recordedAt,
            String status,
            boolean corrected,
            long version,
            List<HistoryView> history) {}

    /** 一步留痕；App 按 {@code action} 与前后值自己拼文字。 */
    record HistoryView(
            String at,
            String by,
            String byName,
            String action,
            String reason,
            Long fromAmountMinor,
            Long toAmountMinor,
            String fromMethod,
            String toMethod,
            String fromReceivedAt,
            String toReceivedAt) {}
}
