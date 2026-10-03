-- 门店额度整条阶梯上移：FREE 1→3、PRO 3→10、CHAIN 10→30。
--
-- 起因是 FREE 的 1 家太紧：一个街边小店老板想在隔壁街开第二家，就被挡住去看套餐 ——
-- 而他那时还没赚到钱。1 家在「刚开始用」这个阶段不是额度，是劝退。
--
-- **为什么三档一起抬，而不是只抬 FREE**：只把 FREE 抬到 3，它就和 PRO 相等了
-- （PRO 现在正是 3 家）—— 门店数量这个最直观的升级理由当场消失，
-- PRO 只剩子账号额度与跨店总览。整条阶梯上移之后，档位之间仍然是 3 倍关系。
--
-- ⚠️ **这是 DML**。gen-test-schema.py 会重放单表常量条件的 UPDATE（见该脚本对
-- 「改种子行」与「回填存量」两类的区分），所以测试库跟着变；但生产上仍要在
-- 预发库确认一次，与 V150 尾部那条回填同一个理由。

-- ── 档位定义：只影响之后新订阅的人 ──
UPDATE sys_merchant_plan_def SET store_quota = 3,  updated_at = NOW() WHERE plan_code = 'FREE';
UPDATE sys_merchant_plan_def SET store_quota = 10, updated_at = NOW() WHERE plan_code = 'PRO';
UPDATE sys_merchant_plan_def SET store_quota = 30, updated_at = NOW() WHERE plan_code = 'CHAIN';

-- ── 存量订阅的额度快照，一并抬上来 ──
--
-- `mch_entity_plan.store_quota` 是**快照**，不是每次去查档位定义 ——
-- 那个机制是为了防「运营下调档位时，已开 3 家店的商家突然有一家变只读」。
-- 它防的是**下调**：往上抬永远不会弄坏任何人，所以这里直接抬。
--
-- 不抬的话，这次放开只对新入驻的人生效，而抱怨「只能开一家」的正是存量商家。
--
-- **只抬还停在旧默认值的那些**：手工授过额度、或按别的档位定义快照过的，
-- 那是单独谈过的数，不该被这条批量语句冲掉（自定义额度另有 store_quota_override 列）。
UPDATE mch_entity_plan SET store_quota = 3,  updated_at = NOW() WHERE plan_code = 'FREE'  AND store_quota = 1;
UPDATE mch_entity_plan SET store_quota = 10, updated_at = NOW() WHERE plan_code = 'PRO'   AND store_quota = 3;
UPDATE mch_entity_plan SET store_quota = 30, updated_at = NOW() WHERE plan_code = 'CHAIN' AND store_quota = 10;
