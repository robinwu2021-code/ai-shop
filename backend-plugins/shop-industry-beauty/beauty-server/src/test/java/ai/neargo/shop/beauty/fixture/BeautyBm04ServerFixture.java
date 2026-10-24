package ai.neargo.shop.beauty.fixture;

import ai.neargo.shop.beauty.port.RemoteCheckoutPort;
import ai.neargo.shop.beauty.server.BeautyApplication;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/** Separate private child JVM with actual core kernels and production reverse owner providers. */
public final class BeautyBm04ServerFixture {
    private BeautyBm04ServerFixture() {}

    /**
     * Startup evidence records Bean origins and cannot substitute for the parent HTTP assertions.
     */
    public static void main(String[] arguments) throws Exception {
        for (String absent :
                new String[] {
                    "ai.neargo.shop.ShopApplication",
                    "ai.neargo.shop.trade.service.impl.CheckoutServiceImpl",
                    "ai.neargo.shop.beauty.api.biz.BizWorkOrderController"
                }) {
            try {
                Class.forName(absent);
            } catch (ClassNotFoundException expected) {
                continue;
            }
            throw new IllegalStateException(
                    "private SERVER contains HOST implementation " + absent);
        }
        for (String entry :
                System.getProperty("java.class.path").split(System.getProperty("path.separator"))) {
            if (entry.contains("shop-app")
                    || entry.contains("shop-core")
                    || entry.contains("shop-merchant")) {
                throw new IllegalStateException("private SERVER owner classpath leak");
            }
        }
        var context =
                new SpringApplicationBuilder(BeautyApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(arguments);
        var checkout = context.getBean(CheckoutPort.class);
        if (!(checkout instanceof RemoteCheckoutPort)) {
            throw new IllegalStateException("missing real reverse provider");
        }
        var workOrders = context.getBean(WorkOrderService.class);
        if (!workOrders.getClass().getName().contains("WorkOrderServiceImpl")) {
            throw new IllegalStateException(
                    "private SERVER requires actual core work-order kernel");
        }
        Path path = Path.of(System.getenv("BEAUTY_BM04_FIXTURE_DIR")).resolve("server.json");
        Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(
                path,
                context.getBean(ObjectMapper.class)
                        .writeValueAsString(
                                Map.of(
                                        "url",
                                        "http://127.0.0.1:"
                                                + ((WebServerApplicationContext) context)
                                                        .getWebServer()
                                                        .getPort(),
                                        "checkoutBean",
                                        checkout.getClass().getName(),
                                        "workOrderBean",
                                        workOrders.getClass().getName(),
                                        "classpath",
                                        System.getProperty("java.class.path"))));
    }
}
