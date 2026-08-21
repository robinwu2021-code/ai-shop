#!/usr/bin/env python3
"""由 Flyway 迁移生成 H2 测试用 schema。

生产走 `db/migration/V*.sql`（MySQL 方言），单测走 H2（快、无外部依赖）。
两份 DDL 手工维护必然漂移，所以这里**按顺序重放迁移**再输出一份合并后的建表脚本：

  CREATE TABLE      → 收进表定义
  ALTER ... RENAME  → 改列名
  ALTER ... ADD     → 加列
  CREATE INDEX      → 丢弃（H2 建表内联不需要；唯一索引除外，转成 CONSTRAINT）

之前这个脚本只认 CREATE TABLE，V6 引入 ALTER 之后就会静默漏掉改名与新列 ——
测试库和生产库结构不一致，而 SchemaDriftTest 又只比对列集合，可能刚好放过。
所以重放是必须的，不是优化。

用法：python3 backend/scripts/gen-test-schema.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION_DIR = ROOT / "backend/shop-app/src/main/resources/db/migration"
OUT = ROOT / "backend/shop-app/src/test/resources/schema-test.sql"

HEADER = """-- 【自动生成，勿手改】由 backend/scripts/gen-test-schema.py 重放 db/migration/V*.sql 得到。
-- 生产是 MySQL 方言；这份是 H2 等价物（去列注释与普通索引，UNIQUE 转 CONSTRAINT）。
-- 与源文件的漂移由 SchemaDriftTest 拦截。

