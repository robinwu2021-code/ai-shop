package ai.neargo.shop.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 零售域要改短的状态取值只能写在所属表的常量上（用户 2026-11-27 定；docs/technical/零售域-状态短值换值表.md）。
 *
 * <p>别处一律引用常量：Java 里写 {@code OrdSubOrder.WAIT_FULFILL}，注解 SQL 的文本块里写
 * {@code '${@ai.neargo.shop.trade.entity.OrdSubOrder@WAIT_FULFILL}'}，拼接的 SQL 字符串里拼常量。
 * 这样换短值时只改一处。打印、积分、券与权益、商户开户、生产耗用与库存里有同名的值，含义不同、不归交易库与行业库，不在守卫范围。
 */
@DisplayName("架构 · 零售域状态取值只在所属表的常量上写字面量")
class RetailStatusLiteralGuardTest {
    private static final Path ROOT = Paths.get(System.getProperty("user.dir")).getParent().getParent();

    private static final List<String> SOURCES = List.of(
            "backend/shop-core/src/main/java", "backend/shop-base/src/main/java", "backend/shop-gateway/src/main/java",
            "backend-plugins/shop-commerce-integration", "backend-plugins/shop-industry-food",
            "backend-plugins/shop-industry-beauty", "backend-plugins/shop-retail-commerce");

    private static final List<String> OUT_OF_SCOPE = List.of(
            "/printing/", "/paybridge/", "/promotion/", "/payclient/", "/trade/order/points/",
            "PointsRefundResourceServiceImpl", "OrderPointsRefundBindingMapper", "LegacyCoupon", "/spi/entitlement/",
            "/shop/merchant/", "/merchantbridge/", "BizEntityController", "DiningProductionConsumer",
            "ProductionConsumptionServiceImpl", "FoodLocalStockPortImpl", "/src/test/");

