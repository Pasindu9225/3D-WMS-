import { MoreHorizontal, Search, UserPlus } from 'lucide-react'
import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { errorMessage } from '@/lib/errors'
import { t, type MessageKey } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'
import { PAGE_SIZE, useReinvite, useRoles, useSetEnabled, useUsers, type Invite, type User, type UserStatus } from './api'
import { DisableDialog, InviteDialog, InviteLinkDialog, RenameDialog, RolesDialog } from './UserDialogs'

const STATUS_TONE: Record<UserStatus, 'good' | 'warn' | 'bad' | 'neutral'> = {
  ACTIVE: 'good',
  INVITED: 'warn',
  DISABLED: 'bad',
  LOCKED: 'bad',
}

type Open = { kind: 'rename' | 'roles' | 'disable'; user: User } | { kind: 'link'; invite: Invite } | null

export function UsersPage() {
  const me = useMe()
  const manage = can(me.data, 'users:manage')
  const [search, setSearch] = useState('')
  const q = useDebounced(search.trim())
  const [page, setPage] = useState(0)
  const users = useUsers(q, page)
  const roleNames = new Map((useRoles().data ?? []).map((r) => [r.code, r.name]))
  const [inviteOpen, setInviteOpen] = useState(false)
  const [open, setOpen] = useState<Open>(null)
  const reinvite = useReinvite()
  const setEnabled = useSetEnabled()
  const data = users.data

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">{t('users.title')}</h1>
        {manage && (
          <Button onClick={() => setInviteOpen(true)}>
            <UserPlus /> {t('users.invite')}
          </Button>
        )}
      </div>

      <div className="relative max-w-sm">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input className="pl-9" type="search" placeholder={t('users.search')} aria-label={t('users.search')} maxLength={100}
          value={search} onChange={(e) => { setSearch(e.target.value); setPage(0) }} />
      </div>

      {users.isError && <FormMessage tone="error">{errorMessage(users.error)}</FormMessage>}
      {(reinvite.isError || setEnabled.isError) && <FormMessage tone="error">{errorMessage(reinvite.error ?? setEnabled.error)}</FormMessage>}

      <Table>
        <thead>
          <tr>
            <Th>{t('users.col.name')}</Th>
            <Th className="hidden md:table-cell">{t('users.col.email')}</Th>
            <Th>{t('users.col.roles')}</Th>
            <Th>{t('users.col.status')}</Th>
            {manage && <Th className="w-12"><span className="sr-only">{t('users.col.actions')}</span></Th>}
          </tr>
        </thead>
        <tbody>
          {data?.items.map((u) => (
            <Tr key={u.id}>
              <td>
                <div className="font-medium">{u.fullName}</div>
                <div className="text-xs text-muted-foreground md:hidden">{u.email}</div>
              </td>
              <td className="hidden text-muted-foreground md:table-cell">{u.email}</td>
              <td>
                <div className="flex flex-wrap gap-1">
                  {u.roles.map((r) => <Badge key={r}>{roleNames.get(r) ?? r}</Badge>)}
                </div>
              </td>
              <td><Badge tone={STATUS_TONE[u.status]}>{t(`users.status.${u.status}` as MessageKey)}</Badge></td>
              {manage && (
                <td className="text-right">
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" aria-label={t('users.actions', { name: u.fullName })}><MoreHorizontal /></Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent>
                      <DropdownMenuItem onSelect={() => setOpen({ kind: 'rename', user: u })}>{t('users.rename')}</DropdownMenuItem>
                      <DropdownMenuItem onSelect={() => setOpen({ kind: 'roles', user: u })}>{t('users.setRoles')}</DropdownMenuItem>
                      {u.status === 'INVITED' && (
                        <DropdownMenuItem onSelect={() => reinvite.mutate(u.id, { onSuccess: (invite) => setOpen({ kind: 'link', invite }) })}>
                          {t('users.reinvite')}
                        </DropdownMenuItem>
                      )}
                      {u.status === 'DISABLED' ? (
                        <DropdownMenuItem onSelect={() => setEnabled.mutate({ id: u.id, enabled: true })}>{t('users.enable')}</DropdownMenuItem>
                      ) : (
                        u.id !== me.data?.id && (
                          <DropdownMenuItem className="text-destructive" onSelect={() => setOpen({ kind: 'disable', user: u })}>
                            {t('users.disable')}
                          </DropdownMenuItem>
                        )
                      )}
                    </DropdownMenuContent>
                  </DropdownMenu>
                </td>
              )}
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">{t('users.empty')}</td></tr>
          )}
          {!data && users.isPending && (
            <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">{t('app.loading')}</td></tr>
          )}
        </tbody>
      </Table>

      {data && data.total > PAGE_SIZE && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{
            previous: t('common.previous'),
            next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
          }} />
      )}

      {inviteOpen && <InviteDialog open onOpenChange={setInviteOpen} />}
      {open?.kind === 'rename' && <RenameDialog user={open.user} onClose={() => setOpen(null)} />}
      {open?.kind === 'roles' && <RolesDialog user={open.user} onClose={() => setOpen(null)} />}
      {open?.kind === 'disable' && <DisableDialog user={open.user} onClose={() => setOpen(null)} />}
      {open?.kind === 'link' && <InviteLinkDialog invite={open.invite} open onOpenChange={(o) => !o && setOpen(null)} />}
    </div>
  )
}
