package ai.neargo.shop.pay.openapp;

import java.util.List;

/** 接入方当前可用于验签的 HMAC 密钥（轮换重叠期可能两把；安全规范 §3.4） */
public interface AppKeyResolver {

    List<String> hmacSecretsOf(String appNo);
}
