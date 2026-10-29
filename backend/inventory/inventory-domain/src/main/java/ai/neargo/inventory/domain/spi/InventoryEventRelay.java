package ai.neargo.inventory.domain.spi;

/**
 * 把进销存事件交给调用方的统一出站表（可选）。
 *
 * <p><b>只在 ai-shop 内嵌的过渡期存在</b>：商城有一张跨域统一出站表，开着时库存事件交给它投，
 * 不进本库的 {@code inv_outbox}。独立部署没有这个实现，事件一律写 {@code inv_outbox}、由宿主拉取。
 * 商城切流完成（T8.6）后与内嵌装配一起删除。
 */
public interface InventoryEventRelay {

    /** 统一出站表在维护中：此时不能写事件，过账应当失败，而不是把事件丢掉。 */
    boolean maintenance();

    /** 统一出站表接管了进销存事件。 */
    boolean active();

    /** 过账事件交给统一出站表；与过账同事务。 */
    void documentPosted(String ownerId, String docNo, String docKind);

    /** 让统一出站表投一轮。 */
    void signal();
}
