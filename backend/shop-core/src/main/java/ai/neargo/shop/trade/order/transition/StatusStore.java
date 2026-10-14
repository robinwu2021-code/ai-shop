package ai.neargo.shop.trade.order.transition;

/** 状态的读与条件写。转换门之外不许直接写状态列（架构测试守，见 TDD-订单状态机）。 */
public interface StatusStore {

    /** 当前状态；单不存在返回 null */
    String load(Aggregate aggregate, String no);

    /**
     * 条件更新：{@code where no = ? and status = from}。
     *
     * @return 是否真的写进去了 —— false 表示状态已被别的流程改走（并发），调用方报 CONFLICT，绝不覆盖
     */
    boolean cas(Aggregate aggregate, String no, String from, String to);
}
