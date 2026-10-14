package ai.neargo.shop.logistics.domain;

import ai.neargo.shop.spi.logistics.TraceStatus;

/**
 * 运单状态机：<b>只进不退</b>（TDD-物流模块 §2.4.5，AC4 / AC12）。
 *
 * <p>取值沿用 {@code FulShipment} 那一套（前端类型与存量数据都是它）。
 * 线上出过的缺陷不是映射错，是「另一个数据源（订单状态）也能写这个字段」—— 已签收被打回运输中、
 * 每轮再查一次快递100。这里所有来源（推送、查询）都经同一个 {@link #advance}，没有任何路径能退回去。
 */
public final class WaybillStatus {

    public static final String CREATED = "CREATED";
    public static final String PICKED_UP = "PICKED_UP";
    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String DELIVERING = "DELIVERING";
    public static final String DELIVERED = "DELIVERED";
    public static final String EXCEPTION = "EXCEPTION";
    public static final String CANCELLED = "CANCELLED";

    private WaybillStatus() {
    }

    /**
     * 一次推进的结果。
     *
     * @param next            推进后的状态（没变就等于当前）
     * @param firstPickedUp   这一次才首次进入揽收（或更后的阶段）—— 记 picked_up_at、判换 token
     * @param firstDelivered  这一次才首次签收 —— 记 signed_at、发签收事件
     */
    public record Transition(String next, boolean changed, boolean firstPickedUp, boolean firstDelivered) {
    }

    /**
     * @param cur      当前运单状态
     * @param incoming 渠道给的统一状态
     */
    public static Transition advance(String cur, TraceStatus incoming) {
        String current = cur == null ? CREATED : cur;
        if (isTerminal(current) || incoming == null) {
            return new Transition(current, false, false, false);
        }
        String target = switch (incoming) {
            case PICKED -> PICKED_UP;
            case IN_TRANSIT -> IN_TRANSIT;
            case DELIVERING -> DELIVERING;
            case SIGNED -> DELIVERED;
            case EXCEPTION -> EXCEPTION;
            case UNKNOWN -> current;
        };
        String next;
        if (EXCEPTION.equals(target)) {
            // 疑难：任一非终态都可进入（快递可能疑难之后又派送成功，所以它不是终态）
            next = EXCEPTION;
        } else if (EXCEPTION.equals(current)) {
            // 从疑难恢复：来了任何正常阶段都接受（疑难之前在哪一档已经不重要了）
            next = target;
        } else {
            next = rank(target) > rank(current) ? target : current;
        }
        boolean changed = !next.equals(current);
        boolean firstPickedUp = changed && rank(next) >= rank(PICKED_UP) && rank(current) < rank(PICKED_UP)
                && !EXCEPTION.equals(next);
        boolean firstDelivered = changed && DELIVERED.equals(next);
        return new Transition(next, changed, firstPickedUp, firstDelivered);
    }

    public static boolean isTerminal(String status) {
        return DELIVERED.equals(status) || CANCELLED.equals(status);
    }

    private static int rank(String s) {
        return switch (s) {
            case PICKED_UP -> 1;
            case IN_TRANSIT -> 2;
            case DELIVERING -> 3;
            case DELIVERED -> 4;
            default -> 0;
        };
    }
}
