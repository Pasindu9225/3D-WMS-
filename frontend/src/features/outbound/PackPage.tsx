import { ArrowLeft, Check, FileText, Package, Undo2 } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { ScanButton } from '@/features/worker/Scanner'
import { useBase } from '@/lib/base'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { deliveryNoteUrl, packingSlipUrl, useFinishPack, usePackView, useToPack, type PackItem, type PackView } from './api'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

/** /worker/pack (and /app/pack): picked orders to pack; /pack/:id checks one order by scanning everything. */
export function PackPage() {
  const { id } = useParams()
  return id ? <PackOrder id={id} /> : <ToPack />
}

function ToPack() {
  const base = useBase()
  const list = useToPack()
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('pack.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('pack.hint')}</p>
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      <ul className="grid gap-2">
        {list.data?.items.map((o) => (
          <li key={o.id}>
            <Link to={`${base}/pack/${o.id}`} className="flex items-center gap-3 rounded-lg border bg-card p-4 hover:bg-secondary">
              <Package className="size-6 shrink-0 text-primary" />
              <span className="min-w-0 flex-1">
                <span className="font-mono font-semibold">{o.number}</span>{o.customerRef && <span className="text-muted-foreground"> · {o.customerRef}</span>}
                <span className="block truncate text-sm text-muted-foreground">{o.customerName} · {o.warehouseCode}</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
      {list.data && list.data.items.length === 0 && <p className="rounded-lg border p-4 text-sm text-muted-foreground">{t('pack.none')}</p>}
    </div>
  )
}

function PackOrder({ id }: { id: string }) {
  const base = useBase()
  const view = usePackView(id)
  if (view.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (view.isError) return <FormMessage tone="error">{errorMessage(view.error)}</FormMessage>
  const p = view.data
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <Link to={`${base}/pack`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('pack.back')}
      </Link>
      <div>
        <h1 className="text-xl font-semibold font-mono">{p.number}</h1>
        <p className="text-sm text-muted-foreground">{p.customerName}{p.customerRef && ` · ${p.customerRef}`} · {p.warehouseCode}</p>
      </div>
      {p.status === 'PICKED' ? <Scanning key={p.version} view={p} /> : <Papers id={p.id} />}
    </div>
  )
}

/** After packing: the two papers to print. */
function Papers({ id }: { id: string }) {
  return (
    <div className="grid gap-3">
      <FormMessage tone="success">{t('pack.done')}</FormMessage>
      <div className="grid grid-cols-2 gap-2">
        <Button asChild variant="outline" className="h-12"><a href={packingSlipUrl(id)} target="_blank" rel="noopener"><FileText /> {t('pack.slip')}</a></Button>
        <Button asChild className="h-12"><a href={deliveryNoteUrl(id)} target="_blank" rel="noopener"><FileText /> {t('pack.note')}</a></Button>
      </div>
    </div>
  )
}

interface Scan {
  sku: string
  base: number
}

/** Every product is scanned and counted on this screen; Finish sends the counts for the server to check. */
function Scanning({ view }: { view: PackView }) {
  const [code, setCode] = useState('')
  const [qty, setQty] = useState('1')
  const [scans, setScans] = useState<Scan[]>([])
  const [problem, setProblem] = useState('')
  const [finishing, setFinishing] = useState(false)
  const counted = (sku: string) => scans.filter((s) => s.sku === sku).reduce((sum, s) => sum + s.base, 0)
  const toPack = view.items.filter((i) => i.picked > 0)
  const allMatch = toPack.every((i) => Math.abs(counted(i.sku) - i.picked) < 0.0005)

  const add = (raw: string) => {
    const c = raw.trim()
    if (!c || !QTY.test(qty.trim()) || Number(qty) <= 0) return
    let item: PackItem | undefined
    let factor = 1
    for (const i of view.items) {
      const hit = i.codes.find((x) => x.code.toUpperCase() === c.toUpperCase())
      if (hit) {
        item = i
        factor = hit.factor
        break
      }
    }
    if (!item) {
      setProblem(t('pack.notOnOrder', { code: c }))
    } else {
      const base = Number(qty) * factor
      if (counted(item.sku) + base > item.picked + 0.0005) {
        setProblem(t('pack.tooMany', { sku: item.sku, picked: num(item.picked), uom: item.baseUom }))
      } else {
        const sku = item.sku
        setScans((s) => [...s, { sku, base }])
        setProblem('')
      }
    }
    setCode('')
    setQty('1')
  }

  return (
    <section className="grid gap-3">
      <form className="grid gap-3 rounded-xl border bg-card p-4" onSubmit={(e) => {
        e.preventDefault()
        add(code)
      }}>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label={t('pack.scan')}>
              {(p) => <Input {...p} autoFocus autoComplete="off" className="h-12 font-mono text-lg" maxLength={64} value={code} onChange={(e) => setCode(e.target.value)} />}
            </Field>
          </div>
          <ScanButton label={t('scan.open')} onCode={add} />
        </div>
        <div className="flex items-end gap-2">
          <div className="w-32">
            <Field label={t('pick.qty')}>
              {(p) => <Input {...p} inputMode="decimal" className="h-12" value={qty} onChange={(e) => setQty(e.target.value)} />}
            </Field>
          </div>
          <Button type="submit" variant="outline" className="h-12 flex-1" disabled={!code.trim()}>{t('pack.add')}</Button>
          <Button type="button" variant="ghost" className="h-12" aria-label={t('pack.undo')} disabled={scans.length === 0}
            onClick={() => setScans((s) => s.slice(0, -1))}><Undo2 /></Button>
        </div>
        <p className="text-xs text-muted-foreground">{t('pick.unitHint')}</p>
        {problem && <FormMessage tone="error">{problem}</FormMessage>}
      </form>
      <ul className="divide-y rounded-lg border">
        {toPack.map((i) => {
          const n = counted(i.sku)
          const done = Math.abs(n - i.picked) < 0.0005
          return (
            <li key={i.sku} className="flex items-center justify-between gap-2 px-3 py-3">
              <span className="min-w-0"><span className="font-mono text-sm font-semibold">{i.sku}</span> <span className="text-sm">{i.name}</span></span>
              <span className={`flex shrink-0 items-center gap-1 tabular-nums ${done ? 'text-green-600 dark:text-green-400' : ''}`}>
                {done && <Check className="size-4" aria-label={t('pack.matched')} />}{num(n)} / {num(i.picked)} {i.baseUom}
              </span>
            </li>
          )
        })}
      </ul>
      <Button className="h-12" disabled={!allMatch} onClick={() => setFinishing(true)}>{t('pack.finish')}</Button>
      {finishing && <FinishDialog view={view} counts={toPack.map((i) => ({ sku: i.sku, qty: counted(i.sku) }))} onClose={() => setFinishing(false)} />}
    </section>
  )
}

function FinishDialog({ view, counts, onClose }: { view: PackView; counts: { sku: string; qty: number }[]; onClose: () => void }) {
  const finish = useFinishPack()
  const [cartons, setCartons] = useState('1')
  const [kg, setKg] = useState('')
  const cartonsOk = /^\d{1,3}$/.test(cartons) && Number(cartons) >= 1
  const kgOk = kg.trim() === '' || /^\d{1,6}(\.\d{1,3})?$/.test(kg.trim())
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('pack.finishTitle', { number: view.number })} description={t('pack.finishHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (cartonsOk && kgOk) {
          finish.mutate({ id: view.id, version: view.version, cartons: Number(cartons), weightG: kg.trim() ? Math.round(Number(kg) * 1000) : null, counts },
            { onSuccess: onClose })
        }
      }}>
        {finish.isError && <FormMessage tone="error">{errorMessage(finish.error)}</FormMessage>}
        <Field label={t('pack.cartons')} error={cartonsOk ? undefined : t('pack.cartonsRule')}>
          {(p) => <Input {...p} inputMode="numeric" className="h-12" value={cartons} onChange={(e) => setCartons(e.target.value)} />}
        </Field>
        <Field label={t('pack.weight')} error={kgOk ? undefined : t('pack.weightRule')}>
          {(p) => <Input {...p} inputMode="decimal" className="h-12" value={kg} onChange={(e) => setKg(e.target.value)} />}
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!cartonsOk || !kgOk || finish.isPending}>{t('pack.finishSave')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
