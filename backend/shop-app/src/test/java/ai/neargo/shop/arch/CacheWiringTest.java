package ai.neargo.shop.arch;

import ai.neargo.shop.product.service.CategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存接线守卫（工单-规格联动与查询性能一期 步骤 5）。
 *
 * <p>它就是那次「消融」的常驻版：把 {@code CategoryServiceImpl} 上的
 * {@code @Cacheable} 注掉，第一条用例立刻红；把 {@code @CacheEvict} 注掉，
 * 第二条红。一次性的人工消融验完就没了，这里钉住它 ——
 * 不然哪天有人「清理注解」，缓存静默消失，没有任何测试会发现
 * （功能全对，只是每次都打库）。
 *
 * <p>只验接线，不验命中率与 TTL —— 那些是 ehcache 自己的行为，测它等于测依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("缓存接线：@Cacheable 真的在缓存，@CacheEvict 真的在失效")
class CacheWiringTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private java.util.Map<String, CacheManager> allCacheManagers;

    @Test
    @DisplayName("tree() 调过一次之后，categoryTree 缓存里必须有值")
    void treeIsCached() {
        var cache = cacheManager.getCache("categoryTree");
        assertThat(cache).as("categoryTree 缓存区必须存在（CacheConfig 注册的两个之一）").isNotNull();
        cache.clear();

        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(categoryService))
                .as("categoryService 必须是代理 —— 不是的话 @Cacheable 根本没人拦")
                .isTrue();
        categoryService.tree();
        assertThat(cache.get(SimpleKey.EMPTY))
                .as("调过 tree() 之后缓存该有值 —— 为空说明 @Cacheable 没接上（代理没生效或注解被删）")
                .isNotNull();
    }

    @Test
    @DisplayName("categoryTypeOf 按 key 缓存；save 失效整区")
    void typeIsCachedAndEvictedOnSave() {
        var cache = cacheManager.getCache("categoryType");
        assertThat(cache).isNotNull();
        cache.clear();

        categoryService.categoryTypeOf("CAT110");
        assertThat(cache.get("CAT110"))
                .as("按 categoryNo 作 key 的缓存该命中").isNotNull();

        /*
         * 走 service 的写路径触发 @CacheEvict。用一个不存在的父类目让 save 失败也没关系 ——
         * 失效发生在方法调用上，@CacheEvict 默认 beforeInvocation=false，
         * 所以这里必须用**能成功**的调用。建一个真实的一级类目最稳。
         */
        categoryService.save(new CategoryService.SaveCategoryCommand(
                null, "缓存失效验证类目", null, null, null, null, null, null, null));
        assertThat(cache.get("CAT110"))
                .as("save 之后整区该被清掉 —— 还在说明 @CacheEvict 没接上")
                .isNull();
    }
}
