package ai.neargo.auth.store;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 一个池里装着两类主体时，按会话行上的 {@code subject_kind} 分发。
 *
 * <h2>只有 B 端需要它</h2>
 * <p>店员从 {@code /biz/auth/staff-login} 进来，主体是 {@code mch_account_no}；
 * 老板与<b>还没开店的人</b>从 {@code /biz/auth/login} 进来，主体是 {@code user_no}。
 * 生产实测 9 个商家账号里 8 个没有 {@code usr_account}，所以两类都得认。
 *
 * <h2>失败即关闭</h2>
 * <p>{@code subject_kind} 认不出来时<b>返回空，而不是挨个试</b>。
 * 挨个试就等于把「这个号该去哪张表查」退回成猜 —— 而猜错的后果是
 * <b>把会话解析成另一个人</b>：不报错，只是让人看见别人的数据。
 *
 * <p>这也是为什么 {@code subject_kind} 在 {@code SessionDao.insert} 上必传：
 * 一个没有 kind 的会话行在这里会被直接拒绝，而不是被某条回落路径救活。
 */
public final class CompositeIdentityLoader<T> implements IdentityLoader<T> {

    private final Map<SubjectKind, IdentityLoader<T>> byKind;

    @SafeVarargs
    public CompositeIdentityLoader(IdentityLoader<T>... loaders) {
        Map<SubjectKind, IdentityLoader<T>> m = new EnumMap<>(SubjectKind.class);
        for (IdentityLoader<T> l : loaders) {
            IdentityLoader<T> old = m.put(l.kind(), l);
            if (old != null) {
                // 两个加载器认领同一类，分发结果就取决于参数顺序 —— 那是隐形的
                throw new IllegalArgumentException("重复的 SubjectKind：" + l.kind());
            }
        }
        this.byKind = Map.copyOf(m);
    }

    /**
     * <b>不该被调到。</b>组合加载器必须知道 kind 才能分发；
     * 走到这里说明调用方用的是单参版本，那条路径会静默地只认一类。
     */
    @Override
    public Optional<T> load(String userNo) {
        throw new UnsupportedOperationException(
                "组合加载器必须带 subjectKind 调用：load(userNo, kind)");
    }

    public Optional<T> load(String userNo, SubjectKind kind) {
        IdentityLoader<T> loader = kind == null ? null : byKind.get(kind);
        return loader == null ? Optional.empty() : loader.load(userNo);
    }

    @Override
    public SubjectKind kind() {
        throw new UnsupportedOperationException("组合加载器不属于单一 SubjectKind");
    }
}
