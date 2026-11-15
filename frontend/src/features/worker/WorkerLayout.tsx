import { ArrowLeft, LogOut, Monitor } from 'lucide-react'
import { Link, Navigate, Outlet, useLocation, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { useLogout, useMe } from '@/features/auth/session'
import { t } from '@/lib/i18n'

/** The phone app: one column, big targets, no sidebar. Same sign-in and permissions as the office app. */
export function WorkerLayout() {
  const me = useMe()
  const location = useLocation()
  const navigate = useNavigate()
  const logout = useLogout()
  if (me.isPending) return <p className="p-6 text-sm text-muted-foreground">{t('app.loading')}</p>
  if (me.isError) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  const home = location.pathname === '/worker' || location.pathname === '/worker/'
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-10 flex h-14 items-center gap-2 border-b bg-background px-3">
        {home ? <span className="px-2 font-semibold">{t('worker.title')}</span> : (
          <Button variant="ghost" size="icon" className="size-11" aria-label={t('worker.home')} onClick={() => void navigate('/worker')}>
            <ArrowLeft />
          </Button>
        )}
        <span className="ml-auto truncate text-sm text-muted-foreground">{me.data.fullName}</span>
        <Button asChild variant="ghost" size="icon" className="size-11" aria-label={t('worker.office')}>
          <Link to="/app"><Monitor /></Link>
        </Button>
        <Button variant="ghost" size="icon" className="size-11" aria-label={t('menu.signOut')}
          onClick={() => logout.mutate(undefined, { onSettled: () => void navigate('/login', { replace: true }) })}>
          <LogOut />
        </Button>
      </header>
      <main className="mx-auto w-full max-w-lg flex-1 p-4">
        <Outlet />
      </main>
    </div>
  )
}
