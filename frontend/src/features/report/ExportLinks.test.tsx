import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ExportLinks } from './ExportLinks'

function renderAs(permissions: string[]) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => (url === '/api/v1/auth/me'
    ? Response.json({ id: 'u', email: 'm@b.lk', displayName: 'M', permissions })
    : new Response(null, { status: 404 }))))
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ExportLinks what="movements" params={{ warehouse: 'w1', type: 'SHIP', from: undefined, to: '2026-11-16' }} />
    </QueryClientProvider>,
  )
}
afterEach(() => vi.unstubAllGlobals())

it('links both formats with the active filters', async () => {
  renderAs(['reports:export'])
  expect(await screen.findByRole('link', { name: 'Download movements as Excel' }))
    .toHaveAttribute('href', '/api/v1/exports/movements.xlsx?warehouse=w1&type=SHIP&to=2026-11-16')
  expect(screen.getByRole('link', { name: 'Download movements as PDF' }))
    .toHaveAttribute('href', '/api/v1/exports/movements.pdf?warehouse=w1&type=SHIP&to=2026-11-16')
})

it('shows nothing to people who may not export', async () => {
  renderAs(['inventory:read'])
  await new Promise((r) => setTimeout(r, 50))
  expect(screen.queryByRole('link')).not.toBeInTheDocument()
})
