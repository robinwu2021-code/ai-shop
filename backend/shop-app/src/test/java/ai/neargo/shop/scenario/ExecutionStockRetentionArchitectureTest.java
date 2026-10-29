package ai.neargo.shop.scenario;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.InventoryExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.StockPort;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.stereotype.Component;

/** Bytecode boundaries preserve the no-owner-IO guard and the persisted-original production return call surface. */
class ExecutionStockRetentionArchitectureTest {
    @Test
    void guardCannotDependOnAnotherOwnerOrCreateTheCoreCycle() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ai.neargo.shop.product.policy");
        noClasses().that().haveSimpleName("ExecutionStockRetentionGuard").should().dependOnClassesThat()
                .resideInAnyPackage("..trade..", "..spi.trade..", "..inventory..", "..invbridge..",
                        "..product.service..", "..product.port..")
                .check(classes);
    }

    @Test
    void inventoryGuardCannotCallSourceOwnersOrCreateAServiceCycle() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ai.neargo.shop.inventory.policy");
        noClasses().that().haveSimpleName("ExecutionStockRetentionGuard").should().dependOnClassesThat()
                .resideInAnyPackage("..trade..", "..spi.trade..", "..invbridge..", "..inventory.service..",
                        "..inventory.port..").check(classes);
    }

    @Test
    void nativeInventoryExportsOneComponentWithoutReplacingTheHostAggregate() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ai.neargo.shop.inventory");
        assertThat(classes.stream().filter(type -> type.isAssignableTo(
                InventoryExecutionStockRetentionPort.class)
                && type.isAnnotatedWith(Component.class)).toList()).hasSize(1);
        assertThat(classes.stream().filter(type -> type.isAssignableTo(ExecutionStockRetentionPort.class))
                .toList()).isEmpty();
    }

    @Test
    void defaultSpringBeanNamingKeepsBothNativeAndPlatformGuardsDistinct() throws Exception {
        var registry = new SimpleBeanDefinitionRegistry();
        var generator = AnnotationBeanNameGenerator.INSTANCE;
        var classes = List.of("ai.neargo.shop.inventory.policy.ExecutionStockRetentionGuard",
                "ai.neargo.shop.inventory.policy.ExecutionStockRetentionSchemaVerifier",
                "ai.neargo.shop.product.policy.ExecutionStockRetentionGuard",
                "ai.neargo.shop.product.policy.ExecutionStockRetentionSchemaVerifier");
        var names = new ArrayList<String>();
        for (var type : classes) {
            var definition = new AnnotatedGenericBeanDefinition(Class.forName(type));
            names.add(generator.generateBeanName(definition, registry));
        }
        assertThat(names).containsExactly("inventoryExecutionStockRetentionGuard",
                "inventoryExecutionStockRetentionSchemaVerifier", "executionStockRetentionGuard",
                "executionStockRetentionSchemaVerifier").doesNotHaveDuplicates();
    }

    @Test
    void productionCallersOutsideLegacyAdaptersCannotRestoreWithoutAnOriginal() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ai.neargo.shop");
        noClasses().that().resideOutsideOfPackages("..product.port..", "..invbridge..")
                .should().callMethod(StockPort.class, "restore", String.class, List.class).check(classes);
    }

    @Test
    void aggregateRetentionHasExactlyOneExportedProvider() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ai.neargo.shop.product", "ai.neargo.shop.invbridge");
        assertThat(classes.stream().filter(type -> type.isAssignableTo(ExecutionStockRetentionPort.class)
                && type.isAnnotatedWith(Component.class)).toList()).hasSize(1);
    }
}
