package ai.neargo.commerce.api;
import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PortableApiContractTest {
 @Test void differentHostModelsCanImplementPortsUsingOnlyPortableContracts() throws Exception {
  var compiler=ToolProvider.getSystemJavaCompiler();
  var root=Files.createTempDirectory("commerce-hosts");
  var source=root.resolve("Hosts.java");
  Files.writeString(source, """
   import ai.neargo.commerce.api.common.*;
   import ai.neargo.commerce.api.port.*;
   import java.util.*;
   class Hosts {
    record LegacyStore(String number) {}
    record OtherShop(long key) {}
    static StoreDirectoryPort legacy = ref -> Optional.of(new StoreSnapshot(ref,"merchant-a","RETAIL",StoreStatus.ACTIVE,CommerceModel.STORE_V1));
    static StoreDirectoryPort other = ref -> Optional.of(new StoreSnapshot(ref,"merchant-b","RETAIL",StoreStatus.ACTIVE,CommerceModel.STORE_V1));
    static ActorAuthorizationPort authA = (actor,store,capability) -> new AuthorizationDecision(true,"owner",Optional.empty());
    static ActorAuthorizationPort authB = (actor,store,capability) -> new AuthorizationDecision(false,"guest",Optional.of("NOT_MEMBER"));
    static boolean verify() {
     var ref=new StoreRef("tenant-a","store-a");
     var actor=new ActorContext("tenant-a",SubjectKind.ACCOUNT,"account-a","fingerprint");
     return legacy.find(ref).orElseThrow().store().equals(ref) && other.find(ref).isPresent()
      && authA.authorize(actor,ref,"commerce.purchase").allowed() && !authB.authorize(actor,ref,"commerce.purchase").allowed();
    }
   }
   """);
  var diagnostics=new java.io.ByteArrayOutputStream();
  assertEquals(0,compiler.run(null,null,diagnostics,"-classpath",System.getProperty("java.class.path"),"-d",root.toString(),source.toString()),diagnostics.toString());
  try(var loader=new java.net.URLClassLoader(new java.net.URL[]{root.toUri().toURL()},getClass().getClassLoader())) {
   var method=loader.loadClass("Hosts").getDeclaredMethod("verify"); method.setAccessible(true); assertEquals(true,method.invoke(null));
  }
 }
 @Test void sixFacadesArePublicTypedInterfaces() {
  for(var name: java.util.List.of("catalog.CatalogFacade","mall.MallFacade","fulfillment.FulfillmentFacade","subscription.SubscriptionFacade","access.EndAccessFacade","reminder.ReminderFacade")) {
   var type=assertDoesNotThrow(()->Class.forName("ai.neargo.commerce.api."+name));
   assertTrue(type.isInterface()); assertTrue(type.getMethods().length>0);
   for(var m:type.getMethods()) {assertNotEquals(Object.class,m.getReturnType()); for(var p:m.getParameterTypes()) assertNotEquals(Object.class,p);}
  }
 }
}
