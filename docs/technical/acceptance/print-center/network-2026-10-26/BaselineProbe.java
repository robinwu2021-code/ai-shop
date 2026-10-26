import org.flywaydb.core.Flyway;
public class BaselineProbe {
 public static void main(String[] args) {
 var f=Flyway.configure().dataSource("jdbc:mariadb://127.0.0.1:33556/print_accept_baseline","root","").locations("filesystem:backend/shop-app/src/main/resources/db/migration").placeholderReplacement(false).target("587").cleanDisabled(true).load();
 f.migrate(); f.validate(); System.out.println("BASELINE_VERSION="+f.info().current().getVersion());
 }
}
