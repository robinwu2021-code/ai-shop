package ai.neargo.shop.marketing.port;

import ai.neargo.shop.marketing.attribution.FissionInviteService;
import ai.neargo.shop.marketing.attribution.FissionService;
import ai.neargo.shop.spi.marketing.FissionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 见 {@link FissionPort}。 */
@Component
public class FissionPortImpl implements FissionPort {

    private static final Logger log = LoggerFactory.getLogger(FissionPortImpl.class);

    private final FissionService fissionService;
    private final FissionInviteService inviteService;

    public FissionPortImpl(FissionService fissionService, FissionInviteService inviteService) {
        this.fissionService = fissionService;
        this.inviteService = inviteService;
    }

    @Override
    public void onRegister(String inviterNo, String inviteeNo, String deviceId, String phoneTail) {
        if (inviterNo == null || inviterNo.isBlank()) {
            return;   // 自然注册，不是邀请
        }
        try {
            /*
             * **落到「当前启用的那个活动」上。** 同时启用多个的话取第一个 ——
             * 运营端的设计是同一时间只跑一个邀请有礼（`enabledOnly` 那个开关），
             * 这里不去发明「按什么规则挑一个」，那是产品决定。
             */
            var enabled = fissionService.list(true);
            if (enabled.isEmpty()) {
                return;
            }
            inviteService.record(enabled.get(0).fissionNo(), inviterNo, inviteeNo,
                    deviceId, phoneTail);
        } catch (RuntimeException e) {
            // 失败不打断注册：让一次营销统计挡住用户注册，代价方向完全反了
            log.warn("[裂变] 记邀请失败 inviter={} invitee={}：{}", inviterNo, inviteeNo, e.toString());
        }
    }
}
