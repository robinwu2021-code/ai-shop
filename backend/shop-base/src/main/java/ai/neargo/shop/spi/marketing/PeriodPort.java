package ai.neargo.shop.spi.marketing;

import java.util.Collection;
import java.util.Optional;

/**
 * trade → promotion：<b>社区集单</b>下单与撤单时要问的两件事（TDD-营销-活动统一模型与集单 §2.5）。
 *
 * <p>集单是活动的一种触发（CUTOFF），「一期」是它的实例。订单只经这个 Port 拿到
 * 期号与提货日，不直接读 {@code pmt_period} —— 与 {@link GroupRulePort} 同一条理由。
 */
public interface PeriodPort {

    /**
     * 这个商家这一批货，此刻属于哪一期。<b>会按需建期</b>（唯一键兜住并发）。
     *
     * <p>截单后下的单进<b>下一期</b>：归属只看 {@code orderAt} 与截单时刻，不看任务跑没跑到。
     *
     * @param qty 这一单里命中集单的份数。超出每期上限时抛 {@code PERIOD_FULL}
     * @return 都不是集单商品时为空。命中两个不同的集单活动时抛 {@code PERIOD_MIXED}
     *         —— 一张子单只能挂一期，否则其中一期的汇总会少掉这几件
     */
    Optional<PeriodTicket> ticketFor(String entityNo, Collection<String> goodsNos, int qty, long orderAt);

    /** 这一期截单了没有（到点或商家提前截单都算）。期不存在时按已截单处理 —— 宁可拒绝撤单也不误退 */
    boolean isCutOff(String periodNo, long now);

    /** 还在收单时返回截单时刻，已截单返回空。订单详情用来显示「截单前可取消」（s37） */
    Long openUntil(String periodNo, long now);

    /**
     * 商品详情要显示的集单信息（s26）：<b>只读，不建期</b>。
     * 不是集单商品时为空；当期还没有人下单时 {@code orderedQty} 为 0。
     */
    Optional<BatchView> viewFor(String entityNo, String goodsNo, long now);

    /**
     * @param cutoffAt   这一单若此刻下，会落进的那一期的截单时刻
     * @param pickupDate 提货日 YYYY-MM-DD
     * @param pickupFrom 提货日几点起 HH:mm，可空
     * @param orderedQty 这一期已订份数（已付款且未退）
     */
    record BatchView(String activityNo, String activityName, long batchPriceMinor,
                     long cutoffAt, String pickupDate, String pickupFrom, int orderedQty) {
    }

    /**
     * @param pickupDate 写进子单 {@code arrive_date}，YYYY-MM-DD
     * @param cutoffAt   这一期的截单时刻（毫秒）
     */
    record PeriodTicket(String periodNo, String activityNo, String pickupDate, long cutoffAt) {
    }
}
