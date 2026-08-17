package ai.neargo.shop.message.notify;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyChannelMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 外部接入：商家自带渠道的配置（设计：触达推送中台-模块抽象 · N5）。
 *
 * <p>商家把自己的短信签名 / 推送账号密钥交给平台，只服务该商家（{@code scope=MERCHANT}，
 * {@code owner_no=商家号}）。密钥经 {@link NotifyCredCipher} 加密存 {@code secret_cipher}，
 * <b>明文永不落库、永不回前端</b> —— 读取仅供发送侧解密用（{@link #decryptSecret}）。
 */
@Service
public class MerchantChannelService {

    private final NotifyChannelMapper mapper;
    private final NotifyCredCipher cipher;
    private final PlatformChannelCredentials creds;
    private final tools.jackson.databind.ObjectMapper json;

    public MerchantChannelService(NotifyChannelMapper mapper, NotifyCredCipher cipher,
                                  PlatformChannelCredentials creds,
                                  tools.jackson.databind.ObjectMapper json) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.creds = creds;
        this.json = json;
    }

    /**
     * 商家新增/更新自己的一条渠道。幂等：同商家同类型同供应商只一条。
     *
     * @param secretPlain 商家凭据明文（签名/密钥的 JSON）。**非空才加密覆盖**；传空表示只改非密项、
     *                    不动已存密钥（避免「编辑一次配置就把密钥清了」）
     */
    @Transactional
    public NotifyChannel upsert(String ownerNo, String channelType, String provider,
                                String configJson, String secretPlain, String operator) {
        if (ownerNo == null || ownerNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 有密钥要存，就必须先配好加密密钥 —— 否则拒绝，绝不明文落库
        boolean hasSecret = secretPlain != null && !secretPlain.isBlank();
        if (hasSecret && !cipher.configured()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 商家凭证 JSON 必须含该供应商所需字段（与平台侧同一份规格）—— 配错在这里拦，
        // 不留到发送那一刻才发现「少了 masterSecret」
        if (hasSecret) {
            validateSecret(channelType, provider, secretPlain);
        }
        return DataScopeContext.executeWithoutScope(() -> {
            NotifyChannel ch = mapper.selectOne(Wrappers.<NotifyChannel>lambdaQuery()
                    .eq(NotifyChannel::getChannelType, channelType)
                    .eq(NotifyChannel::getProvider, provider)
                    .eq(NotifyChannel::getScope, NotifyChannel.SCOPE_MERCHANT)
                    .eq(NotifyChannel::getOwnerNo, ownerNo).last("limit 1"));
            boolean isNew = ch == null;
            if (isNew) {
                ch = new NotifyChannel();
                ch.setChannelNo("NCH-M-" + ownerNo + "-" + channelType + "-" + provider);
                ch.setChannelType(channelType);
                ch.setProvider(provider);
                ch.setScope(NotifyChannel.SCOPE_MERCHANT);
                ch.setOwnerNo(ownerNo);
                ch.setEnabled(true);
                ch.setPriority(50); // 商家自配优先于平台默认（100），小者先
            }
            ch.setConfigJson(configJson == null || configJson.isBlank() ? "{}" : configJson);
            if (hasSecret) {
                ch.setSecretCipher(cipher.encrypt(secretPlain));
            }
            ch.setUpdatedBy(operator);
            if (isNew) {
                mapper.insert(ch);
            } else {
                mapper.updateById(ch);
            }
            return ch;
        });
    }

    /**
     * 校验商家凭证 JSON：能解析，且含该供应商所需的全部字段（非空）。缺字段直接拒 ——
     * 用与平台侧同一份规格（{@link PlatformChannelCredentials#requiredSecretKeys}），两边不会分叉。
     */
    private void validateSecret(String channelType, String provider, String secretPlain) {
        List<String> required = creds.requiredSecretKeys(channelType, provider);
        if (required.isEmpty()) {
            return; // 未知供应商不强校验（也拦不出什么）
        }
        tools.jackson.databind.JsonNode node;
        try {
            node = json.readTree(secretPlain);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        for (String key : required) {
            tools.jackson.databind.JsonNode v = node.get(key);
            if (v == null || v.asString() == null || v.asString().isBlank()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }

    /** 商家自己的渠道列表。<b>调用方必须映射成不含 secret_cipher 的 VO</b>再下发。 */
    public List<NotifyChannel> listForOwner(String ownerNo) {
        return DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<NotifyChannel>lambdaQuery()
                        .eq(NotifyChannel::getScope, NotifyChannel.SCOPE_MERCHANT)
                        .eq(NotifyChannel::getOwnerNo, ownerNo)
                        .orderByAsc(NotifyChannel::getChannelType)
                        .orderByAsc(NotifyChannel::getProvider)));
    }

    /**
     * 解密商家凭据 —— **仅发送侧内部调用**。返回明文只在内存里流转，绝不进任何响应体。
     * 无密文返回 null（商家还没配密钥）。
     */
    public String decryptSecret(NotifyChannel ch) {
        String ct = ch.getSecretCipher();
        return ct == null || ct.isBlank() ? null : cipher.decrypt(ct);
    }
}
