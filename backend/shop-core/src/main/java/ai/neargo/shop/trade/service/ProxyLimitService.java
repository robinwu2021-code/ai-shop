package ai.neargo.shop.trade.service;

/**
 * 代客下单的**限额**（M6：客服代客操作的权限边界与金额阈值）。
 *
 * <p>此前只有留痕没有闸门：客服能替任何人下任意金额的单，事后查得到、当时拦不住。
 * 留痕回答的是「谁干的」，闸门回答的是「能干多大」—— 两件事。
 *
 * <p><b>为什么是平台参数而不是常量</b>：这两个数会随业务变
 * （大促期间客服代下的单会多、客单价也会高），而改常量要发版。
 * 与关单时限（{@link CloseRuleService}）同一套存法：`sys_setting` 一行 JSON，改了留痕。
 */
public interface ProxyLimitService {

    /**
     * 出厂默认：单笔 <b>2000 元</b>。
     *
     * <p>取的是「社区店里一次买得离谱就该有人看一眼」的量级 ——
     * 平台上代客单的典型客单价是几十到几百，2000 已经是异常值。
     * 拦住不等于不让做：超了就让顾客自己下单，那条路本来就通。
     */
    long DEFAULT_MAX_AMOUNT_MINOR = 200_000L;

    /**
     * 出厂默认：每人每天 <b>20 笔</b>。
     *
     * <p>一个客服一天接得完的电话就是这个量级；超过它多半不是「今天特别忙」，
     * 而是有人拿代客下单在刷什么。同样是拦住 + 让人来看，不是禁止。
     */
    int DEFAULT_MAX_PER_DAY = 20;

    /**
     * @param maxAmountMinor 单笔上限（分）。<b>按订单实际应付额判</b>，不按商品估算 ——
     *                       有运费与优惠时估算说不清
     * @param maxPerDay      每个客服每天最多几笔。按**自然日**算（与对账口径一致）
     */
    record ProxyLimitVO(long maxAmountMinor, int maxPerDay, String updatedAt, String updatedBy) {
    }

    /** 没配过时返回出厂默认 —— 参数表少一行不该让代客下单整条路走不通 */
    ProxyLimitVO get();

    /** 写入并留痕。两个数都必须为正；上限 0 会把整条路关死，那是关功能不是设限额 */
    ProxyLimitVO save(long maxAmountMinor, int maxPerDay, String operatorNo);
}
