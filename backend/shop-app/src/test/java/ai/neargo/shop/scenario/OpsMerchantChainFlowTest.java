package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家链条画像（M1）。
 *
 * <p>这一页的价值全在 {@code stuckAt} 那一列 —— 六个数字自己不指向任何人，
 * 「卡在哪一层」才是能拿去打电话的东西。所以测试压在那一列上：
 *
 * <ol>
 *   <li><b>取第一个断掉的环，不是所有断掉的环。</b>一家没建品的商家后面五列
 *       当然全是 0，标成「五处都有问题」会把真正该做的那一件事淹掉。</li>
 *   <li><b>「全卡在审核」与「审完了没上架」必须分开。</b>前者该催的是平台的审核员，
 *       后者是商家 —— 混成一类，运营会去催错人。</li>
 *   <li><b>stuckOnly 是筛选不是判据</b>：筛出来的每一行都得是全量里也卡着的那一行。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsMerchantChainFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private MchEntityMapper merchantMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private JsonNode chain(String token, boolean stuckOnly) throws Exception {
        String body = mvc().perform(get("/ops/merchant/chain?limit=500&stuckOnly=" + stuckOnly)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private static JsonNode rowOf(JsonNode rows, String entityNo) {
        for (JsonNode r : rows) {
            if (entityNo.equals(r.get("entityNo").asString())) {
                return r;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★★★ 卡点取第一个断掉的环 —— 没建品的商家不该被标成「五处都有问题」")
    void stuckAtIsTheFirstBrokenLink() throws Exception {
        String token = TestLogin.admin(mvc(), json);

        assertThat(chain(token, false).size())
                .as("一行都没有 —— 这一页由 mch_entity 驱动，测试库里没有商家的话它测不到任何东西")
                .isGreaterThan(0);

        /*
         * **自己种一家商家**，不从存量里挑。挑的那一版是假绿的反面：
         * 存量商家的建品数由别的用例决定，挑不到符合条件的行时断言就断言不到东西，
         * 而「断言不到」和「结论错了」在报错里长得一样。
         */
        String probe = seedMerchant();
        try {
            JsonNode r = rowOf(chain(token, false), probe);
            assertThat(r).as("自己种的商家没出现在画像里 —— 行集不是由 mch_entity 驱动的？").isNotNull();
            assertThat(r.get("goods").asLong()).isZero();
            assertThat(r.get("stuckAt").asString())
                    .as("一个品都没建，卡点必须是 NO_GOODS —— 后面五列的 0 是它的后果，不是五个独立问题")
                    .isEqualTo("NO_GOODS");
        } finally {
            dropMerchant(probe);
        }
    }

    @Test
    @DisplayName("★★★ 「全卡在审核」与「审完了没上架」是两个卡点 —— 混一起会去催错人")
    void auditBacklogIsNotTheSameAsNotOnSale() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String entityNo = seedMerchant();

        List<Long> seeded = new ArrayList<>();
        try {
            // 只有待审：卡点该指向平台的审核员
            seeded.add(seed(entityNo, "AUDITING", false));
            JsonNode r = rowOf(chain(token, false), entityNo);
            assertThat(r.get("pendingAudit").asLong()).isGreaterThan(0L);
            assertThat(r.get("stuckAt").asString())
                    .as("有品在待审、且一个都没上架 —— 这一层的账该算在平台头上")
                    .isEqualTo("IN_AUDIT");

            // 再加一件审完了但没上架：仍然没有任何一件在架，但卡点换人了
            for (Long id : seeded) {
                final Long fid = id;
                DataScopeContext.executeWithoutScope(() -> {
                    PrdGoods u = new PrdGoods();
                    u.setId(fid);
                    u.setAuditStatus("APPROVED");
                    return goodsMapper.updateById(u);
                });
            }
            r = rowOf(chain(token, false), entityNo);
            assertThat(r.get("pendingAudit").asLong()).isZero();
            assertThat(r.get("stuckAt").asString())
                    .as("审完了还是没上架 —— 这时候该找的是商家，不是审核员")
                    .isEqualTo("NOT_ON_SALE");
        } finally {
            restore(seeded);
            dropMerchant(entityNo);
        }
    }

    @Test
    @DisplayName("★★ stuckOnly 是筛选不是判据 —— 筛出来的每一行在全量里也卡着")
    void stuckOnlyIsAFilterNotASecondOpinion() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        JsonNode all = chain(token, false);
        JsonNode stuck = chain(token, true);

        assertThat(stuck.size())
                .as("筛出来的比全量还多 —— 两条路径算的不是同一个 stuckAt")
                .isLessThanOrEqualTo(all.size());
        for (JsonNode r : stuck) {
            assertThat(r.get("stuckAt").isNull())
                    .as("stuckOnly 里出现了不卡的行：%s", r.get("entityNo"))
                    .isFalse();
            JsonNode same = rowOf(all, r.get("entityNo").asString());
            assertThat(same).isNotNull();
            assertThat(same.get("stuckAt").asString())
                    .as("同一家商家两条路径给了两个卡点")
                    .isEqualTo(r.get("stuckAt").asString());
        }
    }

    /** 种一家干净的商家：它的链条状态完全由本用例决定 */
    private String seedMerchant() {
        MchEntity m = new MchEntity();
        m.setEntityNo("ECHAIN-" + System.nanoTime());
        m.setName("链条探针商家");
        m.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> merchantMapper.insert(m));
        return m.getEntityNo();
    }

    private void dropMerchant(String entityNo) {
        DataScopeContext.executeWithoutScope(() -> merchantMapper.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, entityNo)));
    }

    private Long seed(String entityNo, String auditStatus, boolean onSale) {
        PrdGoods g = new PrdGoods();
        g.setGoodsNo("GCHAIN-" + System.nanoTime());
        g.setEntityNo(entityNo);
        g.setTitle("链条探针");
        g.setType("GOODS");
        g.setAuditStatus(auditStatus);
        g.setOnSale(onSale);
        g.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(g));
        return g.getId();
    }

    /** 种子必须还原：留着它，别的用例里这家商家的链条凭空多几件品 */
    private void restore(List<Long> ids) {
        DataScopeContext.executeWithoutScope(() -> {
            for (Long id : ids) {
                goodsMapper.deleteById(id);
            }
            return null;
        });
    }
}
