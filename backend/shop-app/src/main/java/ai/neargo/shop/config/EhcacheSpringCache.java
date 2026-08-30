package ai.neargo.shop.config;

import org.springframework.cache.support.AbstractValueAdaptingCache;

import java.util.concurrent.Callable;

/**
 * Spring {@link org.springframework.cache.Cache} 到 ehcache 3 编程式缓存的薄适配。
 *
 * <p>继承 {@link AbstractValueAdaptingCache} 是为了 null 值：ehcache 不收 null，
 * 而 {@code @Cacheable} 的方法可以返回 null（{@code categoryTypeOf} 查无此类目
 * 就返回 null，且那是有意的 —— 见其注释）。基类把 null 包成占位对象存进去，
 * 取出时再还原，null 结果也能被缓存住，不会每次都穿透到库。
 */
final class EhcacheSpringCache extends AbstractValueAdaptingCache {

    private final String name;
    private final org.ehcache.Cache<Object, Object> store;

    EhcacheSpringCache(String name, org.ehcache.Cache<Object, Object> store) {
        super(true);   // 允许缓存 null（见类注释）
        this.name = name;
        this.store = store;
    }

    @Override
    protected Object lookup(Object key) {
        return store.get(key);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return store;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object hit = lookup(key);
        if (hit != null) {
            return (T) fromStoreValue(hit);
        }
        /*
         * 不加锁：并发未命中时最多几个请求各查一次库再各写一次同样的值 ——
         * 这两个缓存装的是类目树这种秒级重建的东西，惊群的代价远小于
         * 为它引入每键锁的复杂度。换 Redis 时如需 singleflight 再说。
         */
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e);
        }
    }

    @Override
    public void put(Object key, Object value) {
        store.put(key, toStoreValue(value));
    }

    @Override
    public void evict(Object key) {
        store.remove(key);
    }

    @Override
    public void clear() {
        store.clear();
    }
}
