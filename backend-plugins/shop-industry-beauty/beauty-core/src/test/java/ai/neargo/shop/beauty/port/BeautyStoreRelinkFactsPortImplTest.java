package ai.neargo.shop.beauty.port;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BeautyStoreRelinkFactsPortImplTest {
    @Test void scopedHistoryIncludesArchivedOriginalsAndUnknownTablesFailClosed() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:industry_relink_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        var tables=List.of("bty_appointment","bty_appointment_operation","bty_appointment_preparation","bty_work_order","bty_reception_operation");
        for(String table:tables) jdbc.execute("CREATE TABLE "+table+"(tenant_no VARCHAR(32),entity_no VARCHAR(64),store_no VARCHAR(64),status VARCHAR(24),deleted INT)");
        var owner=new BeautyStoreRelinkFactsPortImpl(jdbc);
        assertFalse(owner.inspect("MAIN","M","S").complete(), "Standalone evidence cannot promise a host write fence");
        owner.setOwnershipWrites(org.mockito.Mockito.mock(ai.neargo.shop.spi.user.StoreOwnershipWritePort.class));
        assertTrue(owner.inspect("MAIN","M","S").complete());
        assertTrue(owner.businessTypes().isEmpty(), "No invented mapping from caller/sourceOwner to business type");
        assertFalse(owner.inspect("MAIN","M","S").hasHistory());
        for(String table:tables) {
            jdbc.update("INSERT INTO "+table+" VALUES('OTHER','M','S','CANCELLED',1)");
            jdbc.update("INSERT INTO "+table+" VALUES('MAIN','M','SIBLING','CANCELLED',1)");
            assertFalse(owner.inspect("MAIN","M","S").hasHistory());
            jdbc.update("INSERT INTO "+table+" VALUES('MAIN','OLD_ENTITY','S','CANCELLED',1)");
            assertTrue(owner.inspect("MAIN","M","S").hasHistory());
            jdbc.update("DELETE FROM "+table);
            jdbc.update("INSERT INTO "+table+" VALUES('MAIN','M',NULL,'CANCELLED',1)");
            assertTrue(owner.inspect("MAIN","M","S").hasHistory());
            jdbc.update("DELETE FROM "+table);
        }
        jdbc.execute("DROP TABLE "+tables.getFirst());
        assertThrows(org.springframework.dao.DataAccessException.class,()->owner.inspect("MAIN","M","S"));
    }
}
