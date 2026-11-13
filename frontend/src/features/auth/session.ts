import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'

/** The signed-in user, as GET /auth/me returns it. Never contains tokens (they are HttpOnly cookies). */
export interface Me {
  id: string
  email: string
  fullName: string
  roles: string[]
  permissions: string[]
}

export const ME_KEY = ['me'] as const

/** Who is signed in. A 401 here (after the client's one refresh attempt) means "not signed in". */
export function useMe() {
  return useQuery({ queryKey: ME_KEY, queryFn: () => api<Me>('/auth/me'), retry: false, staleTime: 60_000 })
}

/** UI hint only; the server checks every permission again (SEC-09). */
export function can(me: Me | undefined, permission: string): boolean {
  return me?.permissions.includes(permission) ?? false
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { email: string; password: string }) => api<Me>('/auth/login', { method: 'POST', body }),
    onSuccess: (me) => qc.setQueryData(ME_KEY, me),
  })
}

export function useLogout(all = false) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api<void>(all ? '/auth/logout-all' : '/auth/logout', { method: 'POST' }),
    onSettled: () => qc.clear(), // forget everything cached for this user, even if the call failed
  })
}
