package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.channel.config.FoodCatalogClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreGoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoreGoodsCatalogQueryPortTest {
    @Test void standaloneConfigurationQueriesPlatformWithOriginalToken() throws Exception {
        var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(GoodsCatalogInternalApi.LOOKUP_PATH,exchange -> {
            count.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8))
                    .contains("\"entityNo\":\"E\"","\"storeNo\":\"S\"","\"goodsNo\":\"G\"");
            byte[] response=("{\"goodsNo\":\"G\",\"entityNo\":\"E\",\"storeNo\":\"S\",\"title\":\"菜品\",\"cover\":null,"
                    +"\"baseOnSale\":false,\"auditStatus\":\"PENDING\",\"version\":3,\"skus\":[{\"skuNo\":\"SKU\",\"spec\":\"大份\",\"version\":1}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class).withPropertyValues("shop.food.enabled=true")
                    .withPropertyValues("shop.services.targets.PLATFORM=http://127.0.0.1:"+server.getAddress().getPort(),
                            "shop.services.internal-token=wire").run(c -> {
                        assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(GoodsCatalogQueryPort.class);
                        var fact=c.getBean(GoodsCatalogQueryPort.class).getGoods("E","S","G");
                        assertThat(fact).isEqualTo(new GoodsFact("G","E","S","菜品",null,false,"PENDING",3,List.of(new SkuFact("SKU","大份",1))));
                    });
            assertThat(count.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
    @Test void preservesBusinessAndTransportErrorsWithoutRetry() {
        for(int status:new int[]{400,403,404,409,503,0}) {
            var count=new AtomicInteger();
            GoodsCatalogInternalApi api=q -> {
                count.incrementAndGet();throw new ServiceCallException(status==0?CallOutcome.TIMEOUT:CallOutcome.REMOTE_ERROR,
                        ServiceName.PLATFORM,status,"safe");
            };
            var service=new CoreGoodsCatalogQueryPortImpl(() -> api);
            if(status>=400&&status<500) {
                var code=switch(status) { case 400 -> ErrorCode.BAD_REQUEST;case 403 -> ErrorCode.FORBIDDEN;case 404 -> ErrorCode.NOT_FOUND;default -> ErrorCode.CONFLICT; };
                assertThatThrownBy(() -> service.getGoods("E","S","G")).isInstanceOfSatisfying(BizException.class,e -> assertThat(e.errorCode()).isEqualTo(code));
            } else assertThatThrownBy(() -> service.getGoods("E","S","G")).isInstanceOf(ServiceCallException.class);
            assertThat(count.get()).isEqualTo(1);
        }
    }
    @Test void missingConfigurationAndEmptyResponseNeverBecomeEmptyOrSellableFacts() {
        new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class).withPropertyValues("shop.food.enabled=true").run(c ->
                assertThatThrownBy(() -> c.getBean(GoodsCatalogQueryPort.class).getGoods("E","S","G"))
                        .isInstanceOf(ServiceCallException.class));
        var wrong=new CoreGoodsCatalogQueryPortImpl(() -> q -> new GoodsFact("G","OTHER","S","private",null,true,"APPROVED",0,List.of()));
        assertThatThrownBy(() -> wrong.getGoods("E","S","G")).isInstanceOf(ServiceCallException.class);
        var service=new CoreGoodsCatalogQueryPortImpl(() -> q -> null);
        assertThatThrownBy(() -> service.getGoods("E","S","G")).isInstanceOf(ServiceCallException.class);
    }
}
