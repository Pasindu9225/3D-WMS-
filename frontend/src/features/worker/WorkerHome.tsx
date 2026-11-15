import { ArrowDownToLine, ArrowLeftRight, Download, PackageCheck, ScanLine, Scale } from 'lucide-react'
import { useEffect, useState, type ComponentType } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { can, useMe } from '@/features/auth/session'
import { useReceivableOrders } from '@/features/inbound/api'
import { useAdjustments, useWaiting } from '@/features/inventory/api'
import { useWarehouses } from '@/features/warehouses/api'
import { canInstall, install, onInstallChange } from '@/lib/install'
import { t, type MessageKey } from '@/lib/i18n'

interface Action {
  to: string
  label: MessageKey
  icon: ComponentType<{ className?: string }>
  permission: string
}

const ACTIONS: Action[] = [
  { to: '/worker/receive', label: 'worker.receive', icon: PackageCheck, permission: 'inbound:receive' },
  { to: '/worker/putaway', label: 'worker.putaway', icon: ArrowDownToLine, permission: 'putaway:execute' },
  { to: '/worker/move', label: 'worker.move', icon: ArrowLeftRight, permission: 'inventory:transfer' },
  { to: '/worker/find', label: 'worker.find', icon: ScanLine, permission: 'catalog:read' },
]

/** /worker: what is waiting for me, and one big button per job. */
export function WorkerHome() {
  const me = useMe()
  const may = (p: string) => can(me.data, p)
  const [installable, setInstallable] = useState(canInstall())
  useEffect(() => onInstallChange(() => setInstallable(canInstall())), [])

  return (
    <div className="grid gap-5">
      {installable && (
        <Button variant="outline" className="h-12" onClick={() => void install()}><Download /> {t('worker.install')}</Button>
      )}
      <section className="grid gap-2">
        <h2 className="text-sm font-medium text-muted-foreground">{t('worker.todo')}</h2>
        <Todo />
      </section>
      <nav className="grid grid-cols-2 gap-3">
        {ACTIONS.filter((a) => may(a.permission)).map((a) => (
          <Link key={a.to} to={a.to}
            className="flex h-28 flex-col items-center justify-center gap-2 rounded-xl border bg-card text-base font-medium hover:bg-secondary">
            <a.icon className="size-8 text-primary" />
            {t(a.label)}
          </Link>
        ))}
      </nav>
    </div>
  )
}

/** Derived from the work itself: orders to receive, goods to put away, adjustments to approve. */
function Todo() {
  const me = useMe()
  const warehouses = useWarehouses()
  const first = warehouses.data?.items[0]?.id
  const receive = useReceivableOrders('')
  const waiting = useWaiting(can(me.data, 'putaway:execute') ? first : undefined)
  const approvals = useAdjustments('PENDING', 0)
  const rows: { to: string; text: string }[] = []
  if (can(me.data, 'inbound:receive') && receive.data && receive.data.total > 0) {
    rows.push({ to: '/worker/receive', text: t('worker.todo.receive', { n: receive.data.total }) })
  }
  if (waiting.data && waiting.data.total > 0) {
    rows.push({ to: '/worker/putaway', text: t('worker.todo.putaway', { n: waiting.data.total }) })
  }
  if (can(me.data, 'inventory:approve') && approvals.data && approvals.data.total > 0) {
    rows.push({ to: '/app/adjustments', text: t('worker.todo.approve', { n: approvals.data.total }) })
  }
  if (rows.length === 0) return <p className="rounded-lg border p-4 text-sm text-muted-foreground">{t('worker.todo.none')}</p>
  return (
    <ul className="grid gap-2">
      {rows.map((r) => (
        <li key={r.to}>
          <Link to={r.to} className="flex items-center gap-3 rounded-lg border bg-card p-4 hover:bg-secondary">
            <Scale className="size-5 shrink-0 text-primary" />
            <span>{r.text}</span>
          </Link>
        </li>
      ))}
    </ul>
  )
}
