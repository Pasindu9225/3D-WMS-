import { ArrowLeft, CheckCircle2, ClipboardList, Layers, MapPin, Route } from 'lucide-react'
import { useRoute } from '@/features/view3d/route'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { ScanButton } from '@/features/worker/Scanner'
import { useBase } from '@/lib/base'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { usePickAction, usePickList, usePickLists, useWave, useWaves, type PickLineView, type ShortReason } from './api'
import { PriorityBadge } from './TaskBoardPage'

const QTY = /^\d{1,7}(\.\d{1,3})?$/
const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')
/** What a line card needs: a single list's line or a wave's walk line. */
type CardLine = Pick<PickLineView, 'seq' | 'sku' | 'productName' | 'baseUom' | 'binCode' | 'batchNo' | 'expiryDate' | 'qtyToPick' | 'qtyPicked'>
const lot = (l: CardLine) => [l.batchNo, l.expiryDate].filter(Boolean).join(' · ')

/** /worker/pick (and /app/pick): open pick lists; /pick/:id walks one list line by line. */
export function PickPage() {
  const { id, waveId } = useParams()
  return waveId ? <WaveWalk id={waveId} /> : id ? <PickWalk id={id} /> : <PickLists />
}

function PickLists() {
  const base = useBase()
  const lists = usePickLists()
  const waves = useWaves()
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('pick.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('pick.hint')}</p>
      </div>
      {lists.isError && <FormMessage tone="error">{errorMessage(lists.error)}</FormMessage>}
      {(waves.data?.items.length ?? 0) > 0 && (
        <ul className="grid gap-2">
          {waves.data?.items.map((w) => (
            <li key={w.id}>
              <Link to={`${base}/pick/wave/${w.id}`} className="flex items-center gap-3 rounded-lg border-2 border-primary/40 bg-card p-4 hover:bg-secondary">
                <Layers className="size-6 shrink-0 text-primary" />
                <span className="min-w-0 flex-1">
                  <span className="font-mono font-semibold">{w.number}</span> · {t('wave.orders', { n: w.orders })} {w.urgent && <PriorityBadge priority="URGENT" />}
                  <span className="block truncate text-sm text-muted-foreground">{w.warehouseCode}{w.assignee ? ` · ${w.assignee.name}` : ''}</span>
                </span>
                <span className="text-sm tabular-nums">{t('wave.linesLeft', { n: w.openLines })}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <ul className="grid gap-2">
        {lists.data?.items.map((p) => (
          <li key={p.id}>
            <Link to={`${base}/pick/${p.id}`} className="flex items-center gap-3 rounded-lg border bg-card p-4 hover:bg-secondary">
              <ClipboardList className="size-6 shrink-0 text-primary" />
              <span className="min-w-0 flex-1">
                <span className="font-mono font-semibold">{p.number}</span> · {p.orderNumber} <PriorityBadge priority={p.priority} />
                <span className="block truncate text-sm text-muted-foreground">{p.customerName} · {p.warehouseCode}</span>
              </span>
              <span className="text-sm tabular-nums">{t('pick.linesLeft', { n: p.openLines, of: p.lines })}</span>
            </Link>
          </li>
        ))}
      </ul>
      {lists.data && lists.data.items.length === 0 && (waves.data?.items.length ?? 0) === 0 && <p className="rounded-lg border p-4 text-sm text-muted-foreground">{t('pick.none')}</p>}
    </div>
  )
}

function PickWalk({ id }: { id: string }) {
  const base = useBase()
  const list = usePickList(id)
  if (list.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (list.isError) return <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>
  const p = list.data
  const next = p.lines.find((l) => l.status === 'OPEN')
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <Link to={`${base}/pick`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('pick.back')}
      </Link>
      <div>
        <h1 className="text-xl font-semibold"><span className="font-mono">{p.number}</span> · {p.orderNumber}</h1>
        <p className="text-sm text-muted-foreground">{p.customerName} · {t('pick.linesLeft', { n: p.openLines, of: p.lines.length })}</p>
        {next && <ShowRoute param={'list:' + p.id} />}
      </div>
      {next ? <PickLineCard key={`${next.seq}:${next.qtyPicked}`} listId={p.id} line={next} />
        : <FormMessage tone="success"><CheckCircle2 className="mr-1 inline size-4" />{t('pick.allDone')}</FormMessage>}
      <ol className="divide-y rounded-lg border text-sm">
        {p.lines.map((l) => (
          <li key={l.seq} className={`flex items-center justify-between gap-2 px-3 py-2 ${l === next ? 'bg-secondary' : ''}`}>
            <span className="min-w-0 truncate"><span className="font-mono">{l.binCode}</span> · {l.sku}</span>
            <span className="shrink-0 tabular-nums">
              {num(l.qtyPicked)} / {num(l.qtyToPick)} {l.baseUom}
              {l.status === 'SHORT' && <span className="ml-1 text-destructive">{t('pick.short')}</span>}
            </span>
          </li>
        ))}
      </ol>
    </div>
  )
}

/** One line: go to the bin, scan its label and the product, count, confirm. In a wave, tote says which box it goes in. */
function PickLineCard({ listId, line, tote, orderNumber }: { listId: string; line: CardLine; tote?: number; orderNumber?: string }) {
  const action = usePickAction()
  const left = line.qtyToPick - line.qtyPicked
  const [bin, setBin] = useState('')
  const [code, setCode] = useState('')
  const [qty, setQty] = useState(String(Number(left.toFixed(3))))
  const [shorting, setShorting] = useState(false)
  const binOk = bin.trim() !== '' && bin.trim().toUpperCase() === line.binCode.toUpperCase()
  const ready = binOk && code.trim() !== '' && QTY.test(qty.trim()) && Number(qty) > 0

  return (
    <section className="grid gap-3 rounded-xl border bg-card p-4">
      <p className="flex items-center gap-2 text-lg"><MapPin className="size-5 text-primary" />{t('pick.goTo')}
        <span className="font-mono text-2xl font-bold">{line.binCode}</span></p>
      <p>{t('pick.take', { qty: num(left), uom: line.baseUom })} <span className="font-mono font-semibold">{line.sku}</span> {line.productName}
        {lot(line) && <span className="block text-sm text-muted-foreground">{lot(line)}</span>}</p>
      {tote !== undefined && (
        <p className="flex items-center gap-3 rounded-lg bg-primary/10 px-3 py-2">
          <span className="text-3xl font-bold tabular-nums text-primary">{tote}</span>
          <span className="text-sm">{t('wave.putInTote', { tote })}<span className="block text-xs text-muted-foreground">{orderNumber}</span></span>
        </p>
      )}
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (ready) action.mutate({ kind: 'confirm', id: listId, seq: line.seq, key: crypto.randomUUID(), binCode: bin.trim(), code: code.trim(), qty: Number(qty) })
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
        <Field label={t('pick.qty')}>
          {(p) => <Input {...p} inputMode="decimal" className="h-12 text-lg" value={qty} onChange={(e) => setQty(e.target.value)} />}
        </Field>
        <p className="-mt-2 text-xs text-muted-foreground">{t('pick.unitHint')}</p>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <div className="grid grid-cols-2 gap-2">
          <Button type="button" variant="outline" className="h-12" onClick={() => setShorting(true)}>{t('pick.shortButton')}</Button>
          <Button type="submit" className="h-12" disabled={!ready || action.isPending}>{t('pick.confirm')}</Button>
        </div>
      </form>
      {shorting && <ShortDialog listId={listId} line={line} left={left} onClose={() => setShorting(false)} />}
    </section>
  )
}

/** Fewer there than expected: pick what is there (if any), then close the rest of the line. */
function ShortDialog({ listId, line, left, onClose }: { listId: string; line: CardLine; left: number; onClose: () => void }) {
  const action = usePickAction()
  const [found, setFound] = useState('0')
  const [reason, setReason] = useState<ShortReason>('NOT_THERE')
  const valid = QTY.test(found.trim()) && Number(found) < left
  const submit = async () => {
    if (Number(found) > 0) {
      await action.mutateAsync({ kind: 'confirm', id: listId, seq: line.seq, key: crypto.randomUUID(), binCode: line.binCode, code: line.sku, qty: Number(found) })
    }
    await action.mutateAsync({ kind: 'short', id: listId, seq: line.seq, reason })
    onClose()
  }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('pick.shortTitle', { sku: line.sku, bin: line.binCode })}
      description={t('pick.shortHint', { qty: num(left), uom: line.baseUom })}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (valid) void submit().catch(() => undefined)
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('pick.found', { uom: line.baseUom })}>
          {(p) => <Input {...p} inputMode="decimal" className="h-12" value={found} onChange={(e) => setFound(e.target.value)} />}
        </Field>
        <Field label={t('pick.reason')}>
          {(p) => (
            <select {...p} className="h-12 rounded-md border border-input bg-background px-3" value={reason}
              onChange={(e) => setReason(e.target.value as ShortReason)}>
              <option value="NOT_THERE">{t('pick.reason.NOT_THERE')}</option>
              <option value="DAMAGED">{t('pick.reason.DAMAGED')}</option>
            </select>
          )}
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" variant="destructive" disabled={!valid || action.isPending}>{t('pick.shortSave')}</Button>
        </div>
      </form>
    </Dialog>
  )
}

/** A wave: one walk through the bins for several orders; each line says which tote it goes into. */
function WaveWalk({ id }: { id: string }) {
  const base = useBase()
  const wave = useWave(id)
  if (wave.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (wave.isError) return <FormMessage tone="error">{errorMessage(wave.error)}</FormMessage>
  const w = wave.data
  const next = w.walk.find((l) => l.status === 'OPEN')
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <Link to={`${base}/pick`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('pick.back')}
      </Link>
      <div>
        <h1 className="text-xl font-semibold"><span className="font-mono">{w.number}</span> · {t('wave.orders', { n: w.orders.length })}</h1>
        <p className="text-sm text-muted-foreground">{t('wave.linesLeft', { n: w.openLines })} · {t('wave.walk', { m: Math.round(w.walkMetres), single: Math.round(w.singleWalksMetres) })}</p>
        {next && <ShowRoute param={'wave:' + w.id} />}
      </div>
      {next ? <PickLineCard key={`${next.listId}:${next.seq}:${next.qtyPicked}`} listId={next.listId} line={next} tote={next.tote} orderNumber={next.orderNumber} />
        : <FormMessage tone="success"><CheckCircle2 className="mr-1 inline size-4" />{t('wave.allDone')}</FormMessage>}
      <ol className="divide-y rounded-lg border text-sm">
        {w.walk.map((l) => (
          <li key={`${l.listId}:${l.seq}`} className={`flex items-center justify-between gap-2 px-3 py-2 ${l === next ? 'bg-secondary' : ''}`}>
            <span className="min-w-0 truncate"><span className="font-mono">{l.binCode}</span> · {l.sku} · <span className="font-semibold">{t('wave.tote', { tote: l.tote })}</span></span>
            <span className="shrink-0 tabular-nums">
              {num(l.qtyPicked)} / {num(l.qtyToPick)} {l.baseUom}
              {l.status === 'SHORT' && <span className="ml-1 text-destructive">{t('pick.short')}</span>}
            </span>
          </li>
        ))}
      </ol>
    </div>
  )
}

/** Opens the warehouse plan with this walk drawn on it (v1.2). */
function ShowRoute({ param }: { param: string }) {
  const route = useRoute(param)
  if (!route.data || route.data.stops.length === 0) return null
  return (
    <Link to={'/app/warehouses/' + route.data.warehouseId + '/3d?route=' + param}
      className="mt-1 inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline">
      <Route className="size-4" /> {t(route.data.stops.length === 1 ? 'route.showOne' : 'route.show', { n: route.data.stops.length, m: Math.round(route.data.metres) })}
    </Link>
  )
}
