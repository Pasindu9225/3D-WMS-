import { ArrowLeft, CheckCircle2, ClipboardCheck, MapPin, PackagePlus } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { ScanButton } from '@/features/worker/Scanner'
import { useBase } from '@/lib/base'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useCountAction, useCounterView, useCounts, type CounterLine, type CounterView } from './counts'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')
const lot = (l: CounterLine) => [l.batchNo, l.expiry].filter(Boolean).join(' · ')

/** /worker/count (and /app/count): open counts; /count/:id walks one count line by line. Blind: no expected figures. */
export function CountPage() {
  const { id } = useParams()
  return id ? <CountWalk id={id} /> : <CountList />
}

function CountList() {
  const base = useBase()
  const counts = useCounts()
  const open = counts.data?.items.filter((c) => c.status === 'OPEN') ?? []
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('count.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('count.hint')}</p>
      </div>
      {counts.isError && <FormMessage tone="error">{errorMessage(counts.error)}</FormMessage>}
      <ul className="grid gap-2">
        {open.map((c) => (
          <li key={c.id}>
            <Link to={`${base}/count/${c.id}`} className="flex items-center gap-3 rounded-lg border bg-card p-4 hover:bg-secondary">
              <ClipboardCheck className="size-6 shrink-0 text-primary" />
              <span className="min-w-0 flex-1">
                <span className="font-mono font-semibold">{c.number}</span>
                <span className="block truncate text-sm text-muted-foreground">{c.warehouseCode} · {t(`count.scope.${c.scope}` as MessageKey, { v: c.scopeValue ?? '' })}</span>
              </span>
              <span className="text-sm tabular-nums">{t('count.left', { n: c.lines - c.counted, of: c.lines })}</span>
            </Link>
          </li>
        ))}
      </ul>
      {counts.data && open.length === 0 && <p className="rounded-lg border p-4 text-sm text-muted-foreground">{t('count.none')}</p>}
    </div>
  )
}

function CountWalk({ id }: { id: string }) {
  const base = useBase()
  const view = useCounterView(id)
  const [finding, setFinding] = useState(false)
  if (view.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (view.isError) return <FormMessage tone="error">{errorMessage(view.error)}</FormMessage>
  const c = view.data
  const next = c.status === 'OPEN' ? c.items.find((l) => l.status === 'OPEN') : undefined
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <Link to={`${base}/count`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('count.back')}
      </Link>
      <div>
        <h1 className="text-xl font-semibold"><span className="font-mono">{c.number}</span> · {c.warehouseCode}</h1>
        <p className="text-sm text-muted-foreground">{t('count.left', { n: c.open, of: c.lines })}</p>
      </div>
      {next ? <CountLineCard key={`${next.seq}-${next.again}`} view={c} line={next} />
        : <FormMessage tone="success"><CheckCircle2 className="mr-1 inline size-4" />{t('count.allDone')}</FormMessage>}
      {c.status === 'OPEN' && (
        <Button variant="outline" className="h-12" onClick={() => setFinding(true)}><PackagePlus /> {t('count.found')}</Button>
      )}
      <ol className="divide-y rounded-lg border text-sm">
        {c.items.map((l) => (
          <li key={l.seq} className={`flex items-center justify-between gap-2 px-3 py-2 ${l === next ? 'bg-secondary' : ''}`}>
            <span className="min-w-0 truncate"><span className="font-mono">{l.binCode}</span> · {l.sku}
              {l.added && <span className="ml-1 text-xs text-muted-foreground">{t('count.addedTag')}</span>}
              {l.again && <span className="ml-1 text-xs font-medium text-amber-700 dark:text-amber-400">{t('count.againTag')}</span>}</span>
            <span className="shrink-0 tabular-nums">{l.counted === null ? '—' : `${num(l.counted)} ${l.baseUom}`}</span>
          </li>
        ))}
      </ol>
      {finding && <FoundDialog view={c} onClose={() => setFinding(false)} />}
    </div>
  )
}

