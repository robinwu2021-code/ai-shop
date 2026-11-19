package ai.neargo.shop.spi.merchant;

/**
 * 「这个人在这个管理空间里有没有这个权限码」（TDD-管理范围权限搬到网关-权限码方案）。
 *
 * <p>管理范围的授权模型本来就有（管理空间、资源、权限码、授权表），判断一直在服务里做。
 * 这个出口让<b>入口上的注解和网关</b>也能问同一处，而不必依赖商户模块的类型。授权数据还是那一份，这里不产生第二套规则。
 */
public interface ManagementAuthorityPort {

    /** 管理空间的选择放在这个请求头里，与选门店的 {@code X-Store-No} 同一个思路；没有时退到查询参数 {@link #PARAM}。 */
    String HEADER = "X-Management-No";
    String PARAM = "managementNo";

    /**
     * 对明确的资源持有某个码。
     *
     * @param actorKey     操作人，{@code USER:<用户号>} 或 {@code MCH:<成员号>}
     * @param resourceType {@code SPACE} / {@code BRAND} / {@code STORE} / {@code ENTITY} / {@code PAYMENT_MERCHANT}
     */
    boolean can(String tenantNo, String actorKey, String managementNo, String resourceType, String resourceNo,
                String permission);

    /**
     * 在这个管理空间里，对<b>至少一个</b>该类型的资源持有某个码。
     * 用在资源要从业务对象查出来的入口上（按结算单号、发行方号）：先保证他有这类权限，具体哪一个资源仍由服务裁。
     */
    boolean canAny(String tenantNo, String actorKey, String managementNo, String resourceType, String permission);
}
