package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Native SQL validates catalog projections; external facts are explicitly controlled contract fixtures. */
class PublicCatalogIT extends BeautyExecutionFixture {
    private String owner = "E";
    private String state = "ACTIVE";
    private String sku = "SKU";
    private int minutes = 60;

    @BeforeEach
    void database() throws Exception {
        required("service.impl.ServiceCatalogQueryServiceImpl");
        initialize("BtyStorePolicy", "BtyStorePolicyVersion", "BtyServiceDefinition", "BtyServiceDefinitionVersion");
        seed("bty_store_policy", "policy_no", "P", "store_no", "S", "current_policy_version_no", "P2",
                "status", "ACTIVE", "version", 2L);
        policy("P1", 1, T0, 30);
        policy("P2", 2, T0.plusYears(20), 99);
        seed("bty_service_definition", "definition_no", "D", "store_no", "S", "sku_no", "SKU",
                "current_definition_version_no", "DV", "status", "ACTIVE", "version", 1L);
        seed("bty_service_definition_version", "definition_no", "D", "definition_version_no", "DV",
                "revision_index", 1, "duration_min_snapshot", 60, "buffer_before_min", 5, "buffer_after_min", 10,
                "steps_template", "[]", "notes", "[\"请提前到店\"]", "schema_version", 1, "snapshot_hash", "a".repeat(64));
    }

    private void policy(String no, int revision, Object at, int ahead) {
        seed("bty_store_policy_version", "policy_version_no", no, "policy_no", "P", "revision_index", revision,
                "booking_ahead_days", ahead, "cancel_rule", "{\"freeCancelBeforeMinutes\":60,"
                        + "\"allowCustomerCancel\":true}", "allowed_modes", "[\"PREPAID_SINGLE\"]",
                "effective_at", at, "schema_version", 1, "snapshot_hash", "b".repeat(64));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApplicationContextRunner catalog() {
        Class port = requiredPort();
        Object facts = Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, (proxy, method, args) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Class<?> storeType = Class.forName(port.getName() + "$Store");
            Object store = storeType.getConstructors()[0].newInstance(owner, "S", "门店", "logo", state,
                    "09:00-18:00", "地址", new StoreSalesCatalogPort.Snapshot(
                            owner, "S", null, 0, null, 0, null, false, null, null));
            if (method.getName().equals("store")) { return store; }
            return Class.forName(port.getName() + "$Service").getConstructor(storeType, String.class, String.class,
                    String.class, String.class, long.class, int.class)
                    .newInstance(store, sku, "G", "护理", "cover", 9900L, minutes);
        });
        return runner().withUserConfiguration(required("config.BeautyCatalogConfiguration"))
                .withBean(ObjectMapper.class, ObjectMapper::new).withBean(port, () -> facts);
    }

    private Class<?> requiredPort() {
        return assertDoesNotThrow(() ->
                Class.forName("ai.neargo.shop.spi.product.ServiceCatalogFactsPort"));
    }

    private Object invoke(ApplicationContext context, String method, Object... args) {
        Object service = context.getBean(required("service.ServiceCatalogQueryService"));
        var target = Arrays.stream(service.getClass().getMethods())
                .filter(m -> m.getName().equals(method) && m.getParameterCount() == args.length)
                .findFirst().orElseThrow();
        try { return target.invoke(service, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    private Object query(String store) {
        try { return required("dto.ServiceDetailQuery").getConstructors()[0].newInstance(store); }
        catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }

    @Test
    void publicStoreSelectsEffectivePolicyAndExcludesInternalEvidence() {
        catalog().run(context -> {
            String response = new ObjectMapper().writeValueAsString(invoke(context, "getPublicStore", "S"));
            assertThat(response).contains("P1", "30", "门店", "09:00-18:00")
                    .doesNotContain("P2", "snapshotHash", "entityNo", "createdBy", "revisionIndex");
        });
    }

    @Test
    void publicServiceCombinesLiveQuoteAndPublishedDefinitionWithoutPrivateRequirements() {
        catalog().run(context -> {
            String response = new ObjectMapper().writeValueAsString(
                    invoke(context, "getPublicService", "SKU", query("S")));
            assertThat(response).contains("9900", "护理", "DV", "请提前到店", "bufferBeforeMin")
                    .doesNotContain("requirements", "skillGoodsNo", "snapshotHash", "entityNo");
        });
    }

    @Test
    void publicServiceRejectsForeignSkuAndStaleProductDuration() {
        catalog().run(context -> {
            sku = "FOREIGN";
            assertThatThrownBy(() -> invoke(context, "getPublicService", "SKU", query("S")))
                    .isInstanceOf(BizException.class);
            sku = "SKU";
            minutes = 90;
            assertThatThrownBy(() -> invoke(context, "getPublicService", "SKU", query("S")))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test
    void inactiveFutureAndForeignOwnedLocalFactsAreNotPublic() {
        catalog().run(context -> {
            sql.update("UPDATE bty_store_policy_version SET effective_at=?", T0.plusYears(20));
            assertThatThrownBy(() -> invoke(context, "getPublicStore", "S")).isInstanceOf(BizException.class);
            sql.update("UPDATE bty_store_policy_version SET effective_at=? WHERE policy_version_no='P1'", T0);
            owner = "OTHER";
            assertThatThrownBy(() -> invoke(context, "getPublicService", "SKU", query("S")))
                    .isInstanceOf(BizException.class);
            owner = "E";
            sql.update("UPDATE bty_service_definition SET status='INACTIVE'");
            assertThatThrownBy(() -> invoke(context, "getPublicService", "SKU", query("S")))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test
    void merchantCannotExpandStoreAndRemoteLookupCannotRunInsideCallerTransaction() {
        catalog().run(context -> {
            assertThatThrownBy(() -> invoke(context, "getService", "E", "S", "ACTOR", "SKU", query("OTHER")))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> tx(context, () -> invoke(context, "getPublicStore", "S")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }
}
