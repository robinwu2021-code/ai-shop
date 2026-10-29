package ai.neargo.shop.scenario;

import ai.neargo.shop.ShopApplication;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real child HOST; only external TEST payment status is controllable and credentials stay in owned
 * 0600 files.
 */
public final class BeautyBm04HostFixture {
    private BeautyBm04HostFixture() {}

    /**
     * The runner supplies a separately resolved classpath and owns the lifetime of this process.
     */
    public static void main(String[] arguments) throws Exception {
        String mode = required("BEAUTY_BM04_SHAPE");
        if (!List.of("embedded", "standalone").contains(mode)) {
            throw new IllegalArgumentException("invalid shape");
        }
        if (mode.equals("standalone")) {
            absent("ai.neargo.shop.beauty.config.BeautyAutoConfiguration");
            absent("ai.neargo.shop.beauty.mapper.BeautyMappers");
            for (String entry :
                    System.getProperty("java.class.path")
                            .split(System.getProperty("path.separator"))) {
                if (entry.contains("beauty-core") || entry.contains("beauty-server")) {
                    throw new IllegalStateException("HOST contains private beauty implementation");
                }
            }
        }
        Path directory = Path.of(required("BEAUTY_BM04_FIXTURE_DIR"));
        Files.createDirectories(directory);
        try (var context =
                new SpringApplicationBuilder(ShopApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .profiles("api")
                        .run(arguments)) {
            var checkout = context.getBean(CheckoutPort.class);
            if (!checkout.getClass().getName().contains("CheckoutPortImpl")) {
                throw new IllegalStateException("HOST requires its real checkout owner");
            }
            var jdbc = context.getBean(JdbcTemplate.class);
            var tokens = context.getBean(TokenStores.class);
            var json = context.getBean(ObjectMapper.class);
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String entity = "E-" + suffix, store = "S-" + suffix, actor = "A-" + suffix;
            String buyer = "C-" + suffix, otherBuyer = "C2-" + suffix, ownerUser = "U-" + suffix;
            String manager = "M-" + suffix, staff = "F-" + suffix, staffAccount = "MF-" + suffix;
            String goods = "G-" + suffix, sku = "K-" + suffix, payMerchant = "PM-" + suffix;
            for (String user : List.of(actor, buyer, otherBuyer, ownerUser, staff)) {
                jdbc.update(
                        "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) "
                                + "VALUES (?,'BM04 fixture','NORMAL',NOW(),NOW())",
                        user);
            }
            jdbc.update(
                    "INSERT INTO mch_entity(entity_no,name,status,funds_mode,created_at,updated_at)"
                        + " VALUES (?,'BM04 fixture','ACTIVE','DIRECT',NOW(),NOW())",
                    entity);
            jdbc.update(
                    "INSERT INTO"
                        + " mch_store(entity_no,store_no,name,status,is_default,pay_merchant_no,created_at,updated_at)"
                        + " VALUES (?,?,'BM04 fixture','ACTIVE',1,?,NOW(),NOW())",
                    entity,
                    store,
                    payMerchant);
            for (var account :
                    List.of(
                            new String[] {actor, ownerUser, "1"},
                            new String[] {manager, actor, "1"},
                            new String[] {staffAccount, staff, "0"})) {
                jdbc.update(
                        "INSERT INTO"
                            + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                            + " VALUES (?,?,?,?,'ACTIVE',NOW(),NOW())",
                        entity,
                        account[0],
                        account[1],
                        Integer.valueOf(account[2]));
            }
            jdbc.update(
                    "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                        + " VALUES (?,'BM04_TEST','BM04"
                        + " staff','[\"biz:booking:manage\",\"biz:customer\",\"biz:order:view\"]',NOW(),NOW())",
                    entity);
            jdbc.update(
                    "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                        + " VALUES (?,?,'BM04_TEST',NOW(),NOW())",
                    staffAccount,
                    store);
            jdbc.update(
                    "INSERT INTO"
                        + " mch_payment_merchant(entity_no,store_no,pay_merchant_no,pay_channel,"
                            + "apply_status,sub_mchid,created_at,updated_at)"
                        + " VALUES (?,?,?,'TEST','ACTIVE','test-owner',NOW(),NOW())",
                    entity,
                    store,
                    payMerchant);
            jdbc.update(
                    "INSERT INTO"
                        + " prd_goods(goods_no,entity_no,store_no,title,type,on_sale,"
                            + "audit_status,fulfillments,duration_min,created_at,updated_at)"
                        + " VALUES (?,?,?,'BM04 service','SERVICE',1,'APPROVED',"
                        + "'[\"STORE_VERIFY\"]',30,NOW(),NOW())",
                    goods,
                    entity,
                    store);
            jdbc.update(
                    "INSERT INTO"
                        + " prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,created_at,updated_at)"
                        + " VALUES (?,?,?,'CN',101,1000,0,NOW(),NOW())",
                    sku,
                    goods,
                    entity);
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", "MCH", actor),
                    () -> {
                        context.getBean(StoreSalesCatalogService.class)
                                .publish(
                                        entity,
                                        store,
                                        actor,
                                        new StoreSalesCatalogPort.PublishReq(
                                                0L,
                                                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                                                "UTC",
                                                List.of(),
                                                List.of(
                                                        new StoreSalesCatalogPort.Entry(
                                                                sku,
                                                                null,
                                                                0,
                                                                true,
                                                                StoreSalesCatalogPort.SalesMode
                                                                        .UNRESTRICTED,
                                                                List.of()))),
                                        "catalog-" + suffix);
                        return null;
                    });
            Map<String, String> issued = new LinkedHashMap<>();
            issued.put("merchant", issue(tokens, LoginUser.merchant(actor, "BM04 merchant")));
            issued.put("manager", issue(tokens, LoginUser.merchantByUser(actor, "BM04 manager")));
            issued.put("staff", issue(tokens, LoginUser.merchantByUser(staff, "BM04 staff")));
            issued.put("consumer", issue(tokens, LoginUser.consumer(buyer, "BM04 consumer")));
            issued.put(
                    "otherConsumer",
                    issue(tokens, LoginUser.consumer(otherBuyer, "BM04 other consumer")));
            var info = new LinkedHashMap<String, Object>();
            info.put(
                    "url",
                    "http://127.0.0.1:"
                            + ((WebServerApplicationContext) context).getWebServer().getPort());
            info.put("shape", mode);
            info.put("tokens", issued);
            info.put("entity", entity);
            info.put("store", store);
            info.put("actor", actor);
            info.put("buyer", buyer);
            info.put("otherBuyer", otherBuyer);
            info.put("managerAccount", manager);
            info.put("staffAccount", staffAccount);
            info.put("goods", goods);
            info.put("sku", sku);
            info.put("checkoutBean", checkout.getClass().getName());
            info.put("classpath", System.getProperty("java.class.path"));
            writePrivate(directory.resolve("host.json"), json.writeValueAsString(info));
            // Test-only local IPC avoids exposing credential issuance/revocation or gateway
            // controls over HTTP.
            while (context.isActive()) {
                Path command = directory.resolve("host-command.json");
                if (Files.exists(command)) {
                    var request = json.readTree(Files.readString(command));
                    Files.delete(command);
                    boolean completed =
                            switch (request.path("action").asString()) {
                                case "REVOKE" -> {
                                    String label = request.path("argument").asString();
                                    if (!issued.containsKey(label)) {
                                        throw new IllegalArgumentException(
                                                "unknown fixture identity");
                                    }
                                    Realm realm =
                                            label.contains("Consumer") || label.equals("consumer")
                                                    ? Realm.CONSUMER
                                                    : Realm.MERCHANT;
                                    tokens.of(realm).revoke(issued.get(label));
                                    yield true;
                                }
                                case "PAY_PAID" ->
                                        context.getBean(TestPayGateway.class)
                                                .markPaid(request.path("argument").asString());
                                default ->
                                        throw new IllegalArgumentException(
                                                "unknown fixture command");
                            };
                    writePrivate(
                            directory.resolve("host-command-result.json"),
                            json.writeValueAsString(
                                    Map.of(
                                            "id",
                                            request.path("id").asString(),
                                            "completed",
                                            completed)));
                }
                Thread.sleep(50);
            }
        }
    }

    private static String issue(TokenStores tokens, LoginUser user) {
        return tokens.of(user.realm()).issue(new TokenStore.SessionData(user));
    }

    private static void absent(String name) throws ClassNotFoundException {
        try {
            Class.forName(name);
        } catch (ClassNotFoundException absent) {
            return;
        }
        throw new IllegalStateException("unexpected HOST class " + name);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing fixture environment " + name);
        }
        return value;
    }

    private static void writePrivate(Path target, String content) throws Exception {
        Path temporary =
                Files.createTempFile(
                        target.getParent(),
                        "bm04-",
                        ".json",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temporary, content);
        Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }
}
