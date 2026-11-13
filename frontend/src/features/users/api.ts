import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

export type UserStatus = 'ACTIVE' | 'INVITED' | 'DISABLED' | 'LOCKED'

export interface User {
  id: string
  email: string
  fullName: string
  status: UserStatus
  roles: string[]
  createdAt: string
  /** Set only while a lockout after failed sign-ins is running. */
  lockedUntil: string | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface Role {
  code: string
  name: string
  permissions: string[]
}

export interface Invite {
  user: User
  inviteUrl: string
  expiresAt: string
}

export const PAGE_SIZE = 25

export function useUsers(q: string, page: number) {
  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) })
  if (q) params.set('q', q)
  return useQuery({
    queryKey: ['users', q, page],
    queryFn: () => api<Page<User>>(`/users?${params}`),
    placeholderData: keepPreviousData, // no flicker while paging or typing
  })
}

export function useRoles() {
  return useQuery({ queryKey: ['roles'], queryFn: () => api<Role[]>('/roles'), staleTime: 10 * 60_000 })
}

/** All user changes refresh the list; the server returns the updated user. */
function useUserMutation<V, R>(fn: (v: V) => Promise<R>) {
  const qc = useQueryClient()
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }) })
}

export const useInvite = () =>
  useUserMutation((body: { email: string; fullName: string; roles: string[] }) =>
    api<Invite>('/users', { method: 'POST', body }))

export const useReinvite = () => useUserMutation((id: string) => api<Invite>(`/users/${id}/invite`, { method: 'POST' }))

export const useRename = () =>
  useUserMutation(({ id, fullName }: { id: string; fullName: string }) =>
    api<User>(`/users/${id}`, { method: 'PATCH', body: { fullName } }))

export const useSetRoles = () =>
  useUserMutation(({ id, roles }: { id: string; roles: string[] }) =>
    api<User>(`/users/${id}/roles`, { method: 'PUT', body: { roles } }))

export const useUnlock = () => useUserMutation((id: string) => api<User>(`/users/${id}/unlock`, { method: 'POST' }))

export const useSetEnabled = () =>
  useUserMutation(({ id, enabled }: { id: string; enabled: boolean }) =>
    api<User>(`/users/${id}/${enabled ? 'enable' : 'disable'}`, { method: 'POST' }))
