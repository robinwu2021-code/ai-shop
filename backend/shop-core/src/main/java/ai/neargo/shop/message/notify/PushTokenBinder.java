package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备绑定与解绑（ADR-018）。
 *
 * <p><b>绑定要同时做「抢占」</b>：同一台设备（clientId）之前挂在别人名下时，
 * 先把那条删掉。共用设备的门店换班是真实场景 —— 不抢占的话，
 * 上一班的人会继续收到这家店的订单推送，那是隐私事故，不是「多推一条」。
 */
@Service
public class PushTokenBinder {

    private static final Logger log = LoggerFactory.getLogger(PushTokenBinder.class);

    private final PushTokenMapper tokenMapper;

    public PushTokenBinder(PushTokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    /**
     * 登录后上报。幂等：同一人同一平台重复上报只更新 clientId。
     *
     * @param platform {@link MsgPushToken#APP_ANDROID} / {@link MsgPushToken#APP_IOS}
     */
    @Transactional
    public void register(String receiverType, String receiverNo, String platform, String clientId) {
        if (receiverNo == null || receiverNo.isBlank()
                || clientId == null || clientId.isBlank()
                || platform == null || platform.isBlank()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> {
            // 抢占：这台设备之前登的是别人，先解绑他
            tokenMapper.delete(Wrappers.<MsgPushToken>lambdaQuery()
                    .eq(MsgPushToken::getClientId, clientId)
                    .ne(MsgPushToken::getReceiverNo, receiverNo));

            MsgPushToken existing = tokenMapper.selectOne(Wrappers.<MsgPushToken>lambdaQuery()
                    .eq(MsgPushToken::getReceiverType, receiverType)
                    .eq(MsgPushToken::getReceiverNo, receiverNo)
                    .eq(MsgPushToken::getPlatform, platform).last("limit 1"));
            if (existing != null) {
                existing.setClientId(clientId);
                return tokenMapper.updateById(existing);
            }
            MsgPushToken t = new MsgPushToken();
            t.setReceiverType(receiverType);
            t.setReceiverNo(receiverNo);
            t.setPlatform(platform);
            t.setClientId(clientId);
            return tokenMapper.insert(t);
        });
    }

    /**
     * 登出时解绑。**必须调**：不解绑的话，换人登录这台设备后，
     * 前一个账号的订单仍然推到这台机器上。
     *
     * <p>按 clientId 删而不是按人删：一个人有多台设备，登出的只是手上这一台。
     * clientId 拿不到时（端上初始化失败）退化成按人+平台删，宁可多解一台。
     */
    @Transactional
    public void unregister(String receiverType, String receiverNo, String clientId) {
        if (receiverNo == null || receiverNo.isBlank()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> {
            if (clientId != null && !clientId.isBlank()) {
                return tokenMapper.delete(Wrappers.<MsgPushToken>lambdaQuery()
                        .eq(MsgPushToken::getReceiverType, receiverType)
                        .eq(MsgPushToken::getReceiverNo, receiverNo)
                        .eq(MsgPushToken::getClientId, clientId));
            }
            log.info("[push] 解绑未带 clientId，按人解绑全部设备 receiver={}", receiverNo);
            return tokenMapper.delete(Wrappers.<MsgPushToken>lambdaQuery()
                    .eq(MsgPushToken::getReceiverType, receiverType)
                    .eq(MsgPushToken::getReceiverNo, receiverNo));
        });
    }
}
