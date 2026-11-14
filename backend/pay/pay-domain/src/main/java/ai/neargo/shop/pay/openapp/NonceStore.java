package ai.neargo.shop.pay.openapp;

import java.time.Duration;

/** 签名防重放：同一调用方的同一 nonce 在有效期内只能被抢占一次（安全规范 §2.3 / §5.4） */
public interface NonceStore {

    /** @return 抢占成功（第一次见）；false = 重放 */
    boolean claim(String callerType, Long callerId, String nonce, Duration ttl);
}
