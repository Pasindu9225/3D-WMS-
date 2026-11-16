import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { TaskBoardPage } from './TaskBoardPage'

const calls: { url: string; body: unknown }[] = []

beforeEach(() => {
  calls.length = 0
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 's@b.lk', displayName: 'S', permissions: ['tasks:read', 'tasks:assign'] })
    if (url.startsWith('/api/v1/warehouses')) return Response.json({ items: [{ id: 'w1', code: 'MAIN', name: 'Main' }], page: 0, size: 100, total: 1 })
    if (url.startsWith('/api/v1/task-board/pickers')) return Response.json([{ id: 'nimal', name: 'Nimal Picker' }])
    if (url.startsWith('/api/v1/task-board')) {
      return Response.json({ toRelease: 3, toPick: 1, picking: 1, pickedToday: 4, toPack: 2, toDispatch: 0, inReceiving: 5, lists: [
        { id: 'pl1', number: 'PL-000001', status: 'OPEN', orderNumber: 'SO-000007', customerName: 'Kandy Super', warehouseCode: 'MAIN',
          lines: 2, openLines: 2, priority: 'NORMAL', assignee: null, startedBy: null, createdAt: new Date(Date.now() - 3.5 * 3_600_000).toISOString() },
      ] })
    }
    if (init.method === 'POST') {
      calls.push({ url, body: JSON.parse(init.body as string) })
      return Response.json({ id: 'pl1' })
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

it('shows the stage counts and gives a list to a picker with a priority', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <TaskBoardPage />
    </QueryClientProvider>,
  )
  expect(await screen.findByText('Orders to release')).toBeInTheDocument()
  expect(await screen.findByText('PL-000001')).toBeInTheDocument()
  expect(screen.getByText('3 h')).toHaveClass('text-destructive') // waiting more than 2 hours
  await user.selectOptions(await screen.findByLabelText('Give PL-000001 to'), await screen.findByRole('option', { name: 'Nimal Picker' }))
  await user.selectOptions(screen.getByLabelText('Priority of PL-000001'), 'URGENT')
  await waitFor(() => expect(calls).toEqual([
    { url: '/api/v1/pick-lists/pl1/assign', body: { userId: 'nimal' } },
    { url: '/api/v1/pick-lists/pl1/priority', body: { priority: 'URGENT' } },
  ]))
})