"""



def _h2_type(col: str) -> str:
    """列类型的 H2 兼容映射。

    **JSON → TEXT**：H2 的 JSON 列经 MyBatis 映射成 String 时读回来是**空串**，
    于是「存下去了、字段全没了」，而代码与 SQL 各自看都正常。
    本库其余 JSON 串（title_i18n / spec_groups / featured / out_of_range）
    在建表时本来就写的 TEXT，只有 prd_spec_template.options 用了 JSON —— 
    它让平台规格模板（E27）在测试里从来跑不通。

    **修在生成器而不是产物**：手改 schema-test.sql 会在下一次重新生成时被冲掉，
    这一条已经被覆盖过三次。生产库仍是 JSON，那边没有这个问题。
    """
    return re.sub(r"(?<![\w])JSON(?![\w])", "TEXT", col, flags=re.I)


def main():
    # 可选的输出路径：**先生成到别处比对、确认无误再覆盖**。
    # 这个口子是有来由的：这份产物一度与生成器分叉了很久（生成器根本跑不通，
    # 文件其实在手工维护），而发现分叉的唯一办法就是先生成一份出来 diff ——
    # 直接覆盖的话，分叉会被自己的产物盖掉，再也看不出差在哪。
    out_path = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else OUT

    tables = {}   # name -> list[str] 列/约束定义，保持顺序
    order = []
    seeds = []    # INSERT 种子数据，原样保留
    renames = {}  # 旧表名 -> 新表名（ALTER ... RENAME TO）。种子里的旧名要按它回填

    # **按版本号数字排序**，不是字典序。字典序把 V15 排在 V2 前面，
    # 于是 V15 的 ALTER 在建表之前重放 —— alter_table() 里 tables.get() 拿到 None
    # 就直接 return，**不报错、不提示**，产出一份缺列的 schema。
    # 缺的列只有等某个测试恰好用到它才会暴露，而多数测试用不到。
    for f in sorted(MIGRATION_DIR.glob("V*.sql"),
                    key=lambda p: int(re.match(r"V(\d+)", p.name).group(1))):
        replay(f.read_text(), tables, order, seeds, renames)

    out = [HEADER]
    for name in order:
        out.append(f"CREATE TABLE IF NOT EXISTS {name}\n(\n"
                   + ",\n".join(tables[name]) + "\n);\n")
    if seeds:
        # 种子里的旧表名回填成改名后的新名（见 alter_table 里 RENAME TO 那段）
        fixed = []
        for stmt in seeds:
            for old, new in renames.items():
                stmt = re.sub(rf"\b{re.escape(old)}\b", new, stmt)
            fixed.append(stmt)
        out.append("-- 种子数据\n" + "\n".join(fixed) + "\n")
    out_path.write_text("\n".join(out))
    try:
        shown = out_path.relative_to(ROOT)
    except ValueError:
        shown = out_path
    print(f"wrote {shown}: {len(order)} tables, {len(seeds)} seeds")
    return 0


def strip_comments(sql):
    """剥掉 `--` 行注释，**必须在按分号切分之前做**。

    此前是先切后剥，于是**注释里出现一个分号就会把语句切碎** ——
    V91 的注释里写了一段含分号的正则，生成器当场报「不认识的语句：\\n]*)」，
    而那个报错既不指向 V91，也看不出跟注释有关。

    要认引号：种子数据的 remark 里可能出现 `--`（中文破折号 `——` 是另一个码位，
    不会误伤，但英文场景会）。在字符串字面量内部的 `--` 不是注释。
    """
    out = []
    for line in sql.splitlines():
        in_str = False
        cut = None
        i = 0
        while i < len(line):
            c = line[i]
            if c == "'":
                # SQL 的转义是叠写两个单引号，跳过即可
                if in_str and i + 1 < len(line) and line[i + 1] == "'":
                    i += 2
                    continue
                in_str = not in_str
            elif not in_str and c == "-" and i + 1 < len(line) and line[i + 1] == "-":
                cut = i
                break
            i += 1
        out.append(line[:cut] if cut is not None else line)
    return "\n".join(out)


def replay(sql, tables, order, seeds, renames):
    # 逐语句切分（本项目的迁移脚本里没有存储过程，分号切分是安全的）
    for stmt in [s.strip() for s in strip_comments(sql).split(";") if s.strip()]:
        if not stmt:
            continue
        low = stmt.lower()
        if low.startswith("create table"):
            create_table(stmt, tables, order)
        elif low.startswith("alter table"):
            alter_table(stmt, tables, order, renames)
        elif low.startswith("create unique index"):
            create_unique_index(stmt, tables)
        elif low.startswith("drop table"):
            drop_table(stmt, tables, order)
        elif low.startswith("update ") or low.startswith("delete from"):
            # UPDATE / DELETE 有**两种**，处理方式相反，判据是「有没有 JOIN / SELECT」：
            #
            # 1. **改种子行的**（单表、常量条件）—— 必须重放。
            #    这里原先一律 `pass`，理由是「作用于生产存量数据，H2 测试库是空的」。
            #    那个理由对业务数据成立，对**主数据**不成立：V22 用 UPDATE 停用行业与
            #    授权码，改的正是 V2/V5 用 INSERT 灌进来的种子行，而那些种子就在
            #    这份产物里。跳过的后果是 H2 上七个行业全启用而真库只剩两个 ——
            #    「一期只能选两个行业」那条用例永远失败，且看起来像校验没写对。
            #
            # 2. **回填存量业务数据的**（带 JOIN 或子查询）—— 不能重放，
            #    与 INSERT ... SELECT 同一个理由，外加一条更硬的：它们是 MySQL 方言。
            #    V16 那条 `UPDATE cmt_pickup_point p JOIN mch_store s ...` 在 H2 上
            #    直接语法错（H2 的 UPDATE 不接 JOIN），整个 schema 加载失败。
            #    而它要搬的是存量自提点的归属，H2 测试库里一行都没有 —— 搬无可搬。
            if re.search(r"\b(join|select)\b", low):
                pass
            else:
                seeds.append(stmt + ";")
        elif low.startswith("insert ignore into"):
            # `INSERT IGNORE` 是可重入写法（V72/V74 用它灌权限点与授权），
            # 但 **H2 不认这个 MySQL 关键字**。语义上它就是种子 INSERT，
            # 所以照种子处理，只是把 IGNORE 去掉再落进测试 schema。
            stmt_h2 = re.sub(r"^INSERT\s+IGNORE\s+INTO", "INSERT INTO", stmt, flags=re.I)
            if re.search(r"\bselect\b", low):
                pass
            else:
                seeds.append(stmt_h2 + ";")
        elif low.startswith("insert into"):
            # 用正则而不是 `" select " in low`：回填语句里 SELECT 常常另起一行，
            # 而 low 是原样文本 —— 子串判断会漏掉带换行的写法，然后把回填当种子抄进测试库
            if re.search(r"\bfrom\s+dual\b", low):
                # **`INSERT … SELECT … FROM DUAL WHERE NOT EXISTS (…)` 是可重入的种子，
                # 不是回填。** 数据源是常量（DUAL），不读任何存量表 ——
                # V74 用这个写法灌权限点授权，跳过它的后果是：H2 上 SUPER_ADMIN
                # 只有 82 个功能点而代码期望 104，OpsPermConfigFlowTest 直接红，
                # 而报错看起来像「权限配置写错了」，与生成器毫无关系。
                # H2 跑在 MODE=MySQL 下，认识 FROM DUAL。
                seeds.append(stmt + ";")
            elif _reads_only(low, stmt):
                # **常量派生表也是种子**，与上面 FROM DUAL 同一条理由 ——
                # 数据源是 `FROM (SELECT … UNION ALL …) t`，不读任何存量表；
                # 末尾的 `WHERE NOT EXISTS (SELECT 1 FROM 自己)` 只是可重入判据。
                #
                # 不认它的后果**已经发生过**：V156 那批「场景×通道」种子被当成回填跳掉，
                # 于是有人把整段 H2 等价物**手工抄进产物**（产物开头明明写着「勿手改」）。
                # 下一个人重新生成 → 手抄的那段被冲掉 → 站内信照发、微信订阅消息一条不出，
                # 而报错是 `Expected size: 1 but was: 0`，和 schema 看不出任何关系。
                #
                # 判据用「除目标表外不读任何表」，不用「长得像不像」：
                # 回填语句一定要读别的表（那才是它存在的意义），种子一定不读。
                seeds.append(stmt + ";")
            elif re.search(r"\bselect\b", low):
                # **INSERT ... SELECT 是数据回填，不是种子。**
                #
                # 回填读的是**中间态的表结构** —— V42 的回填从 usr_merchant.address
                # 取值，而同一个迁移随后就把那一列删了。重放到最终 schema 上必然报
                # 「列不存在」，而报错指向的是一个毫不相干的 Controller（上下文起不来）。
                #
                # 何况它的目的是搬运存量数据，而 H2 测试库本来就是空的 —— 搬无可搬。
                pass
            else:
                # 种子数据：H2 建表脚本里保留，测试要用到（如端×品类可售规则的 25 行）
                seeds.append(stmt + ";")
        elif low.startswith("create temporary table") or low.startswith("drop temporary table"):
            # **临时表是迁移过程中的草稿纸，不是结构的一部分。**
            #
            # V72 用 `CREATE TEMPORARY TABLE tmp_rp_identity AS SELECT …` 把「清空重建
            # 之前的自定义角色授权」暂存下来，重建完再 `INSERT … SELECT … FROM tmp_…`
            # 搬回去 —— 与 INSERT ... SELECT 是同一件事：**搬运存量数据**。
            # H2 测试库本来就是空的，搬无可搬；而它的建表体是一条 MySQL 方言的
            # 多表 JOIN 查询，重放到 H2 上只会语法错。
            #
            # 之前这里没有这一支，V72 一进来整个生成器就 SystemExit ——
            # 报错说「不认识的语句」，而真正该说的是「这类语句本来就不该进 schema」。
            pass
        elif low.startswith("create index") or low.startswith("drop index"):
            # 普通索引 H2 测试用不上；DROP INDEX 同理（约束由 CREATE UNIQUE INDEX 重建）
            pass
        else:
            # **不认识的语句必须炸，不能静默跳过。**
            # 这个脚本此前只认 CREATE TABLE 与部分 ALTER，DROP TABLE / DROP COLUMN
            # 全被丢弃 —— 产出的 schema 看着正常，实际多了一张已删的表、
            # 少删了两列。缺的东西只有等某个测试恰好用到才会暴露。
            raise SystemExit(f"✗ 不认识的语句，请在本脚本里实现：\n  {stmt[:120]}")


def create_table(stmt, tables, order):
    m = re.match(r"CREATE TABLE(?: IF NOT EXISTS)?\s+(\w+)\s*\((.*)\)", stmt, re.S | re.I)
    if not m:
        return
    name, body = m.group(1), m.group(2)
    cols = []
    # 先合并续行：列定义可能换行写（如 COMMENT 单独一行），逐行处理会把它当成独立列
    merged = []
    for raw in body.splitlines():
        line = raw.strip()
        if not line or line.startswith("--"):
            continue
        if merged and (line.upper().startswith("COMMENT") or not merged[-1].endswith(",")):
            merged[-1] = merged[-1].rstrip(",") + " " + line
        else:
            merged.append(line)

    for line in merged:
        line = re.sub(r"\s+COMMENT\s+'[^']*'", "", line.rstrip(",")).strip()
        if not line:
            continue
        if re.match(r"^(KEY|INDEX)\s", line, re.I):
            continue
        u = re.match(r"^UNIQUE KEY\s+(\w+)\s*\((.*)\)$", line, re.I)
        if u:
            line = f"CONSTRAINT {u.group(1)} UNIQUE ({u.group(2)})"
        line = _h2_type(line)
        cols.append("    " + line)
    # ENGINE=... 尾巴会粘在最后一行上，切掉
    if cols:
        cols[-1] = re.sub(r"\)?\s*ENGINE\s*=.*$", "", cols[-1], flags=re.I).rstrip()
    tables[name] = cols
    if name not in order:
        order.append(name)


def alter_table(stmt, tables, order, renames):
    m = re.match(r"ALTER TABLE\s+(\w+)\s+(.*)", stmt, re.S | re.I)
    if not m:
        return
    table, action = m.group(1), m.group(2).strip()

    # 表改名：整表挪到新名下。不认它的话，后续针对新表名的 ALTER 全部报
    # 「目标表还不存在」，而真正的原因在几十行之前
    ren_tbl = re.match(r"RENAME TO\s+(\w+)", action, re.I)
    if ren_tbl:
        new_name = ren_tbl.group(1)
        if table not in tables:
            raise SystemExit(f"✗ RENAME TO：源表 {table} 不存在\n  {stmt[:80]}")
        tables[new_name] = tables.pop(table)
        order[order.index(table)] = new_name
        # **种子里的旧表名也要跟着改**。
        #
        # 这个脚本把所有迁移压平成一份最终 schema：建表按最终名生成，
        # 而**更早的 INSERT 用的是改名前的表名** —— 真实数据库按顺序重放没问题
        # （那时表还叫旧名），压平之后就变成「往一张不存在的表插数据」。
        #
        # 症状极难认：Spring 上下文起不来，报错指向一个毫不相干的 Controller。
        # 2026-08-17 撞到一次（msg_template → notify_template），
        # 而 SchemaGeneratorTest 的报错文案早就预言了这个形状。
        renames[table] = new_name
        return

    cols = tables.get(table)
    if cols is None:
        # 静默 return 是这个脚本此前最坏的行为：迁移顺序一错，整批 ALTER 全被丢掉，
        # 而产出的 schema 看上去完全正常。宁可炸。
        raise SystemExit(f"✗ ALTER 的目标表 {table} 还不存在 —— 迁移重放顺序错了？\n  {stmt[:80]}")

    # **一条 ALTER 里可以有多个动作**（MySQL 允许 `MODIFY ..., ADD UNIQUE KEY ...`）。
    # 不切分的话整段当成一个动作，而 MODIFY 的正则是贪婪的 —— 它会把
    # 「, ADD UNIQUE KEY uk_x (c)」一起吞进列定义，产出
    # 「area_no VARCHAR(64) NOT NULL, ADD UNIQUE KEY ...」这样的列行，H2 建表即语法错。
    # 而报错指向的是一个毫不相干的 Controller（上下文起不来），根因在这里。
    for one in _split_actions(action):
        _apply_action(table, one, cols)


def _split_actions(action):
    """按顶层逗号切多动作 ALTER —— 括号内（如 UNIQUE KEY 的列清单）与
    单引号内（COMMENT 文本）的逗号不算分隔符。"""
    parts, buf, depth, quoted = [], [], 0, False
    for ch in action:
        if quoted:
            buf.append(ch)
            if ch == "'":
                quoted = False
            continue
        if ch == "'":
            quoted = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
            continue
        buf.append(ch)
    tail = "".join(buf).strip().rstrip(";")
    if tail:
        parts.append(tail)
    return [p for p in parts if p]


def _apply_action(table, action, cols):

    rename = re.match(r"RENAME COLUMN\s+(\w+)\s+TO\s+(\w+)", action, re.I)
    if rename:
        old, new = rename.group(1), rename.group(2)
        hit = False
        for i, c in enumerate(cols):
            if re.match(rf"^\s*{old}\s", c):
                cols[i] = re.sub(rf"^(\s*){old}(\s)", rf"\g<1>{new}\g<2>", c)
                hit = True
                continue
            # 约束里的列引用也要改名。漏掉这一步的后果不是"少改一处"，而是产出一份
            # **建不起来的 schema**：UNIQUE (merchant_no, channel) 指向一个已经不存在的列，
            # H2 在建表那一刻就报错，整个 Spring 上下文起不来 —— 而错误信息指向的是
            # 一个毫不相干的 Controller，很难看出根因在生成器上。
            if re.match(r"^\s*(UNIQUE|KEY|CONSTRAINT|PRIMARY|INDEX)\b", c, re.I):
                cols[i] = re.sub(rf"(?<![\w]){old}(?![\w])", new, c)
        if not hit:
            raise SystemExit(f"✗ RENAME COLUMN {table}.{old}：这一列本来就不存在")
        return

    # MODIFY COLUMN：改类型与可空性。**不重放的话测试库停在建表当天的定义** ——
    # V46 把 user_no 从 NOT NULL 改成可空，而测试库仍是 NOT NULL，
    # 于是「员工不必有 C 端账号」这条在真库成立、在测试里插不进去。
    # 两边结构分叉是最难查的一类差异：代码没错，只有测试环境炸。
    mod = re.match(r"MODIFY COLUMN\s+(\w+)\s+(.+)", action, re.I | re.S)
    if mod:
        col, rest = mod.group(1), mod.group(2).strip()
        # 只取类型与 NULL/NOT NULL / DEFAULT，丢掉 COMMENT（H2 不需要）
        rest = re.sub(r"\s*COMMENT\s+'(?:[^']|'')*'", "", rest, flags=re.I).strip().rstrip(";")
        for i, c in enumerate(cols):
            if re.match(rf"^\s*{col}\s", c):
                cols[i] = f"    {col} {rest}"
                break
        else:
            raise SystemExit(f"✗ MODIFY COLUMN {table}.{col}：这一列本来就不存在")
        return

    drop = re.match(r"DROP COLUMN\s+(\w+)", action, re.I)
    if drop:
        col = drop.group(1)
        before = len(cols)
        cols[:] = [c for c in cols if not re.match(rf"^\s*{col}\s", c)]
        if len(cols) == before:
            raise SystemExit(f"✗ DROP COLUMN {table}.{col}：这一列本来就不存在")
        return

    add = re.match(r"ADD COLUMN\s+(?:IF NOT EXISTS\s+)?(.*)", action, re.I | re.S)
    if add:
        # ↑ `IF NOT EXISTS` 要在这里吃掉。不吃的话它会当成**列名**落进建表语句
        #   （`IF NOT EXISTS archived_at DATETIME ...`），H2 建表即语法错。
        col = re.sub(r"\s+COMMENT\s+'[^']*'", "", add.group(1).strip())
        # `AFTER x` / `FIRST` 是 MySQL 的列序语法，**H2 不认**，而且这里也不需要它 ——
        # 我们在重建 CREATE TABLE，列序由下面的插入位置决定。
        # 不剥的话它原样落进建表语句（`grid VARCHAR(64) DEFAULT NULL AFTER city_code`），
        # H2 在建表那一刻就报错，整个 Spring 上下文起不来 ——
        # 而错误信息指向的是一个毫不相干的 Controller，很难看出根因在生成器上。
        col = re.sub(r"\s+(AFTER\s+\w+|FIRST)\s*$", "", col, flags=re.I).strip()
        # 插在最后一个业务列之后。**PRIMARY KEY 也算约束行** ——
        # 只认 CONSTRAINT 的话，新列会插到 `PRIMARY KEY (id)` 后面，
        # 建表语句里出现「主键声明之后又冒出一列」，同样建不起来。
        idx = next((i for i, c in enumerate(cols)
                    if re.match(r"^\s*(CONSTRAINT|PRIMARY\s+KEY|UNIQUE)\b", c, re.I)), len(cols))
        cols.insert(idx, "    " + col)
        return

    # ALTER 里的唯一键增删。**必须实现，不能靠「反正 H2 用不上索引」糊过去** ——
    # 唯一键不是索引，它是约束：漏掉一次 DROP + ADD，测试库就停在旧的键上，
    # 于是 V14 把 uk_mp_entity_channel 从 (entity_no,pay_channel) 换成
    # (entity_no,pay_channel,store_no) 之后，H2 里仍是两列版，
    # 「一个主体给两家店各进一次件」在测试里必然 DuplicateKey，而生产是好的。
    # 这类漂移最难查：报错指向业务代码，根因在这个脚本里。
    drop_idx = re.match(r"DROP\s+(?:INDEX|KEY)\s+(?:IF\s+EXISTS\s+)?(\w+)", action, re.I)
    if drop_idx:
        name = drop_idx.group(1)
        before = len(cols)
        cols[:] = [c for c in cols
                   if not re.match(rf"^\s*CONSTRAINT\s+{name}\b", c, re.I)]
        if len(cols) == before:
            # 普通索引没被写进建表语句，删不到是正常的；唯一键删不到才是问题，
            # 但这里分不出来，所以只提示不中断
            print(f"  · DROP {name} on {table}: 建表语句里没有同名约束（普通索引则正常）")
        return

    add_uk = re.match(
        r"ADD\s+(?:CONSTRAINT\s+\w+\s+)?UNIQUE(?:\s+(?:KEY|INDEX))?\s*"
        r"(?:IF\s+NOT\s+EXISTS\s+)?(\w+)?\s*\(([^)]+)\)", action, re.I)
    if add_uk:
        name, uk_cols = add_uk.group(1), add_uk.group(2)
        name = name or f"uk_{table}_{re.sub(r'[^a-z0-9]+', '_', uk_cols.lower())}"
        idx = next((i for i, c in enumerate(cols)
                    if re.match(r"^\s*(CONSTRAINT|PRIMARY\s+KEY|UNIQUE)\b", c, re.I)), len(cols))
        cols.insert(idx, f"    CONSTRAINT {name} UNIQUE ({uk_cols.strip()})")
        return

    if re.match(r"ADD\s+(?:INDEX|KEY)\s+(?:IF\s+NOT\s+EXISTS\s+)?\w*\s*\(", action, re.I):
        # 普通索引：H2 测试库用不上，与 CREATE INDEX 同样跳过
        return

    # **兜底必须炸。** 这个分支此前是隐式的 `pass` —— 与本脚本开头写的
    # 「不认识的语句必须炸，不能静默跳过」自相矛盾，而唯一键漂移正是它放过去的。
    raise SystemExit(f"✗ 不认识的 ALTER 动作，请在本脚本里实现：\n  {table}: {action[:100]}")


def drop_table(stmt, tables, order):
    m = re.match(r"DROP TABLE(?: IF EXISTS)?\s+(\w+)", stmt, re.I)
    if not m:
        return
    name = m.group(1)
    tables.pop(name, None)
    if name in order:
        order.remove(name)


def create_unique_index(stmt, tables):
    m = re.match(r"CREATE UNIQUE INDEX\s+(\w+)\s+ON\s+(\w+)\s*\((.*)\)", stmt, re.I | re.S)
    if not m:
        return
    name, table, cols_expr = m.group(1), m.group(2), m.group(3)
    if table in tables:
        tables[table].append(f"    CONSTRAINT {name} UNIQUE ({cols_expr.strip()})")


def _reads_only(low: str, stmt: str) -> bool:
    """`INSERT INTO t … SELECT …` 是否**只读它自己**（= 常量种子，不是回填）。

    真表引用取 `FROM x` / `JOIN x` 中 x 不是左括号的那些。`FROM (` 是派生表，
    里面全是常量 SELECT；`FROM t`（目标表自己）出现在可重入的 NOT EXISTS 里。
    只要出现**第三张表**，它就是在搬运存量数据 —— 那种语句重放到最终 schema 上
    会报「列不存在」（读的是中间态结构），且 H2 测试库本来就没有存量可搬。
    """
    if not re.search(r"\bselect\b", low):
        return False
    target = re.match(r"insert\s+(?:ignore\s+)?into\s+([\w.]+)", low)
    if not target:
        return False
    refs = re.findall(r"\b(?:from|join)\s+([\w.]+)", low)
    # 一个真表引用都没有（纯常量 SELECT）同样是种子 —— all([]) 为真，正是要的语义
    return all(r == target.group(1) for r in refs)



if __name__ == "__main__":
    sys.exit(main())
