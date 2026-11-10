package ai.neargo.shop.commercebridge;

import ai.neargo.auth.store.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.DbTokenStore;
import ai.neargo.shop.config.*;
import ai.neargo.shop.payclient.PayBizExceptionHandler;
import ai.neargo.shop.platform.auth.OperatorIdentityLoader;
import ai.neargo.shop.platform.mapper.PlatformMappers;
import ai.neargo.shop.platform.perm.*;
import ai.neargo.shop.platform.perm.mapper.PermMappers;
import ai.neargo.shop.portal.biz.*;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.catalina.startup.Tomcat;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import tools.jackson.databind.ObjectMapper;

/** N2 loopback fixture. BIZ uses real password login; OPS is an explicit controlled persisted session. */
public final class ChannelAccountClaimHostLocalLauncher {
    private static final String OPERATOR="n2-local-operator", ROLE="N2_LOCAL_OPS";
    private ChannelAccountClaimHostLocalLauncher() {}

    /** Projects only the operator authenticated by the production OPS chain and DB identity loader. */
    @TestComponent @RestController
    public static class LocalOperatorIdentity {
        @GetMapping("/ops/auth/me") @PreAuthorize("isAuthenticated()")
        public Map<String,Object> me() {
            var user=SecurityUtils.requireUser();
            if(user.realm()!=Realm.OPERATOR || !ai.neargo.common.data.scope.DataScopeSpec.ALL.equals(user.dataScope()))throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.FORBIDDEN);
            var result=new LinkedHashMap<String,Object>();
            result.put("staffNo",user.userNo());result.put("username",user.userNo()+"@local.test");
            result.put("realName",user.nickname());result.put("status","ACTIVE");
            result.put("roles",user.roles());result.put("perms",user.perms());
            result.put("merchantNo",null);result.put("communityNo",null);result.put("pickupNo",null);
            return result;
        }
    }
    private static final class OperatorSession implements AutoCloseable {
        final AuthCache<String,DbTokenStore.CachedSession> sessions;
        final AuthCache<String,LoginUser> identities;
        final LoginLogWriter audit;
        final DbTokenStore store;
        final OperatorIdentityLoader loader;
        final RolePermResolver permissions;
        final StaffIdentityResolver live;
        OperatorSession(BizLoginStoreNativeMysqlTest.LoginFixture f,boolean fresh) throws Exception {
            if(fresh) {
                String ddl=new ClassPathResource("schema-test.sql").getContentAsString(StandardCharsets.UTF_8);
                var create=Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS ops_login_log\\s*\\(.*?\n\\);").matcher(ddl);
                if(!create.find())throw new IllegalStateException("Missing OPS audit schema");
                f.db.jdbc.execute(create.group());seedOperator(f);
            }
            f.db.jdbc.queryForObject("SELECT COUNT(*) FROM ops_login_log",Long.class);
            permissions=new RolePermResolver(f.db.mybatis.getMapper(PermMappers.RolePointMapper.class),
                    f.db.mybatis.getMapper(PermMappers.FunctionPointMapper.class),f.db.mybatis.getMapper(PermMappers.RoleMapper.class),0);
            var staff=f.db.mybatis.getMapper(PlatformMappers.StaffMapper.class);
            loader=new OperatorIdentityLoader(staff,permissions);live=new StaffIdentityResolver(staff,permissions,0);
            var profile=SessionProfiles.OPERATOR;var jdbc=JdbcClient.create(f.db.ds);
            sessions=new AuthCache<>(f.db.schema+".n2.ops.session",String.class,DbTokenStore.CachedSession.class,profile.cacheTtl(),100);
            identities=new AuthCache<>(f.db.schema+".n2.ops.identity",String.class,LoginUser.class,profile.identityTtl(),100);
            audit=new LoginLogWriter(new LoginLogDao(jdbc,profile),profile);
            store=new DbTokenStore(Realm.OPERATOR,profile,new SessionDao(jdbc,profile),loader,sessions,identities,audit,f.db.clock);
        }
        String token(String previous) {
            if(previous!=null&&!previous.isBlank()) {
                var session=store.get(previous).orElseThrow(()->new IllegalStateException("Original controlled OPS session is no longer valid"));
                if(session.user().realm()!=Realm.OPERATOR||!OPERATOR.equals(session.user().userNo()))throw new IllegalStateException("Wrong controlled OPS session");
                return previous;
            }
            return store.issue(TokenStore.SessionData.of(loader.loadFresh(OPERATOR).orElseThrow()));
        }
        @Override public void close(){audit.close();sessions.close();identities.close();}
    }
    private static void seedOperator(BizLoginStoreNativeMysqlTest.LoginFixture f) {
        f.db.jdbc.update("INSERT INTO sys_ops_staff(staff_no,username,password,real_name,roles,status,created_at,updated_at) VALUES(?,?,?,?,?,'ACTIVE',NOW(),NOW())",
                OPERATOR,OPERATOR+"@local.test",UUID.randomUUID().toString(),"Local controlled operator","[\""+ROLE+"\"]");
        f.db.jdbc.update("INSERT INTO sys_role(role_code,name,end_code,wildcard,created_at,updated_at) VALUES(?,'Local channel claims','OPS',0,NOW(),NOW())",ROLE);
        for(String permission:List.of("pay:channel-account:view","pay:channel-account:manage","pay:channel-account:approve")) {
            String point="N2_"+permission.substring(permission.lastIndexOf(':')+1).toUpperCase(Locale.ROOT);
            f.db.jdbc.update("INSERT INTO sys_function_point(point_code,function_code,name,perm_code,created_at,updated_at) VALUES(?,'N2',?,?,NOW(),NOW())",point,permission,permission);
            f.db.jdbc.update("INSERT INTO sys_role_point(role_code,point_code,end_code,created_at,updated_at) VALUES(?,?,'OPS',NOW(),NOW())",ROLE,point);
        }
    }
    public static void main(String[] args) throws Exception {
        if(!Boolean.getBoolean("channel.account.claim.host.local.acceptance"))throw new IllegalStateException("Explicit N2 host loopback opt-in required");
        if(!required("COMMERCE_MYSQL_URL").matches("jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*"))throw new IllegalArgumentException("Owned loopback MySQL 9 required");
        String origin=required("ALIGNMENT_PAY_ORIGIN");var uri=URI.create(origin);
        if(!"http".equals(uri.getScheme())||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1)throw new IllegalArgumentException("Loopback PAY origin required");
        String internal=required("ALIGNMENT_INTERNAL_TOKEN"),credential=required("ALIGNMENT_PAY_INTERNAL_CREDENTIAL");
        if(internal.length()<32||credential.length()<32)throw new IllegalArgumentException("Private fixture credentials must contain at least 32 characters");
        String existing=System.getenv("N2_HOST_SCHEMA");
        if(existing!=null&&!existing.matches("commerce_test_[0-9a-f]{32}"))throw new IllegalArgumentException("Only an owned host schema may resume");
        String password=existing==null?System.getenv().getOrDefault("N2_OWNER_PASSWORD",UUID.randomUUID().toString()):required("N2_OWNER_PASSWORD");
        var operator=new AtomicReference<OperatorSession>();
        var fixture=new BizLoginStoreNativeMysqlTest().new LoginFixture(existing,true,f->{
            StoreEntityClaimLocalLauncher.install(f,existing==null,origin,credential,internal,false);
            try {operator.set(new OperatorSession(f,existing==null));}catch(Exception failure){throw new IllegalStateException("Cannot initialize controlled OPS identity",failure);}
            var ops=operator.get();
            f.realServices.put(TokenStore.class,new RealmRoutingTokenStore(Map.of(Realm.MERCHANT,f.db.store,
                    Realm.CONSUMER,f.tokens.of(Realm.CONSUMER),Realm.OPERATOR,ops.store)));
            var settings=new LinkedHashMap<String,Object>();
            settings.put("shop.pay.channel-account-claims.enabled","true");settings.put("shop.pay.channel-account-claims.base-url",origin);
            settings.put("shop.pay.biz-onboarding.enabled","true");settings.put("shop.pay.biz-onboarding.base-url",origin);
            settings.put("shop.auth.token-store-by-realm.merchant","db");settings.put("shop.auth.token-store-by-realm.operator","db");
            f.web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("n2LocalOnly",settings));
            f.web.register(PayChannelAccountClaimConfiguration.class,PayBizOnboardingConfiguration.class,
                    BizChannelAccountClaimController.class,BizPaySessionController.class,BizPayOnboardingController.class,
                    BizPayServiceController.class,PayBizExceptionHandler.class,LocalOperatorIdentity.class);
            f.web.addBeanFactoryPostProcessor(beans->{
                beans.registerSingleton("authJdbcClient",JdbcClient.create(f.db.ds));
                beans.registerSingleton("n2LivePermissions",ops.permissions);beans.registerSingleton("n2LiveOperator",ops.live);
            });
        });
        var tomcat=new Tomcat();Path scratch=Files.createTempDirectory("n2-host-http-");
        Path ready=Path.of(required("N2_HOST_READY_FILE")).toAbsolutePath();var closed=new AtomicBoolean();
        Runnable close=()->{
            if(!closed.compareAndSet(false,true))return;
            try{tomcat.stop();tomcat.destroy();}catch(Exception ignored){System.err.println("N2 host shutdown incomplete");}
            finally {
                operator.get().close();fixture.close();
                try{Files.deleteIfExists(ready);try(var paths=Files.walk(scratch)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
                catch(Exception ignored){System.err.println("N2 host scratch cleanup incomplete");}
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(close,"n2-host-cleanup"));
        try {
            if(!fixture.db.jdbc.queryForObject("SELECT VERSION()",String.class).startsWith("9."))throw new IllegalStateException("MySQL 9 required");
            if(existing==null){fixture.ownerPhone("15965040911");fixture.auth.setPassword("U",password);StoreEntityClaimLocalLauncher.seed(fixture);}
            String opsToken=operator.get().token(System.getenv("N2_OPS_TOKEN"));
            tomcat.setBaseDir(scratch.toString());tomcat.setPort(Integer.parseInt(System.getenv().getOrDefault("N2_HOST_PORT","0")));
            tomcat.getConnector().setProperty("address","127.0.0.1");
            var container=tomcat.addContext("",scratch.toString());container.setParentClassLoader(ChannelAccountClaimHostLocalLauncher.class.getClassLoader());
            container.addServletContainerInitializer((classes,servlet)->{
                fixture.web.setServletContext(servlet);
                servlet.addFilter("springSecurityFilterChain",new DelegatingFilterProxy("springSecurityFilterChain",fixture.web))
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST),false,"/*");
                var dispatcher=servlet.addServlet("dispatcher",new DispatcherServlet(fixture.web));dispatcher.addMapping("/");dispatcher.setLoadOnStartup(1);
            },Set.of());tomcat.start();
            var state=new LinkedHashMap<String,Object>();state.put("hostOrigin","http://127.0.0.1:"+tomcat.getConnector().getLocalPort());
            state.put("schema",fixture.db.schema);state.put("principal","15965040911");state.put("password",password);state.put("ownerRef","U");
            state.put("sourceEntityNo","M");state.put("storeNo","S");state.put("sourceScope","MAIN");state.put("sourceCaller","AI_SHOP_UME");
            state.put("opsToken",opsToken);state.put("opsSessionKind","CONTROLLED_DB_SESSION_NOT_PASSWORD_LOGIN");state.put("databasePreserved",true);
            Path pending=Files.createTempFile(ready.getParent(),".n2-host-ready-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(pending,new ObjectMapper().writeValueAsString(state));Files.move(pending,ready,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            System.out.println("N2_HOST_LOCAL_READY");new CountDownLatch(1).await();
        } finally {close.run();}
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing local fixture setting: "+name);return value;
    }
}
