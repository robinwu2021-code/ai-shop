package ai.neargo.commerce.architecture;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class CoreDependencyTest {
 @Test void productionBytecodeReferencesOnlyJdkAndCommerceApiOrCore() throws Exception {
  var root=Path.of("..").toAbsolutePath().normalize();
  var api=root.resolve("commerce-api/target/classes");
  assertTrue(Files.isDirectory(api),"API production bytecode must exist");
  var inputs=new java.util.ArrayList<String>(); inputs.add(api.toString());
  var core=root.resolve("commerce-core/target/classes"); if(Files.isDirectory(core)) inputs.add(core.toString());
  int references=0;
  for(var input:inputs) {
   var command=java.util.List.of(Path.of(System.getProperty("java.home"),"bin/jdeps").toString(),"-verbose:class","-filter:none","--module-path",api.toString(),input);
   var process=new ProcessBuilder(command).redirectErrorStream(true).start();
   var output=new String(process.getInputStream().readAllBytes()); assertEquals(0,process.waitFor(),output);
   for(var line:output.lines().filter(s->s.startsWith(" ") && s.contains(" -> ")).toList()) {
    references++;
    var target=line.trim().split("\\s+->\\s+")[1].split("\\s+")[0];
    assertTrue(target.startsWith("java.")||target.startsWith("ai.neargo.commerce.api.")||(input.contains("commerce-core") && target.startsWith("ai.neargo.commerce.core.")),line);
   }
  }
  assertTrue(references>0,"jdeps must inspect real production class references");
  try(var files=Files.walk(api)) { assertTrue(files.filter(p->p.toString().endsWith(".class") && !p.getFileName().toString().equals("module-info.class")).count()>0,"API may not be empty"); }

 }
 @Test void runtimeDependencyGraphHasOnlyApi() throws Exception {
  var classpath=Files.readString(Path.of("target/runtime-classpath.txt")).trim();
  var entries=classpath.split(java.io.File.pathSeparator);
  assertEquals(1,entries.length,classpath);
  assertTrue(entries[0].endsWith("commerce-api/target/classes") || entries[0].endsWith("commerce-api-0.1.0-SNAPSHOT.jar"),classpath);
  assertTrue(Files.readString(Path.of("../commerce-api/target/runtime-classpath.txt")).isBlank());
 }
}
