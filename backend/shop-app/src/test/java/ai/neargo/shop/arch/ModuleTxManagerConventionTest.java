package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.config.ModuleDataSources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 可选数据源的模块（ADR-066）里，每一处事务都必须点名本模块的事务管理器句柄。
 *
 * <p>和 {@link PayTxManagerConventionTest} 同一个道理：不点名用的是主库的事务管理器。
 * 模块没绑定数据源时句柄就是主库那一个，漏了也看不出区别；绑定之后，漏点名的方法
 * 写的是模块库、开的却是主库事务 —— 语句各自自动提交，出错不回滚，而且不报错。
 *
 * <p>扫两种写法：{@code @Transactional}，以及给 {@code TransactionTemplate} 注入管理器时的
 * {@code @Qualifier("transactionManager")}。事件表在主库，登记事件事务的那一处是有意的例外。
 */
class ModuleTxManagerConventionTest {

    private static final Path BACKEND = Paths.get(System.getProperty("user.dir")).getParent();

    /** 模块源码根 → 必须点名的常量。 */
    private static final Map<String, String> MODULE_SOURCES = Map.of(
            "shop-core/src/main/java/ai/neargo/shop/user", "ModuleDataSources.PLATFORM_TX_MANAGER",
            "shop-merchant/src/main/java/ai/neargo/shop/merchant", "ModuleDataSources.PLATFORM_TX_MANAGER",
            // 网关里 B 端登录与身份、换号注销、PIN 找回、门店换主体：读写的都是平台基础库的表
            "shop-gateway/src/main/java/ai/neargo/shop/merchantbridge", "ModuleDataSources.PLATFORM_TX_MANAGER",
            "invoice/invoice-domain/src/main/java/ai/neargo/shop/invoice", "ModuleDataSources.INVOICE_TX_MANAGER",
            "shop-core/src/main/java/ai/neargo/shop/printing", "ModuleDataSources.PRINT_TX_MANAGER");

    /** 发票留在主库的那部分（申请事件、邮件投递、通道申请）的入口：这个包里用主库事务是本意。 */
    private static final String PRIMARY_PACKAGE = "/invoice/legacy/";

    /**
     * 商户域（shop-merchant）里表不在平台基础库的类：它们留在主库句柄上，等各自的库对齐时再接。
     * 这个模块的代码按表分属好几个库，所以这里用名单而不是目录来划界；新加的类默认算平台基础库的。
     * 最后两个是端口：它们要在调用方的事务里加锁，换库那一步要单独处理。
     */
    private static final java.util.Set<String> MERCHANT_NOT_PLATFORM = java.util.Set.of(
            // 门店交易库：资源、排班、预约、门店分类
            "ResourceServiceImpl.java", "ResourceCommandServiceImpl.java", "ResourceBookingServiceImpl.java",
            "ResourceAdmissionServiceImpl.java", "ResourceSourceAuthorizationServiceImpl.java",
            "ResourceOccupationSchemaVerifier.java", "ScheduleServiceImpl.java", "AppointmentSlotServiceImpl.java",
            "StoreCategoryServiceImpl.java",
            // 支付商户库、账务结算库
            "PayoutAccountServiceImpl.java", "PayoutAccountApprovalSubject.java", "MerchantPaymentServiceImpl.java",
            "AdmissionServiceImpl.java", "DebtServiceImpl.java", "PayServiceApplicationStore.java",
            // 平台商城库、基础能力库
            "EntityPlanPurchaseServiceImpl.java", "PlanPaymentApprovalSubject.java", "AuthCodeAdminServiceImpl.java",
            "StoreIndustryAdminServiceImpl.java", "StoreOpeningApprovalSubject.java",
            "StoreDirectoryPortImpl.java", "StoreOwnershipWritePortImpl.java",
            // C 端账号包里：收货地址（门店交易库）、收藏（会员营销库）
            "AddressServiceImpl.java", "StoreFavoriteServiceImpl.java");

    /** 有意用主库事务管理器的地方：文件名 → 该行必须包含的片段。 */
    private static final Map<String, String> PRIMARY_ON_PURPOSE = Map.of(
            "PrintingConfiguration.java", "@Qualifier(\"transactionManager\") PlatformTransactionManager manager) {");

    @Test
    @DisplayName("发票、打印、C 端账号、商户域的事务必须点名本模块的事务管理器句柄")
    void module_transactions_name_their_own_manager() throws IOException {
        List<String> wrong = new ArrayList<>();
        int total = 0;
        for (var module : MODULE_SOURCES.entrySet()) {
            Path root = BACKEND.resolve(module.getKey());
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toString().contains(PRIMARY_PACKAGE))
                        .filter(p -> !((p.toString().contains("/shop/merchant/") || p.toString().contains("/shop/user/"))
                                && MERCHANT_NOT_PLATFORM.contains(p.getFileName().toString()))).toList()) {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        // 也认 `@Override @Transactional` 这种写在同一行的；注释里提到它不算
                        boolean declared = line.startsWith("@") && line.contains("@Transactional");
                        boolean injected = line.contains("@Qualifier(\"transactionManager\")");
                        if (!declared && !injected) {
                            continue;
                        }
                        total++;
                        String allowed = PRIMARY_ON_PURPOSE.get(file.getFileName().toString());
                        if (injected && allowed != null && line.contains(allowed)) {
                            continue;
                        }
                        if (injected || !line.contains(module.getValue())) {
                            wrong.add(BACKEND.relativize(file) + ":" + (i + 1) + "  " + line);
                        }
                    }
                }
            }
        }
        assertThat(total).as("一处事务都没扫到 —— 目录改名了？那这一检已经失效").isPositive();
        assertThat(wrong).as("这些事务没有点名本模块的事务管理器句柄（见 ModuleDataSources）：\n  %s",
                String.join("\n  ", wrong)).isEmpty();
    }

    @Test
    @DisplayName("句柄常量与命名规则一致")
    void handle_constants_follow_the_naming_rule() {
        assertThat(ModuleDataSources.INVOICE_TX_MANAGER).isEqualTo(ModuleDataSources.txManager(ModuleDataSources.INVOICE));
        assertThat(ModuleDataSources.INVOICE_DATA_SOURCE).isEqualTo(ModuleDataSources.dataSource(ModuleDataSources.INVOICE));
        assertThat(ModuleDataSources.INVOICE_SQL_SESSION_FACTORY)
                .isEqualTo(ModuleDataSources.sqlSessionFactory(ModuleDataSources.INVOICE));
        assertThat(ModuleDataSources.PRINT_TX_MANAGER).isEqualTo(ModuleDataSources.txManager(ModuleDataSources.PRINT));
        assertThat(ModuleDataSources.PRINT_DATA_SOURCE).isEqualTo(ModuleDataSources.dataSource(ModuleDataSources.PRINT));
        assertThat(ModuleDataSources.PLATFORM_TX_MANAGER).isEqualTo(ModuleDataSources.txManager(ModuleDataSources.PLATFORM));
        assertThat(ModuleDataSources.PLATFORM_DATA_SOURCE)
                .isEqualTo(ModuleDataSources.dataSource(ModuleDataSources.PLATFORM));
        assertThat(ModuleDataSources.PLATFORM_SQL_SESSION_FACTORY)
                .isEqualTo(ModuleDataSources.sqlSessionFactory(ModuleDataSources.PLATFORM));
        assertThat(ModuleDataSources.PRINT_SQL_SESSION_FACTORY)
                .isEqualTo(ModuleDataSources.sqlSessionFactory(ModuleDataSources.PRINT));
    }
}
