package ai.neargo.commerce.persistence;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;
class CommerceSchemaMysqlTest {
 @Test void ownsThirtyTwoTablesAndIndependentHistory() {
  String db="commerce_test_"+java.util.UUID.randomUUID().toString().replace("-","");
  var admin=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:23317/?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","commerce_test","commerce_local_test");
  var a=new JdbcTemplate(admin); a.execute("CREATE DATABASE "+db);
  try {var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:23317/"+db+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true","commerce_test","commerce_local_test");
   Flyway.configure().dataSource(ds).table("commerce_schema_history").locations("classpath:db/commerce/migration").load().migrate();
   assertEquals(33,new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?",Integer.class,db));
  } finally {a.execute("DROP DATABASE "+db);}
 }
}
