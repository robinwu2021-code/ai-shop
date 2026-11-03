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
  var command=new java.util.ArrayList<String>(); command.add(Path.of(System.getProperty("java.home"),"bin/jdeps").toString());
  command.addAll(java.util.List.of("-verbose:class","-filter:none")); command.addAll(inputs);
  var process=new ProcessBuilder(command).redirectErrorStream(true).start();
  var output=new String(process.getInputStream().readAllBytes()); assertEquals(0,process.waitFor(),output);
  for(var line:output.lines().filter(s->s.contains(" -> ")).toList()) {
   var target=line.trim().split("\\s+->\\s+")[1].split("\\s+")[0];
   if(!line.startsWith(" ")) continue;
   assertTrue(target.startsWith("java.")||target.startsWith("ai.neargo.commerce.api.")||target.startsWith("ai.neargo.commerce.core."),line);
  }
 }
 @Test void runtimeDependencyGraphHasOnlyApi() throws Exception {
  var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
  var doc=factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
  var dependencies=doc.getElementsByTagName("dependency");
  var runtime=new java.util.ArrayList<String>();
  for(int i=0;i<dependencies.getLength();i++) {
   var e=(org.w3c.dom.Element)dependencies.item(i);
   var scopes=e.getElementsByTagName("scope"); if(scopes.getLength()>0 && scopes.item(0).getTextContent().equals("test"))continue;
   runtime.add(e.getElementsByTagName("groupId").item(0).getTextContent()+":"+e.getElementsByTagName("artifactId").item(0).getTextContent());
  }
  assertEquals(java.util.List.of("ai.neargo.commerce:commerce-api"),runtime);
 }
}
