package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.ListReservationsQuery;
import ai.neargo.shop.food.dto.ReservationVO;
import ai.neargo.shop.food.mapper.FoodMappers.ReservationMapper;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.impl.ReservationServiceImpl;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class ReservationServiceTest extends FoodWriteGuardFixture {
    static final Instant FROM=Instant.parse("2026-10-21T12:00:00Z"),TO=Instant.parse("2026-10-21T14:00:00Z");
    void run(ContextConsumer<AssertableApplicationContext> test) { runner().withUserConfiguration(Reservations.class).run(test); }
    Fixture fixture(AssertableApplicationContext c) {
        var f=prepare(c);
        f.sql().execute("CREATE TABLE fnb_reservation(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                +"entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),"
                +"version BIGINT DEFAULT 0,deleted INT DEFAULT 0,reservation_no VARCHAR(32) UNIQUE,store_no VARCHAR(32),"
                +"resource_hold_no VARCHAR(32),buyer_no VARCHAR(32),contact_ref VARCHAR(64),party_size INT,start_at DATETIME(3),"
                +"end_at DATETIME(3),status VARCHAR(24),deposit_ref VARCHAR(64),check_no VARCHAR(32))");
        f.sql().update("INSERT INTO fnb_reservation(entity_no,store_no,reservation_no,party_size,start_at,end_at,status,buyer_no,contact_ref) VALUES"
                +"('E','A','R1',2,'2026-10-21 11:00:00','2026-10-21 13:00:00','CONFIRMED','real-buyer','secret-contact'),"
                +"('E','A','R2',3,'2026-10-21 12:00:00','2026-10-21 13:00:00','HELD',NULL,'contact'),"
                +"('E','A','R3',2,'2026-10-21 14:00:00','2026-10-21 15:00:00','CONFIRMED',NULL,'contact'),"
                +"('E','A','R4',2,'2026-10-21 10:00:00','2026-10-21 12:00:00','CONFIRMED',NULL,'contact'),"
                +"('E','B','RB',2,'2026-10-21 12:00:00','2026-10-21 13:00:00','CONFIRMED',NULL,'contact'),"
                +"('OTHER','A','RX',2,'2026-10-21 12:00:00','2026-10-21 13:00:00','CONFIRMED',NULL,'contact')");
        return f;
    }
    @Test void overlapWindowAndPaginationShareExactFilters() {
        run(c -> {
            fixture(c);var s=c.getBean(ReservationService.class);
            var page=s.listReservations("E","A",new ListReservationsQuery(1,1,FROM,TO,null));
            assertThat(page.total()).isEqualTo(2);assertThat(page.records()).extracting(ReservationVO::reservationNo).containsExactly("R1");
            assertThat(s.listReservations("E","A",new ListReservationsQuery(2,1,FROM,TO,null)).records())
                    .extracting(ReservationVO::reservationNo).containsExactly("R2");
            assertThat(s.listReservations("E","A",new ListReservationsQuery(null,null,FROM,TO,"CONFIRMED")).total()).isEqualTo(1);
        });
    }
    @Test void ownerStoreAndPrivateContactNeverLeak() {
        run(c -> {
            var f=fixture(c);var service=c.getBean(ReservationService.class);var query=new ListReservationsQuery(null,null,FROM,TO,null);
            assertThat(service.listReservations("E","B",query).records()).extracting(ReservationVO::reservationNo).containsExactly("RB");
            assertThat(service.listReservations("OTHER","A",query).records()).extracting(ReservationVO::reservationNo).containsExactly("RX");
            assertThat(c.getBean(ObjectMapper.class).writeValueAsString(service.listReservations("E","A",query)))
                    .doesNotContain("secret-contact").doesNotContain("real-buyer");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_reservation",Integer.class)).isEqualTo(6);
        });
    }
    @Test void rejectsInvalidRangeUnknownStatusAndUnboundedPage() {
        run(c -> {
            fixture(c);var service=c.getBean(ReservationService.class);
            for(var query:List.of(new ListReservationsQuery(0,20,FROM,TO,null),new ListReservationsQuery(1,101,FROM,TO,null),
                    new ListReservationsQuery(1,20,TO,FROM,null),new ListReservationsQuery(1,20,null,TO,null),
                    new ListReservationsQuery(1,20,FROM,TO,"PAID"),new ListReservationsQuery(1,20,FROM,Instant.MAX,null)))
                DiningSessionTest.code(() -> service.listReservations("E","A",query),ErrorCode.BAD_REQUEST);
        });
    }
    @Configuration(proxyBeanMethods=false) static class Reservations {
        @Bean
        MapperFactoryBean<ReservationMapper> reservations(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var mapper=new MapperFactoryBean<>(ReservationMapper.class);
            mapper.setSqlSessionTemplate(t);return mapper;
        }
        @Bean
        ReservationService service(ReservationMapper mapper) {
            return new ReservationServiceImpl(mapper);
        }
    }
}
