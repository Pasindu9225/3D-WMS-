import { Plus } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { formatMoney } from '@/features/layout/units'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'
import { PO_PAGE_SIZE, PO_STATUSES, usePurchaseOrders, type PoStatus } from './api'

const TONE: Record<PoStatus, 'neutral' | 'good' | 'warn' | 'bad'> = {
  DRAFT: 'neutral', APPROVED: 'warn', PARTLY_RECEIVED: 'warn', RECEIVED: 'good', CANCELLED: 'bad',
}

export function PoStatusBadge({ status }: { status: PoStatus }) {
  return <Badge tone={TONE[status]}>{t(`po.status.${status}` as MessageKey)}</Badge>
}

/** Purchase orders of the warehouses this user works in, newest first. */
export function PurchaseOrdersPage() {
  const me = useMe()
  const manage = can(me.data, 'inbound:manage')
  const [status, setStatus] = useState<PoStatus | ''>('')
  const [search, setSearch] = useState('')
  const q = useDebounced(search.trim())
  const [page, setPage] = useState(0)
  const list = usePurchaseOrders(status, q, page)
  const data = list.data

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('po.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('po.hint')}</p>
        </div>
        {manage && <Button asChild><Link to="/app/purchase-orders/new"><Plus /> {t('po.new')}</Link></Button>}
      </div>
      <div className="flex flex-wrap gap-2">
        <Input aria-label={t('po.search')} placeholder={t('po.search')} value={search} maxLength={200} className="max-w-xs"
          onChange={(e) => {
            setSearch(e.target.value)
            setPage(0)
          }} />
        <select aria-label={t('po.col.status')} className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={status}
          onChange={(e) => {
            setStatus(e.target.value as PoStatus | '')
            setPage(0)
          }}>
          <option value="">{t('po.allStatuses')}</option>
          {PO_STATUSES.map((s) => <option key={s} value={s}>{t(`po.status.${s}` as MessageKey)}</option>)}
        </select>
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('po.col.number')}</Th>
            <Th>{t('po.col.supplier')}</Th>
            <Th className="hidden sm:table-cell">{t('po.col.warehouse')}</Th>
            <Th className="hidden md:table-cell">{t('po.col.expected')}</Th>
            <Th className="text-right">{t('po.col.total')}</Th>
            <Th>{t('po.col.status')}</Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((o) => (
            <Tr key={o.id}>
              <td className="font-mono font-medium">
                <Link className="underline-offset-2 hover:underline" to={`/app/purchase-orders/${o.id}`}>{o.number}</Link>
              </td>
              <td>{o.supplierName} <span className="text-xs text-muted-foreground">{o.supplierCode}</span></td>
              <td className="hidden font-mono text-sm sm:table-cell">{o.warehouseCode}</td>
              <td className="hidden text-sm md:table-cell">{o.expectedDate ?? '—'}</td>
              <td className="text-right tabular-nums">{formatMoney(o.totalMinor)}</td>
              <td><PoStatusBadge status={o.status} /></td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{q || status ? t('po.noMatch') : t('po.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {data && data.total > PO_PAGE_SIZE && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{
            previous: t('common.previous'),
            next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
          }} />
      )}
    </div>
  )
}
