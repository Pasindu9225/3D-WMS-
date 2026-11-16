import { useEffect, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { FormMessage } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { useWarehouses } from '@/features/warehouses/api'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { PRIORITIES, useBoard, useBoardAction, usePickers, type Priority, type PickSummary } from './api'

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
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
        {tiles.map(([label, n]) => (
          <div key={label} className="rounded-lg border bg-card p-3">
            <div className="text-2xl font-semibold tabular-nums">{n ?? '–'}</div>
            <div className="text-xs text-muted-foreground">{t(label)}</div>
          </div>
        ))}
      </div>
      <Table>
        <thead>
          <tr>
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
            onAssign={(userId) => action.mutate({ kind: 'assign', id: l.id, userId })}
            onPriority={(priority) => action.mutate({ kind: 'priority', id: l.id, priority })} />)}
          {b && b.lists.length === 0 && <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{t('board.empty')}</td></tr>}
        </tbody>
      </Table>
    </div>
  )
}

function Row({ list, now, manage, pickers, onAssign, onPriority }: {
  list: PickSummary
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
      <td><span className="font-mono font-medium">{list.number}</span> <span className="text-xs text-muted-foreground">{list.orderNumber}</span>
        <span className="block text-xs text-muted-foreground">{status}</span></td>
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
