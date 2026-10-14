/**
 * 极小的 markdown 渲染器 —— 只认这套内容集用到的语法。
 *
 * 为什么不引 marked / remark：**md 的方言由我们自己定**（content/README.md §三），
 * 用到的是段落、`**粗**`、`` `码` ``、链接、有序/无序列表、表格、`###`、引用 —— 就这些。
 * 引一个通用解析器换来的是一整套我们不会用的语法，以及它对表格与中文换行的自作主张。
 *
 * ⚠️ 内容是我们自己写的、进了 git 的文件，不是用户输入 —— 所以这里不做 XSS 处理，
 * 也不该被复用到渲染外部内容的地方。
 */
import type { ReactNode } from "react";

/** 行内：`**粗**` `` `码` `` `[文字](链接)` */
export function inline(src: string, keyPrefix = ""): ReactNode[] {
  const out: ReactNode[] = [];
  const re = /\*\*([^*]+)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^)]+)\)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let i = 0;
  while ((m = re.exec(src))) {
    if (m.index > last) out.push(src.slice(last, m.index));
    const key = `${keyPrefix}-${i++}`;
    if (m[1]) out.push(<b key={key} className="font-semibold text-ink">{m[1]}</b>);
    else if (m[2])
      out.push(
        <code key={key} className="rounded bg-panel px-1.5 py-0.5 text-[0.92em]">
          {m[2]}
        </code>,
      );
    else
      out.push(
        <a key={key} href={m[4]} className="text-brand-deep underline underline-offset-4">
          {m[3]}
        </a>,
      );
    last = m.index + m[0].length;
  }
  if (last < src.length) out.push(src.slice(last));
  return out;
}

type Block =
  | { kind: "p"; lines: string[] }
  | { kind: "ul" | "ol"; items: string[] }
  | { kind: "h3"; text: string }
  | { kind: "quote"; lines: string[] }
  | { kind: "table"; rows: string[][] };

/** 表格行 `| a | b |` → ["a","b"]；分隔行（`|---|`）由调用方跳过 */
const cells = (line: string) =>
  line
    .replace(/^\||\|$/g, "")
    .split("|")
    .map((c) => c.trim());

export function blocks(src: string): Block[] {
  const out: Block[] = [];
  const lines = src.split("\n");

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    if (!line.trim()) continue;

    if (line.startsWith("### ")) {
      out.push({ kind: "h3", text: line.slice(4).trim() });
    } else if (/^\s*\|/.test(line)) {
      const rows: string[][] = [];
      while (i < lines.length && /^\s*\|/.test(lines[i]!)) {
        const raw = lines[i]!;
        if (!/^\s*\|[\s:|-]+\|\s*$/.test(raw)) rows.push(cells(raw));
        i++;
      }
      i--;
      out.push({ kind: "table", rows });
    } else if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i]!)) {
        items.push(lines[i]!.replace(/^\s*[-*]\s+/, ""));
        i++;
      }
      i--;
      out.push({ kind: "ul", items });
    } else if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i]!)) {
        // 续行：下一行有缩进且不是新条目
        let item = lines[i]!.replace(/^\s*\d+\.\s+/, "");
        while (i + 1 < lines.length && /^\s{2,}\S/.test(lines[i + 1]!)) {
          item += lines[++i]!.trim();
        }
        items.push(item);
        i++;
      }
      i--;
      out.push({ kind: "ol", items });
    } else if (line.startsWith("> ")) {
      const ls: string[] = [];
      while (i < lines.length && lines[i]!.startsWith(">")) {
        ls.push(lines[i]!.replace(/^>\s?/, ""));
        i++;
      }
      i--;
      out.push({ kind: "quote", lines: ls });
    } else {
      const ls: string[] = [];
      while (i < lines.length && lines[i]!.trim() && !/^\s*([-*>|#]|\d+\.)\s/.test(lines[i]!)) {
        ls.push(lines[i]!);
        i++;
      }
      i--;
      out.push({ kind: "p", lines: ls });
    }
  }
  return out;
}

/** 正文渲染。`invert` 用于墨底区块 */
export function Markdown({ src, invert = false }: { src: string; invert?: boolean }) {
  const muted = invert ? "text-white/66" : "text-muted";
  const strong = invert ? "text-white" : "text-ink";
  return (
    <div className={`grid gap-4 ${muted}`}>
      {blocks(src).map((b, i) => {
        switch (b.kind) {
          case "h3":
            return (
              <h3 key={i} className={`text-[17px] font-semibold ${strong}`}>
                {inline(b.text, `h${i}`)}
              </h3>
            );
          case "ul":
            return (
              <ul key={i} className="grid gap-2.5">
                {b.items.map((it, j) => (
                  <li key={j} className="grid grid-cols-[16px_1fr] gap-2.5 text-[15px]">
                    <span
                      className={`mt-[8px] size-1.5 rounded-full ${invert ? "bg-white/50" : "bg-brand"}`}
                      aria-hidden
                    />
                    <span>{inline(it, `u${i}-${j}`)}</span>
                  </li>
                ))}
              </ul>
            );
          case "ol":
            return (
              <ol key={i} className="grid gap-3">
                {b.items.map((it, j) => (
                  <li key={j} className="grid grid-cols-[28px_1fr] gap-3 text-[15px]">
                    <b
                      className={`mt-0.5 grid size-6 place-items-center rounded-full text-[12px] font-bold tabular-nums ${
                        invert ? "bg-white/12 text-white" : "bg-tint text-brand-deep"
                      }`}
                    >
                      {j + 1}
                    </b>
                    <span>{inline(it, `o${i}-${j}`)}</span>
                  </li>
                ))}
              </ol>
            );
          case "quote":
            return (
              <blockquote
                key={i}
                className={`border-l-2 pl-4 text-[14px] ${invert ? "border-white/25" : "border-line-strong"}`}
              >
                {inline(b.lines.join(" "), `q${i}`)}
              </blockquote>
            );
          case "table":
            return <Table key={i} rows={b.rows} invert={invert} />;
          default:
            return (
              <p key={i} className="max-w-[66ch] text-[15.5px]">
                {inline(b.lines.join(""), `p${i}`)}
              </p>
            );
        }
      })}
    </div>
  );
}

/**
 * 表格。窄屏在自己的容器里横滚 —— 页面本身不许出现横向滚动条。
 * 首列当表头（内容集里的表都是「项 | 说明」这种形状）。
 */
function Table({ rows, invert }: { rows: string[][]; invert: boolean }) {
  const line = invert ? "border-white/12" : "border-line";
  return (
    <div className="-mx-[clamp(20px,5vw,64px)] overflow-x-auto px-[clamp(20px,5vw,64px)]">
      <table className="w-full min-w-[520px] border-collapse text-left">
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className={`border-b ${line} align-top`}>
              {r.map((c, j) => (
                <td
                  key={j}
                  className={`py-4 pr-5 text-[15px] ${
                    j === 0 ? `w-[26%] font-semibold ${invert ? "text-white" : "text-ink"}` : ""
                  }`}
                >
                  {inline(c, `t${i}-${j}`)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
