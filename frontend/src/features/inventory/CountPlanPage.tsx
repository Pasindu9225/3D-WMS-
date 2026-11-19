import { ArrowLeft } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { formatMoney } from '@/features/layout/units'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { CountStatusBadge } from './CountsPage'
import { useReview, useReviewAction, type ReviewView } from './counts'

const num = (n: number) => Number(Number(n).toFixed(3)).toLocaleString('en')

/** /app/counts/:id: the manager's review: expected, counted, difference and value; recount lines or approve. */
export function CountPlanPage() {
  const { id } = useParams()
  const review = useReview(id)
  const action = useReviewAction()
  const [picked, setPicked] = useState<number[]>([])
  const [confirming, setConfirming] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  if (review.isPending) return <p className="text-sm text-muted-foreground">{t('app.loading')}</p>
  if (review.isError) return <FormMessage tone="error">{errorMessage(review.error)}</FormMessage>
  const c = review.data
  const live = c.status === 'OPEN' || c.status === 'REVIEW'
  const toggle = (seq: number) => setPicked((p) => (p.includes(seq) ? p.filter((s) => s !== seq) : [...p, seq]))
  return (
    <div className="grid gap-4">
      <Link to="/app/counts" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" /> {t('counts.back')}
      </Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-semibold"><span className="font-mono">{c.number}</span> <CountStatusBadge status={c.status} /></h1>
          <p className="text-sm text-muted-foreground">{c.warehouseCode} · {t(`count.scope.${c.scope}` as MessageKey, { v: c.scopeValue ?? '' })}</p>
        </div>
        {live && (
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" disabled={picked.length === 0 || action.isPending}
              onClick={() => action.mutate({ kind: 'recount', id: c.id, version: c.version, lines: picked }, { onSuccess: () => setPicked([]) })}>
              {t('counts.recount', { n: picked.length })}
            </Button>
            <Button variant="outline" onClick={() => setCancelling(true)}>{t('counts.cancel')}</Button>
            <Button disabled={c.status !== 'REVIEW' || action.isPending} onClick={() => setConfirming(true)}>{t('counts.approve')}</Button>
          </div>
        )}
      </div>
      {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      {c.status === 'OPEN' && <FormMessage tone="success">{t('counts.stillCounting')}</FormMessage>}
      {c.cancelReason && <p className="text-sm text-muted-foreground">{t('counts.cancelledBecause', { reason: c.cancelReason })}</p>}
      <p className="text-sm">{t('counts.summary', { n: c.differences, value: formatMoney(c.valueMinor) })}
        {' · '}{c.tolerancePct === null ? t('counts.noLimit') : t('counts.limit', { pct: num(c.tolerancePct) })}</p>
      <Table>
        <thead>
          <tr>
            {live && <Th className="w-1"><span className="sr-only">{t('counts.pick')}</span></Th>}
            <Th>{t('stock.col.place')}</Th><Th>{t('stock.col.product')}</Th><Th>{t('stock.col.lot')}</Th>
            <Th className="text-right">{t('counts.col.expected')}</Th><Th className="text-right">{t('counts.col.counted')}</Th>
            <Th className="text-right">{t('counts.col.difference')}</Th><Th className="text-right">{t('counts.col.value')}</Th>
            <Th className="hidden md:table-cell">{t('counts.col.by')}</Th>
          </tr>
        </thead>
        <tbody>
          {c.lines.map((l) => (
            <Tr key={l.seq}>
              {live && (
                <td><input type="checkbox" aria-label={t('counts.pickLine', { bin: l.binCode, sku: l.sku })} disabled={l.status !== 'COUNTED'}
                  checked={picked.includes(l.seq)} onChange={() => toggle(l.seq)} /></td>
              )}
              <td className="font-mono text-sm">{l.binCode}</td>
              <td><span className="font-mono text-sm font-semibold">{l.sku}</span> {l.productName}
                {l.added && <span className="ml-1 text-xs text-muted-foreground">{t('count.addedTag')}</span>}</td>
              <td className="text-sm">{[l.batchNo, l.expiry].filter(Boolean).join(' · ') || '—'}</td>
              <td className="text-right tabular-nums">{l.expected === null ? '—' : num(l.expected)}</td>
              <td className="text-right tabular-nums">{l.counted === null ? t('counts.notYet') : `${num(l.counted)} ${l.baseUom}`}
                {l.firstCount !== null && <span className="block text-xs text-muted-foreground">{t('counts.firstCount', { n: num(l.firstCount) })}</span>}</td>
              <td className={`text-right tabular-nums ${l.difference ? 'font-medium text-destructive' : ''}`}>
                {l.difference === null ? '—' : (l.difference > 0 ? '+' : '') + num(l.difference)}
                {l.overLimit && <span className="block text-xs">{t('counts.overLimit')}</span>}
              </td>
              <td className="text-right tabular-nums">{l.valueMinor === null || l.difference === 0 ? '—' : formatMoney(l.valueMinor)}</td>
              <td className="hidden text-sm md:table-cell">{l.countedBy ?? '—'}</td>
            </Tr>
          ))}
        </tbody>
      </Table>
      {confirming && <ApproveDialog count={c} onClose={() => setConfirming(false)} />}
      {cancelling && <CancelDialog count={c} onClose={() => setCancelling(false)} />}
    </div>
  )
}

function ApproveDialog({ count, onClose }: { count: ReviewView; onClose: () => void }) {
  const action = useReviewAction()
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('counts.approveTitle', { number: count.number })}
      description={t('counts.approveHint', { n: count.differences, value: formatMoney(count.valueMinor) })}>
      {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
        <Button disabled={action.isPending} onClick={() => action.mutate({ kind: 'approve', id: count.id, version: count.version }, { onSuccess: onClose })}>
          {t('counts.approve')}
        </Button>
      </div>
    </Dialog>
  )
}

function CancelDialog({ count, onClose }: { count: ReviewView; onClose: () => void }) {
  const action = useReviewAction()
  const [reason, setReason] = useState('')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('counts.cancelTitle', { number: count.number })} description={t('counts.cancelHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (reason.trim()) action.mutate({ kind: 'cancel', id: count.id, version: count.version, reason: reason.trim() }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('po.cancelReason')}>{(p) => <Input {...p} autoFocus maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.close')}</Button>
          <Button type="submit" variant="destructive" disabled={!reason.trim() || action.isPending}>{t('counts.cancel')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
