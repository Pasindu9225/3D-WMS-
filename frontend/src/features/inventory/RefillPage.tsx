import { ArrowLeft, ArrowUpFromLine, CheckCircle2, MapPin } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { ScanButton } from '@/features/worker/Scanner'
import { useBase } from '@/lib/base'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useRefillConfirm, useRefillTask, useRefillTasks, type RefillTask } from './refill'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

/** /worker/refill (and /app/refill-tasks): refills to do; /refill/:id does one (bulk bin -> pick bin). */
export function RefillPage() {
  const { id } = useParams()
  return id ? <RefillDo id={id} /> : <RefillList />
}

function RefillList() {
  const base = useBase()
  const tasks = useRefillTasks()
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('refill.tasksTitle')}</h1>
        <p className="text-sm text-muted-foreground">{t('refill.tasksHint')}</p>
      </div>
      {tasks.isError && <FormMessage tone="error">{errorMessage(tasks.error)}</FormMessage>}
      <ul className="grid gap-2">
        {tasks.data?.items.map((r) => (
          <li key={r.id}>
            <Link to={`${base}/refill/${r.id}`} className="flex items-center gap-3 rounded-lg border bg-card p-4 hover:bg-secondary">
              <ArrowUpFromLine className="size-6 shrink-0 text-primary" />
              <span className="min-w-0 flex-1"><span className="font-mono font-semibold">{r.binCode}</span> · {r.sku}
                <span className="block truncate text-sm text-muted-foreground">{r.number} · {r.productName}</span></span>
              <span className="text-sm tabular-nums">{num(r.qty)} {r.baseUom}</span>
            </Link>
          </li>
        ))}
      </ul>
      {tasks.data && tasks.data.items.length === 0 && <p className="rounded-lg border p-4 text-sm text-muted-foreground">{t('refill.none')}</p>}
    </div>
  )
}

function RefillDo({ id }: { id: string }) {
  const base = useBase()
  const task = useRefillTask(id)
  if (task.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (task.isError) return <FormMessage tone="error">{errorMessage(task.error)}</FormMessage>
  const r = task.data
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <Link to={`${base}/refill`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('refill.back')}
      </Link>
      <h1 className="text-xl font-semibold"><span className="font-mono">{r.number}</span> · {r.sku} {r.productName}</h1>
      {r.status === 'OPEN' ? <RefillCard task={r} />
        : <FormMessage tone="success"><CheckCircle2 className="mr-1 inline size-4" />{t('refill.done', { qty: num(r.movedQty ?? 0), uom: r.baseUom, bin: r.binCode })}</FormMessage>}
    </div>
  )
}

/** Take from the suggested storage bin (or another), put into the pick bin. */
function RefillCard({ task }: { task: RefillTask }) {
  const confirm = useRefillConfirm()
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [code, setCode] = useState('')
  const [qty, setQty] = useState(String(task.from ? Math.min(task.qty, task.from.free) : task.qty))
  const toOk = to.trim() !== '' && to.trim().toUpperCase() === task.binCode.toUpperCase()
  const ready = from.trim() !== '' && toOk && code.trim() !== '' && QTY.test(qty.trim()) && Number(qty) > 0
  return (
    <section className="grid gap-3 rounded-xl border bg-card p-4">
      {task.from ? (
        <p className="flex items-center gap-2 text-lg"><MapPin className="size-5 text-primary" />{t('refill.takeFrom')}
          <span className="font-mono text-2xl font-bold">{task.from.binCode}</span></p>
      ) : <FormMessage tone="error">{t('refill.noSource')}</FormMessage>}
      <p>{t('refill.bring', { qty: num(task.qty), uom: task.baseUom })} <span className="font-mono font-semibold">{task.binCode}</span>
        {task.from && (task.from.batchNo || task.from.expiryDate) && <span className="block text-sm text-muted-foreground">{[task.from.batchNo, task.from.expiryDate].filter(Boolean).join(' · ')}</span>}</p>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (ready) confirm.mutate({ id: task.id, fromBinCode: from.trim(), toBinCode: to.trim(), code: code.trim(), qty: Number(qty) })
      }}>
        <div className="flex items-end gap-2">
          <div className="flex-1"><Field label={t('refill.scanFrom')}>{(p) => <Input {...p} autoFocus className="h-12 font-mono" maxLength={60} value={from} onChange={(e) => setFrom(e.target.value)} />}</Field></div>
          <ScanButton label={t('scan.open')} onCode={setFrom} />
        </div>
        <div className="flex items-end gap-2">
          <div className="flex-1"><Field label={t('pick.scanProduct')}>{(p) => <Input {...p} className="h-12 font-mono" maxLength={64} value={code} onChange={(e) => setCode(e.target.value)} />}</Field></div>
          <ScanButton label={t('scan.open')} onCode={setCode} />
        </div>
        <Field label={t('pick.qty')}>{(p) => <Input {...p} inputMode="decimal" className="h-12 text-lg" value={qty} onChange={(e) => setQty(e.target.value)} />}</Field>
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label={t('refill.scanTo')} error={to.trim() && !toOk ? t('pick.wrongBin', { bin: task.binCode }) : undefined}>
              {(p) => <Input {...p} className="h-12 font-mono" maxLength={60} value={to} onChange={(e) => setTo(e.target.value)} />}
            </Field>
          </div>
          <ScanButton label={t('scan.open')} onCode={setTo} />
        </div>
        {confirm.isError && <FormMessage tone="error">{errorMessage(confirm.error)}</FormMessage>}
        <Button type="submit" className="h-12" disabled={!ready || confirm.isPending}>{t('refill.confirm')}</Button>
      </form>
    </section>
  )
}
