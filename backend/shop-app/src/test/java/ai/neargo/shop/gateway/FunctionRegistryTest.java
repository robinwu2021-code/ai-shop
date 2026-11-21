package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.function.FunctionRegistry;
import ai.neargo.shop.gateway.core.function.ModuleOwnership;
import ai.neargo.shop.gateway.core.function.ShopFunctionModules;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * 功能点按模块登记（TDD-网关层与统一权限 AC4、AC5；开发计划 F）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("网关 · 功能点登记表")
class FunctionRegistryTest {

    /** 模块 → 它的开关。不在这里的模块始终装配。 */
    private static final Map<String, String> SWITCHES = Map.of(ModuleOwnership.INVENTORY, "shop.inventory.enabled",
            ModuleOwnership.PRINT, "shop.print.enabled", ModuleOwnership.COMMERCE, "commerce.enabled",
            ModuleOwnership.JOB, "shop.job.enabled");

    @Autowired
    FunctionRegistry registry;
    @Autowired
    ResourceIndexes indexes;
    @Autowired
    Environment environment;

    @Test
    @DisplayName("★★★ 两个常量类里的每个权限码都有且只有一个模块；清点各模块的码数")
    void everyCodeHasExactlyOneModule() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Realm realm : List.of(Realm.MERCHANT, Realm.OPERATOR)) {
            for (String code : ModuleOwnership.codes(realm)) {
                String module = ModuleOwnership.moduleOf(realm, code);
                counts.merge(realm.name() + " " + module, 1, Integer::sum);
                // 归属模块的声明里，这个码恰好出现一次
                assertThat(ModuleOwnership.pointsOf(module).stream()
                        .filter(p -> p.realm() == realm && p.code().equals(code))).hasSize(1);
            }
        }
        System.out.println("功能点清点：" + counts);
        assertThat(ModuleOwnership.moduleOf(Realm.MERCHANT, "mch:pay:refund")).isEqualTo(ModuleOwnership.PAY);
        assertThat(ModuleOwnership.moduleOf(Realm.MERCHANT, "biz:stock")).isEqualTo(ModuleOwnership.RETAIL);
        assertThat(ModuleOwnership.moduleOf(Realm.MERCHANT, "biz:service:manage")).isEqualTo(ModuleOwnership.SERVICE);
        assertThat(ModuleOwnership.moduleOf(Realm.OPERATOR, "ops:pay:ent:read")).isEqualTo(ModuleOwnership.PAY);
        assertThat(ModuleOwnership.moduleOf(Realm.OPERATOR, "system:job:read")).isEqualTo(ModuleOwnership.JOB);
        assertThat(ModuleOwnership.moduleOf(Realm.OPERATOR, "system:param:read")).isEqualTo(ModuleOwnership.PLATFORM);
    }

    @Test
    @DisplayName("★★★ AC4：入口上用到的每个权限码，都由一个已装配的模块提供")
    void everyCodeUsedByAnEndpointIsRegistered() {
        TreeSet<String> missing = new TreeSet<>();
        Map<String, Realm> chains = Map.of("/ops", Realm.OPERATOR, "/pay-ops", Realm.OPERATOR, "/biz", Realm.MERCHANT);
        chains.forEach((prefix, realm) -> {
            for (ApiResource resource : indexes.of(List.of(prefix)).all()) {
                if (resource.access() != Access.POINT) {
                    continue;
                }
                for (String code : resource.rule().codes()) {
                    if (!registry.registered(realm, code)) {
                        missing.add(realm + " " + code + " ← " + resource.method() + " " + resource.pattern()
                                + "（归属模块 " + ModuleOwnership.moduleOf(realm, code) + "）");
                    }
                }
            }
        });
        assertThat(missing).as("入口在、它要的功能点却不在登记表里：要么模块的开关与入口的开关不一致，要么码没写进常量类")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 登记表与本次部署的开关一致：开着的模块在，关着的不在")
    void registryFollowsTheSwitches() {
        SWITCHES.forEach((module, property) -> assertThat(registry.modules().contains(module))
                .as("模块 %s 跟着开关 %s", module, property)
                .isEqualTo(environment.getProperty(property, Boolean.class, false)));
        assertThat(registry.modules()).contains(ModuleOwnership.RETAIL, ModuleOwnership.PLATFORM, ModuleOwnership.PAY);
    }

    @Test
    @DisplayName("★★★ AC5：模块没装配，它的功能点就不存在，也不会被算到基础模块头上")
    void absentModuleHasNoPoints() {
        new ApplicationContextRunner().withUserConfiguration(ShopFunctionModules.class, FunctionRegistry.class)
                .withPropertyValues("shop.inventory.enabled=false")
                .run(context -> {
                    FunctionRegistry off = context.getBean(FunctionRegistry.class);
                    assertThat(off.modules()).doesNotContain(ModuleOwnership.INVENTORY);
                    assertThat(off.registered(Realm.OPERATOR, "inventory:stock:read")).isFalse();
                    assertThat(off.registered(Realm.OPERATOR, "system:param:read")).isTrue();
                });
        new ApplicationContextRunner().withUserConfiguration(ShopFunctionModules.class, FunctionRegistry.class)
                .withPropertyValues("shop.inventory.enabled=true")
                .run(context -> assertThat(context.getBean(FunctionRegistry.class)
                        .moduleOf(Realm.OPERATOR, "inventory:stock:read")).contains(ModuleOwnership.INVENTORY));
    }
}
