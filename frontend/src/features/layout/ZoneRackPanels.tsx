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

/** The nine main UN dangerous goods classes a zone can take. */
const HAZARD_CLASSES = ['1', '2', '3', '4', '5', '6', '7', '8', '9']
const ZONE_TYPES: ZoneType[] = ['RECEIVING', 'STORAGE', 'PICKING', 'DISPATCH', 'QUARANTINE', 'COLD', 'FROZEN']
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
      <fieldset className="space-y-1 text-sm">
        <legend className="font-medium">{t('layout.zone.hazard')}</legend>
        <div className="flex flex-wrap gap-x-3 gap-y-1">
          {HAZARD_CLASSES.map((c) => {
            const on = zone.hazardClasses?.includes(c) ?? false
            const toggle = () => {
              const next = on ? (zone.hazardClasses ?? []).filter((x) => x !== c) : [...(zone.hazardClasses ?? []), c].sort()
              updateZone(index, { hazardClasses: next.length ? next : null })
            }
            return (
              <label key={c} className="flex items-center gap-1" title={t(('hazard.' + c) as MessageKey)}>
                <input type="checkbox" checked={on} onChange={toggle} /> {c}
              </label>
            )
          })}
        </div>
        <p className="text-xs text-muted-foreground">{t('layout.zone.hazardHint')}</p>
      </fieldset>
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
  const unit = useEditor((s) => s.unit)
  const minAisle = useEditor((s) => s.doc?.settings.minAisleMm ?? 1200)
  const { updateRack, rotateRack, repeatRack, removeRack, duplicateRack, moveRack } = useEditor.getState()
  const [count, setCount] = useState(9)
  const [rows, setRows] = useState(1)
  const [aisle, setAisle] = useState<number | null>(null)
  if (!rack) return null
  const type = types[rack.templateId]
  const resized = rack.widthMm != null || rack.depthMm != null || rack.heightMm != null
  /** Size typed equal to the type size is stored as "no override", so the rack follows later type edits. */
  const size = (key: 'widthMm' | 'depthMm' | 'heightMm', mm: number) =>
    updateRack(index, { [key]: type && mm === type[key] ? null : mm })
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
        <>
          <div className="grid grid-cols-3 gap-2 text-sm">
            <label className="grid gap-1">{t('layout.rackW', { unit })}
              <LengthInput label={t('layout.rackW', { unit })} mm={rack.widthMm ?? type.widthMm} min={300} className="w-full"
                onCommit={(mm) => size('widthMm', Math.min(30_000, mm))} />
            </label>
            <label className="grid gap-1">{t('layout.rackD', { unit })}
              <LengthInput label={t('layout.rackD', { unit })} mm={rack.depthMm ?? type.depthMm} min={300} className="w-full"
                onCommit={(mm) => size('depthMm', Math.min(5_000, mm))} />
            </label>
            <label className="grid gap-1">{t('layout.rackH', { unit })}
              <LengthInput label={t('layout.rackH', { unit })} mm={rack.heightMm ?? type.heightMm} min={500} className="w-full"
                onCommit={(mm) => size('heightMm', Math.min(40_000, mm))} />
            </label>
          </div>
          <p className="text-xs text-muted-foreground">
            {t('layout.rackLevels', { levels: type.levels, bins: type.binsPerLevel })}
            {resized && <> · <button type="button" className="underline" onClick={() => updateRack(index, { widthMm: null, depthMm: null, heightMm: null })}>{t('layout.rackReset')}</button></>}
          </p>
        </>
      )}
      <div className="grid grid-cols-2 gap-2 text-sm">
        <label className="grid gap-1">{t('layout.centreX', { unit })}
          <LengthInput label={t('layout.centreX', { unit })} mm={rack.x} className="w-full" onCommit={(mm) => moveRack(index, { x: mm, y: rack.y }, true)} />
        </label>
        <label className="grid gap-1">{t('layout.centreY', { unit })}
          <LengthInput label={t('layout.centreY', { unit })} mm={rack.y} className="w-full" onCommit={(mm) => moveRack(index, { x: rack.x, y: mm }, true)} />
        </label>
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <label className="grid gap-1 text-sm">{t('layout.angle')}
          <input aria-label={t('layout.angle')} type="number" min={0} max={359} value={rack.rotation}
            onChange={(e) => {
              const deg = Math.round(Number(e.target.value))
              if (Number.isFinite(deg)) updateRack(index, { rotation: ((deg % 360) + 360) % 360 })
            }}
            className="h-8 w-20 rounded-md border border-input bg-background px-2 text-sm tabular-nums" />
        </label>
        <Button variant="outline" size="sm" onClick={() => rotateRack(index)}><RotateCw /> {t('layout.rotate90')}</Button>
      </div>
      <div className="flex flex-wrap gap-2">
        {type && <Button variant="outline" size="sm" onClick={() => duplicateRack(index, type)}><Copy /> {t('layout.duplicate')}</Button>}
        <Button variant="outline" size="sm" className="text-destructive" onClick={() => removeRack(index)}><Trash2 /> {t('layout.remove')}</Button>
      </div>
      {type && (
        <fieldset className="space-y-2 rounded-md border p-2">
          <legend className="px-1 text-sm font-medium">{t('layout.block')}</legend>
          <div className="grid grid-cols-3 gap-2 text-sm">
            <label className="grid gap-1">{t('layout.repeat')}
              <input type="number" min={0} max={99} value={count} aria-label={t('layout.repeat')}
                onChange={(e) => setCount(Math.max(0, Math.min(99, Math.round(Number(e.target.value)) || 0)))}
                className="h-8 w-full rounded-md border border-input bg-background px-2 text-sm tabular-nums" />
            </label>
            <label className="grid gap-1">{t('layout.rows')}
              <input type="number" min={1} max={50} value={rows} aria-label={t('layout.rows')}
                onChange={(e) => setRows(Math.max(1, Math.min(50, Math.round(Number(e.target.value)) || 1)))}
                className="h-8 w-full rounded-md border border-input bg-background px-2 text-sm tabular-nums" />
            </label>
            <label className="grid gap-1">{t('layout.aisle', { unit })}
              <LengthInput label={t('layout.aisle', { unit })} mm={aisle ?? Math.max(minAisle, 3000)} live onCommit={setAisle} className="w-full" />
            </label>
          </div>
          <p className="text-xs text-muted-foreground">{t('layout.blockResult', { n: (count + 1) * rows })}</p>
          <Button variant="outline" size="sm" disabled={count === 0 && rows === 1}
            onClick={() => repeatRack(index, count, type, rows, aisle ?? Math.max(minAisle, 3000))}><Copy /> {t('layout.repeatGo')}</Button>
        </fieldset>
      )}
      <p className="text-xs text-muted-foreground">{t('layout.rack.hint')}</p>
    </section>
  )
}
