package ai.neargo.shop.pay.channel.api;

import java.util.Objects;

/**
 * 来源两维 + 端上细节 + 业务归属（渠道接入统一封装设计 §2.4、§11.2）。四向记录逐字落库，任一维缺了排障就少一条线索。
 *
 * @param deviceId 终端号或设备指纹
 * @param operatorNo 收银员 / 商家操作员 / 运营
 */
public record SourceInfo(CallerType sourceType, Long sourceId, String appNo, ClientType clientType,
                         String clientIp, String userAgentDigest, String deviceId, String operatorNo,
                         String bizType, String bizNo) {

    public SourceInfo {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(clientType, "clientType");
    }

    /** 系统内部发起（补查、对账、定时任务） */
    public static SourceInfo system(String bizType, String bizNo) {
        return new SourceInfo(CallerType.INTERNAL, null, null, ClientType.SERVER, null, null, null, null, bizType, bizNo);
    }
}
