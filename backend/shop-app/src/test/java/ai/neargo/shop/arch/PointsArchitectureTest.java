package ai.neargo.shop.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Protects points as a pay business package, without adding a deployment boundary. */
class PointsArchitectureTest {

    @Test
    void business_package_boundary() {
        JavaClasses points = new ClassFileImporter().importPackages("ai.neargo.shop.pay.points");
        assertThat(points).as("points must exist inside pay-domain").isNotEmpty();
        noClasses().that().resideInAPackage("ai.neargo.shop.pay.points..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "ai.neargo.shop.trade..", "ai.neargo.shop.product..", "ai.neargo.shop.pay.impl..")
                .check(points);
    }

    @Test
    void settlement_and_funds_contracts_are_local_interfaces() {
        assertThat(load("ai.neargo.shop.pay.points.app.PointsSettlementHook").isInterface()).isTrue();
        assertThat(load("ai.neargo.shop.pay.points.port.PointsFundsGateway").isInterface()).isTrue();
    }

    @Test
    void business_contracts_do_not_depend_on_frameworks() {
        JavaClasses contracts = new ClassFileImporter().importPackages("ai.neargo.shop.spi.points");
        assertThat(contracts).as("cross-domain contracts must exist in shop-base").isNotEmpty();
        noClasses().that().resideInAPackage("ai.neargo.shop.spi.points..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "com.baomidou..", "ai.neargo.shop.pay..")
                .check(contracts);
    }

    private Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Missing approved points contract: " + name, e);
        }
    }
}
