package ai.neargo.shop.spi.product;

/**
 * trade → product：这一单评价过没有。
 *
 * <p>为什么要一个 Port 而不是让 trade 直接查评价表：模块边界靠自觉维护，
 * {@code ArchitectureTest} 会挡下反向依赖 —— 与 {@code ReviewableOrderPort}
 * 是同一条边的两个方向（那个是发表评价前核实订单，这个是订单详情要知道评没评过）。
 *
 * <p><b>为什么订单详情需要它</b>：C 端「去评价」的判据是
 * 「已完成 且 没评过」，而后半截此前**根本没有数据源** ——
 * 契约里声明了 {@code reviewed}，后端从来不发，于是判据恒为真，
 * 已经评过的订单照样显示那个按钮（见 TDD-交互清单缺口修复 G15）。
 */
public interface ReviewQueryPort {

    /**
     * 这张**子单**有没有被评价过。
     *
     * <p>键是子单号不是主单号：一个主单会按商家拆成多个子单，
     * 而评价是**对商家**的（同 {@code ReviewableOrderPort} 的口径）。
     * 用主单号判的话，买了两家的东西评了其中一家，另一家的入口也会消失。
     */
    boolean reviewed(String subOrderNo);
}
