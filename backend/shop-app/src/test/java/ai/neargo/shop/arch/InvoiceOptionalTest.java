package ai.neargo.shop.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 发票模块可以打包排除（{@code -Dinvoice.exclude}，TDD-发票模块 §17）的前提：shop-app 只经 {@code spi.invoice}
 * 接口用发票，<b>不直接 import 发票模块的任何类</b>。一旦直接引用，排除打包时编译就会失败 —— 而默认打包照样绿，
 * 所以要在默认形态里就守住。
 *
 * <p>只导入 shop-app 自己的产物（{@code target/classes}）：发票模块的类在类路径上，但不是被检查的对象。
 */
class InvoiceOptionalTest {

    @Test
    void shopAppReachesInvoiceOnlyThroughTheSpi() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPath(Path.of("target/classes"));
        noClasses().should().dependOnClassesThat().resideInAPackage("ai.neargo.shop.invoice..")
                .because("排除发票模块打包时这些类不存在；要用发票请经 ai.neargo.shop.spi.invoice")
                .check(classes);
    }
}
