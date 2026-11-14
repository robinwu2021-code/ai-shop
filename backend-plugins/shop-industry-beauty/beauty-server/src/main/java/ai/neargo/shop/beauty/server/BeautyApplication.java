package ai.neargo.shop.beauty.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立美业进程入口；业务装配和内部接口在后续切片接入。 */
@SpringBootApplication(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "ai.neargo.common.data.DataAutoConfiguration",
        "ai.neargo.common.security.SecurityAutoConfiguration",
        "ai.neargo.common.security.SessionStoreAutoConfiguration",
        "ai.neargo.common.security.rbac.RbacAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration"
})
public class BeautyApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeautyApplication.class, args);
    }
}
