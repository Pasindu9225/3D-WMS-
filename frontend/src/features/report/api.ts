import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'

export interface LowStock {
  productId: string
  sku: string
  name: string
  baseUom: string
  onHand: number
  reorderPoint: number
}

export interface Dashboard {
  /** Cents. */
  stockValueMinor: number
  productsWithoutCost: number
  lowStock: LowStock[]
  today: { receipts: number; unitsReceived: number; shipments: number; unitsShipped: number; ordersReleased: number; ordersShipped: number; adjustments: number }
  bins: number
  binsInUse: number
  binUsePercent: number
  averageFillPercent: number
  /** Null when nothing was put away in the last 7 days. */
  dockToStockMinutes: number | null
  linesPerHour: number | null
}

/** The manager's morning page for one warehouse; refreshed every minute while open. */
export function useDashboard(warehouseId: string | undefined) {
  return useQuery({
    queryKey: ['dashboard', warehouseId],
    queryFn: () => api<Dashboard>(`/dashboard?warehouse=${warehouseId}`),
    enabled: !!warehouseId,
    refetchInterval: 60_000,
  })
}
