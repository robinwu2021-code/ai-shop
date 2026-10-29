package ai.neargo.inventory.svc;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 闸门 I2（按字节码）：进销存独立服务不依赖 ai-shop 的代码（ADR-046，TDD §2.1）。
 *
 * <p>pre-push 里 {@code check-inventory-independence.mjs} 按源码文本查 import；
 * 这里按字节码再查一遍 —— 全限定名写法、注解里的类引用，文本扫描会漏。
 */
class InventoryArchitectureTest {

    private static final JavaClasses INVENTORY = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ai.neargo.inventory");

    @Test
    void inventoryDoesNotDependOnShopCode() {
        noClasses().that().resideInAPackage("ai.neargo.inventory..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "ai.neargo.shop..", "ai.neargo.secret..", "ai.neargo.job.core..", "ai.neargo.job.store..")
                .because("进销存是独立服务，白名单只有 neargo 框架、job-api 与 svc-client（TDD §2.1）")
                .check(INVENTORY);
    }

    @Test
    void domainDoesNotKnowWhichInstanceItRunsIn() {
        noClasses().that().resideInAPackage("ai.neargo.inventory.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("ai.neargo.inventory.guard..", "ai.neargo.inventory.svc..")
                .because("测试一套、生产两套的差异只在环境文件；领域不许知道自己是哪一套（执行方案 §二）")
                .check(INVENTORY);
    }
}
