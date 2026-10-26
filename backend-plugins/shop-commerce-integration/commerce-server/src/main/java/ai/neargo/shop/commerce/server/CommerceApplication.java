package ai.neargo.shop.commerce.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立服务装配入口；未实现可信内部合同前不暴露匿名业务接口。 */
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
        SpringApplication.run(CommerceApplication.class, args);
    }
}
