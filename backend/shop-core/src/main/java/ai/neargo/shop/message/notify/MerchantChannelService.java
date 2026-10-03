package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.entity.NotifyChannel;

import java.util.List;

/**
 * 外部接入：商家自带渠道的配置（设计：触达推送中台-模块抽象 · N5）。
 *
 * <p>商家把自己的短信签名 / 推送账号密钥交给平台，只服务该商家（{@code scope=MERCHANT}，
 * {@code owner_no=商家号}）。密钥经 {@code NotifyCredCipher} 加密存 {@code secret_cipher}，
 * <b>明文永不落库、永不回前端</b> —— 读取仅供发送侧解密用（{@link #decryptSecret}）。
 *
 * <p><b>为什么是接口</b>：同目录的 {@code NotifyChannelService} / {@code NotifyLogService}
 * 早就是「接口 + impl/」，这个类此前是具体类 —— `ArchitectureTest.serviceMustBeInterface`
 * 一直红着报它。补齐的是同一套约定，不是新立规矩。
 */
public interface MerchantChannelService {

    /** 新增或覆盖一条商家渠道；密钥留空表示不改动已存的那份。 */
    NotifyChannel upsert(String ownerNo, String channelType, String provider,
                         String signName, String secret, String extraJson);

    /** 该商家配了哪些渠道。**不带密钥明文**。 */
    List<NotifyChannel> listForOwner(String ownerNo);

    /** 解出密钥明文 —— **只给发送侧调**，不要经任何控制器回前端。 */
    String decryptSecret(NotifyChannel ch);
}
