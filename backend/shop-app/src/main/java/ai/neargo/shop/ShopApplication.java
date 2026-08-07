package ai.neargo.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ai-shop 服务端（模块化单体形态）。
 *
 * <p>三端同一进程：{@code /mp/**}(C 端) · {@code /biz/**}(B 端) · {@code /ops/**}(平台端)。
 * 拆微服务时新增 {@code shop-app-xxx} 启动模块，只改依赖列表，本类与业务代码不动。
 */
@SpringBootApplication
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
