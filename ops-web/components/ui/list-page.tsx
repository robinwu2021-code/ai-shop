"use client";

// 列表页骨架（TDD-支付中心运营页面 §2.3）：标题 + 说明 + 工具栏（筛选、页面级操作、我的草稿）
// + 分页表格 + 操作列 + 抽屉/弹框挂载点。页面文件只写配置与业务映射。
//
//   <ListPage header={<TabHeader tabs={TABS} value={tab} onChange={setTab} />} query={q} page={page} size={size} onPage={...} onSize={...}
//     columns={COLUMNS} rowKey={(r) => r.payOrderNo} actions={ACTIONS} pageActions={[create]}
//     allow={allow} syncUrl={{ resolve: getOrder }} drafts={{ operator, page: "pay-orders", resolve: getOrder }} />
import * as React from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { PagedTable, type PagedQuery } from "./paged-table";
import type { Column } from "./data-table";
import { Toolbar } from "./toolbar";
import { Button } from "./button";
import { HelpNote } from "./help-note";
import { RowActionButtons, useActionHost, type ActionSpec } from "./action-host";
import { draftStore, type DraftListItem } from "@/lib/draft";
import { useI18n } from "@/lib/i18n";
import { fmtTime } from "@/lib/utils";

export interface ListPageProps<T> {
  /**
   * 页头：业务页一律 `<TabHeader>`（单 tab 也走它，传 desc）。由页面传入而不是这里代画，
   * 是因为 `design-tokens.test.ts` 按页面源码判「有没有页头件」—— 页头藏进组件，守卫就看不见。
   */
  header: React.ReactNode;
  /** 默认收起的说明 */
  help?: React.ReactNode;
  /** 页头下、工具栏上的前提 / 风险提示（Notice、ReadOnlyNotice） */
  notice?: React.ReactNode;
  search?: string;
  onSearch?: (v: string) => void;
  searchPlaceholder?: string;
  /** 筛选控件（FilterSelect / TextFilter） */
  filters?: React.ReactNode;
  onExport?: () => void;
  query: PagedQuery<T>;
  page: number;
  size: number;
  onPage: (p: number) => void;
  onSize: (n: number) => void;
  columns: Column<T>[];
  rowKey: (row: T) => string;
  /** 行操作（含查看）。第一个通常是「查看」，点行也会打开它。 */
  actions: ActionSpec<T>[];
  /** 页面级操作（新建等），row 为 null */
  pageActions?: ActionSpec<T>[];
  allow: (perm: string) => boolean;
  /** 抽屉与地址栏同步；resolve 用编号取回对象 */
  syncUrl?: { resolve: (id: string) => T | null | Promise<T | null> };
  /** 「我的草稿」入口；不传则不显示 */
  drafts?: { operator: string; page: string; resolve?: (id: string) => T | null | Promise<T | null> };
  empty?: string;
}

export function ListPage<T>(props: ListPageProps<T>) {
  const { t } = useI18n();
  const all = React.useMemo(() => [...props.actions, ...(props.pageActions ?? [])], [props.actions, props.pageActions]);
  const host = useActionHost<T>({ actions: all, allow: props.allow, getRowId: props.rowKey, syncUrl: props.syncUrl });
  const pageKeys = new Set((props.pageActions ?? []).map((a) => a.key));
  const pageButtons = host.available(null).filter((a) => pageKeys.has(a.key));
  const firstRowAction = props.actions[0]?.key;
  const rowKeys = React.useMemo(() => new Set(props.actions.map((a) => a.key)), [props.actions]);

  const columns: Column<T>[] = [
    ...props.columns,
    { header: t("common.actions"), align: "end", stickyEnd: true, cell: (row) => <RowActionButtons<T> row={row} host={host} only={rowKeys} /> },
  ];

  return (
    <div>
      {props.header}
      {props.help && <HelpNote>{props.help}</HelpNote>}
      {props.notice}
      <Toolbar search={props.search} onSearch={props.onSearch} searchPlaceholder={props.searchPlaceholder} onExport={props.onExport}>
        {props.filters}
        <div className="ms-auto flex items-center gap-2">
          {props.drafts && <DraftsButton {...props.drafts} refreshKey={host.current ? `${host.current.key}` : ""}
            labelOf={(k) => all.find((a) => a.key === k)?.label ?? k}
            onPick={async (item) => {
              const row = item.ref.id && props.drafts?.resolve ? await props.drafts.resolve(item.ref.id) : null;
              host.open(item.ref.action, row);
            }} />}
          {pageButtons.map((a) => (
            <Button key={a.key} size="sm" variant={a.danger ? "destructive" : "default"} onClick={() => host.open(a.key, null)}>{a.label}</Button>
          ))}
        </div>
      </Toolbar>
      <PagedTable<T>
        query={props.query}
        page={props.page}
        size={props.size}
        onPage={props.onPage}
        onSize={props.onSize}
        columns={columns}
        rowKey={props.rowKey}
        empty={props.empty}
        rowProps={firstRowAction ? (row) => ({
          onClick: () => host.open(firstRowAction, row),
          className: "cursor-pointer",
        }) : undefined}
      />
      {host.node}
    </div>
  );
}

/** 工具栏「我的草稿 (n)」：列出本人在本页未提交的草稿，点一条直接打开对应操作。 */
function DraftsButton({ operator, page, labelOf, onPick, refreshKey }: {
  operator: string; page: string; labelOf: (actionKey: string) => string; onPick: (item: DraftListItem) => void;
  /** 抽屉开合时变化：关掉抽屉的那一刻草稿刚落盘，计数要跟着刷新 */
  refreshKey: string;
}) {
  const { t } = useI18n();
  const [open, setOpen] = React.useState(false);
  const [items, setItems] = React.useState<DraftListItem[]>([]);
  // 打开弹框时也现读：草稿是别的抽屉刚写进去的，不能用挂载时的快照
  const refresh = React.useCallback(() => setItems(operator ? draftStore.list(operator, page) : []), [operator, page]);
  React.useEffect(() => { refresh(); }, [refresh, refreshKey]);
  if (!operator) return null;
  return (
    <Dialog.Root open={open} onOpenChange={(o) => { if (o) refresh(); setOpen(o); }}>
      <Dialog.Trigger asChild>
        <Button size="sm" variant="secondary">{items.length ? t("actionKit.myDraftsCount", { n: items.length }) : t("actionKit.myDrafts")}</Button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-[var(--z-dialog)] bg-black/40" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-[var(--z-dialog)] w-[min(28rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-sheet bg-card p-5 shadow-pop">
          <Dialog.Title className="txt-heading mb-3">{t("actionKit.myDrafts")}</Dialog.Title>
          {items.length === 0 ? (
            <p className="txt-body text-muted-foreground">{t("actionKit.myDraftsEmpty")}</p>
          ) : (
            <ul className="divide-y divide-[var(--border)]">
              {items.map((d) => (
                <li key={d.key}>
                  <button type="button" className="flex w-full items-center justify-between gap-3 py-2 text-start hover:bg-accent rounded-field px-2 focus-ring"
                    onClick={() => { setOpen(false); onPick(d); }}>
                    <span className="txt-body">{labelOf(d.ref.action)} · {d.ref.id ?? t("actionKit.newRecord")}</span>
                    <span className="txt-caption text-muted-foreground">{fmtTime(d.savedAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div className="mt-4 flex justify-end">
            <Dialog.Close asChild><Button variant="outline">{t("common.close")}</Button></Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
