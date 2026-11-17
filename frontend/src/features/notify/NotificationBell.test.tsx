import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { NotificationBell } from './NotificationBell'
import { ago } from './notifications'

const posts: string[] = []
let unread = 2

beforeEach(() => {
  posts.length = 0
  unread = 2
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('WebSocket', undefined) // no live socket in tests; the list still loads over HTTP
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (init.method === 'POST') {
      posts.push(url)
      unread = url.endsWith('read-all') ? 0 : 1
      return Response.json({})
    }
    if (url.startsWith('/api/v1/notifications')) {
      return Response.json({ page: 0, size: 20, total: 2, unread, items: [
        { id: 'n1', kind: 'TASK_ASSIGNED', params: { task: 'WAVE', number: 'WV-000004' }, link: '/worker/pick/wave/w4', createdAt: new Date(Date.now() - 5 * 60_000).toISOString(), read: unread === 0 },
        { id: 'n2', kind: 'LOW_STOCK', params: { sku: 'OIL', name: 'Coconut oil', onHand: '15', reorderPoint: '20', uom: 'EA', warehouse: 'MAIN' }, link: '/app/dashboard',
          createdAt: new Date(Date.now() - 3 * 3_600_000).toISOString(), read: unread < 2 },
      ] })
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function show() {
  const router = createMemoryRouter([{ path: '/app', element: <NotificationBell userId="u1" /> }, { path: '/worker/pick/wave/:id', element: <p>wave page</p> }],
    { initialEntries: ['/app'] })
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>)
}

it('shows the unread count and the words for each notification; opening one marks it read', async () => {
  const user = userEvent.setup()
  show()
  await user.click(await screen.findByRole('button', { name: 'Notifications, 2 new' }))
  expect(screen.getByText('Pick wave WV-000004 was given to you.')).toBeInTheDocument()
  expect(screen.getByText('Low stock in MAIN: OIL Coconut oil has 15 EA left (reorder at 20).')).toBeInTheDocument()
  await user.click(screen.getByText('Pick wave WV-000004 was given to you.'))
  await waitFor(() => expect(posts).toEqual(['/api/v1/notifications/n1/read']))
  expect(await screen.findByText('wave page')).toBeInTheDocument()
})

it('marks all as read', async () => {
  const user = userEvent.setup()
  show()
  await user.click(await screen.findByRole('button', { name: 'Notifications, 2 new' }))
  await user.click(screen.getByRole('button', { name: 'Mark all as read' }))
  await waitFor(() => expect(posts).toEqual(['/api/v1/notifications/read-all']))
  expect(await screen.findByRole('button', { name: 'Notifications', hidden: true })).toBeInTheDocument() // the open menu hides the rest of the page from screen readers
})

it('says how long ago', () => {
  const now = Date.parse('2026-11-18T10:00:00Z')
  expect(ago('2026-11-18T09:59:40Z', now)).toBe('just now')
  expect(ago('2026-11-18T09:45:00Z', now)).toBe('15 min ago')
  expect(ago('2026-11-18T07:00:00Z', now)).toBe('3 h ago')
  expect(ago('2026-11-16T10:00:00Z', now)).toBe('2 days ago')
})
