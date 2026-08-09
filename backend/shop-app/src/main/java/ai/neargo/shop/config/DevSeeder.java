package ai.neargo.shop.config;

import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CommunityPoolMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.user.community.entity.CmtCommunity;
import ai.neargo.shop.user.community.entity.CmtPickupPoint;
import ai.neargo.shop.user.merchant.entity.MchEntity;
import ai.neargo.shop.user.mapper.UserMappers.CommunityMapper;
import ai.neargo.shop.user.mapper.UserMappers.MchEntityMapper;
import ai.neargo.shop.platform.entity.SysOpsStaff;
import ai.neargo.shop.platform.mapper.PlatformMappers.StaffMapper;
import ai.neargo.shop.user.mapper.UserMappers.PickupPointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 演示种子数据，**默认关闭**（{@code shop.seed.enabled=true} 才灌）。
 *
 * <p>powerbank 那边 5 个 Seeder 一度没有任何门禁，任何空库启动都会灌进假数据 ——
 * 生产环境出现「12 个假代理商」就是这么来的。这里从第一天就上开关，且幂等（有数据即跳过）。
 *
 * <p>灌的是**能把 c-app 首页跑起来的最小集**：2 社区 / 2 自提点 / 2 商家 / 4 商品，
 * 覆盖标品与生鲜两条计价线。
 */
