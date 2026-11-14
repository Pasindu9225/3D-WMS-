import { Copy, RotateCw, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Field, Input } from '@/components/ui/input'
import type { RackType, ZoneType } from '@/features/warehouses/api'
import { t, type MessageKey } from '@/lib/i18n'
import { useEditor } from './editorStore'
import { areaM2, bounds } from './geometry'
import { LengthInput } from './LengthInput'
import { formatArea } from './units'

const ZONE_TYPES: ZoneType[] = ['RECEIVING', 'STORAGE', 'PICKING', 'DISPATCH', 'QUARANTINE', 'COLD']
const SELECT = 'h-10 w-full rounded-md border border-input bg-background px-3 text-sm'
/** Server rule for zone and rack codes; typing is forced to upper case and filtered to this. */
const code = (v: string) => v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 10)

export function ZonePanel({ index }: { index: number }) {
  const zone = useEditor((s) => s.doc?.zones[index])
  const racks = useEditor((s) => s.doc?.racks.filter((r) => r.zone === zone?.code).length ?? 0)
  const unit = useEditor((s) => s.unit)
  const { updateZone, removeZone, moveZone, resizeZone, duplicateZone } = useEditor.getState()
  if (!zone) return null
  const b = bounds(zone.polygon)
  return (
    <section className="space-y-3 rounded-lg border p-3">
      <h2 className="text-sm font-semibold">{t('layout.zone')}</h2>
      <Field label={t('layout.code')}>
        {(p) => <Input {...p} value={zone.code} onChange={(e) => code(e.target.value) && updateZone(index, { code: code(e.target.value) })} />}
      </Field>
      <Field label={t('layout.name')}>
        {(p) => <Input {...p} value={zone.name} maxLength={60} onChange={(e) => updateZone(index, { name: e.target.value })} />}
      </Field>
      <Field label={t('layout.zoneType')}>
        {(p) => (
          <select {...p} className={SELECT} value={zone.type} onChange={(e) => updateZone(index, { type: e.target.value as ZoneType })}>
            {ZONE_TYPES.map((z) => <option key={z} value={z}>{t(`layout.zoneType.${z}` as MessageKey)}</option>)}
          </select>
        )}
      </Field>
      <div className="grid grid-cols-2 gap-2 text-sm">
        <label className="grid gap-1">{t('layout.posX', { unit })}
          <LengthInput label={t('layout.posX', { unit })} mm={b.minX} className="w-full" onCommit={(mm) => moveZone(index, mm - b.minX, 0)} />
        </label>
        <label className="grid gap-1">{t('layout.posY', { unit })}
          <LengthInput label={t('layout.posY', { unit })} mm={b.minY} className="w-full" onCommit={(mm) => moveZone(index, 0, mm - b.minY)} />
        </label>
        <label className="grid gap-1">{t('layout.sizeW', { unit })}
          <LengthInput label={t('layout.sizeW', { unit })} mm={b.maxX - b.minX} min={100} className="w-full"
            onCommit={(mm) => resizeZone(index, mm, b.maxY - b.minY)} />
        </label>
        <label className="grid gap-1">{t('layout.sizeL', { unit })}
          <LengthInput label={t('layout.sizeL', { unit })} mm={b.maxY - b.minY} min={100} className="w-full"
            onCommit={(mm) => resizeZone(index, b.maxX - b.minX, mm)} />
        </label>
      </div>
      <p className="text-xs tabular-nums text-muted-foreground">{t('layout.zone.area', { area: formatArea(areaM2(zone.polygon) * 1_000_000, unit), unit })}</p>
      <p className="text-xs text-muted-foreground">{t('layout.zone.hint', { code: zone.code })}</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" onClick={() => duplicateZone(index)}><Copy /> {t('layout.duplicate')}</Button>
        <Button variant="outline" size="sm" className="text-destructive" onClick={() => removeZone(index)}>
          <Trash2 /> {racks > 0 ? t('layout.zone.removeWithRacks', { n: racks }) : t('layout.remove')}
        </Button>
      </div>
    </section>
  )
}

export function RackPanel({ index, types }: { index: number; types: Record<string, RackType> }) {
  const rack = useEditor((s) => s.doc?.racks[index])
  const { updateRack, rotateRack, repeatRack, removeRack } = useEditor.getState()
  const [count, setCount] = useState(5)
  if (!rack) return null
  const type = types[rack.templateId]
  return (
    <section className="space-y-3 rounded-lg border p-3">
      <h2 className="text-sm font-semibold">{t('layout.rack')} · {t('layout.inZone', { zone: rack.zone })}</h2>
      <Field label={t('layout.code')}>
        {(p) => <Input {...p} value={rack.code} onChange={(e) => code(e.target.value) && updateRack(index, { code: code(e.target.value) })} />}
      </Field>
      <Field label={t('layout.rackType')}>
        {(p) => (
          <select {...p} className={SELECT} value={rack.templateId} onChange={(e) => updateRack(index, { templateId: e.target.value })}>
            {Object.values(types).map((rt) => <option key={rt.id} value={rt.id}>{rt.code} · {rt.name}</option>)}
          </select>
        )}
      </Field>
      {type && (
        <p className="text-xs text-muted-foreground">
          {t('layout.rackSize', { w: type.widthMm / 1000, d: type.depthMm / 1000, levels: type.levels, bins: type.binsPerLevel })}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" size="sm" onClick={() => rotateRack(index)}><RotateCw /> {t('layout.rotate', { deg: rack.rotation })}</Button>
        <Button variant="outline" size="sm" className="text-destructive" onClick={() => removeRack(index)}><Trash2 /> {t('layout.remove')}</Button>
      </div>
      {type && (
        <div className="flex items-end gap-2">
          <Field label={t('layout.repeat')}>
            {(p) => <Input {...p} type="number" min={1} max={50} value={count} className="w-20"
              onChange={(e) => setCount(Math.max(1, Math.min(50, Math.round(Number(e.target.value)) || 1)))} />}
          </Field>
          <Button variant="outline" size="sm" className="mb-1" onClick={() => repeatRack(index, count, type)}><Copy /> {t('layout.repeatGo')}</Button>
        </div>
      )}
    </section>
  )
}
