package ai.neargo.auth.store;

import java.util.Optional;

/**
 * 从 {@code user_no} 还原身份。**SPI —— 实现留在各业务模块。**
 *
 * <p>还原身份要读用户表（{@code usr_account} / {@code mch_account} / {@code sys_ops_staff}），
 * 而<b>鉴权基础代码不能依赖业务模块</b>。所以这里只留接口，
 * 与 {@code LivePermResolver} / {@code BizIdentityResolver} 同一手法 —— 那两个已经这么做了。
 *
 * <p>泛型而不是绑死 {@code LoginUser}：那个类型在 shop-base，
 * 而本模块连 shop-base 都不引。对本模块来说，身份是什么形状并不重要，
 * 它只负责「按 user_no 取一次、缓存起来」。
 *
 * <h2>实现约定两条</h2>
 * <ol>
 *   <li><b>只读本端用户表。</b>C 端读 {@code usr_account}、B 端读 {@code mch_account}、
 *       运营端读 {@code sys_ops_staff}。这同时是「将来能拆库」的第三条约束 ——
 *       跨端读一次，那条约束就破了，而破的那天没有任何东西会报错。</li>
 *   <li><b>账号不可用时返回 {@link Optional#empty()}</b>（停用、注销、软删）。
 *       调用方会据此 401 —— 这让「停用后立即失效」多了一道保险，
 *       不必只依赖踢人那条路径。</li>
 * </ol>
 *
 * @param <T> 身份对象的类型，由使用方决定（平台侧是 {@code LoginUser}）
 */
@FunctionalInterface
public interface IdentityLoader<T> {

    /**
     * @return 身份；账号不存在或不可用时为 {@link Optional#empty()}
     */
    Optional<T> load(String userNo);
}
