import { ArrowLeft, Plus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { REASONS, useCreateReturn, useReturnable, type Returnable, type ReturnReason } from './returns'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const SELECT = 'h-10 rounded-md border border-input bg-background px-2'
const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

interface Row { sku: string; qty: string; uom: string; reason: ReturnReason }

/**
 * /app/returns/new/:orderId: what comes back from a shipped order, how much and why. A product can have several
 * lines with different reasons (4 fine, 2 damaged), so each can be inspected on its own. Nothing moves in stock yet.
 */
export function NewReturnPage() {
  const { orderId } = useParams()
  const items = useReturnable(orderId)
  return items.data ? <ReturnForm orderId={orderId!} items={items.data} />
    : items.isError ? <FormMessage tone="error">{errorMessage(items.error)}</FormMessage>
      : <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
}

function ReturnForm({ orderId, items }: { orderId: string; items: Returnable[] }) {
  const navigate = useNavigate()
  const create = useCreateReturn()
  const [rows, setRows] = useState<Row[]>(() => items.map((i) => ({ sku: i.sku, qty: '', uom: i.baseUom, reason: 'NOT_WANTED' })))
  const [note, setNote] = useState('')
  const set = (n: number, patch: Partial<Row>) => setRows((r) => r.map((row, i) => (i === n ? { ...row, ...patch } : row)))
  const chosen = rows.filter((r) => r.qty.trim() !== '')
  const valid = chosen.length > 0 && chosen.every((r) => QTY.test(r.qty.trim()) && Number(r.qty) > 0)
  const bySku = new Map(items.map((i) => [i.sku, i]))
  return (
    <div className="grid max-w-4xl gap-4">
      <Link to={`/app/sales-orders/${orderId}`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('ret.backToOrder')}
      </Link>
      <div>
        <h1 className="text-2xl font-semibold">{t('ret.new')}</h1>
        <p className="text-sm text-muted-foreground">{t('ret.newHint')}</p>
      </div>
      {create.isError && <FormMessage tone="error">{errorMessage(create.error)}</FormMessage>}
      <Table>
        <thead>
          <tr><Th>{t('stock.col.product')}</Th><Th className="text-right">{t('ret.col.canReturn')}</Th><Th>{t('ret.col.qty')}</Th>
            <Th>{t('ret.col.unit')}</Th><Th>{t('ret.col.reason')}</Th><Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th></tr>
        </thead>
        <tbody>
          {rows.map((r, n) => {
            const i = bySku.get(r.sku)!
            const first = rows.findIndex((x) => x.sku === r.sku) === n
            return (
              <Tr key={n}>
                <td>{first ? <><span className="font-mono text-sm font-semibold">{i.sku}</span> {i.productName}</> : <span className="pl-4 text-sm text-muted-foreground">{i.sku}</span>}</td>
                <td className="text-right tabular-nums">{first ? <>{num(i.left)} <span className="text-xs text-muted-foreground">{i.baseUom}</span></> : ''}</td>
                <td><Input aria-label={t('ret.qtyOf', { sku: i.sku })} inputMode="decimal" className="w-24" disabled={i.left <= 0}
                  value={r.qty} onChange={(e) => set(n, { qty: e.target.value })} /></td>
                <td>
                  <select aria-label={t('ret.unitOf', { sku: i.sku })} className={SELECT} value={r.uom} onChange={(e) => set(n, { uom: e.target.value })}>
                    {Object.entries(i.units).map(([u, f]) => <option key={u} value={u}>{f === 1 ? u : `${u} × ${f}`}</option>)}
                  </select>
                </td>
                <td>
                  <select aria-label={t('ret.reasonOf', { sku: i.sku })} className={SELECT} value={r.reason} onChange={(e) => set(n, { reason: e.target.value as ReturnReason })}>
                    {REASONS.map((x) => <option key={x} value={x}>{t(`ret.reason.${x}` as MessageKey)}</option>)}
                  </select>
                </td>
                <td>{first && i.left > 0 && (
                  <Button type="button" variant="ghost" size="icon" aria-label={t('ret.addReason', { sku: i.sku })}
                    onClick={() => setRows((all) => [...all.slice(0, n + 1), { sku: i.sku, qty: '', uom: i.baseUom, reason: 'DAMAGED' }, ...all.slice(n + 1)])}>
                    <Plus />
                  </Button>
                )}</td>
              </Tr>
            )
          })}
        </tbody>
      </Table>
      <Field label={t('ret.note')}>{(p) => <Input {...p} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}</Field>
      <div>
        <Button disabled={!valid || create.isPending} onClick={() => create.mutate(
          { orderId, note: note.trim() || undefined, lines: chosen.map((r) => ({ sku: r.sku, uom: r.uom, qty: Number(r.qty), reason: r.reason })) },
          { onSuccess: (ret) => navigate(`/app/returns/${ret.id}`) },
        )}>{t('ret.create')}</Button>
      </div>
    </div>
  )
}
