"use client";

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import * as Dialog from '@radix-ui/react-dialog';
import { Library, X } from 'lucide-react';
import { can } from '@/lib/permissions';
import { useAuth } from '@/lib/auth';
import { useI18n } from '@/lib/i18n';
import { isLeafDisabled, matchesQuery, visibleLeaves, visibleSections } from '@/lib/nav';
import { useCatalogNavTree, useServerMenu, isPointUnimplemented } from '@/lib/stores/server-menu';

/** Permanent browseable compatibility access; never adds legacy modules to the primary rail. */
export function CompatibilityCatalog({ expanded }: { expanded: boolean }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const pathname = usePathname();
  const search = useSearchParams();
  const perms = useAuth(s => s.perms);
  const hrefs = useServerMenu(s => s.hrefSet);
  const byHref = useServerMenu(s => s.byHref);
  const nav = useCatalogNavTree();
  const { t, tNav } = useI18n();
  useEffect(() => { setOpen(false); }, [pathname, search]);
  const sections = visibleSections(perms, hrefs, nav).map(s => ({ ...s, children: visibleLeaves(s, perms, hrefs)
    .filter(l => matchesQuery([tNav(s.label), tNav(l.group ?? ''), tNav(l.label)].join(' '), query)),
  })).filter(s => s.children.length);

  return <Dialog.Root open={open} onOpenChange={value => { setOpen(value); if (value) setQuery(''); }}>
    <Dialog.Trigger className="focus-ring flex shrink-0 items-center justify-center gap-2 px-2 py-3 text-muted-foreground hover:bg-accent"
      title={tNav('完整功能目录')} aria-label={tNav('完整功能目录')}>
      <Library className="size-5 shrink-0" />{expanded && <span className="txt-caption">{tNav('完整功能目录')}</span>}
    </Dialog.Trigger>
    <Dialog.Portal>
      <Dialog.Overlay className="fixed inset-0 z-[var(--z-dialog)] bg-black/40" />
      <Dialog.Content className="fixed left-1/2 top-[8vh] z-[var(--z-dialog)] flex max-h-[84vh] w-[min(94vw,900px)] -translate-x-1/2 flex-col rounded-sheet bg-card p-5 shadow-pop">
        <div className="flex items-center justify-between gap-4">
          <Dialog.Title className="txt-strong">{tNav('完整功能目录')}</Dialog.Title>
          <Dialog.Close aria-label={t('common.close')} className="focus-ring rounded-field p-1"><X className="size-4" /></Dialog.Close>
        </div>
        <Dialog.Description className="mt-2 txt-caption text-muted-foreground">{tNav('原业务入口保留，按既有授权访问')}</Dialog.Description>
        <input value={query} onChange={e => setQuery(e.target.value)} placeholder={t('nav.searchPlaceholder')} aria-label={t('nav.search')}
          className="focus-ring my-4 rounded-field border border-border bg-transparent px-3 py-2" />
        <div className="min-h-0 overflow-y-auto">
          {can(perms, 'dashboard:overview:read') && matchesQuery(tNav('工作台'), query) && <Link href="/" onClick={() => setOpen(false)} className="focus-ring mb-4 block rounded-field px-3 py-2 txt-body hover:bg-accent">{tNav('工作台')}</Link>}
          {sections.map(s => <section key={s.key} className="mb-4">
            <h2 className="mb-2 txt-strong">{tNav(s.label)}</h2>
            <div className="grid gap-1 sm:grid-cols-2 lg:grid-cols-3">
              {s.children.map(l => isLeafDisabled(l) || isPointUnimplemented(byHref, l.href)
                ? <span key={l.href} aria-disabled className="rounded-field px-3 py-2 txt-body text-muted-foreground/50">{tNav(l.label)} · {t('nav.notImplemented')}</span>
                : <Link key={l.href} href={l.href} onClick={() => setOpen(false)} className="focus-ring rounded-field px-3 py-2 txt-body hover:bg-accent">{tNav(l.label)}</Link>)}
            </div>
          </section>)}
          {!sections.length && <p className="py-4 txt-body text-muted-foreground">{t('nav.searchEmpty')}</p>}
        </div>
      </Dialog.Content>
    </Dialog.Portal>
  </Dialog.Root>;
}
