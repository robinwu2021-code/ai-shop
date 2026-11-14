package ai.neargo.shop.invbridge;

import static org.assertj.core.api.Assertions.*;
import ai.neargo.inventory.client.InventoryClient;
import ai.neargo.shop.spi.product.StockReplenishmentSourcePort;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.invbridge.port.InventoryReplenishmentSourcePortImpl;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class InventoryReplenishmentSourceHttpTest {
    @Test void sourceTransportBootstrapsWithTheEmbeddedInventoryEngineDisabled(){
        new ApplicationContextRunner().withUserConfiguration(InventoryClientConfiguration.class,InventoryReplenishmentSourcePortImpl.class)
                .withPropertyValues("shop.inventory.enabled=false","inventory.enabled=false","shop.inventory.service.base-url=http://127.0.0.1:1", "shop.inventory.replenishment.source-system=TEST")
                .run(c->{assertThat(c).hasNotFailed().hasSingleBean(InventoryClient.class).hasSingleBean(StockReplenishmentSourcePort.class);
                    assertThat(c.getBeansOfType(ai.neargo.inventory.domain.service.InventoryAclService.class)).isEmpty();});
    }
    @Test void realClientTransportsPostedProofWithAuthenticatedOwnerAndExactDecimals()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var owner=new AtomicReference<String>();var actor=new AtomicReference<String>();var path=new AtomicReference<String>();
        server.createContext("/",e->{
            owner.set(e.getRequestHeaders().getFirst("X-Inventory-Owner-Ref"));actor.set(e.getRequestHeaders().getFirst("X-Inventory-Actor"));path.set(e.getRequestURI().getPath());
            String body=e.getRequestURI().getPath().endsWith("checkpoint")?"{\"ownerId\":\"OWNER\",\"postingSequence\":50}":e.getRequestURI().getPath().contains("outbounds")
                    ?"{\"ownerId\":\"OWNER\",\"documentNo\":\"OUT\",\"lineNo\":1,\"itemId\":\"I\",\"baseUnit\":\"KG\",\"quantity\":0.123456,\"targetStoreNo\":\"S\",\"postingSequence\":51}"
                    :"{\"ownerId\":\"OWNER\",\"documentNo\":\"IN\",\"lineNo\":1,\"itemId\":\"I\",\"baseUnit\":\"KG\",\"quantity\":0.123456,\"sourceRef\":\"FRP\"}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}
        });server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var client=InventoryClient.create(()->Optional.of(base),"test","test-secret",Duration.ofSeconds(2),Duration.ofSeconds(2),5,Duration.ofSeconds(5),"test");
            var factory=new DefaultListableBeanFactory();factory.registerSingleton("client",client);
            var source=new InventoryReplenishmentSourcePortImpl(factory.getBeanProvider(InventoryClient.class),"STABLE_TEST_SOURCE");
            var s=new Scope("MAIN","ENTITY","S");var fact=source.outbound(s,"ACTOR","OUT",1);
            assertThat(fact.quantity()).isEqualByComparingTo("0.123456");assertThat(fact.postingSequence()).isEqualTo(51);assertThat(fact.sourceSystem()).isEqualTo("STABLE_TEST_SOURCE");
            assertThat(owner.get()).isEqualTo("ENTITY");assertThat(actor.get()).isEqualTo("ACTOR");
            assertThat(path.get()).endsWith("/replenishment-facts/outbounds/OUT/lines/1");
            assertThat(source.inbound(s,"ACTOR","IN",1).sourceRef()).isEqualTo("FRP");assertThat(source.checkpoint(s,"ACTOR").postingSequence()).isEqualTo(50);
            assertThatThrownBy(()->source.outbound(new Scope("MAIN","ENTITY","FOREIGN"),"ACTOR","OUT",1)).isInstanceOf(BizException.class);
        }finally{server.stop(0);}
    }
}
