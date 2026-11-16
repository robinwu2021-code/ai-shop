package ai.neargo.shop.spi.merchant;

/**
 * Merchant-side rules for deleting a personal account (TDD-商户App统一登录与账号 AC17–AC19, D7, D8, D14).
 *
 * <p>Both deregistration entries use it: the merchant App ({@code POST /biz/auth/deregister}) and the
 * mini-program ({@code POST /mp/user/deregister}). Without it an owner deleting the account from the
 * mini-program would leave stores nobody can sign in to.
 */
public interface MerchantDeregisterPort {

    /** Whether merchant-side blockers (open store orders, pending payouts, paid plans) stop the deletion. */
    boolean blocked(String userNo);

    /**
     * Runs in the deregistration transaction, before the personal credentials are deleted:
     * the account's owned stores become read-only and every merchant membership is disabled.
     *
     * @param phone the account's login phone before deletion; may be null
     */
    void onDeregistered(String userNo, String phone);
}
