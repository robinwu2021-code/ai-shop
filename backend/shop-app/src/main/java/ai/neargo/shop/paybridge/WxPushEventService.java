package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.service.OrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 微信消息推送事件的处理入口（TDD-物流域-完整方案 批 B）。
 *
 * <p>今天只认一个事件：<b>{@code trade_manage_order_settlement}</b> —— 订单将要结算或已经结算。
 * 它在买家点了确认收货（或微信到期自动确认）之后推过来，是我们唯一能从微信侧得知
 * 「买家收到货了」的信号（微信不回调物流节点，只回调结算）。收到就把子单推成已完成，
 * 不用再等「签收后 7 天 / 发货后 15 天」那个兜底定时器。
 *
 * <h2>为什么是「按字段名在任意层级找」</h2>
 * 微信文档给了字段表（{@code confirm_receive_method} 1 手动 / 2 自动、
 * {@code confirm_receive_time} 秒、{@code settlement_time} 秒），
 * <b>但没给完整报文的嵌套结构</b>。硬按猜的层级取值，猜错时的表现是
 * 「事件收到了、字段取不到、什么都没发生」—— 和没接一样，且不报错。
 *
 * <p>所以这里按**字段名**递归找，不依赖层级；真找不到订单标识就把原文整条落 WARN。
 * 第一条真实事件到达时，日志里那条原文就是把它改成精确解析的依据。
 */
@Service
public class WxPushEventService {

    private static final Logger log = LoggerFactory.getLogger(WxPushEventService.class);

    /** 订单将要结算或已经结算。买家确认收货之后推 */
    static final String EVT_SETTLEMENT = "trade_manage_order_settlement";

    /** 订单标识可能叫这几个名字之一（微信文档未给完整报文，按已知命名逐个试） */
    private static final List<String> ORDER_KEYS =
            List.of("merchant_trade_no", "out_trade_no", "transaction_id");

    private final OrderService orderService;
    private final TradeMappers.SubOrderMapper subOrderMapper;
    private final TradeMappers.OrderMapper orderMapper;
    private final ObjectMapper json;

    public WxPushEventService(OrderService orderService, TradeMappers.SubOrderMapper subOrderMapper,
                              TradeMappers.OrderMapper orderMapper, ObjectMapper json) {
        this.orderService = orderService;
        this.subOrderMapper = subOrderMapper;
        this.orderMapper = orderMapper;
        this.json = json;
    }

    /** @return 真的推进了几张子单；认不出的事件返回 0（不抛，微信不该为我们的解析失败重推） */
    public int onEvent(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return 0;
        }
        JsonNode root;
        try {
            root = json.readTree(rawBody);
        } catch (Exception e) {
            log.warn("[wxpush] 事件不是 JSON（可能是 XML 明文模式），原文：{}", clip(rawBody));
            return 0;
        }
        String event = firstText(root, "Event", "event", "event_type", "MsgType");
        if (!EVT_SETTLEMENT.equalsIgnoreCase(event)) {
            log.info("[wxpush] 事件 {} 暂不处理", event == null ? "(认不出类型)" : event);
            return 0;
        }
        String orderNo = resolveOrderNo(root);
        if (orderNo == null) {
            // 认不出订单就把原文整条留下 —— 这条日志就是把容错解析改成精确解析的依据
            log.warn("[wxpush] 结算事件认不出订单标识，原文：{}", clip(rawBody));
            return 0;
        }
        int n = completeSubOrders(orderNo);
        log.info("[wxpush] 结算事件：订单 {} 推进 {} 张子单到已完成（方式={}）",
                orderNo, n, firstText(root, "confirm_receive_method"));
        return n;
    }

    /**
     * 把这个支付单下还在履约中的子单推成已完成。
     *
     * <p><b>幂等</b>：已经是 COMPLETED 的不动 —— 兜底定时器（签收后 7 天 / 发货后 15 天）
     * 可能已经先一步完成过，那时这个事件就是一条确认，不该再动一次状态。
     */
    private int completeSubOrders(String orderNo) {
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getOrderNo, orderNo)
                        .eq(OrdSubOrder::getStatus, OrdSubOrder.FULFILLING)));
        int n = 0;
        for (OrdSubOrder sub : subs) {
            try {
                orderService.confirmReceipt(sub.getSubOrderNo());
                n++;
            } catch (RuntimeException e) {
                // 单张失败不影响同单其余（售后进行中之类会被业务规则拒，那是对的）
                log.warn("[wxpush] 子单 {} 确认收货被拒：{}", sub.getSubOrderNo(), e.toString());
            }
        }
        return n;
    }

    /** 事件里的订单标识 → 我方订单号。商户单号就是我方订单号；微信交易号要反查 */
    private String resolveOrderNo(JsonNode root) {
        for (String key : ORDER_KEYS) {
            String v = firstText(root, key);
            if (v == null || v.isBlank()) {
                continue;
            }
            if ("transaction_id".equals(key)) {
                var o = DataScopeContext.executeWithoutScope(() -> orderMapper.selectOne(
                        Wrappers.<ai.neargo.shop.trade.entity.OrdOrder>lambdaQuery()
                                .eq(ai.neargo.shop.trade.entity.OrdOrder::getPayTradeNo, v).last("limit 1")));
                if (o != null) {
                    return o.getOrderNo();
                }
                continue;
            }
            return v;
        }
        return null;
    }

    /** 在整棵 JSON 里按字段名找第一个有值的（不依赖嵌套层级，理由见类注释） */
    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode hit = find(node, name);
            if (hit != null && !hit.isNull()) {
                String v = hit.isValueNode() ? hit.asString() : null;
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static JsonNode find(JsonNode node, String name) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode direct = node.get(name);
            if (direct != null) {
                return direct;
            }
        }
        for (JsonNode child : node) {
            JsonNode hit = find(child, name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String clip(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000) + "…";
    }
}
