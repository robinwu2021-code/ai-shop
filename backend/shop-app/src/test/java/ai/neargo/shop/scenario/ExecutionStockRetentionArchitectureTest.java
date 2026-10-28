package ai.neargo.shop.scenario;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.StockPort;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
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