/** One place: go to the bin, scan the label and the product, type how many are there. */
function CountLineCard({ view, line }: { view: CounterView; line: CounterLine }) {
  const action = useCountAction()
  const [bin, setBin] = useState('')
  const [code, setCode] = useState('')
  const [qty, setQty] = useState('')
  const binOk = bin.trim() !== '' && bin.trim().toUpperCase() === line.binCode.toUpperCase()
  const ready = binOk && code.trim() !== '' && QTY.test(qty.trim())
  return (
    <section className="grid gap-3 rounded-xl border bg-card p-4">
      <p className="flex items-center gap-2 text-lg"><MapPin className="size-5 text-primary" />{t('pick.goTo')}
        <span className="font-mono text-2xl font-bold">{line.binCode}</span></p>
      <p>{t('count.countThis')} <span className="font-mono font-semibold">{line.sku}</span> {line.productName}
        {lot(line) && <span className="block text-sm text-muted-foreground">{lot(line)}</span>}</p>
      {line.again && <FormMessage tone="error">{t('count.again')}</FormMessage>}
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (ready) action.mutate({ kind: 'count', id: view.id, seq: line.seq, binCode: bin.trim(), code: code.trim(), qty: Number(qty) })
      }}>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label={t('pick.scanBin')} error={bin.trim() && !binOk ? t('pick.wrongBin', { bin: line.binCode }) : undefined}>
              {(p) => <Input {...p} autoFocus autoComplete="off" className="h-12 font-mono" maxLength={60} value={bin} onChange={(e) => setBin(e.target.value)} />}
            </Field>
          </div>
          <ScanButton label={t('scan.open')} onCode={setBin} />
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label={t('pick.scanProduct')}>
              {(p) => <Input {...p} autoComplete="off" className="h-12 font-mono" maxLength={64} value={code} onChange={(e) => setCode(e.target.value)} />}
            </Field>
          </div>
          <ScanButton label={t('scan.open')} onCode={setCode} />
        </div>
        <Field label={t('count.howMany')}>
          {(p) => <Input {...p} inputMode="decimal" className="h-12 text-lg" value={qty} onChange={(e) => setQty(e.target.value)} />}
        </Field>
        <p className="-mt-2 text-xs text-muted-foreground">{t('count.unitHint')}</p>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Button type="submit" className="h-12" disabled={!ready || action.isPending}>{t('count.save')}</Button>
      </form>
    </section>
  )
}

/** Goods in a bin that are not on the count: bin, product, quantity (and lot when the product tracks one). */
function FoundDialog({ view, onClose }: { view: CounterView; onClose: () => void }) {
  const action = useCountAction()
  const [bin, setBin] = useState('')
  const [code, setCode] = useState('')
  const [qty, setQty] = useState('')
  const [batch, setBatch] = useState('')
  const [expiry, setExpiry] = useState('')
  const valid = bin.trim() !== '' && code.trim() !== '' && QTY.test(qty.trim()) && Number(qty) > 0
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('count.foundTitle')} description={t('count.foundHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (valid) {
          action.mutate({ kind: 'found', id: view.id, binCode: bin.trim(), code: code.trim(), qty: Number(qty), batchNo: batch.trim(), expiryDate: expiry },
            { onSuccess: onClose })
        }
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <div className="flex items-end gap-2">
          <div className="flex-1"><Field label={t('pick.scanBin')}>{(p) => <Input {...p} autoFocus className="h-12 font-mono" maxLength={60} value={bin} onChange={(e) => setBin(e.target.value)} />}</Field></div>
          <ScanButton label={t('scan.open')} onCode={setBin} />
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1"><Field label={t('pick.scanProduct')}>{(p) => <Input {...p} className="h-12 font-mono" maxLength={64} value={code} onChange={(e) => setCode(e.target.value)} />}</Field></div>
          <ScanButton label={t('scan.open')} onCode={setCode} />
        </div>
        <Field label={t('count.howMany')}>{(p) => <Input {...p} inputMode="decimal" className="h-12" value={qty} onChange={(e) => setQty(e.target.value)} />}</Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label={t('receive.batch')}>{(p) => <Input {...p} maxLength={40} value={batch} onChange={(e) => setBatch(e.target.value)} />}</Field>
          <Field label={t('receive.expiry')}>{(p) => <Input {...p} type="date" value={expiry} onChange={(e) => setExpiry(e.target.value)} />}</Field>
        </div>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || action.isPending}>{t('count.foundSave')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
