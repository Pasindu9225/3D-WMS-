import { Client } from '@stomp/stompjs'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api } from '@/lib/api'
import type { Scene } from './api'

/** What the server pushes on /topic/wh/{warehouse}/bins. */
export type Push = { type: 'BIN'; id: string; flags: number } | { type: 'LAYOUT'; publishedAt: string }

/** New flags string with one bin changed; unchanged scene when the bin is not in it (layout changed). */
export function applyPush(scene: Scene, push: Push): Scene {
  if (push.type !== 'BIN') return scene
  const i = scene.bins.ids.indexOf(push.id)
  if (i < 0) return scene
  return { ...scene, flags: scene.flags.slice(0, i) + String(push.flags) + scene.flags.slice(i + 1) }
}

export type LiveState = 'connecting' | 'live' | 'offline'

/**
 * Live bin status while the 3D page is open. Bin pushes update the cached scene in place (colours and the
 * bin panel follow); a new publish reloads the scene. The server closes sockets every 15 minutes or when the
 * session ends, so before each (re)connect the session is touched once, which refreshes the cookie if needed.
 */
export function useLiveBins(warehouseId: string, enabled: boolean): LiveState {
  const queryClient = useQueryClient()
  const [state, setState] = useState<LiveState>('connecting')
  useEffect(() => {
    if (!enabled) return
    const key = ['scene', warehouseId]
    const client = new Client({
      brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
      reconnectDelay: 5000,
      heartbeatIncoming: 25_000,
      heartbeatOutgoing: 25_000,
      beforeConnect: async () => {
        setState('connecting')
        await api('/auth/me').catch(() => undefined) // refreshes the session cookie when it has expired
      },
      onConnect: () => {
        setState('live')
        client.subscribe(`/topic/wh/${warehouseId}/bins`, (frame) => {
          const push = JSON.parse(frame.body) as Push
          if (push.type === 'LAYOUT') void queryClient.invalidateQueries({ queryKey: key })
          else queryClient.setQueryData<Scene>(key, (s) => (s ? applyPush(s, push) : s))
        })
      },
      onWebSocketClose: () => setState('offline'),
      onStompError: () => setState('offline'),
    })
    client.activate()
    return () => void client.deactivate()
  }, [warehouseId, enabled, queryClient])
  return state
}
