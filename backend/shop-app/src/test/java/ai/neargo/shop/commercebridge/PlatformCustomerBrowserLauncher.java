package ai.neargo.shop.commercebridge;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.*;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.impl.*;
import ai.neargo.shop.platform.mapper.PlatformMappers.*;
import ai.neargo.shop.platform.perm.*;
import ai.neargo.shop.platform.perm.mapper.PermMappers.*;
import ai.neargo.shop.portal.ops.OpsCustomerController;
import ai.neargo.shop.portal.ops.OpsOperatingEntityController;
import ai.neargo.shop.portal.service.*;
import ai.neargo.shop.portal.service.impl.CustomerReportingServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.catalina.startup.Tomcat;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.mock;

/** Test-classpath ONLY: actual credentials, live permissions and customer SQL on disposable native MySQL9. */
public final class PlatformCustomerBrowserLauncher {
  private PlatformCustomerBrowserLauncher() {}
  @Configuration @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity @EnableTransactionManagement
  static class Web {
    @Bean SecurityFilterChain chain(HttpSecurity http,TokenStore tokens,ObjectProvider<LiveIdentityResolver> identities)throws Exception {
      return http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(r->r.requestMatchers("/ops/auth/login").permitAll().anyRequest().authenticated())
        .addFilterBefore(new OperatorTokenAuthFilter(tokens,identities),UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(e->e.authenticationEntryPoint(new ApiAuthEntryPoint())).build();
    }
  }
  @RestController static class Authentication {
    final OpsService ops; final JdbcTemplate jdbc;
    Authentication(OpsService ops,JdbcTemplate jdbc){this.ops=ops;this.jdbc=jdbc;}
    record Login(String username,String password) {}
    @PostMapping("/ops/auth/login") Object login(@RequestBody Login c){return ops.login(c.username(),c.password());}
    @GetMapping("/ops/auth/me") Object me(){return ops.me();}
    @GetMapping("/ops/menu") Object menu(){
      var user=SecurityUtils.requireUser();
      var result=new ArrayList<Map<String,Object>>();
      for(var function:jdbc.queryForList("SELECT function_code functionCode,name,href,icon,sort FROM sys_function WHERE end_code='OPS' AND deleted=0 ORDER BY sort")) {
        var rows=jdbc.queryForList("SELECT point_code pointCode,name,href,group_name groupName,point_type pointType,backend_status backendStatus,ui_ready uiReady,perm_code permCode,sort FROM sys_function_point WHERE function_code=? AND deleted=0 ORDER BY sort",function.get("functionCode"));
        rows.removeIf(p->p.get("href")==null || !ai.neargo.common.security.rbac.Permissions.matches(user.grantedPermissions(),(String)p.get("permCode")));
        function.put("points",rows);result.add(function);
      }
      return result;
    }
  }
  static void table(JdbcTemplate jdbc,String source,String name) {
    var matcher=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS "+name+"\\s*\\(.*?\\n\\);").matcher(source);
    if(!matcher.find())throw new IllegalStateException("Missing native permission table "+name);
    jdbc.execute(matcher.group());
  }
  public static void main(String[] args)throws Exception {
    if(!Boolean.getBoolean("platform.customer.browser.local"))throw new IllegalStateException("Explicit local acceptance opt-in required");
    String url=System.getenv("COMMERCE_MYSQL_URL");
    if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*"))throw new IllegalArgumentException("Only local native3308 accepted");
    var adminDs=new DriverManagerDataSource(url,System.getenv("COMMERCE_MYSQL_USER"),System.getenv("COMMERCE_MYSQL_PASSWORD"));
    var admin=new JdbcTemplate(adminDs);
    if(!admin.queryForObject("SELECT VERSION()",String.class).startsWith("9."))throw new IllegalStateException("MySQL9 required");
    String schema="customer_browser_"+UUID.randomUUID().toString().replace("-","");
    admin.execute("CREATE DATABASE "+schema);
    var ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),System.getenv("COMMERCE_MYSQL_USER"),System.getenv("COMMERCE_MYSQL_PASSWORD"));
    var jdbc=new JdbcTemplate(ds); var web=new AnnotationConfigWebApplicationContext(); var tomcat=new Tomcat();
    var cleaned=new java.util.concurrent.atomic.AtomicBoolean();
    Runnable cleanup=()->{if(!cleaned.compareAndSet(false,true))return;try{tomcat.stop();tomcat.destroy();}catch(Exception ignored){}web.close();admin.execute("DROP DATABASE "+schema);};
    Runtime.getRuntime().addShutdownHook(new Thread(cleanup));
    try {
      CustomerReportingFixture.createAndSeed(jdbc);
      String ddl=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
      for(var name:List.of("sys_ops_staff","sys_role","sys_role_point","sys_function_point","sys_function","sys_role_member","sys_audit_log"))table(jdbc,ddl,name);
      new ResourceDatabasePopulator(new FileSystemResource(System.getenv("PLATFORM_MENU_SEED_FILE"))).execute(ds);
      var hash=new PasswordHasher();String password="local-browser-only";
      String[][] identities={{"admin","SUPER_ADMIN"},{"bd","BD"},{"goods","GOODS_OPS"},{"support","SUPPORT"},{"campaign","CAMPAIGN_OPS"},{"community","COMMUNITY_OPS"},{"auditor","AUDITOR"},{"finance","FINANCE"},{"risk","RISK"},{"analyst","ANALYST"},{"tech","TECH_OPS"},{"elec","ELEC_ADMIN"}};
      for(var i:identities)jdbc.update("INSERT INTO sys_ops_staff(staff_no,username,password,real_name,roles,created_at,updated_at) VALUES(?,?,?,?,?,NOW(),NOW())","LOCAL_"+i[0],i[0],hash.encode(password),"Local "+i[0],"[\""+i[1]+"\"]");
      var cfg=new MybatisConfiguration();var factory=new MybatisSqlSessionFactoryBean();factory.setConfiguration(cfg);factory.setDataSource(ds);
      var plugins=new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();plugins.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor());factory.setPlugins(plugins);
      factory.setGlobalConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig().setMetaObjectHandler(new AuditMetaObjectHandler()));
      var sql=new SqlSessionTemplate(factory.getObject());
      for(var mapper:List.of(StaffMapper.class,RolePointMapper.class,FunctionPointMapper.class,RoleMapper.class,RoleMemberMapper.class))cfg.addMapper(mapper);
      var perms=new RolePermResolver(sql.getMapper(RolePointMapper.class),sql.getMapper(FunctionPointMapper.class),sql.getMapper(RoleMapper.class),0);
      var identitiesResolver=new StaffIdentityResolver(sql.getMapper(StaffMapper.class),perms,0);
      var memory=new MemoryTokenStore(Duration.ofHours(2));
      var tokens=new RealmRoutingTokenStore(Map.of(Realm.OPERATOR,memory,Realm.CONSUMER,new MemoryTokenStore(Duration.ofHours(1)),Realm.MERCHANT,new MemoryTokenStore(Duration.ofHours(1))));
      var json=tools.jackson.databind.json.JsonMapper.builder().build();
      var ops=new OpsServiceImpl(sql.getMapper(StaffMapper.class),sql.getMapper(RoleMemberMapper.class),perms,identitiesResolver,
          null,null,null,tokens,TokenStores.single(tokens),json,json,null,null,hash,null,null,null,null,"mail",null,"",new OpsLoginGuard(),new OpsLoginChallenges());
      web.getEnvironment().setActiveProfiles("ops");
      web.addBeanFactoryPostProcessor(b->{
        b.registerSingleton("dataSource",ds);b.registerSingleton("jdbc",jdbc);b.registerSingleton("transactionManager",new DataSourceTransactionManager(ds));
        b.registerSingleton("tokens",tokens);b.registerSingleton("livePerms",perms);b.registerSingleton("liveIdentity",identitiesResolver);b.registerSingleton("ops",ops);
      });
      web.register(Web.class,Authentication.class,OpsCustomerController.class,OpsOperatingEntityController.class,CustomerReadAccess.class,CustomerReportingServiceImpl.class,
        PermChecker.class,ApiResponseWrapper.class,GlobalExceptionHandler.class);
      Path scratch=Files.createTempDirectory("customer-browser-http-");
      tomcat.setBaseDir(scratch.toString());tomcat.setPort(34111);tomcat.getConnector().setProperty("address","127.0.0.1");
      var container=tomcat.addContext("",scratch.toString());container.setParentClassLoader(PlatformCustomerBrowserLauncher.class.getClassLoader());
      container.addServletContainerInitializer((classes,servlet)->{
        web.setServletContext(servlet);
        servlet.addFilter("security",new DelegatingFilterProxy("springSecurityFilterChain",web)).addMappingForUrlPatterns(EnumSet.of(jakarta.servlet.DispatcherType.REQUEST),false,"/*");
        var dispatcher=servlet.addServlet("dispatcher",new DispatcherServlet(web));dispatcher.addMapping("/");dispatcher.setLoadOnStartup(1);
      },Set.of());
      tomcat.start();
      System.out.println("CUSTOMER_BROWSER_NATIVE_READY");
      new CountDownLatch(1).await();
    } finally {cleanup.run();}
  }
}
