import { useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import type { Product } from '@/features/catalog/api'
import { useWarehouses } from '@/features/warehouses/api'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useTransfer } from './api'
import { ProductChooser } from './ProductChooser'

const QTY = /^\d{1,7}(\.\d{1,3})?$/

/** /app/move: move stock from one bin to another: scan the bin taken from, the product, and the bin put into. */
export function MoveStockPage() {
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouseId = chosen || warehouses.data?.items[0]?.id
  const [product, setProduct] = useState<Product | null>(null)
  const [from, setFrom] = useState('')
  const [fromReceiving, setFromReceiving] = useState(false)
  const [to, setTo] = useState('')
  const [qty, setQty] = useState('1')
  const [lot, setLot] = useState(false)
  const [batch, setBatch] = useState('')
  const [expiry, setExpiry] = useState('')
  const [done, setDone] = useState('')
  const transfer = useTransfer()
  const key = useRef(crypto.randomUUID())
  const changed = () => {
    key.current = crypto.randomUUID()
    setDone('')
  }
  const qtyOk = QTY.test(qty.trim()) && Number(qty) > 0
  const ready = !!warehouseId && !!product && (fromReceiving || from.trim() !== '') && to.trim() !== '' && qtyOk && !transfer.isPending

  const submit = () => {
    if (!ready || !warehouseId || !product) return
    transfer.mutate({
      key: key.current, warehouseId, productId: product.id, qty: Number(qty), toBin: to.trim().toUpperCase(),
      ...(fromReceiving ? {} : { fromBin: from.trim().toUpperCase() }),
      ...(lot && batch.trim() ? { batchNo: batch.trim() } : {}),
      ...(lot && expiry ? { expiryDate: expiry } : {}),
    }, {
      onSuccess: (m) => {
        setDone(t('move.done', { qty: m.qty, sku: m.sku, from: m.fromBin ?? t('move.receiving'), to: m.toBin }))
        setFrom('')
        setTo('')
        setQty('1')
        key.current = crypto.randomUUID()
      },
      onError: (e) => {
        if (e instanceof ApiError && e.code === 'LOT_REQUIRED') setLot(true)
        if (!(e instanceof ApiError && e.code === 'NETWORK')) key.current = crypto.randomUUID()
      },
    })
  }

  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('move.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('move.hint')}</p>
      </div>
      {(warehouses.data?.items.length ?? 0) > 1 && (
        <select aria-label={t('putaway.warehouse')} className="h-12 rounded-md border border-input bg-background px-3"
          value={warehouseId ?? ''} onChange={(e) => {
            changed()
            setChosen(e.target.value)
          }}>
          {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
        </select>
      )}
      <form className="grid gap-3 rounded-lg border bg-card p-4" onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}>
        <Field label={t('move.from')}>
          {(p) => <Input {...p} autoFocus autoComplete="off" className="h-12 font-mono text-lg uppercase" maxLength={40}
            disabled={fromReceiving} placeholder={t('putaway.scanBinHint')} value={from} onChange={(e) => {
              changed()
              setFrom(e.target.value)
            }} />}
        </Field>
        <label className="-mt-1 flex items-center gap-2 text-sm">
          <input type="checkbox" className="size-5" checked={fromReceiving} onChange={(e) => {
            changed()
            setFromReceiving(e.target.checked)
          }} /> {t('move.fromReceiving')}
        </label>
        <ProductChooser value={product} onChange={(p) => {
          changed()
          setProduct(p)
        }} />
        <Field label={product ? t('move.qty', { uom: product.baseUom }) : t('receive.qty')} error={qtyOk ? undefined : t('po.qtyRule')}>
          {(p) => <Input {...p} inputMode="decimal" className="h-12 w-32 text-center text-lg" value={qty} onChange={(e) => {
            changed()
            setQty(e.target.value)
          }} />}
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" className="size-5" checked={lot} onChange={(e) => {
            changed()
            setLot(e.target.checked)
          }} /> {t('receive.lot')}
        </label>
        {lot && (
          <div className="grid grid-cols-2 gap-2">
            <Field label={t('receive.batch')}>
              {(p) => <Input {...p} className="h-12 font-mono uppercase" maxLength={40} value={batch} onChange={(e) => {
                changed()
                setBatch(e.target.value)
              }} />}
            </Field>
            <Field label={t('receive.expiry')}>
              {(p) => <Input {...p} type="date" className="h-12" value={expiry} onChange={(e) => {
                changed()
                setExpiry(e.target.value)
              }} />}
            </Field>
          </div>
        )}
        <Field label={t('move.to')}>
          {(p) => <Input {...p} autoComplete="off" className="h-12 font-mono text-lg uppercase" maxLength={40}
            placeholder={t('putaway.scanBinHint')} value={to} onChange={(e) => {
              changed()
              setTo(e.target.value)
            }} />}
        </Field>
        {transfer.isError && <FormMessage tone="error">{errorMessage(transfer.error)}</FormMessage>}
        {done && !transfer.isError && <FormMessage tone="success">{done}</FormMessage>}
        <Button type="submit" className="h-12 text-base" disabled={!ready}>{transfer.isPending ? t('move.working') : t('move.submit')}</Button>
      </form>
    </div>
  )
}
