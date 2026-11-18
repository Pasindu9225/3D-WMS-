import { zodResolver } from '@hookform/resolvers/zod'
import { Archive, Pencil, Plus } from 'lucide-react'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { useEditor } from '@/features/layout/editorStore'
import { format, formatKg, formatWith, parse, parseWeight, UNITS, type Unit } from '@/features/layout/units'
import { useArchiveRackType, useRackTypes, useSaveRackType, type RackType } from '@/features/warehouses/api'
import { errorMessage, fieldErrors } from '@/lib/errors'
import { t } from '@/lib/i18n'

/** The company's rack types: the sizes racks are placed from. Sizes and weights in any unit. */
export function RackTypesPage() {
  const me = useMe()
  const manage = can(me.data, 'warehouses:manage')
  const list = useRackTypes()
  const archive = useArchiveRackType()
  const unit = useEditor((s) => s.unit)
  const setUnit = useEditor((s) => s.setUnit)
  const [editing, setEditing] = useState<RackType | 'new' | null>(null)
  const [archiving, setArchiving] = useState<RackType | null>(null)

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('rackTypes.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('rackTypes.hint')}</p>
        </div>
        <div className="flex items-center gap-2">
          <select aria-label={t('layout.unit')} value={unit} onChange={(e) => setUnit(e.target.value as Unit)}
            className="h-10 rounded-md border border-input bg-background px-2 text-sm">
            {(Object.keys(UNITS) as Unit[]).map((u) => <option key={u} value={u}>{u}</option>)}
          </select>
          {manage && <Button onClick={() => setEditing('new')}><Plus /> {t('rackTypes.new')}</Button>}
        </div>
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      {archive.isError && <FormMessage tone="error">{errorMessage(archive.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('rackTypes.col.code')}</Th>
            <Th>{t('rackTypes.col.name')}</Th>
            <Th>{t('rackTypes.col.size')}</Th>
            <Th className="hidden sm:table-cell">{t('rackTypes.col.bins')}</Th>
            <Th className="hidden md:table-cell">{t('rackTypes.col.weight')}</Th>
            <Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th>
          </tr>
        </thead>
        <tbody>
          {list.data?.items.map((rt) => (
            <Tr key={rt.id}>
              <td className="font-mono font-medium">{rt.code}</td>
              <td>{rt.name}</td>
              <td className="tabular-nums">
                {format(rt.widthMm, unit)} × {format(rt.depthMm, unit)} × {formatWith(rt.heightMm, unit)}
              </td>
              <td className="hidden tabular-nums sm:table-cell">{t('rackTypes.bins', { levels: rt.levels, bins: rt.binsPerLevel })}</td>
              <td className="hidden tabular-nums md:table-cell">{formatKg(rt.maxWeightPerLevelG)}</td>
              <td className="whitespace-nowrap text-right">
                {manage && (
                  <>
                    <Button variant="ghost" size="icon" aria-label={t('rackTypes.edit', { code: rt.code })} onClick={() => setEditing(rt)}><Pencil /></Button>
                    <Button variant="ghost" size="icon" aria-label={t('rackTypes.archive', { code: rt.code })} onClick={() => setArchiving(rt)}><Archive /></Button>
                  </>
                )}
              </td>
            </Tr>
          ))}
          {list.data && list.data.items.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{t('rackTypes.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {editing && <RackTypeDialog type={editing === 'new' ? null : editing} unit={unit} onClose={() => setEditing(null)} />}
      {archiving && (
        <Dialog open onOpenChange={(o) => !o && setArchiving(null)} title={t('rackTypes.archiveTitle')}
          description={t('rackTypes.archiveConfirm', { code: archiving.code })}>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setArchiving(null)}>{t('common.cancel')}</Button>
            <Button variant="destructive" disabled={archive.isPending}
              onClick={() => archive.mutate(archiving.id, { onSuccess: () => setArchiving(null) })}>{t('rackTypes.archiveTitle')}</Button>
          </div>
        </Dialog>
      )}
    </div>
  )
}

function RackTypeDialog({ type, unit, onClose }: { type: RackType | null; unit: Unit; onClose: () => void }) {
  const save = useSaveRackType()
  // Lengths are typed in the page unit (or with their own unit) and checked against the server's limits in mm.
  const length = (min: number, max: number) => z.string().refine((v) => {
    const mm = parse(v, unit)
    return mm !== null && mm >= min && mm <= max
  }, t('rackTypes.lengthRange', { min: formatWith(min, unit), max: formatWith(max, unit) }))
  const whole = (min: number, max: number) => z.coerce.number<string>().int(t('rackTypes.whole'))
    .min(min, t('warehouses.range', { min, max })).max(max, t('warehouses.range', { min, max }))
  const schema = z.object({
    code: z.string().trim().regex(/^[A-Z0-9][A-Z0-9-]{0,19}$/, t('warehouses.codeRule')),
    name: z.string().trim().min(1, t('common.required')).max(80, t('common.tooLong')),
    width: length(300, 30_000),
    depth: length(300, 5_000),
    height: length(500, 40_000),
    levels: whole(1, 20),
    binsPerLevel: whole(1, 50),
    style: z.enum(['PALLET', 'SHELF', 'FLOOR']),
    uprightColor: z.string().regex(/^#[0-9a-fA-F]{6}$/),
    beamColor: z.string().regex(/^#[0-9a-fA-F]{6}$/),
    weight: z.string().refine((v) => {
      const g = parseWeight(v)
      return g !== null && g >= 1 && g <= 100_000_000
    }, t('rackTypes.weightRange')),
  })
  type Values = z.input<typeof schema>
  const form = useForm<Values, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: type
      ? { code: type.code, name: type.name, width: format(type.widthMm, unit), depth: format(type.depthMm, unit),
          height: format(type.heightMm, unit), levels: String(type.levels), binsPerLevel: String(type.binsPerLevel),
          weight: String(type.maxWeightPerLevelG / 1000), style: type.style ?? 'PALLET', uprightColor: type.uprightColor ?? '#1d4ed8',
          beamColor: type.beamColor ?? '#f97316' }
      : { code: '', name: '', width: '', depth: '', height: '', levels: '4', binsPerLevel: '3', weight: '1000', style: 'PALLET',
          uprightColor: '#1d4ed8', beamColor: '#f97316' },
  })
  const { errors } = form.formState
  const server = fieldErrors(save.error)
  const submit = form.handleSubmit((v) => save.mutate({
    id: type?.id,
    code: type ? undefined : v.code,
    name: v.name,
    widthMm: parse(v.width, unit)!,
    depthMm: parse(v.depth, unit)!,
    heightMm: parse(v.height, unit)!,
    levels: v.levels,
    binsPerLevel: v.binsPerLevel,
    maxWeightPerLevelG: parseWeight(v.weight)!,
    style: v.style,
    uprightColor: v.uprightColor,
    beamColor: v.beamColor,
  }, { onSuccess: onClose }))
  const look = useWatch({ control: form.control, name: ['style', 'uprightColor', 'beamColor', 'levels', 'binsPerLevel'] })
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={type ? t('rackTypes.editTitle') : t('rackTypes.new')}
      description={t('rackTypes.formHint', { unit })}>
      <form noValidate className="grid gap-4" onSubmit={submit}>
        {save.isError && Object.keys(server).length === 0 && <FormMessage tone="error">{errorMessage(save.error)}</FormMessage>}
        <div className="grid grid-cols-[8rem_1fr] gap-3">
          <Field label={t('rackTypes.col.code')} error={errors.code?.message ?? server.code}>
            {(p) => <Input {...p} className="font-mono uppercase" maxLength={20} placeholder="PAL27" disabled={!!type}
              {...form.register('code', { setValueAs: (v: string) => v.toUpperCase() })} />}
          </Field>
          <Field label={t('rackTypes.col.name')} error={errors.name?.message ?? server.name}>
            {(p) => <Input {...p} maxLength={80} placeholder={t('rackTypes.namePlaceholder')} {...form.register('name')} />}
          </Field>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <Field label={t('layout.rackW', { unit })} error={errors.width?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...form.register('width')} />}
          </Field>
          <Field label={t('layout.rackD', { unit })} error={errors.depth?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...form.register('depth')} />}
          </Field>
          <Field label={t('layout.rackH', { unit })} error={errors.height?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...form.register('height')} />}
          </Field>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <Field label={look[0] === 'FLOOR' ? t('rackTypes.palletsHigh') : t('rackTypes.levels')} error={errors.levels?.message}>
            {(p) => <Input {...p} type="number" min={1} max={20} {...form.register('levels')} />}
          </Field>
          <Field label={look[0] === 'FLOOR' ? t('rackTypes.positions') : t('rackTypes.binsPerLevel')} error={errors.binsPerLevel?.message}>
            {(p) => <Input {...p} type="number" min={1} max={50} {...form.register('binsPerLevel')} />}
          </Field>
          <Field label={t('rackTypes.weightPerLevel')} error={errors.weight?.message}>
            {(p) => <Input {...p} inputMode="decimal" {...form.register('weight')} />}
          </Field>
        </div>
        <fieldset className="grid grid-cols-[1fr_auto] items-end gap-3 rounded-md border p-3">
          <legend className="px-1 text-sm font-medium">{t('rackTypes.look')}</legend>
          <div className="grid gap-3">
            <Field label={t('rackTypes.style')}>
              {(p) => (
                <select {...p} className="h-10 rounded-md border border-input bg-background px-3" {...form.register('style')}>
                  <option value="PALLET">{t('rackTypes.style.PALLET')}</option>
                  <option value="SHELF">{t('rackTypes.style.SHELF')}</option>
                  <option value="FLOOR">{t('rackTypes.style.FLOOR')}</option>
                </select>
              )}
            </Field>
            <div className="flex gap-4">
              <label className="flex items-center gap-2 text-sm">
                <input type="color" className="h-9 w-12 cursor-pointer rounded border" {...form.register('uprightColor')} /> {t('rackTypes.uprightColor')}
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input type="color" className="h-9 w-12 cursor-pointer rounded border" {...form.register('beamColor')} /> {t('rackTypes.beamColor')}
              </label>
            </div>
          </div>
          <RackPreview style={look[0]} upright={look[1]} beam={look[2]} levels={Number(look[3]) || 1} slots={Number(look[4]) || 1} />
        </fieldset>
        {type && <p className="text-xs text-muted-foreground">{t('rackTypes.editNote')}</p>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={save.isPending}>{save.isPending ? t('common.saving') : t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}

/** A small front view of the rack in its chosen look: uprights, beams (or shelves) and loads. */
function RackPreview({ style, upright, beam, levels, slots }: { style: 'PALLET' | 'SHELF' | 'FLOOR'; upright: string; beam: string; levels: number; slots: number }) {
  const n = Math.min(Math.max(levels, 1), 8)
  const s = Math.min(Math.max(slots, 1), 6)
  const w = 120
  const h = 130
  const rows = Array.from({ length: n }, (_, i) => i)
  const cols = Array.from({ length: s }, (_, i) => i)
  const levelH = (h - 10) / n
  const slotW = (w - 16) / s
  return (
    <svg role="img" aria-label={t('rackTypes.preview')} width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="rounded bg-muted">
      {rows.map((r) => {
        const y = h - 4 - r * levelH
        return (
          <g key={r}>
            {cols.map((c) => (
              <g key={c}>
                {style !== 'SHELF' && <rect x={8 + c * slotW + 2} y={y - 5} width={slotW - 4} height={4} fill="#a16207" />}
                <rect x={8 + c * slotW + 3} y={y - (style !== 'SHELF' ? 5 : 1) - levelH * 0.5} width={slotW - 6} height={levelH * 0.5} fill="#d8a86c" stroke="#92400e" strokeWidth={0.5} />
              </g>
            ))}
            {r > 0 && style !== 'FLOOR' && <rect x={6} y={y} width={w - 12} height={style === 'SHELF' ? 3 : 4} fill={beam} />}
          </g>
        )
      })}
      {style === 'FLOOR' ? <rect x={2} y={h - 4} width={w - 4} height={3} fill="#fde047" /> : (
        <>
          <rect x={4} y={4} width={5} height={h - 4} fill={upright} />
          <rect x={w - 9} y={4} width={5} height={h - 4} fill={upright} />
        </>
      )}
    </svg>
  )
}
