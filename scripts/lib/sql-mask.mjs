/**
 * 把 SQL 里的**注释与字符串字面量**盖成空格，长度与行号保持不变。
 *
 * <h2>为什么需要它</h2>
 * 2026-08-30：`check-sql-portability` 匹配整份文件的字面串，不剥注释。
 * 于是有人在迁移注释里解释「为什么这张表和旁边 19 张不一样」、
 * 顺手写出了那个不该用的排序规则名，闸门就红了 —— 而那一行永远不会被执行。
 *
 * 后果不只是误报烦人：**它让人不敢在迁移注释里写出错误示例**，
 * 而「为什么不能这么写」恰恰最该写在离现场最近的地方。
 * 一道惩罚反例注释的闸门，是在系统性地删掉最有价值的那类注释。
 *
 * <h2>为什么盖成空格而不是删掉</h2>
 * 调用方用 `src.slice(0, m.index)` 数换行来报行号。删字符会让行号整体错位，
 * 而**报错行号错了的闸门比没有闸门更费时间** —— 人会照着那一行去找，找不到，
 * 然后开始怀疑闸门以外的一切。等长替换让下游一行都不用改。
 *
 * <h2>三处刻意的取舍</h2>
 * <ul>
 *   <li><b>`--` 只有后面跟空白或行尾才算注释</b> —— 这是 MariaDB 的真实规则。
 *       `--中文` 在 MariaDB 里是语法错，不是注释；把它当注释盖掉，
 *       等于替一段跑不起来的 SQL 打掩护。仓库里目前**没有**守这条的闸，
 *       所以这里更不能顺手放行。</li>
 *   <li><b>字符串字面量也盖掉</b> —— `COMMENT='...'` 里的文字与注释同性质：
 *       不会被当成 SQL 执行。不盖的话，中文表注释里出现一个规则关键词就误报，
 *       而那正是这个仓库最常见的写法。</li>
 *   <li><b>`#` 也算行注释</b> —— MySQL 与 MariaDB 都认。</li>
 * </ul>
 *
 * <p><b>这个函数不会让闸门放宽</b>：被盖掉的每一段都是「不会被数据库执行的字符」。
 * 判据见 {@code packages/shared/tests/sql-mask.test.ts}，
 * 其中有一条对照断言：同一个方言关键词写在真 SQL 里必须仍然被抓到 ——
 * 没有那条，一个「把整份文件都盖成空格」的实现也能让其余断言全绿。
 */

/** @param {string} src @returns {string} 等长、等行数，注释与字符串已变成空格 */
export function maskSqlNoise(src) {
  const out = src.split("");
  const n = src.length;
  let i = 0;

  /** 从 i 起把整行盖掉（保留换行符本身） */
  const maskToEol = () => {
    while (i < n && src[i] !== "\n") {
      out[i] = " ";
      i++;
    }
  };

  while (i < n) {
    const c = src[i];

    // 字符串 / 反引号标识符：整段跳过并盖掉内容
    if (c === "'" || c === '"' || c === "`") {
      const quote = c;
      out[i] = " ";
      i++;
      while (i < n) {
        if (src[i] === "\\") {
          // 反斜杠转义：两个字符一起吃掉
          if (src[i] !== "\n") out[i] = " ";
          if (i + 1 < n && src[i + 1] !== "\n") out[i + 1] = " ";
          i += 2;
          continue;
        }
        if (src[i] === quote) {
          // SQL 里 '' 表示一个引号，不是结束
          if (src[i + 1] === quote) {
            out[i] = " ";
            out[i + 1] = " ";
            i += 2;
            continue;
          }
          out[i] = " ";
          i++;
          break;
        }
        if (src[i] !== "\n") out[i] = " ";
        i++;
      }
      continue;
    }

    // -- 行注释（**后面必须是空白或行尾**，见类注释）
    if (c === "-" && src[i + 1] === "-") {
      const after = src[i + 2];
      if (after === undefined || after === " " || after === "\t"
          || after === "\n" || after === "\r") {
        maskToEol();
        continue;
      }
    }

    // # 行注释
    if (c === "#") {
      maskToEol();
      continue;
    }

    // /* 块注释 */（可跨行，换行符保留）
    if (c === "/" && src[i + 1] === "*") {
      out[i] = " ";
      out[i + 1] = " ";
      i += 2;
      while (i < n && !(src[i] === "*" && src[i + 1] === "/")) {
        if (src[i] !== "\n") out[i] = " ";
        i++;
      }
      if (i < n) {
        out[i] = " ";
        out[i + 1] = " ";
        i += 2;
      }
      continue;
    }

    i++;
  }

  return out.join("");
}
