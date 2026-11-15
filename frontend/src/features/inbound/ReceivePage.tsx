import { ArrowLeft, Check, Minus, PackageCheck, Plus, Trash2 } from 'lucide-react'
import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { can, useMe } from '@/features/auth/session'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useBase } from '@/lib/base'
import { useDebounced } from '@/lib/useDebounced'
import { ScanButton } from '@/features/worker/Scanner'
import { useReceipt, useReceiptAction, useReceivableOrders, type LineProgress, type Receipt } from './api'
import { PoStatusBadge } from './PurchaseOrdersPage'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const num = (n: number) => Number(n.toFixed(3)).toLocaleString('en')

/** /app/receive: approved orders of my warehouses; tap one (or type its number and press Enter) to receive it. */
export function ReceivePage() {
  const navigate = useNavigate()
  const base = useBase()
  const [search, setSearch] = useState('')
  const q = useDebounced(search.trim())
  const list = useReceivableOrders(q)
  const open = useReceiptAction()
  const start = (poId: string) => open.mutate({ kind: 'open', poId }, { onSuccess: (r) => void navigate(`${base}/receive/${r.id}`) })
  const items = list.data?.items ?? []

  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('receive.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('receive.hint')}</p>
      </div>
      <div className="flex gap-2">
      <Input aria-label={t('receive.findPo')} placeholder={t('receive.findPo')} className="h-12 text-base" value={search} maxLength={40}
        autoFocus onChange={(e) => setSearch(e.target.value)}
        onKeyDown={(e) => {
          if (e.key !== 'Enter') return
          const exact = items.find((o) => o.number.toUpperCase() === search.trim().toUpperCase())
          const only = exact ?? (items.length === 1 ? items[0] : undefined)
          if (only) start(only.id)
        }} />
        <ScanButton label={t('scan.open')} onCode={setSearch} />
      </div>
      {(list.isError || open.isError) && <FormMessage tone="error">{errorMessage(list.error ?? open.error)}</FormMessage>}
      <ul className="grid gap-2">
        {items.map((o) => (
          <li key={o.id}>
            <button type="button" disabled={open.isPending} onClick={() => start(o.id)}
              className="flex w-full items-center gap-3 rounded-lg border bg-card p-4 text-left hover:bg-secondary disabled:opacity-60">
              <PackageCheck className="size-6 shrink-0 text-primary" />
              <span className="grid min-w-0 flex-1">
                <span className="font-mono text-base font-semibold">{o.number}</span>
                <span className="truncate text-sm">{o.supplierName}</span>
                <span className="text-xs text-muted-foreground">
                  {o.warehouseCode}{o.expectedDate ? ` · ${t('receive.expected', { date: o.expectedDate })}` : ''}
                </span>
              </span>
              <PoStatusBadge status={o.status} />
            </button>
          </li>
        ))}
      </ul>
      {list.data && items.length === 0 && (
        <p className="py-8 text-center text-muted-foreground">{q ? t('receive.noMatch') : t('receive.empty')}</p>
      )}
    </div>
  )
}

/** /app/receive/:id: scan products into one goods received note, then close it. */
export function ReceiptPage() {
  const { id } = useParams()
  const me = useMe()
  const receipt = useReceipt(id)
  const base = useBase()
  if (receipt.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (receipt.isError) return <FormMessage tone="error">{errorMessage(receipt.error)}</FormMessage>
  const r = receipt.data
  const editable = r.status === 'OPEN' && can(me.data, 'inbound:receive')
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <Link to={`${base}/receive`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('receive.back')}
      </Link>
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="font-mono text-2xl font-semibold">{r.number}</h1>
        <Badge tone={r.status === 'OPEN' ? 'warn' : 'good'}>{t(r.status === 'OPEN' ? 'receive.status.OPEN' : 'receive.status.CLOSED')}</Badge>
      </div>
      <p className="text-sm text-muted-foreground">
        <Link className="font-mono underline-offset-2 hover:underline" to={`/app/purchase-orders/${r.poId}`}>{r.poNumber}</Link>
        {' · '}{r.supplierName}{' · '}{r.warehouseCode}
      </p>
      {editable && <ScanForm receipt={r} />}
      {r.status === 'CLOSED' && <FormMessage tone="success">{t('receive.closedNote')}</FormMessage>}
      {r.note && <p className="text-sm"><span className="font-medium">{t('po.note')}:</span> {r.note}</p>}
      <Progress lines={r.lines} />
      <Scans receipt={r} editable={editable} />
      {editable && <CloseButton receipt={r} />}
    </div>
  )
}

