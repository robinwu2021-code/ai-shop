package ai.neargo.shop.commerce.server;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立服务仅开放可信内部合同；公共BFF仍由平台承载。 */
@SpringBootApplication(
        excludeName = {
            "ai.neargo.common.data.DataAutoConfiguration",
            "ai.neargo.common.security.SecurityAutoConfiguration",
            "ai.neargo.common.security.SessionStoreAutoConfiguration",
            "ai.neargo.common.security.rbac.RbacAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
        })
public class CommerceApplication {
    public static void main(String[] args) {
        var app = new SpringApplication(CommerceApplication.class);
        app.setDefaultProperties(Map.of("shop.commerce.deployment", "standalone"));
        app.run(args);
    }
}
