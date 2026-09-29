package ai.neargo.shop.payclient;

import java.util.List;

/**
 * 平台端 · 结算口径的多维统计（TDD-供应商结算与双轨资金 §2.1）。
 *
 * <p>这一层只做两件领域层不该做的事：<b>把日期串转成区间</b>，
 * 以及<b>给维度值补展示名</b>。
 *
 * <p>补名字为什么放这里：那要跨域读商家主数据（门店名、主体名），
 * 而聚合本身必须只认快照列 —— 两件事混在一个方法里，
 * 迟早有人为了"少查一次"把 join 写进聚合，而那正是
 * {@code SettleStatsService} 类注释里第一条禁止的事。
 */
public interface OpsSettleStatsAppService {

    /**
     * @param dim          STORE / ENTITY / PAY_MERCHANT
     * @param from         起始日 {@code yyyy-MM-dd}（含）
     * @param to           结束日 {@code yyyy-MM-dd}（含，取到当日 23:59:59.999）
     * @param businessMode 经营模式过滤，空则不限
     */
    List<StatRowVO> stats(String dim, String from, String to, String businessMode);

    /**
     * @param dimName 展示名。<b>查不到时回落成 {@code dimKey} 本身，绝不给空串</b> ——
     *                空白会被读成「没有这家店」，而真相通常是它改名或停用了
     */
    record StatRowVO(String dimKey, String dimName, long grossMinor, long commissionMinor,
                     long serviceFeeMinor, long channelFeeMinor, long netMinor,
                     int billCount) {
    }
}
