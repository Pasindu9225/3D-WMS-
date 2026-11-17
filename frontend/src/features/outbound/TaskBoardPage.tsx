import { Layers } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { PRIORITIES, useBoard, useBoardAction, usePickers, useWaveAction, useWaves, type Priority, type PickSummary } from './api'

const SELECT = 'h-9 rounded-md border border-input bg-background px-2 text-sm'
const TONE: Record<Priority, 'bad' | 'warn' | 'neutral'> = { URGENT: 'bad', HIGH: 'warn', NORMAL: 'neutral' }

export function PriorityBadge({ priority }: { priority: Priority }) {
  return !priority || priority === 'NORMAL' ? null : <Badge tone={TONE[priority]}>{t(`board.priority.${priority}` as MessageKey)}</Badge>
}

/** How long a list has waited, in minutes or hours. */
function waited(createdAt: string, now: number) {
  const minutes = Math.max(0, Math.floor((now - new Date(createdAt).getTime()) / 60_000))
  return minutes < 60 ? t('board.minutes', { n: minutes }) : t('board.hours', { n: Math.floor(minutes / 60) })
}

/** /app/tasks: what waits at every stage of one warehouse, and who picks which list. Refreshes every 10 s. */
export function TaskBoardPage() {
  const me = useMe()
  const manage = can(me.data, 'tasks:assign')
  const warehouses = useWarehouses()
  const [chosen, setChosen] = useState('')
  const warehouseId = chosen || warehouses.data?.items[0]?.id
  const board = useBoard(warehouseId)
  const pickers = usePickers(warehouseId, manage)
  const action = useBoardAction()
  const waveAction = useWaveAction()
  const waves = useWaves(manage)
  const [picked, setPicked] = useState<string[]>([])
  const [cutOff, setCutOff] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 5_000)
    return () => clearInterval(timer)
  }, [])
  const b = board.data
  const tiles: [MessageKey, number | undefined][] = [
    ['board.toRelease', b?.toRelease], ['board.toPick', b?.toPick], ['board.picking', b?.picking], ['board.pickedToday', b?.pickedToday],
    ['board.toPack', b?.toPack], ['board.toDispatch', b?.toDispatch], ['board.inReceiving', b?.inReceiving],
  ]

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('board.title')}</h1>
          <p className="text-sm text-muted-foreground">
            {t('board.hint')}{board.dataUpdatedAt > 0 && ` ${t('board.updated', { n: Math.max(0, Math.round((now - board.dataUpdatedAt) / 1000)) })}`}
          </p>
        </div>
        {(warehouses.data?.items.length ?? 0) > 1 && (
          <select aria-label={t('po.col.warehouse')} className="h-10 rounded-md border border-input bg-background px-3" value={warehouseId ?? ''}
            onChange={(e) => setChosen(e.target.value)}>
            {warehouses.data?.items.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
          </select>
        )}
      </div>
      {board.isError && <FormMessage tone="error">{errorMessage(board.error)}</FormMessage>}
      {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
      {waveAction.isError && <FormMessage tone="error">{errorMessage(waveAction.error)}</FormMessage>}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
        {tiles.map(([label, n]) => (
          <div key={label} className="rounded-lg border bg-card p-3">
            <div className="text-2xl font-semibold tabular-nums">{n ?? '–'}</div>
            <div className="text-xs text-muted-foreground">{t(label)}</div>
          </div>
        ))}
      </div>
      {manage && warehouseId && (
        <div className="flex flex-wrap items-center gap-2">
          <Button disabled={picked.length < 2 || waveAction.isPending}
            onClick={() => waveAction.mutate({ kind: 'lists', warehouseId, listIds: picked }, { onSuccess: () => setPicked([]) })}>
            <Layers /> {t('wave.make', { n: picked.length })}
          </Button>
          <Button variant="outline" onClick={() => setCutOff(true)}>{t('wave.byCutOff')}</Button>
          <span className="text-xs text-muted-foreground">{t('wave.boardHint')}</span>
        </div>
      )}
      {manage && (waves.data?.items.length ?? 0) > 0 && (
        <ul className="grid gap-2 sm:grid-cols-2">
          {waves.data?.items.map((w) => (
            <li key={w.id} className="flex items-center justify-between gap-2 rounded-lg border bg-card p-3 text-sm">
              <span><span className="font-mono font-semibold">{w.number}</span> · {t('wave.orders', { n: w.orders })} · {t('wave.linesLeft', { n: w.openLines })}
                <span className="block text-xs text-muted-foreground">{t(('wave.status.' + w.status) as MessageKey)}{w.assignee ? ' · ' + w.assignee.name : ''}</span></span>
              {w.status === 'OPEN' && (
                <Button size="sm" variant="outline" disabled={waveAction.isPending} onClick={() => waveAction.mutate({ kind: 'dissolve', id: w.id })}>{t('wave.dissolve')}</Button>
              )}
            </li>
          ))}
        </ul>
      )}
      <Table>
        <thead>
          <tr>
            {manage && <Th className="w-1"><span className="sr-only">{t('counts.pick')}</span></Th>}
            <Th>{t('board.col.list')}</Th>
            <Th>{t('so.col.customer')}</Th>
            <Th>{t('board.col.priority')}</Th>
            <Th>{t('board.col.picker')}</Th>
            <Th className="text-right">{t('board.col.left')}</Th>
            <Th className="text-right">{t('board.col.waiting')}</Th>
          </tr>
        </thead>
        <tbody>
          {b?.lists.map((l) => <Row key={l.id} list={l} now={now} manage={manage} pickers={pickers.data ?? []}
            picked={picked.includes(l.id)} onPick={() => setPicked((p) => (p.includes(l.id) ? p.filter((x) => x !== l.id) : [...p, l.id]))}
            onAssign={(userId) => action.mutate({ kind: 'assign', id: l.id, userId })}
            onPriority={(priority) => action.mutate({ kind: 'priority', id: l.id, priority })} />)}
          {b && b.lists.length === 0 && <tr><td colSpan={7} className="py-8 text-center text-muted-foreground">{t('board.empty')}</td></tr>}
        </tbody>
      </Table>
      {cutOff && warehouseId && <CutOffDialog warehouseId={warehouseId} pickers={pickers.data ?? []} onClose={() => setCutOff(false)} />}
    </div>
  )
}

