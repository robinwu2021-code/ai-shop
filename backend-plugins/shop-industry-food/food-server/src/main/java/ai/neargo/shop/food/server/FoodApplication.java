package ai.neargo.shop.food.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立餐饮进程入口；业务装配和内部接口在后续切片接入。 */
@SpringBootApplication(excludeName = {
        "ai.neargo.common.data.DataAutoConfiguration",
        "ai.neargo.common.security.SecurityAutoConfiguration",
        "ai.neargo.common.security.SessionStoreAutoConfiguration",
        "ai.neargo.common.security.rbac.RbacAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
public class FoodApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodApplication.class, args);
    }
}
