import { Check, Plus, X } from 'lucide-react'
import { useRef, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import type { Product } from '@/features/catalog/api'
import { formatMoney } from '@/features/layout/units'
import { useWarehouses } from '@/features/warehouses/api'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { REASONS, useAdjustmentAction, useAdjustments, type Adjustment, type AdjustmentReason, type AdjustmentStatus } from './api'
import { ProductChooser } from './ProductChooser'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const SELECT = 'h-10 w-full rounded-md border border-input bg-background px-3 text-sm'
const TONE = { PENDING: 'warn', APPLIED: 'good', REJECTED: 'bad' } as const

/** /app/adjustments: stock found, lost or damaged, with a reason; big ones wait for a manager. */
export function AdjustmentsPage() {
  const me = useMe()
  const mayAsk = can(me.data, 'inventory:adjust')
  const mayDecide = can(me.data, 'inventory:approve')
  const [status, setStatus] = useState<AdjustmentStatus | ''>('')
  const [page, setPage] = useState(0)
  const list = useAdjustments(status, page)
  const [asking, setAsking] = useState(false)
  const [rejecting, setRejecting] = useState<Adjustment | null>(null)
  const decide = useAdjustmentAction()
  const data = list.data

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('adjust.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('adjust.hint')}</p>
        </div>
        {mayAsk && <Button onClick={() => setAsking(true)}><Plus /> {t('adjust.new')}</Button>}
      </div>
      <select aria-label={t('po.col.status')} className={`${SELECT} max-w-xs`} value={status} onChange={(e) => {
        setStatus(e.target.value as AdjustmentStatus | '')
        setPage(0)
      }}>
        <option value="">{t('po.allStatuses')}</option>
        {(['PENDING', 'APPLIED', 'REJECTED'] as const).map((s) => <option key={s} value={s}>{t(`adjust.status.${s}` as MessageKey)}</option>)}
      </select>
      {(list.isError || decide.isError) && <FormMessage tone="error">{errorMessage(list.error ?? decide.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('adjust.col.number')}</Th>
            <Th>{t('adjust.col.what')}</Th>
            <Th className="hidden md:table-cell">{t('adjust.col.where')}</Th>
            <Th className="text-right">{t('adjust.col.value')}</Th>
            <Th>{t('po.col.status')}</Th>
            <Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((a) => (
            <Tr key={a.id}>
              <td className="font-mono text-sm">{a.number}</td>
              <td className="text-sm">
                <span className={a.direction === 'OUT' ? 'text-destructive' : 'text-emerald-600'}>{a.direction === 'OUT' ? '−' : '+'}{a.qty} {a.baseUom}</span>
                {' '}<span className="font-mono text-xs">{a.sku}</span> {a.productName}
                <div className="text-xs text-muted-foreground">
                  {t(`adjust.reason.${a.reason}` as MessageKey)}{a.note ? `: ${a.note}` : ''} · {a.requestedBy}
                  {a.decisionNote ? ` · ${a.decidedBy}: ${a.decisionNote}` : ''}
                </div>
              </td>
              <td className="hidden font-mono text-xs md:table-cell">{a.warehouseCode} · {a.binCode ?? t('move.receiving')}</td>
              <td className="text-right tabular-nums">{a.valueMinor === null ? t('adjust.noCost') : formatMoney(a.valueMinor)}</td>
              <td><Badge tone={TONE[a.status]}>{t(`adjust.status.${a.status}` as MessageKey)}</Badge></td>
              <td className="whitespace-nowrap text-right">
                {mayDecide && a.status === 'PENDING' && (
                  <>
                    <Button variant="ghost" size="icon" aria-label={t('adjust.approve', { number: a.number })} disabled={decide.isPending}
                      onClick={() => decide.mutate({ kind: 'approve', id: a.id, version: a.version })}><Check /></Button>
                    <Button variant="ghost" size="icon" aria-label={t('adjust.reject', { number: a.number })}
                      onClick={() => setRejecting(a)}><X /></Button>
                  </>
                )}
              </td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{t('adjust.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {data && data.total > data.size && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{
            previous: t('common.previous'), next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
          }} />
      )}
      {asking && <AskDialog onClose={() => setAsking(false)} />}
      {rejecting && <RejectDialog adjustment={rejecting} onClose={() => setRejecting(null)} />}
    </div>
  )
}

function AskDialog({ onClose }: { onClose: () => void }) {
  const warehouses = useWarehouses()
  const action = useAdjustmentAction()
  const [warehouseId, setWarehouseId] = useState('')
  const wh = warehouseId || warehouses.data?.items[0]?.id
  const [product, setProduct] = useState<Product | null>(null)
  const [bin, setBin] = useState('')
  const [direction, setDirection] = useState<'IN' | 'OUT'>('OUT')
  const [qty, setQty] = useState('')
  const [reason, setReason] = useState<AdjustmentReason>('DAMAGED')
  const [note, setNote] = useState('')
  const [lot, setLot] = useState(false)
  const [batch, setBatch] = useState('')
  const [expiry, setExpiry] = useState('')
  const [result, setResult] = useState<Adjustment | null>(null)
  const key = useRef(crypto.randomUUID())
  const changed = () => {
    key.current = crypto.randomUUID()
  }
  const qtyOk = QTY.test(qty.trim()) && Number(qty) > 0
  const ready = !!wh && !!product && qtyOk && !action.isPending

  if (result) {
    return (
      <Dialog open onOpenChange={(o) => !o && onClose()} title={result.number}>
        <FormMessage tone="success">{t(result.status === 'APPLIED' ? 'adjust.applied' : 'adjust.waiting')}</FormMessage>
        <div className="flex justify-end"><Button onClick={onClose}>{t('import.close')}</Button></div>
      </Dialog>
    )
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('adjust.new')} description={t('adjust.formHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (!ready || !wh || !product) return
        action.mutate({
          kind: 'request', key: key.current, body: {
            warehouseId: wh, productId: product.id, direction, qty: Number(qty), reason,
            ...(bin.trim() ? { binCode: bin.trim().toUpperCase() } : {}),
            ...(note.trim() ? { note: note.trim() } : {}),
            ...(lot && batch.trim() ? { batchNo: batch.trim() } : {}),
            ...(lot && expiry ? { expiryDate: expiry } : {}),
          },
        }, {
          onSuccess: setResult,
          onError: (err) => {
            if (err instanceof ApiError && err.code === 'LOT_REQUIRED') setLot(true)
            if (!(err instanceof ApiError && err.code === 'NETWORK')) changed()
          },
        })
      }}>
        {(warehouses.data?.items.length ?? 0) > 1 && (
          <Field label={t('putaway.warehouse')}>
            {(p) => <select {...p} className={SELECT} value={wh ?? ''} onChange={(e) => {
              changed()
              setWarehouseId(e.target.value)
            }}>
              {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </select>}
          </Field>
        )}
        <ProductChooser value={product} onChange={(p) => {
          changed()
          setProduct(p)
        }} />
        <Field label={t('adjust.bin')}>
          {(p) => <Input {...p} className="font-mono uppercase" maxLength={40} placeholder={t('adjust.binHint')} value={bin}
            onChange={(e) => {
              changed()
              setBin(e.target.value)
            }} />}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('adjust.direction')}>
            {(p) => <select {...p} className={SELECT} value={direction} onChange={(e) => {
              changed()
              setDirection(e.target.value as 'IN' | 'OUT')
            }}>
              <option value="OUT">{t('adjust.direction.OUT')}</option>
              <option value="IN">{t('adjust.direction.IN')}</option>
            </select>}
          </Field>
          <Field label={product ? t('move.qty', { uom: product.baseUom }) : t('receive.qty')} error={qty === '' || qtyOk ? undefined : t('po.qtyRule')}>
            {(p) => <Input {...p} inputMode="decimal" value={qty} onChange={(e) => {
              changed()
              setQty(e.target.value)
            }} />}
          </Field>
        </div>
        <Field label={t('adjust.reasonLabel')}>
          {(p) => <select {...p} className={SELECT} value={reason} onChange={(e) => {
            changed()
            setReason(e.target.value as AdjustmentReason)
          }}>
            {REASONS.map((r) => <option key={r} value={r}>{t(`adjust.reason.${r}` as MessageKey)}</option>)}
          </select>}
        </Field>
        <Field label={t('adjust.note')}>
          {(p) => <Input {...p} maxLength={200} value={note} onChange={(e) => {
            changed()
            setNote(e.target.value)
          }} />}
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={lot} onChange={(e) => {
            changed()
            setLot(e.target.checked)
          }} /> {t('receive.lot')}
        </label>
        {lot && (
          <div className="grid grid-cols-2 gap-2">
            <Field label={t('receive.batch')}>
              {(p) => <Input {...p} className="font-mono uppercase" maxLength={40} value={batch} onChange={(e) => {
                changed()
                setBatch(e.target.value)
              }} />}
            </Field>
            <Field label={t('receive.expiry')}>
              {(p) => <Input {...p} type="date" value={expiry} onChange={(e) => {
                changed()
                setExpiry(e.target.value)
              }} />}
            </Field>
          </div>
        )}
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!ready}>{action.isPending ? t('common.saving') : t('adjust.submit')}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function RejectDialog({ adjustment, onClose }: { adjustment: Adjustment; onClose: () => void }) {
  const action = useAdjustmentAction()
  const [note, setNote] = useState('')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('adjust.rejectTitle', { number: adjustment.number })}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (note.trim()) action.mutate({ kind: 'reject', id: adjustment.id, version: adjustment.version, note: note.trim() }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('adjust.rejectNote')}>
          {(p) => <Input {...p} required maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />}
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="destructive" disabled={!note.trim() || action.isPending}>{t('adjust.rejectButton')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
