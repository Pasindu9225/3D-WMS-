import { createBrowserRouter } from 'react-router'

/** Each page is its own chunk, so the first load only ships what that page needs. */
export const router = createBrowserRouter([
  { path: '/', lazy: () => import('./HomePage').then((m) => ({ Component: m.HomePage })) },
  { path: '/login', lazy: () => import('@/features/auth/LoginPage').then((m) => ({ Component: m.LoginPage })) },
  { path: '/invite', lazy: () => import('@/features/auth/InvitePage').then((m) => ({ Component: m.InvitePage })) },
  { path: '*', lazy: () => import('./NotFoundPage').then((m) => ({ Component: m.NotFoundPage })) },
])