function ScanForm({ receipt }: { receipt: Receipt }) {
  const action = useReceiptAction()
  const [code, setCode] = useState('')
  const [qty, setQty] = useState('1')
  const [damaged, setDamaged] = useState(false)
  const [damagedQty, setDamagedQty] = useState('')
  const [note, setNote] = useState('')
  const [lot, setLot] = useState(false)
  const [batch, setBatch] = useState('')
  const [expiry, setExpiry] = useState('')
  const [done, setDone] = useState('')
  const codeRef = useRef<HTMLInputElement>(null)
  // One key per scan: kept when the network fails so the retry is counted once, renewed once the scan lands.
  const key = useRef(crypto.randomUUID())
  /** Any change makes it a different scan: new key, and the last "Added" note no longer applies. */
  const renew = () => {
    key.current = crypto.randomUUID()
    setDone('')
  }
  const qtyOk = QTY.test(qty.trim()) && Number(qty) > 0
  const damagedOk = !damaged || (QTY.test(damagedQty.trim()) && Number(damagedQty) > 0 && Number(damagedQty) <= Number(qty))
  const ready = code.trim() !== '' && qtyOk && damagedOk && !action.isPending
  const step = (d: number) => {
    renew()
    setQty(String(Math.max(1, Math.floor(Number(qty) || 0) + d)))
  }

  const submit = () => {
    if (!ready) return
    const scanned = code.trim()
    action.mutate({
      kind: 'scan', id: receipt.id, scan: {
        code: scanned, qty: Number(qty), key: key.current,
        ...(damaged ? { damagedQty: Number(damagedQty), damageNote: note.trim() || undefined } : {}),
        ...(lot && batch.trim() ? { batchNo: batch.trim() } : {}),
        ...(lot && expiry ? { expiryDate: expiry } : {}),
      },
    }, {
      onSuccess: () => {
        setDone(t('receive.added', { qty, code: scanned }))
        setCode('')
        setQty('1')
        setDamaged(false)
        setDamagedQty('')
        setNote('')
        setBatch('')
        setExpiry('')
        key.current = crypto.randomUUID()
        codeRef.current?.focus()
      },
      onError: (e) => {
        if (e instanceof ApiError && (e.code === 'BATCH_REQUIRED' || e.code === 'EXPIRY_REQUIRED')) setLot(true)
        if (!(e instanceof ApiError && e.code === 'NETWORK')) key.current = crypto.randomUUID() // a refused scan is a new scan next time
      },
    })
  }

  return (
    <form className="grid gap-3 rounded-lg border bg-card p-4" onSubmit={(e) => {
      e.preventDefault()
      submit()
    }}>
      <div className="flex items-end gap-2">
        <div className="flex-1">
          <Field label={t('receive.code')}>
            {(p) => <Input {...p} ref={codeRef} autoFocus autoComplete="off" className="h-12 font-mono text-lg" maxLength={64}
              placeholder={t('receive.codeHint')} value={code} onChange={(e) => {
                renew()
                setCode(e.target.value)
              }} />}
          </Field>
        </div>
        <ScanButton label={t('scan.open')} onCode={(c) => {
          renew()
          setCode(c)
        }} />
      </div>
      <div className="flex items-end gap-2">
        <Field label={t('receive.qty')} error={qtyOk ? undefined : t('po.qtyRule')}>
          {(p) => <Input {...p} inputMode="decimal" className="h-12 w-28 text-center text-lg" value={qty} onChange={(e) => {
            renew()
            setQty(e.target.value)
          }} />}
        </Field>
        <Button type="button" variant="outline" className="size-12" aria-label={t('receive.less')} onClick={() => step(-1)}><Minus /></Button>
        <Button type="button" variant="outline" className="size-12" aria-label={t('receive.more')} onClick={() => step(1)}><Plus /></Button>
      </div>
      <p className="-mt-1 text-xs text-muted-foreground">{t('receive.qtyHint')}</p>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" className="size-5" checked={damaged} onChange={(e) => {
          renew()
          setDamaged(e.target.checked)
        }} /> {t('receive.someDamaged')}
      </label>
      {damaged && (
        <div className="grid grid-cols-[7rem_1fr] gap-2">
          <Field label={t('receive.damagedQty')} error={damagedOk || damagedQty.trim() === '' ? undefined : t('receive.damagedRule')}>
            {(p) => <Input {...p} inputMode="decimal" className="h-12 text-center" value={damagedQty} onChange={(e) => {
              renew()
              setDamagedQty(e.target.value)
            }} />}
          </Field>
          <Field label={t('receive.damageNote')}>
            {(p) => <Input {...p} className="h-12" maxLength={200} value={note} onChange={(e) => {
              renew()
              setNote(e.target.value)
            }} />}
          </Field>
        </div>
      )}
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" className="size-5" checked={lot} onChange={(e) => {
          renew()
          setLot(e.target.checked)
        }} /> {t('receive.lot')}
      </label>
      {lot && (
        <div className="grid grid-cols-2 gap-2">
          <Field label={t('receive.batch')}>
            {(p) => <Input {...p} className="h-12 font-mono uppercase" maxLength={40} value={batch} onChange={(e) => {
              renew()
              setBatch(e.target.value)
            }} />}
          </Field>
          <Field label={t('receive.expiry')}>
            {(p) => <Input {...p} type="date" className="h-12" value={expiry} onChange={(e) => {
              renew()
              setExpiry(e.target.value)
            }} />}
          </Field>
        </div>
      )}
      {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      {done && !action.isError && <FormMessage tone="success">{done}</FormMessage>}
      <Button type="submit" className="h-12 text-base" disabled={!ready}>
        {action.isPending ? t('receive.adding') : t('receive.add')}
      </Button>
    </form>
  )
}

