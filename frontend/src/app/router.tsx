import { createBrowserRouter, Navigate } from 'react-router'
import { t } from '@/lib/i18n'
import { AppLayout } from './AppLayout'

/** Each page is its own chunk, so the first load only ships what that page needs. */
export const router = createBrowserRouter([
  {
    // Shown while the first page's code loads.
    HydrateFallback: () => <p className="p-6 text-sm text-muted-foreground">{t('app.loading')}</p>,
    children: [
      { path: '/', element: <Navigate to="/app" replace /> },
      { path: '/login', lazy: () => import('@/features/auth/LoginPage').then((m) => ({ Component: m.LoginPage })) },
      { path: '/invite', lazy: () => import('@/features/auth/InvitePage').then((m) => ({ Component: m.InvitePage })) },
      {
        path: '/app',
        element: <AppLayout />,
        children: [
          { index: true, lazy: () => import('./AppHome').then((m) => ({ Component: m.AppHome })) },
          { path: 'warehouses', lazy: () => import('@/features/warehouses/WarehousesPage').then((m) => ({ Component: m.WarehousesPage })) },
          {
            path: 'warehouses/:id/layout',
            lazy: () => import('@/features/layout/LayoutEditorPage').then((m) => ({ Component: m.LayoutEditorPage })),
          },
          { path: '3d-perf', lazy: () => import('@/features/view3d/PerfPage').then((m) => ({ Component: m.PerfPage })) },
          { path: '3d', lazy: () => import('@/features/view3d/View3DPage').then((m) => ({ Component: m.View3DIndexPage })) },
          { path: 'warehouses/:id/3d', lazy: () => import('@/features/view3d/View3DPage').then((m) => ({ Component: m.View3DPage })) },
          { path: 'suppliers', lazy: () => import('@/features/catalog/SuppliersPage').then((m) => ({ Component: m.SuppliersPage })) },
          { path: 'products', lazy: () => import('@/features/catalog/ProductsPage').then((m) => ({ Component: m.ProductsPage })) },
          { path: 'rack-types', lazy: () => import('@/features/rack-types/RackTypesPage').then((m) => ({ Component: m.RackTypesPage })) },
          { path: 'warehouses/:id/bins', lazy: () => import('@/features/bins/BinsPage').then((m) => ({ Component: m.BinsPage })) },
          { path: 'users', lazy: () => import('@/features/users/UsersPage').then((m) => ({ Component: m.UsersPage })) },
          { path: 'roles', lazy: () => import('@/features/roles/RolesPage').then((m) => ({ Component: m.RolesPage })) },
          { path: 'audit', lazy: () => import('@/features/audit/AuditPage').then((m) => ({ Component: m.AuditPage })) },
          {
            path: 'account',
            lazy: () => import('@/features/account/AccountPage').then((m) => ({ Component: m.AccountPage })),
          },
        ],
      },
      { path: '*', lazy: () => import('./NotFoundPage').then((m) => ({ Component: m.NotFoundPage })) },
    ],
  },
])
