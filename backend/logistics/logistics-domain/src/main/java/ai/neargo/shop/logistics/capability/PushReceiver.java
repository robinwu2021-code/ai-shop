package ai.neargo.shop.logistics.capability;

import ai.neargo.shop.spi.logistics.TraceResult;

import java.util.Map;

/**
 * 推送接收：验签、解析渠道推来的轨迹，并决定给渠道回什么。
 *
 * <p>回执格式各家不一样（快递100 要 {@code {"result":true,"returnCode":"200"}}），所以由实现给；
 * Controller 只做分派，不含任何渠道逻辑（TDD-物流模块 功能模块方案 M4）。
 */
public interface PushReceiver extends ChannelCapability {

    Parsed parse(Request request);

    /** 给渠道的成功回执，原样写回 HTTP 响应体。**只要渠道名存在就一律回成功**，理由见方案 M4 */
    String ack();

    /** @param form 表单参数；@param body 原始请求体（JSON 推送的渠道用） */
    record Request(Map<String, String> form, String body) {
    }

    /**
     * @param verified           验签通过
     * @param channelCarrierCode 渠道里的承运商编码（经 {@code lgs_carrier_code} 反查我方码）
     * @param waybillNo          运单号
     * @param trace              轨迹（统一结构）；停止跟踪类推送可为 null
     * @param trackingEnded      渠道说不再跟踪了（快递100 abort）
     * @param correctedFrom      渠道纠正承运商时的原编码（快递100 autoCheck=1 的 comOld），没纠正为 null
     */
    record Parsed(boolean verified, String channelCarrierCode, String waybillNo, TraceResult trace,
                  boolean trackingEnded, String correctedFrom) {

        public static Parsed rejected() {
            return new Parsed(false, null, null, null, false, null);
        }
    }
}
