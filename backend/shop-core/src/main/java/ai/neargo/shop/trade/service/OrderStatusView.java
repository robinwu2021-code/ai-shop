package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.List;
import java.util.Set;

/**
 * 订单的<b>展示状态</b>：库里的 6 个状态 × 履约方式 → 端上能直接用的词。
 *
 * <p><b>为什么需要这一层</b>：库里的模型是刻意粗的 —— {@code WAIT_FULFILL / FULFILLING}
 * 对快递、自提、自送一视同仁，状态机因此只有六个节点，好推理也好守。
 * 但对买家来说「已发货」和「已到自提点」是两件完全不同的事（一个是等，一个是去取），
 * 对商家来说「待发货」和「待核销」要点开的也是两个不同的标签页。
 *
 * <p>此前没有这一层，端上三套代码各自声明了一套更细的词
 * （c-app / b-app / ops-web 三套还互不相同），而后端下发的是库里那六个 ——
 * <b>结果是端上的标签页一条也匹配不到</b>：
 * 买家付完钱，订单从「待取货」里消失；商家的「待发货」永远是空的。
 * 而「全部」标签页是好的，所以看起来只是「有几个页签没数据」。
 *
 * <p>映射放在这里而不是各端各写一遍：三端各映射一次，迟早有一处把自提映成「已发货」。
 */
public final class OrderStatusView {

    /** 待付款 */
    public static final String WAIT_PAY = "WAIT_PAY";
    /** 已付款、等商家备货发货。b-app 的「待发货」标签页用它 */
    public static final String PAID = "PAID";
    /** 已发货（快递 / 商家自送）——买家在等 */
    public static final String SHIPPED = "SHIPPED";
    /** 已到自提点，等买家来取。b-app 的「待核销」用它 */
    public static final String ARRIVED = "ARRIVED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REFUNDED = "REFUNDED";

    /** 自提类履约：到货之后是「等人来取」，不是「在路上」。 */
    private static final Set<String> PICKUP = Set.of("STORE_PICKUP", "NEIGHBOR_PICKUP");

    private OrderStatusView() {
    }

    /** 库状态 × 履约方式 → 展示状态。 */
    public static String of(String status, String fulfillment) {
        if (status == null) {
            return WAIT_PAY;
        }
        return switch (status) {
            case OrdSubOrder.WAIT_FULFILL -> PAID;
            case OrdSubOrder.FULFILLING -> isPickup(fulfillment) ? ARRIVED : SHIPPED;
            default -> status;   // WAIT_PAY / COMPLETED / CANCELLED / REFUNDED 同名同义
        };
    }

    /**
     * 反向：端上传来的展示状态 → 该查库里的哪些状态。
     *
     * <p>返回空集合表示<b>这个词不对应任何库状态</b>，调用方应当当作「不过滤」处理 ——
     * 而不是查出零条。未知筛选值让列表变空，看起来就是「一件都没有」，
     * 而真相是端上传了一个后端不认识的词（这正是这次要修的那个 bug 的形状）。
     */
    public static List<String> toStored(String view) {
        if (view == null || view.isBlank()) {
            return List.of();
        }
        return switch (view) {
            case PAID -> List.of(OrdSubOrder.WAIT_FULFILL);
            // 发货与到货在库里是同一个状态，靠履约方式区分；这里先按状态收窄，再由调用方按履约过滤
            case SHIPPED, ARRIVED -> List.of(OrdSubOrder.FULFILLING);
            case WAIT_PAY, COMPLETED, CANCELLED, REFUNDED -> List.of(view);
            // 兼容直接传库状态的调用方（后端自己的测试、运维排查）
            case OrdSubOrder.WAIT_FULFILL -> List.of(OrdSubOrder.WAIT_FULFILL);
            case OrdSubOrder.FULFILLING -> List.of(OrdSubOrder.FULFILLING);
            default -> List.of();
        };
    }

    /** 这个展示状态是否**只要**自提单（{@code ARRIVED}）或**只要**非自提单（{@code SHIPPED}）。 */
    public static Boolean pickupOnly(String view) {
        if (ARRIVED.equals(view)) {
            return Boolean.TRUE;
        }
        if (SHIPPED.equals(view)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isPickup(String fulfillment) {
        return fulfillment != null && PICKUP.contains(fulfillment);
    }
}
