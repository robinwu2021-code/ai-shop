# MenuMigration（V657 发布用 Flyway 执行器，留档）

2026-11-11 在 166 上编译执行过一次（编译产物 SHA256 见 `evidence/deployment-20261111.json` 的 `MenuMigration.class`）。
原为本目录下的 `MenuMigration.java`；它不属于任何 Maven 模块，`OrphanModuleTest` 不允许 Maven 之外存在 Java 源码，
因此原文逐字转存于此，仅作发布记录，不参与构建。

```java
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
```
