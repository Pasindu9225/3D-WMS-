import { useState } from 'react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { FormMessage } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useReturns, type ReturnStatus } from './returns'

const TONE: Record<ReturnStatus, 'neutral' | 'good' | 'bad'> = { OPEN: 'neutral', CLOSED: 'good', CANCELLED: 'bad' }

export function ReturnStatusBadge({ status }: { status: ReturnStatus }) {
  return <Badge tone={TONE[status]}>{t(`ret.status.${status}` as MessageKey)}</Badge>
}

/** /app/returns: customer returns waiting for inspection, and closed ones. New returns start from a shipped order. */
export function ReturnsPage() {
  const [done, setDone] = useState(false)
  const list = useReturns(done)
  return (
    <div className="grid gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('ret.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('ret.hint')}</p>
      </div>
      <div role="radiogroup" aria-label={t('counts.show')} className="flex gap-1 text-sm">
        {[false, true].map((d) => (
          <button key={String(d)} type="button" role="radio" aria-checked={done === d} onClick={() => setDone(d)}
            className={`rounded px-3 py-1.5 font-medium ${done === d ? 'bg-primary text-primary-foreground' : 'hover:bg-secondary'}`}>
            {t(d ? 'ret.closedTab' : 'ret.openTab')}
          </button>
        ))}
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      <Table>
        <thead>
          <tr><Th>{t('ret.col.number')}</Th><Th>{t('ret.col.order')}</Th><Th>{t('so.col.customer')}</Th>
            <Th className="text-right">{t('ret.col.toInspect')}</Th><Th>{t('po.col.status')}</Th></tr>
        </thead>
        <tbody>
          {list.data?.items.map((r) => (
            <Tr key={r.id}>
              <td className="font-mono font-medium"><Link className="hover:underline" to={`/app/returns/${r.id}`}>{r.number}</Link></td>
              <td className="font-mono text-sm">{r.orderNumber}</td>
              <td>{r.customerName}</td>
              <td className="text-right tabular-nums">{r.open} / {r.lines}</td>
              <td><ReturnStatusBadge status={r.status} /></td>
            </Tr>
          ))}
          {list.data && list.data.items.length === 0 && <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">{t('ret.empty')}</td></tr>}
        </tbody>
      </Table>
    </div>
  )
}
