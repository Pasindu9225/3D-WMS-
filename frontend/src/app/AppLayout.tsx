import { Boxes, ClipboardList, Cuboid, LogOut, Menu, MonitorSmartphone, Package, ShieldCheck, User, Users, Warehouse } from 'lucide-react'
import { useState, type ComponentType } from 'react'
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { ThemeToggle } from '@/components/ThemeToggle'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { can, useLogout, useMe, type Me } from '@/features/auth/session'
import { t, type MessageKey } from '@/lib/i18n'
import { cn } from '@/lib/utils'

interface NavItem {
  to: string
  label: MessageKey
  icon: ComponentType<{ className?: string }>
  permission?: string
  soon?: boolean
}

const NAV: NavItem[] = [
  { to: '/app/warehouses', label: 'nav.warehouses', icon: Warehouse, permission: 'warehouses:read' },
  { to: '/app/rack-types', label: 'nav.rackTypes', icon: Boxes, permission: 'warehouses:read' },
  { to: '/app/3d', label: 'nav.view3d', icon: Cuboid, soon: true },
  { to: '/app/stock', label: 'nav.stock', icon: Package, soon: true },
  { to: '/app/users', label: 'nav.users', icon: Users, permission: 'users:read' },
  { to: '/app/roles', label: 'nav.roles', icon: ShieldCheck, permission: 'roles:read' },
  { to: '/app/audit', label: 'nav.audit', icon: ClipboardList, permission: 'audit:read' },
]

/** Everything under /app: needs a signed-in user, shows the sidebar and top bar. */
export function AppLayout() {
  const me = useMe()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)

  if (me.isPending) {
    return <p className="p-6 text-sm text-muted-foreground">{t('app.loading')}</p>
  }
  if (me.isError) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return (
    <div className="flex min-h-dvh">
      <aside className="hidden w-60 shrink-0 border-r bg-card md:block">
        <Brand />
        <SideNav me={me.data} />
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center gap-2 border-b px-4 sm:px-6">
          <Button variant="ghost" size="icon" className="md:hidden" aria-label={t('nav.openMenu')} aria-expanded={menuOpen}
            onClick={() => setMenuOpen((o) => !o)}>
            <Menu />
          </Button>
          <span className="font-semibold md:hidden">{t('app.name')}</span>
          <div className="ml-auto flex items-center gap-1">
            <ThemeToggle />
            <UserMenu me={me.data} />
          </div>
        </header>
        {menuOpen && (
          <div className="border-b bg-card md:hidden" onClick={() => setMenuOpen(false)}>
            <SideNav me={me.data} />
          </div>
        )}
        <main className="min-w-0 flex-1 p-4 sm:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function Brand() {
  return (
    <div className="flex h-14 items-center gap-2 border-b px-4 font-semibold">
      <Boxes className="size-5 text-primary" aria-hidden />
      {t('app.name')}
    </div>
  )
}

function SideNav({ me }: { me: Me }) {
  return (
    <nav aria-label={t('nav.main')} className="grid gap-1 p-3">
      {NAV.filter((item) => item.soon || !item.permission || can(me, item.permission)).map((item) =>
        item.soon ? (
          <span key={item.to} className="flex items-center gap-3 rounded-md px-3 py-2 text-sm text-muted-foreground/70"
            title={t('nav.comingSoon')}>
            <item.icon className="size-4" />
            {t(item.label)}
            <span className="ml-auto text-[10px] tracking-wide uppercase">{t('nav.comingSoon')}</span>
          </span>
        ) : (
          <NavLink key={item.to} to={item.to}
            className={({ isActive }) => cn('flex items-center gap-3 rounded-md px-3 py-2 text-sm hover:bg-secondary',
              isActive && 'bg-secondary font-medium text-foreground')}>
            <item.icon className="size-4" />
            {t(item.label)}
          </NavLink>
        ),
      )}
    </nav>
  )
}

function UserMenu({ me }: { me: Me }) {
  const navigate = useNavigate()
  const logout = useLogout()
  const logoutAll = useLogout(true)
  const toLogin = () => navigate('/login', { replace: true })
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" className="gap-2 px-2">
          <span className="grid size-7 place-items-center rounded-full bg-primary text-xs font-semibold text-primary-foreground">
            {initials(me.fullName)}
          </span>
          <span className="hidden max-w-40 truncate sm:inline">{me.fullName}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <DropdownMenuLabel>{me.email}</DropdownMenuLabel>
        <DropdownMenuItem onSelect={() => navigate('/app/account')}>
          <User /> {t('menu.account')}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => logout.mutate(undefined, { onSettled: toLogin })}>
          <LogOut /> {t('menu.signOut')}
        </DropdownMenuItem>
        <DropdownMenuItem onSelect={() => logoutAll.mutate(undefined, { onSettled: toLogin })}>
          <MonitorSmartphone /> {t('menu.signOutAll')}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/)
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? (parts.at(-1)?.[0] ?? '') : '')).toUpperCase()
}