    /** 取值 → 允许写字面量的定义处（文件名）。 */
    private static final Map<String, Set<String>> OWNERS = Map.ofEntries(
            Map.entry("WAIT_FULFILL", Set.of("IndustryOrderPort.java")),
            Map.entry("WAIT_OFFLINE_PAY", Set.of("OrdOrder.java")),
            Map.entry("WAIT_CHANNEL_PAY", Set.of("OrdOrder.java")),
            Map.entry("POINTS_PENDING", Set.of("OrdOrder.java")),
            Map.entry("WAIT_RECEIPT", Set.of("OrdAfterSale.java")),
            Map.entry("ARBITRATING", Set.of("OrdAfterSale.java")),
            Map.entry("WAIT_RETURN", Set.of("OrdAfterSale.java")),
            Map.entry("PROOF_VALIDATED", Set.of("OrdIndustryFulfillment.java")),
            Map.entry("HANDOVER_COMPLETE", Set.of("IndustryFulfillmentPort.java")),
            Map.entry("BUYER_CONFIRM_PENDING", Set.of("IndustryFulfillmentPort.java")),
            Map.entry("STARTED_SUSPENDED", Set.of("OrdServiceExecution.java")),
            Map.entry("SUSPEND_REQUESTED", Set.of("OrdServiceExecution.java", "BtyServiceTask.java")),
            Map.entry("STOCK_PENDING", Set.of("ManualServiceAdmissionPort.java")),
            Map.entry("AWAITING_RECEIPT", Set.of("ManualServiceAdmissionPort.java")),
            Map.entry("CASH_CONFIRMED", Set.of("OrdRefundExecution.java")),
            Map.entry("CASH_FAILED_PENDING_OPERATOR", Set.of("OrdRefundExecution.java")),
            Map.entry("COMPENSATED_PENDING_OPERATOR", Set.of("OrdRefundExecution.java")),
            Map.entry("NOT_REQUIRED", Set.of("RefundObligationMapper.java", "OrdChannelAfterSale.java")),
            Map.entry("WAITING_FUNDS_OWNER", Set.of("OrdChannelAfterSale.java")),
            Map.entry("WAITING_ORIGINAL_ACTION", Set.of("OrdChannelAfterSale.java")),
            Map.entry("COMMIT_DECIDED", Set.of("OrdEntServiceOrder.java", "OrdEntOrderUse.java")),
            Map.entry("CANCEL_DECIDED", Set.of("OrdEntServiceOrder.java", "OrdEntOrderUse.java")),
            Map.entry("WAITING_FUNDING", Set.of("OrdIndustryOrderSource.java")),
            Map.entry("UNCONFIRMED", Set.of("OrdIndustryOrderSource.java", "CimOrderLink.java")),
            Map.entry("WAITING_STOCK", Set.of("OrdIndustryOrderSource.java")),
            Map.entry("DISPATCHING", Set.of("OrdIndustryOrderSource.java")),
            Map.entry("PLATFORM_CONFIRMED", Set.of("CimOrderLink.java")),
            Map.entry("PLATFORM_REJECTED", Set.of("CimOrderLink.java")),
            Map.entry("WAITING_CANCELLATION", Set.of("CimOrderLink.java")),
            Map.entry("WAITING_CONFIRMATION", Set.of("CimOrderLink.java")),
            Map.entry("WAITING_DECISION", Set.of("CimOrderLink.java")),
            Map.entry("WAITING_MAPPING", Set.of("CimOrderLink.java")),
            Map.entry("WAITING_OWNER", Set.of("CimOrderLink.java")),
            Map.entry("AUTH_REJECTED", Set.of("CimAccount.java")),
            Map.entry("RETURN_IN_PROGRESS", Set.of("CimAfterSaleLink.java")),
            Map.entry("WAITING_FUNDS", Set.of("CimAfterSaleLink.java")),
            Map.entry("WAITING_ORIGINAL_DECISION", Set.of("CimAfterSaleLink.java")),
            Map.entry("APPROVAL_PENDING", Set.of("FnbReturn.java")),
            Map.entry("APPROVAL_UNKNOWN", Set.of("FnbReturn.java")),
            Map.entry("BUSINESS_REQUESTED", Set.of("FnbReturn.java")),
            Map.entry("CANCEL_PENDING", Set.of("FnbReturn.java")),
            Map.entry("NOT_REQUESTED", Set.of("FnbReturn.java")),
            Map.entry("WAIT_KITCHEN", Set.of("FnbReturn.java")),
            Map.entry("DRAFT_BUSINESS_ONLY", Set.of("FnbBusinessSharePlan.java")),
            Map.entry("CONFIRMED_BUSINESS_ONLY", Set.of("FnbBusinessSharePlan.java")),
            Map.entry("CANCELLED_BUSINESS_ONLY", Set.of("FnbBusinessSharePlan.java")),
            Map.entry("NEEDS_CLEANING", Set.of("FnbTable.java")),
            Map.entry("EXECUTION_PENDING", Set.of("BtyServiceTask.java")),
            Map.entry("EXECUTION_PREPARED", Set.of("BtyServiceTask.java")),
            Map.entry("EXECUTION_SUSPENDED", Set.of("BtyServiceTask.java")),
            Map.entry("PREPARED_OBSERVED", Set.of("BtyServiceTask.java")),
            Map.entry("SERVICE_FINISHED", Set.of("BtyServiceTask.java")),
            Map.entry("SOURCE_PENDING", Set.of("BtyServiceTask.java")),
            Map.entry("IN_PROGRESS", Set.of("BtyServiceStep.java")),
            Map.entry("OPEN_PENDING", Set.of("BtyReceptionOperation.java")),
            Map.entry("RESOURCE_PENDING", Set.of("BtyReceptionOperation.java")),
            Map.entry("ARRIVAL_PENDING", Set.of("BtyReceptionOperation.java")));

    @Test
    void literalsLiveOnlyOnTheOwningConstants() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String source : SOURCES) {
            Path root = ROOT.resolve(source);
            assertThat(root).as("源码目录不在了：%s —— 搬了就跟着改 SOURCES", source).isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String path = file.toString().replace('\\', '/');
                    if (OUT_OF_SCOPE.stream().anyMatch(path::contains) || !path.contains("/src/main/java/")) {
                        continue;
                    }
                    scanned++;
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (var entry : OWNERS.entrySet()) {
                            String value = entry.getKey();
                            if (!line.contains("\"" + value + "\"") && !line.contains("'" + value + "'")) {
                                continue;
                            }
                            boolean definition = entry.getValue().contains(file.getFileName().toString())
                                    && Pattern.compile("\\bString " + value + "\\s*=\\s*\"" + value + "\";").matcher(line).find();
                            if (!definition) {
                                violations.add(root.relativize(file) + ":" + (i + 1) + " " + value);
                            }
                        }
                    }
                }
            }
        }
        assertThat(scanned).as("一个文件都没扫到 —— 路径错了").isGreaterThan(100);
        assertThat(violations).as("状态取值请引用所属表的常量（见类注释）").isEmpty();
    }
}
