package ai.neargo.shop.spi.platform;

/**
 * 一个模块在这套部署里装没装（TDD-网关层与统一权限 §2.6）。
 *
 * <p>放在 spi 里是因为问的人与答的人不在一层：菜单在平台域，而「装了哪些模块」由网关层的功能点登记表回答。
 * 没有实现时（只装了平台域的切片测试）调用方按「都装了」处理。
 */
public interface ModuleAvailabilityPort {

    /** @param moduleCode 模块编码，如 inventory、print；为空视为基础模块，始终可用 */
    boolean available(String moduleCode);
}
