import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { View3DPage } from './View3DPage'

vi.mock('./SceneCanvas', () => ({ SceneCanvas: () => <p>3D canvas</p> }))

let sceneReply: Response
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u', email: 'a@b.lk', displayName: 'A', permissions: ['warehouses:read'] })
    if (url === '/api/v1/warehouses/W1') return Response.json({ id: 'W1', code: 'WH1', name: 'Main', layoutStatus: 'DRAFT' })
    if (url === '/api/v1/warehouses/W1/scene') return sceneReply.clone()
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function renderPage() {
  const router = createMemoryRouter([{ path: '/app/warehouses/:id/3d', element: <View3DPage /> }],
    { initialEntries: ['/app/warehouses/W1/3d'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

it('explains when there is no published layout and does not load the 3D engine', async () => {
  sceneReply = Response.json({ code: 'NOT_PUBLISHED', title: 'Not found' }, { status: 404 })
  renderPage()
  expect(await screen.findByText(/no published layout yet/)).toBeInTheDocument()
  expect(screen.queryByText('3D canvas')).not.toBeInTheDocument()
  expect(screen.queryByRole('link', { name: /Edit layout/ })).not.toBeInTheDocument() // read-only user
})

it('shows the scene with its rack and bin count', async () => {
  sceneReply = Response.json({ publishedAt: 'x', floor: [0, 0, 1000, 0, 1000, 1000], openings: [], zones: [],
    racks: [{ code: 'R01' }], bins: { ids: ['a', 'b'], codes: [], rack: [], level: [], pos: [] }, flags: '00' })
  renderPage()
  expect(await screen.findByText('3D canvas')).toBeInTheDocument()
  expect(screen.getByText('1 racks · 2 bins')).toBeInTheDocument()
})
