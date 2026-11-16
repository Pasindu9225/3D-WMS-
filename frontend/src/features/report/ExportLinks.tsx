import { Download } from 'lucide-react'
import { can, useMe } from '@/features/auth/session'
import { t } from '@/lib/i18n'

/**
 * "Excel" and "PDF" download links for one export, carrying the screen's filters; only for people who may export.
 * Plain links: the browser downloads the file the server streams.
 */
export function ExportLinks({ what, params }: { what: 'stock' | 'movements' | 'orders'; params: Record<string, string | undefined> }) {
  const me = useMe()
  if (!can(me.data, 'reports:export')) return null
  const query = new URLSearchParams(Object.entries(params).filter((e): e is [string, string] => !!e[1])).toString()
  const href = (format: 'xlsx' | 'pdf') => `/api/v1/exports/${what}.${format}${query ? `?${query}` : ''}`
  const link = 'inline-flex h-9 items-center gap-1 rounded-md border px-3 text-sm hover:bg-secondary'
  return (
    <span className="flex gap-2">
      <a className={link} href={href('xlsx')} download aria-label={t('export.excelOf', { what: t(`export.what.${what}`) })}>
        <Download className="size-4" /> {t('export.excel')}
      </a>
      <a className={link} href={href('pdf')} download aria-label={t('export.pdfOf', { what: t(`export.what.${what}`) })}>
        <Download className="size-4" /> {t('export.pdf')}
      </a>
    </span>
  )
}
