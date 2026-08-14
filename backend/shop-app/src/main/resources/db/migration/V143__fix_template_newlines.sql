-- 修 V141 三条模板正文里的字面 `\n`。
--
-- **为什么不直接改 V141**：V141 已经落到共享 dev 库了。改一条已应用的迁移
-- 会让 Flyway 的 checksum 对不上，下一个启动后端的人（可能不是我）直接起不来。
-- 已应用的迁移是只读的，修正只能靠新迁移。
--
-- **原因**：MariaDB 把字符串字面量里的反斜杠当转义（`\n` → 真换行），
-- H2 不当（`\n` → 字面的反斜杠加 n）。于是同一份种子在生产是分行的、
-- 在测试库是一整行 —— 两边行为不一致时，测试绿不能说明生产对。
-- 正确写法是在 SQL 里直接敲真换行，两个方言都不需要转义（V142 已照此写）。
--
-- **为什么不加 WHERE 条件只修坏的那些**：要判断「内容里有没有字面反斜杠」，
-- 就得在 LIKE 里写反斜杠 —— 而那正是两个方言写法不同的地方，等于把刚踩的坑
-- 再踩一遍。无条件 UPDATE 是幂等的，重复执行结果一样。
--
-- 代价：会覆盖运营对这三条模板的改动。可接受 —— 它们与 V141 同批次落地，
-- 至今只在 dev 库存在过，没有任何人编辑过的窗口。

UPDATE msg_template SET content = '{subject}

{body}' WHERE template_no = 'TPL_MAIL_TEST';

UPDATE msg_template SET content = '{subject}
{body}' WHERE template_no = 'TPL_PUSH_TEST';

UPDATE msg_template SET content = '{subject}
{body}' WHERE template_no = 'TPL_INAPP_TEST';
