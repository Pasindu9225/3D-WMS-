import { ArrowLeft, MapPin, PackageOpen } from 'lucide-react'
import { useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { useWarehouses } from '@/features/warehouses/api'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'
import { ScanButton } from '@/features/worker/Scanner'
import { usePutaway, useSuggestions, useWaiting, type Waiting } from './api'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const num = (n: number) => Number(n.toFixed(3)).toLocaleString('en')
const lot = (w: Waiting) => [w.batchNo, w.expiryDate].filter(Boolean).join(' · ')

/** /app/putaway: goods waiting in the receiving area; pick one, follow the suggested bin, scan its label. */
export function PutawayPage() {
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouseId = chosen || warehouses.data?.items[0]?.id
  const waiting = useWaiting(warehouseId)
  const [item, setItem] = useState<Waiting | null>(null)
  const [done, setDone] = useState('')

  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('putaway.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('putaway.hint')}</p>
      </div>
      {(warehouses.data?.items.length ?? 0) > 1 && (
        <select aria-label={t('putaway.warehouse')} className="h-12 rounded-md border border-input bg-background px-3"
          value={warehouseId ?? ''} onChange={(e) => {
            setChosen(e.target.value)
            setItem(null)
          }}>
          {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
        </select>
      )}
      {done && !item && <FormMessage tone="success">{done}</FormMessage>}
      {waiting.isError && <FormMessage tone="error">{errorMessage(waiting.error)}</FormMessage>}
      {item && warehouseId ? (
        <PutawayItem key={`${item.productId}|${item.batchNo}|${item.expiryDate}`} warehouseId={warehouseId} item={item}
          onBack={() => setItem(null)}
          onDone={(msg) => {
            setDone(msg)
            setItem(null)
          }} />
      ) : (
        <>
          <ul className="grid gap-2">
            {waiting.data?.items.map((w) => (
              <li key={`${w.productId}|${w.batchNo}|${w.expiryDate}`}>
                <button type="button" onClick={() => {
                  setDone('')
                  setItem(w)
                }}
                  className="flex w-full items-center gap-3 rounded-lg border bg-card p-4 text-left hover:bg-secondary">
                  <PackageOpen className="size-6 shrink-0 text-primary" />
                  <span className="grid min-w-0 flex-1">
                    <span className="truncate"><span className="font-mono text-sm font-semibold">{w.sku}</span> {w.name}</span>
                    {lot(w) && <span className="text-xs text-muted-foreground">{lot(w)}</span>}
                  </span>
                  <span className="text-lg font-semibold tabular-nums">{num(w.qty)} <span className="text-xs font-normal">{w.baseUom}</span></span>
                </button>
              </li>
            ))}
          </ul>
          {waiting.data && waiting.data.items.length === 0 && (
            <p className="py-8 text-center text-muted-foreground">{t('putaway.empty')}</p>
          )}
        </>
      )}
    </div>
  )
}

function PutawayItem({ warehouseId, item, onBack, onDone }: {
  warehouseId: string; item: Waiting; onBack: () => void; onDone: (message: string) => void
}) {
  const [qty, setQty] = useState(String(item.qty))
  const qtyOk = QTY.test(qty.trim()) && Number(qty) > 0 && Number(qty) <= item.qty
  const debouncedQty = useDebounced(qtyOk ? Number(qty) : null)
  const suggestions = useSuggestions(warehouseId, item, debouncedQty)
  const [bin, setBin] = useState('')
  const putaway = usePutaway()
  // One key per putaway: kept if the network fails, so pressing again moves the goods once.
  const key = useRef(crypto.randomUUID())
  const scanned = bin.trim().toUpperCase()
  const offList = scanned !== '' && suggestions.data !== undefined && !suggestions.data.some((s) => s.code === scanned)

  const submit = () => {
    if (!qtyOk || !scanned || putaway.isPending) return
    putaway.mutate({ warehouseId, item, qty: Number(qty), binCode: scanned, key: key.current }, {
      onSuccess: (r) => onDone(t('putaway.done', { qty: num(r.qty), uom: item.baseUom, bin: r.binCode, left: num(r.leftInReceiving) })),
      onError: (e) => {
        if (!(e instanceof ApiError && e.code === 'NETWORK')) key.current = crypto.randomUUID()
      },
    })
  }

  return (
    <div className="grid gap-4">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('putaway.back')}
      </button>
      <div className="rounded-lg border bg-card p-4">
        <p><span className="font-mono font-semibold">{item.sku}</span> {item.name}</p>
        {lot(item) && <p className="text-sm text-muted-foreground">{lot(item)}</p>}
        <p className="text-sm text-muted-foreground">{t('putaway.inReceiving', { qty: num(item.qty), uom: item.baseUom })}</p>
      </div>
      <Field label={t('putaway.qty', { uom: item.baseUom })} error={qtyOk ? undefined : t('putaway.qtyRule', { max: num(item.qty) })}>
        {(p) => <Input {...p} inputMode="decimal" className="h-12 w-36 text-center text-lg" value={qty} onChange={(e) => {
          key.current = crypto.randomUUID()
          setQty(e.target.value)
        }} />}
      </Field>
      <section className="grid gap-2">
        <h2 className="text-sm font-medium">{t('putaway.suggested')}</h2>
        {suggestions.isError && <FormMessage tone="error">{errorMessage(suggestions.error)}</FormMessage>}
        {suggestions.data?.length === 0 && <FormMessage tone="error">{t('putaway.noRoom')}</FormMessage>}
        <ul className="grid gap-2">
          {suggestions.data?.map((s, i) => (
            <li key={s.binId} className={`flex items-center gap-3 rounded-lg border p-3 ${i === 0 ? 'border-primary bg-primary/10' : ''}`}>
              <MapPin className="size-5 shrink-0 text-primary" />
              <span className="grid flex-1">
                <span className="font-mono text-lg font-semibold">{s.code}</span>
                <span className="text-xs text-muted-foreground">
                  {t(s.why === 'SAME_PRODUCT' ? 'putaway.why.SAME_PRODUCT' : 'putaway.why.EMPTY_STORAGE')} · {t('putaway.away', { m: s.distanceM })}
                </span>
              </span>
            </li>
          ))}
        </ul>
      </section>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label={t('putaway.scanBin')}>
              {(p) => <Input {...p} autoFocus autoComplete="off" className="h-12 font-mono text-lg uppercase" maxLength={40}
                placeholder={t('putaway.scanBinHint')} value={bin} onChange={(e) => {
                  key.current = crypto.randomUUID()
                  setBin(e.target.value)
                }} />}
            </Field>
          </div>
          <ScanButton label={t('scan.open')} onCode={(c) => {
            key.current = crypto.randomUUID()
            setBin(c)
          }} />
        </div>
        {offList && <p className="text-sm text-muted-foreground">{t('putaway.offList')}</p>}
        {putaway.isError && <FormMessage tone="error">{errorMessage(putaway.error)}</FormMessage>}
        <Button type="submit" className="h-12 text-base" disabled={!qtyOk || !scanned || putaway.isPending}>
          {putaway.isPending ? t('putaway.working') : t('putaway.confirm')}
        </Button>
      </form>
    </div>
  )
}
