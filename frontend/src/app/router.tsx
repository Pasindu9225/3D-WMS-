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
          { path: 'suppliers', lazy: () => import('@/features/catalog/PartnersPage').then((m) => ({ Component: m.SuppliersPage })) },
          { path: 'customers', lazy: () => import('@/features/catalog/PartnersPage').then((m) => ({ Component: m.CustomersPage })) },
          { path: 'sales-orders', lazy: () => import('@/features/outbound/SalesOrdersPage').then((m) => ({ Component: m.SalesOrdersPage })) },
          { path: 'sales-orders/:id', lazy: () => import('@/features/outbound/SalesOrderPage').then((m) => ({ Component: m.SalesOrderPage })) },
          { path: 'purchase-orders', lazy: () => import('@/features/inbound/PurchaseOrdersPage').then((m) => ({ Component: m.PurchaseOrdersPage })) },
          {
            path: 'purchase-orders/:id',
            lazy: () => import('@/features/inbound/PurchaseOrderPage').then((m) => ({ Component: m.PurchaseOrderPage })),
          },
          { path: 'receive', lazy: () => import('@/features/inbound/ReceivePage').then((m) => ({ Component: m.ReceivePage })) },
          { path: 'receive/:id', lazy: () => import('@/features/inbound/ReceivePage').then((m) => ({ Component: m.ReceiptPage })) },
          { path: 'putaway', lazy: () => import('@/features/inventory/PutawayPage').then((m) => ({ Component: m.PutawayPage })) },
          { path: 'move', lazy: () => import('@/features/inventory/MoveStockPage').then((m) => ({ Component: m.MoveStockPage })) },
          { path: 'adjustments', lazy: () => import('@/features/inventory/AdjustmentsPage').then((m) => ({ Component: m.AdjustmentsPage })) },
          { path: 'stock', lazy: () => import('@/features/inventory/StockPage').then((m) => ({ Component: m.StockPage })) },
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
      {
        // The installable phone app: same pages for the jobs, its own one-column layout.
        path: '/worker',
        lazy: () => import('@/features/worker/WorkerLayout').then((m) => ({ Component: m.WorkerLayout })),
        children: [
          { index: true, lazy: () => import('@/features/worker/WorkerHome').then((m) => ({ Component: m.WorkerHome })) },
          { path: 'receive', lazy: () => import('@/features/inbound/ReceivePage').then((m) => ({ Component: m.ReceivePage })) },
          { path: 'receive/:id', lazy: () => import('@/features/inbound/ReceivePage').then((m) => ({ Component: m.ReceiptPage })) },
          { path: 'putaway', lazy: () => import('@/features/inventory/PutawayPage').then((m) => ({ Component: m.PutawayPage })) },
          { path: 'move', lazy: () => import('@/features/inventory/MoveStockPage').then((m) => ({ Component: m.MoveStockPage })) },
          { path: 'find', lazy: () => import('@/features/worker/FindPage').then((m) => ({ Component: m.FindPage })) },
        ],
      },
      { path: '*', lazy: () => import('./NotFoundPage').then((m) => ({ Component: m.NotFoundPage })) },
    ],
  },
])
