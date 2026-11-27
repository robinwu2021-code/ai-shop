package ai.neargo.shop.spi.member;

/** The cashier presents a member number within the selected store's customer population. */
public interface PosSaleMemberPort {
    String requireUser(String tenant, String entity, String store, String memberNo);
    boolean active(String tenant, String entity, String store, String memberNo);
}
