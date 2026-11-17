import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { SetupPage } from '@/features/setup/SetupPage'
import { SignupPage } from './SignupPage'
import { VerifyPage } from './VerifyPage'

const calls: { url: string; body: unknown }[] = []

beforeEach(() => {
  calls.length = 0
  document.cookie = 'XSRF-TOKEN=tok'
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    if (init.method === 'POST') {
      calls.push({ url, body: JSON.parse(init.body as string) })
      if (url === '/api/v1/signup/verify') return Response.json({ id: 'u1', email: 'nimal@kandy.lk', fullName: 'Nimal', roles: ['TENANT_ADMIN'], permissions: ['warehouses:manage'] })
      return new Response(null, { status: 202 })
    }
    if (url === '/api/v1/auth/me') return Response.json({ id: 'u1', email: 'nimal@kandy.lk', fullName: 'Nimal', roles: [], permissions: ['warehouses:manage'] })
    if (url === '/api/v1/setup') {
      return Response.json({ complete: false, warehouseId: 'w1', steps: [{ key: 'WAREHOUSE', done: true }, { key: 'RACK_TYPE', done: false },
        { key: 'LAYOUT', done: false }, { key: 'PRODUCTS', done: false }, { key: 'RECEIVE', done: false }, { key: 'TEAM', done: false }] })
    }
    return new Response(null, { status: 404 })
  }))
})
afterEach(() => vi.unstubAllGlobals())

function show(path: string, routes: { path: string; element: React.ReactNode }[]) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>)
}

it('signs up and asks to check the email; passwords must match', async () => {
  const user = userEvent.setup()
  show('/signup', [{ path: '/signup', element: <SignupPage /> }])
  await user.type(screen.getByLabelText('Company name'), 'Kandy Foods')
  await user.type(screen.getByLabelText('Your name'), 'Nimal Perera')
  await user.type(screen.getByLabelText('Email'), 'nimal@kandy.lk')
  await user.type(screen.getByLabelText('New password'), 'a-long-enough-pass')
  await user.type(screen.getByLabelText('Repeat password'), 'something-else-xx')
  await user.click(screen.getByRole('button', { name: 'Create company' }))
  expect(await screen.findByText('The passwords do not match')).toBeInTheDocument()
  expect(calls).toHaveLength(0)
  await user.clear(screen.getByLabelText('Repeat password'))
  await user.type(screen.getByLabelText('Repeat password'), 'a-long-enough-pass')
  await user.click(screen.getByRole('button', { name: 'Create company' }))
  expect(await screen.findByText('Check your email')).toBeInTheDocument()
  expect(calls[0]).toEqual({ url: '/api/v1/signup', body: { companyName: 'Kandy Foods', fullName: 'Nimal Perera', email: 'nimal@kandy.lk', password: 'a-long-enough-pass' } })
})

it('confirms the emailed link once and opens the setup wizard', async () => {
  window.history.replaceState(null, '', '/verify#tok123')
  show('/verify', [{ path: '/verify', element: <VerifyPage /> }, { path: '/app/setup', element: <SetupPage /> }])
  expect(await screen.findByText(/Let.s set up your warehouse/)).toBeInTheDocument()
  expect(calls).toEqual([{ url: '/api/v1/signup/verify', body: { token: 'tok123' } }])
  expect(window.location.hash).toBe('') // the token is not left in the address bar
  expect(screen.getAllByLabelText('Done')).toHaveLength(1)
  expect(screen.getByRole('link', { name: 'Add a rack type' })).toHaveAttribute('href', '/app/rack-types')
  expect(screen.getByRole('link', { name: 'Open layout editor' })).toHaveAttribute('href', '/app/warehouses/w1/layout')
  await waitFor(() => expect(calls).toHaveLength(1))
})
