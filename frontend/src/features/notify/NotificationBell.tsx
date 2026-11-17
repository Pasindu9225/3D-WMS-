import { Bell } from 'lucide-react'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { t } from '@/lib/i18n'
import { ago, notificationText, useLiveNotifications, useMarkRead, useNotifications } from './notifications'

/** Header bell: unread count, newest notifications; clicking one marks it read and opens it. */
export function NotificationBell({ userId, className = '' }: { userId: string; className?: string }) {
  const inbox = useNotifications()
  const mark = useMarkRead()
  const navigate = useNavigate()
  useLiveNotifications(userId)
  const unread = inbox.data?.unread ?? 0
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className={`relative ${className}`}
          aria-label={unread > 0 ? t('notify.bellUnread', { n: unread }) : t('notify.bell')}>
          <Bell />
          {unread > 0 && (
            <span aria-hidden className="absolute right-1 top-1 min-w-4 rounded-full bg-destructive px-1 text-[10px] font-semibold leading-4 text-white">
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent>
        <div className="w-80 max-w-[calc(100vw-2rem)]">
          <div className="flex items-center justify-between">
            <DropdownMenuLabel>{t('notify.title')}</DropdownMenuLabel>
            {unread > 0 && (
              <Button variant="ghost" size="sm"onClick={() => mark.mutate('all')}>{t('notify.readAll')}</Button>
            )}
          </div>
          <DropdownMenuSeparator />
          {inbox.data?.items.length === 0 && <p className="px-2 py-4 text-center text-sm text-muted-foreground">{t('notify.none')}</p>}
          <div className="max-h-96 overflow-y-auto">
            {inbox.data?.items.map((n) => (
              <DropdownMenuItem key={n.id} className="items-start gap-2 py-2" onSelect={() => {
                if (!n.read) mark.mutate(n.id)
                if (n.link) void navigate(n.link)
              }}>
                <span aria-hidden className={`mt-1.5 size-2 shrink-0 rounded-full ${n.read ? 'bg-transparent' : 'bg-primary'}`} />
                <span className="min-w-0 flex-1">
                  <span className={`block text-sm ${n.read ? 'text-muted-foreground' : 'font-medium'}`}>{notificationText(n)}</span>
                  <span className="block text-xs text-muted-foreground">{ago(n.createdAt)}{n.read ? '' : ` · ${t('notify.new')}`}</span>
                </span>
              </DropdownMenuItem>
            ))}
          </div>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