/** "Wave by cut-off": the oldest open orders wanted by a date (or with no date), up to a number of totes. */
function CutOffDialog({ warehouseId, pickers, onClose }: { warehouseId: string; pickers: { id: string; name: string }[]; onClose: () => void }) {
  const action = useWaveAction()
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [max, setMax] = useState('10')
  const [picker, setPicker] = useState('')
  const valid = date !== '' && /^[0-9]{1,2}$/.test(max) && Number(max) >= 2 && Number(max) <= 20
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('wave.byCutOff')} description={t('wave.cutOffHint')}>
      <form className="grid gap-3" onSubmit={(e) => {
        e.preventDefault()
        if (valid) action.mutate({ kind: 'cutOff', warehouseId, cutOff: date, maxOrders: Number(max), pickerId: picker || undefined }, { onSuccess: onClose })
      }}>
        {action.isError && <FormMessage tone="error">{errorMessage(action.error)}</FormMessage>}
        <Field label={t('wave.wantedBy')}>{(p) => <Input {...p} type="date" value={date} onChange={(e) => setDate(e.target.value)} />}</Field>
        <Field label={t('wave.maxOrders')}>{(p) => <Input {...p} inputMode="numeric" value={max} onChange={(e) => setMax(e.target.value)} />}</Field>
        <Field label={t('wave.giveTo')}>
          {(p) => (
            <select {...p} className="h-10 rounded-md border border-input bg-background px-3" value={picker} onChange={(e) => setPicker(e.target.value)}>
              <option value="">{t('board.anyone')}</option>
              {pickers.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          )}
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={!valid || action.isPending}>{t('wave.create')}</Button>
        </div>
      </form>
    </Dialog>
  )
}

function Row({ list, now, manage, pickers, onAssign, onPriority, picked, onPick }: {
  list: PickSummary
  picked: boolean
  onPick: () => void
  now: number
  manage: boolean
  pickers: { id: string; name: string }[]
  onAssign: (userId: string | null) => void
  onPriority: (p: Priority) => void
}) {
  const late = now - new Date(list.createdAt).getTime() > 2 * 3_600_000
  const status = list.status === 'IN_PROGRESS' && list.startedBy ? t('board.pickingBy', { name: list.startedBy.name }) : t(`pick.status.${list.status}` as MessageKey)
  return (
    <Tr>
      {manage && (
        <td><input type="checkbox" aria-label={t('wave.select', { number: list.number })} checked={picked} onChange={onPick}
          disabled={list.status !== 'OPEN' || list.wave !== null} /></td>
      )}
      <td><span className="font-mono font-medium">{list.number}</span> <span className="text-xs text-muted-foreground">{list.orderNumber}</span>
        <span className="block text-xs text-muted-foreground">{status}{list.wave ? ' · ' + t('wave.inWave', { wave: list.wave, tote: list.tote ?? 0 }) : ''}</span></td>
      <td className="text-sm">{list.customerName}</td>
      <td>
        {manage ? (
          <select aria-label={t('board.priorityOf', { number: list.number })} className={SELECT} value={list.priority}
            onChange={(e) => onPriority(e.target.value as Priority)}>
            {PRIORITIES.map((p) => <option key={p} value={p}>{t(`board.priority.${p}` as MessageKey)}</option>)}
          </select>
        ) : <PriorityBadge priority={list.priority} />}
      </td>
      <td>
        {manage ? (
          <select aria-label={t('board.giveTo', { number: list.number })} className={SELECT} value={list.assignee?.id ?? ''}
            onChange={(e) => onAssign(e.target.value || null)}>
            <option value="">{t('board.anyone')}</option>
            {list.assignee && !pickers.some((p) => p.id === list.assignee?.id) && <option value={list.assignee.id}>{list.assignee.name}</option>}
            {pickers.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        ) : <span className="text-sm">{list.assignee?.name ?? t('board.anyone')}</span>}
      </td>
      <td className="text-right tabular-nums">{list.openLines} / {list.lines}</td>
      <td className={`text-right text-sm tabular-nums ${late ? 'font-medium text-destructive' : ''}`}>{waited(list.createdAt, now)}</td>
    </Tr>
  )
}
