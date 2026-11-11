import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
public class MenuMigration {
 public static void main(String[] args) throws Exception {
  var p=new Properties();try(var in=Files.newInputStream(Path.of(args[0]))){p.load(in);}
  var f=Flyway.configure().dataSource(p.getProperty("url"),p.getProperty("user"),p.getProperty("password"))
   .locations("filesystem:"+args[1]).table("flyway_schema_history").ignoreMigrationPatterns("*:missing","*:future","*:pending").target("657").load();
  var v=f.validateWithResult();if(!v.validationSuccessful){for(var e:v.invalidMigrations)System.out.println("invalid="+e.version+":"+e.errorDetails.errorCode);throw new IllegalStateException("Migration validation failed");}
  var r=f.migrate();System.out.println("executed="+r.migrationsExecuted+" target="+r.targetSchemaVersion);
 }
}