function Progress({ lines }: { lines: LineProgress[] }) {
  return (
    <section className="grid gap-2">
      <h2 className="text-sm font-medium">{t('receive.progress')}</h2>
      <ul className="grid gap-2">
        {lines.map((l) => {
          const left = l.ordered - l.receivedBefore - l.thisReceipt
          return (
            <li key={l.lineNo} className="flex items-center gap-3 rounded-lg border p-3">
              {left <= 0 ? <Check className="size-5 shrink-0 text-emerald-600" aria-label={t('receive.complete')} />
                : <span className="size-5 shrink-0" />}
              <span className="grid min-w-0 flex-1">
                <span className="truncate text-sm"><span className="font-mono text-xs">{l.sku}</span> {l.productName}</span>
                <span className="text-xs text-muted-foreground">
                  {t('receive.lineFigures', { here: num(l.thisReceipt), left: num(Math.max(left, 0)), ordered: num(l.ordered), uom: l.baseUom })}
                  {l.damagedThisReceipt > 0 && ` · ${t('receive.damagedCount', { n: num(l.damagedThisReceipt) })}`}
                </span>
              </span>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function Scans({ receipt, editable }: { receipt: Receipt; editable: boolean }) {
  const action = useReceiptAction()
  if (receipt.scans.length === 0) return <p className="text-sm text-muted-foreground">{t('receive.noScans')}</p>
  return (
    <section className="grid gap-2">
      <h2 className="text-sm font-medium">{t('receive.scans', { n: receipt.scans.length })}</h2>
      {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      <ul className="divide-y rounded-lg border text-sm">
        {[...receipt.scans].reverse().map((s) => (
          <li key={s.id} className="flex items-center gap-2 px-3 py-2">
            <span className="min-w-0 flex-1">
              <span className="font-mono text-xs">{s.sku}</span> {num(s.qty)} {s.uom}
              {s.factor > 1 && <span className="text-muted-foreground"> = {num(s.qtyBase)}</span>}
              {(s.batchNo || s.expiryDate) && (
                <span className="text-muted-foreground"> · {[s.batchNo, s.expiryDate].filter(Boolean).join(' · ')}</span>
              )}
              {s.damagedBase > 0 && (
                <span className="text-destructive"> · {t('receive.damagedCount', { n: num(s.damagedBase) })}{s.damageNote ? `: ${s.damageNote}` : ''}</span>
              )}
            </span>
            {editable && (
              <Button variant="ghost" size="icon" aria-label={t('receive.removeScan', { sku: s.sku })} disabled={action.isPending}
                onClick={() => action.mutate({ kind: 'remove', id: receipt.id, lineId: s.id })}><Trash2 /></Button>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

function CloseButton({ receipt }: { receipt: Receipt }) {
  const action = useReceiptAction()
  const [asking, setAsking] = useState(false)
  const [note, setNote] = useState('')
  const short = receipt.lines.some((l) => l.ordered - l.receivedBefore - l.thisReceipt > 0)
  return (
    <>
      <Button className="h-12 text-base" variant="outline" disabled={receipt.scans.length === 0} onClick={() => setAsking(true)}>
        {t('receive.close')}
      </Button>
      {asking && (
        <Dialog open onOpenChange={(o) => !o && setAsking(false)} title={t('receive.closeTitle', { number: receipt.number })}
          description={short ? t('receive.closeShort') : t('receive.closeFull')}>
          <form className="grid gap-3" onSubmit={(e) => {
            e.preventDefault()
            action.mutate({ kind: 'close', id: receipt.id, version: receipt.version, note: note.trim() || undefined },
              { onSuccess: () => setAsking(false) })
          }}>
            {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
            <Field label={t('receive.closeNote')}>
              {(p) => <Input {...p} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
            </Field>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="outline" onClick={() => setAsking(false)}>{t('common.cancel')}</Button>
              <Button type="submit" disabled={action.isPending}>{t('receive.close')}</Button>
            </div>
          </form>
        </Dialog>
      )}
    </>
  )
}
