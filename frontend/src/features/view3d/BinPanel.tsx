import { X } from 'lucide-react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatKg } from '@/features/layout/units'
import { useBins } from '@/features/warehouses/api'
import { t } from '@/lib/i18n'
import { useBinStock } from '@/features/inventory/api'
import { BLOCKED, PICK_FACE, type Scene } from './api'

/** Details of the bin picked in 3D: capacity from the bins API, contents from stock. */
export function BinPanel({ scene, index, warehouseId, canManage, onClose }: {
  scene: Scene
  index: number
  warehouseId: string
  canManage: boolean
  onClose: () => void
}) {
  const code = scene.bins.codes[index]!
  const rack = scene.racks[scene.bins.rack[index]!]!
  const flags = Number(scene.flags[index] ?? 0)
  // Prefix search on the full code; pick the exact one (…-01-10 is also a prefix of …-01-100).
  const list = useBins(warehouseId, code, 0)
  const bin = list.data?.items.find((b) => b.code === code)
  const contents = useBinStock(warehouseId, scene.bins.ids[index]!)
  const fill = scene.fill?.[index] ?? 0
  return (
    <aside aria-label={t('view3d.bin.panel', { code })}
      className="absolute top-3 right-3 w-72 max-w-[calc(100%-1.5rem)] space-y-3 rounded-lg border bg-card/95 p-4 shadow-lg">
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="text-xs text-muted-foreground">{t('view3d.bin.title')}</p>
          <h2 className="font-mono text-lg font-semibold">{code}</h2>
        </div>
        <Button variant="ghost" size="icon" aria-label={t('view3d.bin.close')} onClick={onClose}><X /></Button>
      </div>
      <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t('view3d.bin.rack')}</dt>
        <dd>{rack.code} · {t('view3d.bin.zone', { zone: rack.zone })}</dd>
        <dt className="text-muted-foreground">{t('view3d.bin.level')}</dt>
        <dd>{t('view3d.bin.of', { n: scene.bins.level[index]! + 1, total: rack.levels.length })}</dd>
        <dt className="text-muted-foreground">{t('view3d.bin.slot')}</dt>
        <dd>{t('view3d.bin.of', { n: scene.bins.pos[index]! + 1, total: rack.binsPerLevel })}</dd>
        <dt className="text-muted-foreground">{t('view3d.bin.weight')}</dt>
        <dd>{bin ? formatKg(bin.maxWeightG) : '…'}</dd>
        <dt className="text-muted-foreground">{t('view3d.bin.volume')}</dt>
        <dd>{bin ? t('bins.volume', { m3: Math.round(bin.maxVolumeCm3 / 10_000) / 100 }) : '…'}</dd>
        <dt className="text-muted-foreground">{t('view3d.bin.full')}</dt>
        <dd>{fill}%</dd>
      </dl>
      <div className="flex flex-wrap gap-1.5">
        {flags & BLOCKED ? <Badge tone="bad">{t('bins.col.blocked')}</Badge> : null}
        {flags & PICK_FACE ? <Badge tone="good">{t('bins.col.pickFace')}</Badge> : null}
        {bin?.customCapacity && <Badge tone="warn">{t('bins.custom')}</Badge>}
      </div>
      <div>
        <p className="mb-1 text-xs font-medium">{t('view3d.bin.contents')}</p>
        {contents.data && contents.data.items.length === 0 && <p className="text-xs text-muted-foreground">{t('view3d.bin.empty')}</p>}
        <ul className="max-h-40 space-y-1 overflow-y-auto text-xs">
          {contents.data?.items.map((s, i) => (
            <li key={i} className="flex justify-between gap-2">
              <span className="min-w-0 truncate"><span className="font-mono">{s.sku}</span> {s.name}
                {(s.batchNo || s.expiryDate) && <span className="text-muted-foreground"> · {[s.batchNo, s.expiryDate].filter(Boolean).join(' · ')}</span>}
              </span>
              <span className="shrink-0 tabular-nums">{Number(s.qtyOnHand)} {s.baseUom}</span>
            </li>
          ))}
        </ul>
      </div>
      {canManage && (
        <Button asChild variant="outline" size="sm"><Link to={`/app/warehouses/${warehouseId}/bins`}>{t('view3d.bin.edit')}</Link></Button>
      )}
    </aside>
  )
}
