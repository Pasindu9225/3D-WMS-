import { ArrowLeft, PackageX } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { can, useMe } from '@/features/auth/session'
import { ScanButton } from '@/features/worker/Scanner'
import { useBase } from '@/lib/base'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { ReturnStatusBadge } from './ReturnsPage'
import { useReturn, useReturnAction, useReturns, type ReturnDetail, type ReturnLineView, type ReturnOutcome } from './returns'

const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

/** /worker/returns (and /app/returns/:id): inspect returned goods line by line: restock, quarantine or scrap. */
export function ReturnPage() {
  const { id } = useParams()
  return id ? <ReturnInspect id={id} /> : <ReturnList />
}

/** Phone: returns waiting for inspection. */
function ReturnList() {
  const base = useBase()
  const list = useReturns()
  return (
    <div className="mx-auto grid max-w-2xl gap-4">
      <div>
        <h1 className="text-2xl font-semibold">{t('ret.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('ret.inspectHint')}</p>
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      <ul className="grid gap-2">
        {list.data?.items.map((r) => (
          <li key={r.id}>
            <Link to={`${base}/returns/${r.id}`} className="flex items-center gap-3 rounded-lg border bg-card p-4 hover:bg-secondary">
              <PackageX className="size-6 shrink-0 text-primary" />
              <span className="min-w-0 flex-1"><span className="font-mono font-semibold">{r.number}</span> · {r.orderNumber}
                <span className="block truncate text-sm text-muted-foreground">{r.customerName}</span></span>
              <span className="text-sm tabular-nums">{t('ret.toInspect', { n: r.open })}</span>
            </Link>
          </li>
        ))}
      </ul>
      {list.data && list.data.items.length === 0 && <p className="rounded-lg border p-4 text-sm text-muted-foreground">{t('ret.none')}</p>}
    </div>
  )
}

function ReturnInspect({ id }: { id: string }) {
  const base = useBase()
  const me = useMe()
  const ret = useReturn(id)
  const [cancelling, setCancelling] = useState(false)
  if (ret.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (ret.isError) return <FormMessage tone="error">{errorMessage(ret.error)}</FormMessage>
  const r = ret.data
  const mayInspect = can(me.data, 'inbound:receive') && r.status === 'OPEN'
  const mayCancel = can(me.data, 'outbound:manage') && r.status === 'OPEN' && r.lines.every((l) => l.outcome === null)
  return (
    <div className="mx-auto grid max-w-3xl gap-4">
      <Link to={base === '/worker' ? '/worker/returns' : '/app/returns'} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('ret.back')}
      </Link>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h1 className="flex items-center gap-2 text-xl font-semibold"><span className="font-mono">{r.number}</span> <ReturnStatusBadge status={r.status} /></h1>
          <p className="text-sm text-muted-foreground">{r.orderNumber} · {r.customerName} · {r.warehouseCode}</p>
          {r.note && <p className="text-sm">{r.note}</p>}
          {r.cancelReason && <p className="text-sm text-muted-foreground">{t('counts.cancelledBecause', { reason: r.cancelReason })}</p>}
        </div>
        {mayCancel && <Button variant="outline" onClick={() => setCancelling(true)}>{t('ret.cancel')}</Button>}
      </div>
      <ul className="grid gap-3">
        {r.lines.map((l) => (
          <li key={l.seq} className="rounded-xl border bg-card p-4">
            <p><span className="font-mono font-semibold">{l.sku}</span> {l.productName} · <span className="tabular-nums">{num(l.qty)} {l.baseUom}</span></p>
            <p className="text-sm text-muted-foreground">{t(`ret.reason.${l.reason}` as MessageKey)}</p>
            {l.outcome ? (
              <p className="mt-1 text-sm font-medium">{t(`ret.outcome.${l.outcome}` as MessageKey)}{l.binCode && <> · <span className="font-mono">{l.binCode}</span></>}
                {l.inspectedBy && <span className="font-normal text-muted-foreground"> · {l.inspectedBy}</span>}</p>
            ) : mayInspect ? <InspectForm ret={r} line={l} /> : <p className="mt-1 text-sm text-muted-foreground">{t('ret.waiting')}</p>}
          </li>
        ))}
      </ul>
      {cancelling && <CancelDialog ret={r} onClose={() => setCancelling(false)} />}
    </div>
  )
}

/** Restock (scan a bin), quarantine (scan a quarantine bin) or scrap; batch and expiry when the pack shows them. */
function InspectForm({ ret, line }: { ret: ReturnDetail; line: ReturnLineView }) {
  const action = useReturnAction()
  const [outcome, setOutcome] = useState<ReturnOutcome>('RESTOCK')
  const [bin, setBin] = useState('')
  const [batch, setBatch] = useState('')
  const [expiry, setExpiry] = useState('')
  const needsBin = outcome !== 'SCRAP'
  const ready = !needsBin || bin.trim() !== ''
  return (
    <form className="mt-3 grid gap-3" onSubmit={(e) => {
      e.preventDefault()
      if (ready) action.mutate({ kind: 'inspect', id: ret.id, seq: line.seq, outcome, binCode: needsBin ? bin.trim() : undefined, batchNo: batch.trim(), expiryDate: expiry })
    }}>
      <div role="radiogroup" aria-label={t('ret.whatHappens', { sku: line.sku })} className="grid grid-cols-3 gap-2">
        {(['RESTOCK', 'QUARANTINE', 'SCRAP'] as ReturnOutcome[]).map((o) => (
          <button key={o} type="button" role="radio" aria-checked={outcome === o} onClick={() => setOutcome(o)}
            className={`h-11 rounded-md border text-sm font-medium ${outcome === o ? 'border-primary bg-primary text-primary-foreground' : 'hover:bg-secondary'}`}>
            {t(`ret.outcome.${o}` as MessageKey)}
          </button>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">{t(`ret.outcomeHint.${outcome}` as MessageKey)}</p>
      {needsBin && (
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <Field label={t('pick.scanBin')}>
              {(p) => <Input {...p} autoComplete="off" className="h-12 font-mono" maxLength={60} value={bin} onChange={(e) => setBin(e.target.value)} />}
            </Field>
          </div>
          <ScanButton label={t('scan.open')} onCode={setBin} />
        </div>
      )}
      {needsBin && (
        <div className="grid grid-cols-2 gap-2">
          <Field label={t('receive.batch')}>{(p) => <Input {...p} maxLength={40} value={batch} onChange={(e) => setBatch(e.target.value)} />}</Field>
          <Field label={t('receive.expiry')}>{(p) => <Input {...p} type="date" value={expiry} onChange={(e) => setExpiry(e.target.value)} />}</Field>
        </div>
      )}
      {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      <Button type="submit" className="h-12" disabled={!ready || action.isPending}>{t('ret.save')}</Button>
    </form>
  )
}

function CancelDialog({ ret, onClose }: { ret: ReturnDetail; onClose: () => void }) {
  const action = useReturnAction()
  const [reason, setReason] = useState('')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('ret.cancelTitle', { number: ret.number })} description={t('ret.cancelHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (reason.trim()) action.mutate({ kind: 'cancel', id: ret.id, version: ret.version, reason: reason.trim() }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('po.cancelReason')}>{(p) => <Input {...p} autoFocus maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.close')}</Button>
          <Button type="submit" variant="destructive" disabled={!reason.trim() || action.isPending}>{t('ret.cancel')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
