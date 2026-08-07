"use client";

// 列表页统一工具条：搜索框 + 筛选槽(children) + 右侧操作（导出 / 新增）。
// selectedCount > 0 时整条替换为「批量操作条」（静态色块，不用浮层——静态导出下简单可靠）。
import * as React from "react";
import { Plus, Download, X } from "lucide-react";
import { Input } from "./input";
import { Button } from "./button";
import { chipsFrom } from "./filter-chip";
import { SEARCH_DEBOUNCE_MS } from "@/lib/constants";
import { useI18n } from "@/lib/i18n";

export function Toolbar({
  search, onSearch, searchPlaceholder, children, onAdd, addLabel, canAdd = true,
  onExport, exportLabel,
  selectedCount = 0, batchActions, onClearSelection,
}: {
  search?: string;
  onSearch?: (v: string) => void;
  searchPlaceholder?: string;
  children?: React.ReactNode; // 额外筛选（Select 等）
  onAdd?: () => void;
  addLabel?: string;
  canAdd?: boolean;
  /** 传了才显示「导出」按钮（次要样式，位于新增左侧） */
  onExport?: () => void;
  exportLabel?: string;
  /** >0 时切换为批量操作条 */
  selectedCount?: number;
  batchActions?: React.ReactNode;
  onClearSelection?: () => void;
}) {
  const { t } = useI18n();

  if (selectedCount > 0) {
    return (
      <div className="mb-4 flex flex-wrap items-center gap-2 rounded-card bg-accent px-3.5 py-2">
        <span className="txt-strong">{t("table.selectedN", { n: selectedCount })}</span>
        <div className="flex flex-wrap items-center gap-2">{batchActions}</div>
        {onClearSelection && (
          <Button size="sm" variant="ghost" className="ms-auto" onClick={onClearSelection}>
            <X className="size-4" /> {t("table.clearSelection")}
          </Button>
        )}
      </div>
    );
  }

  return (
    <>
    <div data-surface="toolbar" className="mb-4 flex flex-wrap items-center gap-2">
      {onSearch !== undefined && (
        <SearchBox value={search ?? ""} onChange={onSearch} placeholder={searchPlaceholder ?? t("common.search")} />
      )}
      {children}
      {onExport && (
        <Button size="sm" variant="secondary" className="ms-auto" onClick={onExport}>
          <Download className="size-4" /> {exportLabel ?? t("export.label")}
        </Button>
      )}
      {onAdd && canAdd && (
        <Button size="sm" className={onExport ? undefined : "ms-auto"} onClick={onAdd}>
          <Plus className="size-4" /> {addLabel ?? t("common.add")}
        </Button>
      )}
    </div>
    <FilterChips search={search} onSearch={onSearch} filters={children} />
    </>
  );
}

/**
 * 搜索框：**受控值 + 防抖 + 清空按钮**。
 *
 * 为什么要在组件里做防抖：此前每敲一个字符就 `setKeyword` 一次，而 keyword 进了 queryKey，
 * 于是"商家"两个字会连发好几次请求（列表页实测 300ms 内多次）。接了真后端就是几倍的无谓查询，
 * 而且回包乱序时列表会闪。本地 state 立刻回显（输入不卡顿），只把**对外的通知**押后。
 *
 * 清空按钮同理是必需的：清空搜索是高频动作，全选再删是三步操作。
 */
function SearchBox({
  value, onChange, placeholder,
}: { value: string; onChange: (v: string) => void; placeholder: string }) {
  const { t } = useI18n();
  const [local, setLocal] = React.useState(value);
  const timer = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  // 外部改了值（切 tab 清空、点 chip 的 ×）要同步回来，否则框里还留着旧词
  React.useEffect(() => { setLocal(value); }, [value]);
  React.useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);

  const push = (v: string, immediate = false) => {
    setLocal(v);
    if (timer.current) clearTimeout(timer.current);
    if (immediate) { onChange(v); return; }
    timer.current = setTimeout(() => onChange(v), SEARCH_DEBOUNCE_MS);
  };

  return (
    <div className="relative w-60">
      <Input
        className="pe-8"
        placeholder={placeholder}
        value={local}
        onChange={(e) => push(e.target.value)}
        // 回车 = 立即查，不等防抖：用户已经明确表达"就现在"
        onKeyDown={(e) => { if (e.key === "Enter") push(local, true); }}
      />
      {local && (
        <button
          type="button"
          aria-label={t("form.clear")}
          onClick={() => push("", true)}
          className="absolute inset-y-0 end-2 my-auto size-5 rounded-control text-muted-foreground transition-colors hover:text-foreground focus-ring"
        >
          <X className="mx-auto size-3.5" />
        </button>
      )}
    </div>
  );
}

/**
 * 生效中的筛选条件回显。
 *
 * **为什么不让页面自己传一份 chips**：那就成了「筛选器一处、回显一处」两份真相，
 * 加个筛选项忘了加回显是必然的。这里直接问 `children` 里的控件自己（`toChip`，
 * 见 `filter-chip.ts`），用它自身的 value / options / onChange 生成 chip —— 只有一份真相。
 *
 * 解决的是：下拉框选中态在一排「全部 XX」里很不显眼，翻了两页发现数不对，
 * 回头才看见三个筛选里有一个没清 —— 而清空还得一个一个点回「全部」。
 */
function FilterChips({
  search, onSearch, filters,
}: { search?: string; onSearch?: (v: string) => void; filters?: React.ReactNode }) {
  const { t } = useI18n();

  const active: { key: string; label: string; clear: () => void }[] = [];
  if (search) active.push({ key: "__search", label: t("table.filterChip", { name: t("common.search"), value: search }), clear: () => onSearch?.("") });
  chipsFrom(filters).forEach((c, i) =>
    active.push({ key: `f${i}`, label: c.name ? t("table.filterChip", { name: c.name, value: c.label }) : c.label, clear: c.clear }),
  );

  if (active.length === 0) return null;

  return (
    <div className="-mt-2 mb-4 flex flex-wrap items-center gap-2">
      {active.map((f) => (
        <button
          key={f.key}
          type="button"
          className="inline-flex items-center gap-1 rounded-chip bg-accent px-2.5 py-1 txt-caption transition-colors hover:bg-secondary focus-ring"
          onClick={f.clear}
        >
          {f.label}
          <X className="size-3.5 opacity-60" />
        </button>
      ))}
      {active.length > 1 && (
        <button
          type="button"
          className="rounded-field px-1.5 py-1 txt-caption text-muted-foreground underline-offset-2 transition-colors hover:text-foreground hover:underline focus-ring"
          onClick={() => active.forEach((f) => f.clear())}
        >
          {t("table.clearFilters")}
        </button>
      )}
    </div>
  );
}
