package ai.neargo.shop.logistics.capability;

import ai.neargo.shop.spi.logistics.TraceResult;

import java.util.Optional;

/** WeChat logistics-message follow; its token is distinct from the query-plugin display token. */
public interface WaybillFollower {
    boolean available();

    ChannelOutcome follow(FollowCmd command);

    Optional<TraceResult> queryFollow(String token, String carrier, String waybillNo);

    record FollowCmd(String openid, String transId, String waybillNo, String deliveryId,
                     String receiverPhone, String goodsJson, String orderPath) { }
}
