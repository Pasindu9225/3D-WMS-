import { Navigate } from 'react-router'
import { can, useMe } from '@/features/auth/session'
import { DashboardPage } from '@/features/report/DashboardPage'
import { SetupCard } from '@/features/setup/SetupCard'
import { t } from '@/lib/i18n'

/** /app: the dashboard for people who read reports, otherwise a short welcome. */
export function AppHome() {
  const me = useMe()
  if (can(me.data, 'platform:read')) return <Navigate to="/app/admin/companies" replace /> // Super Admin lands on the portal
  if (can(me.data, 'reports:read')) return <>{can(me.data, 'warehouses:manage') && <SetupCard />}<DashboardPage /></>
  return (
    <section className="max-w-2xl space-y-2">
      <h1 className="text-2xl font-semibold">{t('home.welcome', { name: me.data?.fullName ?? '' })}</h1>
      <p className="text-muted-foreground">{t('home.body')}</p>
    </section>
  )
}
