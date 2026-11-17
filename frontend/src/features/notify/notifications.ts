import { Client } from '@stomp/stompjs'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { api } from '@/lib/api'
import { t } from '@/lib/i18n'

export interface AppNotification {
  id: string
  kind: 'LOW_STOCK' | 'TASK_ASSIGNED' | 'EXPIRY'
  params: Record<string, string | number>
  link: string | null
  createdAt: string
  read: boolean
}

export interface Inbox { items: AppNotification[]; page: number; size: number; total: number; unread: number }

const KEY = ['notifications']

/** My newest notifications and the unread count. Live pushes refresh it; every 60 s as a fallback. */
export function useNotifications(enabled = true) {
  return useQuery({ queryKey: KEY, queryFn: () => api<Inbox>('/notifications?size=20'), enabled, refetchInterval: 60_000 })
}

export function useMarkRead() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string | 'all') => (id === 'all' ? api('/notifications/read-all', { method: 'POST' }) : api(`/notifications/${id}/read`, { method: 'POST' })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: KEY }),
  })
}

/**
 * Listens on my own topic while signed in. The push says only "something new"; the list is then fetched over HTTPS.
 * Sessions end every 15 minutes on the server; the client reconnects after touching the session.
 */
export function useLiveNotifications(userId: string | undefined) {
  const qc = useQueryClient()
  useEffect(() => {
    if (!userId || typeof WebSocket === 'undefined') return
    const client = new Client({
      brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
      reconnectDelay: 10_000,
      heartbeatIncoming: 25_000,
      heartbeatOutgoing: 25_000,
      beforeConnect: async () => { await api('/auth/me').catch(() => undefined) },
      onConnect: () => {
        client.subscribe(`/topic/user/${userId}/notifications`, () => void qc.invalidateQueries({ queryKey: KEY }))
      },
    })
    client.activate()
    return () => void client.deactivate()
  }, [userId, qc])
}

/** The sentence for one notification, from en.json. */
export function notificationText(n: AppNotification): string {
  const p = Object.fromEntries(Object.entries(n.params).map(([k, v]) => [k, String(v)]))
  if (n.kind === 'TASK_ASSIGNED') return t(p.task === 'WAVE' ? 'notify.wave' : 'notify.pickList', p)
  if (n.kind === 'LOW_STOCK') return t('notify.lowStock', p)
  return t(Number(p.expired) > 0 ? 'notify.expiryWithExpired' : 'notify.expiry', p)
}

/** "5 min ago", "3 h ago", "2 days ago". */
export function ago(iso: string, now = Date.now()): string {
  const min = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60_000))
  if (min < 1) return t('notify.justNow')
  if (min < 60) return t('notify.minutesAgo', { n: min })
  const h = Math.round(min / 60)
  return h < 24 ? t('notify.hoursAgo', { n: h }) : t('notify.daysAgo', { n: Math.round(h / 24) })
}
