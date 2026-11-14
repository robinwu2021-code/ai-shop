package ai.neargo.shop.config;

import ai.neargo.secret.api.SecretNamespace;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 密钥模块的部署配置（TDD-支付服务-W5密钥模块 §2 配置项）。
 *
 * <pre>
 * shop.secret.env: PRODUCTION                       # SANDBOX / PRODUCTION，无默认值：不配就拒绝加解密
 * shop.secret.master-keys:
 *   - alias: pay-prod-2026a                         # 写进 sec_master_key.kms_key_id 的名字
 *     namespace: PAY
 *     material: ${SECRET_MK_PAY_PROD_2026A}         # 32 字节 Base64，由 systemd EnvironmentFile 注入，不写默认值
 *     active: true                                  # 换主密钥：注入新的并标 true，旧的改 false 留到重新包裹完
 * </pre>
 *
 * <p>生产 yml 里不出现这一段的任何字面量 —— {@code NoSecretDefaultInConfigTest} 盯着；
 * 主密钥材料只走环境变量。
 */
@ConfigurationProperties("shop.secret")
public record SecretProperties(String env, List<MasterKey> masterKeys) {

    public SecretProperties {
        masterKeys = masterKeys == null ? List.of() : List.copyOf(masterKeys);
    }

    /** 一把注入的主密钥；{@link #toString()} 不带材料 */
    public record MasterKey(String alias, SecretNamespace namespace, String material, boolean active) {

        @Override
        public String toString() {
            return "MasterKey[alias=" + alias + ", namespace=" + namespace + ", active=" + active + "]";
        }
    }
}