@Configuration
@ConditionalOnProperty(name = "shop.seed.enabled", havingValue = "true")
public class DevSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    @Bean
    ApplicationRunner seedRunner(CommunityMapper communityMapper, PickupPointMapper pickupMapper,
                                 MchEntityMapper merchantMapper, GoodsMapper goodsMapper,
                                 SkuMapper skuMapper, CommunityPoolMapper poolMapper,
                                 CategoryMapper categoryMapper, StaffMapper staffMapper,
                                 // 平台员工是 staffMapper，商家子账号是 merchantStaffMapper —— 两套人，别混
                                 ai.neargo.shop.user.mapper.UserMappers.MchAccountMapper merchantStaffMapper,
                                 ai.neargo.shop.user.mapper.UserMappers.MchStoreMapper storeMapper) {
        return args -> {
            if (communityMapper.selectCount(Wrappers.emptyWrapper()) > 0) {
                return;   // 幂等：重启不重复灌
            }
            log.warn("[DEV-ONLY] seeding demo data (shop.seed.enabled=true) —— 生产环境不应看到这条日志");

            // 运营账号：**按岗位分角色**，没有「运营」这种什么都能干的大角色 ——
            // 一个能审商家又能改价又能退款的角色，出事时无法定位是谁的职责
            seedStaff(staffMapper, "admin", "超级管理员", "[\"SUPER_ADMIN\"]", "admin123");
            seedStaff(staffMapper, "bd", "商家运营", "[\"BD\"]", "bd123");
            seedStaff(staffMapper, "goods", "商品运营", "[\"GOODS_OPS\"]", "goods123");
            seedStaff(staffMapper, "support", "客服", "[\"SUPPORT\"]", "support123");

            // 类目：一级「日用百货」下挂二级，商品挂在二级 CAT001 上
            categoryMapper.insert(category("CAT-ROOT-1", null, 1, "日用百货", 1));
            categoryMapper.insert(category("CAT001", "CAT-ROOT-1", 2, "米面粮油", 1));
            categoryMapper.insert(category("CAT002", "CAT-ROOT-1", 2, "生鲜果蔬", 2));
            categoryMapper.insert(category("CAT-ROOT-2", null, 1, "生活服务", 2));
            categoryMapper.insert(category("CAT003", "CAT-ROOT-2", 2, "家政保洁", 1));

            communityMapper.insert(community("C0001", "阳光花园", "杭州市西湖区文一西路 100 号", 30280000, 120100000));
            communityMapper.insert(community("C0002", "翡翠城", "杭州市西湖区文二西路 200 号", 30285000, 120105000));

            merchantMapper.insert(merchant("M0001", "老张粮油店", "U-DEMO-1", true,
                    "开了 12 年的街边粮油店，米面油调料齐全", "[\"街坊老店\",\"当日达\"]"));
            merchantMapper.insert(merchant("M0002", "鲜果直供", "U-DEMO-2", false,
                    "产地直发，当日采摘次日到", "[\"产地直发\"]"));

            /*
             * 演示商家也要有**成员行 + 默认门店** —— V44 起 B 端身份来自
             * mch_account，而不是 mch_entity.owner_user_no。
             * 漏掉的话：库里有商家、C 端搜得到，唯独他自己登录 B 端时作用域是空的，
             * 所有 /biz/** 都 403，而看到的只是「打不开」。
             */
            for (var seed : java.util.List.of(
                    new String[]{"M0001", "U-DEMO-1", "老张粮油店"},
                    new String[]{"M0002", "U-DEMO-2", "鲜果直供"})) {
                merchantStaffMapper.insert(ownerStaff(seed[0], seed[1]));
                storeMapper.insert(defaultStore(seed[0], seed[2]));
            }

            pickupMapper.insert(pickup("PP0001", "C0001", "老张粮油店（自提点）",
                    "阳光花园东门旁", "M0001", "08:00-21:00", "每晚 7 点前到货"));
            pickupMapper.insert(pickup("PP0002", "C0002", "翡翠城便利店",
                    "翡翠城 3 号楼底商", "M0001", "07:00-22:00", "每晚 8 点前到货"));

            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0001", "M0001", "NORMAL", "🍚", "五常大米 10斤装", "东北五常，当季新米",
                    List.of(new SkuSeed("SK0001", "10斤装", 4980L, 5980L, 120),
                            new SkuSeed("SK0002", "20斤装", 9580L, 11800L, 60)));
            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0002", "M0001", "NORMAL", "🛢️", "金龙鱼调和油 5L", "家庭装，煎炒烹炸",
                    List.of(new SkuSeed("SK0003", "5L", 6980L, 7980L, 80)));
            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0003", "M0002", "FRESH", "🍑", "阳山水蜜桃 4枚礼盒", "次日到货，坏果包赔",
                    List.of(new SkuSeed("SK0004", "4枚装", 5800L, 6800L, 40)));
            seedGoods(goodsMapper, skuMapper, poolMapper,
                    "G0004", "M0002", "FRESH", "🫐", "云南蓝莓 125g×4盒", "当季头茬",
                    List.of(new SkuSeed("SK0005", "4盒装", 3980L, null, 30)));
        };
    }

    private record SkuSeed(String skuNo, String spec, long price, Long originPrice, int stock) {
    }

    private void seedStaff(StaffMapper mapper, String username, String realName,
                           String rolesJson, String rawPassword) {
        var s = new SysOpsStaff();
        s.setStaffNo("ST-" + username.toUpperCase());
        s.setUsername(username);
        s.setPassword(ai.neargo.shop.platform.impl.OpsServiceImpl.hash(rawPassword));
        s.setRealName(realName);
        s.setRoles(rolesJson);
        s.setStatus("ACTIVE");
        mapper.insert(s);
    }

    private PrdCategory category(String no, String parentNo, int level, String name, int sort) {
        var c = new PrdCategory();
        c.setCategoryNo(no);
        c.setParentNo(parentNo);
        c.setLevel(level);
        c.setName(name);
        c.setIcon("");
        c.setSort(sort);
        c.setStatus("ACTIVE");
        return c;
    }

    private CmtCommunity community(String no, String name, String address, int latE6, int lngE6) {
        var c = new CmtCommunity();
        c.setCommunityNo(no);
        c.setName(name);
        c.setAddress(address);
        c.setLatE6(latE6);
        c.setLngE6(lngE6);
        c.setStatus("OPEN");
        return c;
    }

    private CmtPickupPoint pickup(String no, String communityNo, String name, String address,
                                  String merchantNo, String openHours, String arrivalDesc) {
        var p = new CmtPickupPoint();
        p.setPickupNo(no);
        p.setCommunityNo(communityNo);
        p.setName(name);
        p.setAddress(address);
        p.setType("STORE");
        p.setScope("PERMANENT");
        p.setOwnerRef(merchantNo);
        p.setOpenHours(openHours);
        p.setArrivalDesc(arrivalDesc);
        p.setServiceFeeRate(0);
        p.setStatus("ACTIVE");
        return p;
    }

    private ai.neargo.shop.user.merchant.entity.MchAccount ownerStaff(String merchantNo, String userNo) {
        var st = new ai.neargo.shop.user.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.user.merchant.entity.MchAccount.ACTIVE);
        return st;
    }

    private ai.neargo.shop.user.merchant.entity.MchStore defaultStore(String merchantNo, String name) {
        var s = new ai.neargo.shop.user.merchant.entity.MchStore();
        s.setStoreNo("ST-" + merchantNo);
        s.setEntityNo(merchantNo);
        s.setName(name);
        s.setIsDefault(true);
        s.setStatus(ai.neargo.shop.user.merchant.entity.MchStore.ACTIVE);
        s.setFeatured("[]");
        return s;
    }

    private MchEntity merchant(String no, String name, String ownerUserNo, boolean verified,
                                 String desc, String tags) {
        var m = new MchEntity();
        m.setEntityNo(no);
        m.setName(name);
        m.setLogo("");
        m.setLegalForm("INDIVIDUAL");
        m.setDescription(desc);
        m.setOwnerUserNo(ownerUserNo);
        m.setRating(48);
        m.setRatingCount(126);
        m.setSalesCount(1893);
        m.setGoodsCount(2);
        m.setScoreGoods(49);
        m.setScoreService(47);
        m.setScoreSpeed(48);
        m.setVerified(verified);
        m.setBreachCount(0);
        m.setTags(tags);
        m.setStoreCode(no.equals("M0001") ? "ZHANG01" : "XIANGUO");
        m.setJoinedAt(System.currentTimeMillis());
        m.setStatus("ACTIVE");
        return m;
    }

    private void seedGoods(GoodsMapper goodsMapper, SkuMapper skuMapper, CommunityPoolMapper poolMapper,
                           String goodsNo, String merchantNo, String type, String cover,
                           String title, String subtitle,
                           List<SkuSeed> skus) {
        var g = new PrdGoods();
        g.setGoodsNo(goodsNo);
        g.setEntityNo(merchantNo);
        g.setTitle(title);
        g.setSubtitle(subtitle);
        // 演示封面用 emoji：真实环境这里是图片 URL，但本地联调要的是"一眼能分辨"，
        // 而不是四条一模一样的占位。空字符串在端上会走 GOODS_COVER_FALLBACK（🛒），
        // 满屏同一个购物车比没有图还难看
        g.setCover(cover);
        g.setImages("[]");
        g.setType(type);
        g.setCategoryNo("CAT001");
        g.setFulfillments("[\"STORE_PICKUP\"]");
        g.setSpecGroups("[{\"name\":\"规格\",\"options\":" + optionsJson(skus) + "}]");
        g.setRating(48);
        g.setRatingCount(32);
        g.setSales(240);
        g.setLimitPerUser(0);
        g.setOnSale(true);
        g.setAuditStatus("APPROVED");
        goodsMapper.insert(g);

        for (SkuSeed s : skus) {
            var sku = new PrdSku();
            sku.setSkuNo(s.skuNo());
            sku.setGoodsNo(goodsNo);
            sku.setEntityNo(merchantNo);
            sku.setMarket("CN");
            sku.setOptionValues("[\"" + s.spec() + "\"]");
            sku.setSpec(s.spec());
            sku.setPrice(s.price());
            sku.setOriginPrice(s.originPrice());
            sku.setStock(s.stock());
            sku.setLockedStock(0);
            skuMapper.insert(sku);
        }

        for (String communityNo : List.of("C0001", "C0002")) {
            var pool = new PrdCommunityPool();
            pool.setCommunityNo(communityNo);
            pool.setGoodsNo(goodsNo);
            pool.setEntityNo(merchantNo);
            pool.setSortWeight(0);
            poolMapper.insert(pool);
        }
    }

    private String optionsJson(List<SkuSeed> skus) {
        return skus.stream().map(s -> "\"" + s.spec() + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
