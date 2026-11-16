import { FileUp, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { ImportDialog } from '@/features/catalog/ImportDialog'
import { ExportLinks } from '@/features/report/ExportLinks'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'
import { SO_PAGE_SIZE, SO_STATUSES, useSalesOrders, type SoStatus } from './api'

const TONE: Record<SoStatus, 'neutral' | 'good' | 'warn' | 'bad'> = {
  DRAFT: 'neutral', RELEASED: 'warn', PICKING: 'warn', PICKED: 'warn', PACKED: 'warn', SHIPPED: 'good', CANCELLED: 'bad',
}

export function SoStatusBadge({ status }: { status: SoStatus }) {
  return <Badge tone={TONE[status]}>{t(`so.status.${status}` as MessageKey)}</Badge>
}

/** Sales orders of the warehouses this user works in, newest first. */
export function SalesOrdersPage() {
  const me = useMe()
  const manage = can(me.data, 'outbound:manage')
  const [status, setStatus] = useState<SoStatus | ''>('')
  const [search, setSearch] = useState('')
  const q = useDebounced(search.trim())
  const [page, setPage] = useState(0)
  const [importing, setImporting] = useState(false)
  const list = useSalesOrders(status, q, page)
  const data = list.data

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('so.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('so.hint')}</p>
        </div>
        {manage && (
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setImporting(true)}><FileUp /> {t('import.button')}</Button>
            <Button asChild><Link to="/app/sales-orders/new"><Plus /> {t('so.new')}</Link></Button>
          </div>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        <Input aria-label={t('so.search')} placeholder={t('so.search')} value={search} maxLength={200} className="max-w-xs"
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }} />
        <select aria-label={t('so.col.status')} className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={status}
          onChange={(e) => {
            setStatus(e.target.value as SoStatus | '')
            setPage(0)
          }}>
          <option value="">{t('po.allStatuses')}</option>
          {SO_STATUSES.map((s) => <option key={s} value={s}>{t(`so.status.${s}` as MessageKey)}</option>)}
        </select>
        <span className="ml-auto"><ExportLinks what="orders" params={{ status: status || undefined, q: q || undefined }} /></span>
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('so.col.number')}</Th>
            <Th>{t('so.col.customer')}</Th>
            <Th className="hidden md:table-cell">{t('so.col.ref')}</Th>
            <Th className="hidden sm:table-cell">{t('po.col.warehouse')}</Th>
            <Th className="hidden md:table-cell">{t('so.col.requested')}</Th>
            <Th className="text-right">{t('so.col.lines')}</Th>
            <Th>{t('so.col.status')}</Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((o) => (
            <Tr key={o.id}>
              <td className="font-mono font-medium">
                <Link className="underline-offset-2 hover:underline" to={`/app/sales-orders/${o.id}`}>{o.number}</Link>
              </td>
              <td>{o.customerName} <span className="text-xs text-muted-foreground">{o.customerCode}</span></td>
              <td className="hidden font-mono text-sm md:table-cell">{o.customerRef ?? '—'}</td>
              <td className="hidden font-mono text-sm sm:table-cell">{o.warehouseCode}</td>
              <td className="hidden text-sm md:table-cell">{o.requestedDate ?? '—'}</td>
              <td className="text-right tabular-nums">{o.lineCount}</td>
              <td><SoStatusBadge status={o.status} /></td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={7} className="py-8 text-center text-muted-foreground">{q || status ? t('po.noMatch') : t('so.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {data && data.total > SO_PAGE_SIZE && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{
            previous: t('common.previous'),
            next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
          }} />
      )}
      {importing && <ImportDialog what="sales-orders" onClose={() => setImporting(false)} />}
    </div>
  )
}
