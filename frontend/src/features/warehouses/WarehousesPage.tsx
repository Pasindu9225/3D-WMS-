import { zodResolver } from '@hookform/resolvers/zod'
import { Archive, Map, Plus } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { errorMessage, fieldErrors } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useArchiveWarehouse, useCreateWarehouse, useWarehouses, type Warehouse } from './api'

const metres = (min: number, max: number) => z.coerce.number<string>().min(min, t('warehouses.range', { min, max }))
  .max(max, t('warehouses.range', { min, max }))

const schema = z.object({
  code: z.string().trim().regex(/^[A-Z0-9][A-Z0-9-]{0,19}$/, t('warehouses.codeRule')),
  name: z.string().trim().min(1, t('common.required')).max(120, t('common.tooLong')),
  widthM: metres(1, 1000),
  lengthM: metres(1, 1000),
  wallHeightM: metres(2, 40),
})
type Values = z.input<typeof schema>

export function WarehousesPage() {
  const me = useMe()
  const manage = can(me.data, 'warehouses:manage')
  const list = useWarehouses()
  const archive = useArchiveWarehouse()
  const [creating, setCreating] = useState(false)
  const [archiving, setArchiving] = useState<Warehouse | null>(null)

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold">{t('warehouses.title')}</h1>
        {manage && <Button onClick={() => setCreating(true)}><Plus /> {t('warehouses.new')}</Button>}
      </div>
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      {archive.isError && <FormMessage tone="error">{errorMessage(archive.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('warehouses.col.code')}</Th>
            <Th>{t('warehouses.col.name')}</Th>
            <Th className="hidden sm:table-cell">{t('warehouses.col.size')}</Th>
            <Th>{t('warehouses.col.layout')}</Th>
            <Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th>
          </tr>
        </thead>
        <tbody>
          {list.data?.items.map((w) => (
            <Tr key={w.id}>
              <td className="font-mono font-medium">{w.code}</td>
              <td>{w.name}</td>
              <td className="hidden tabular-nums text-muted-foreground sm:table-cell">
                {t('warehouses.size', { w: w.widthMm / 1000, l: w.lengthMm / 1000, h: w.wallHeightMm / 1000 })}
              </td>
              <td>
                <Badge tone={w.layoutStatus === 'PUBLISHED' ? 'good' : 'warn'}>{t(`warehouses.layout.${w.layoutStatus}`)}</Badge>
              </td>
              <td className="whitespace-nowrap text-right">
                <Button asChild variant="outline" size="sm">
                  <Link to={`/app/warehouses/${w.id}/layout`}><Map /> {manage ? t('warehouses.edit') : t('warehouses.view')}</Link>
                </Button>
                {manage && (
                  <Button variant="ghost" size="icon" aria-label={t('warehouses.archive', { code: w.code })}
                    onClick={() => setArchiving(w)}><Archive /></Button>
                )}
              </td>
            </Tr>
          ))}
          {list.data && list.data.items.length === 0 && (
            <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">{t('warehouses.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {creating && <CreateDialog onClose={() => setCreating(false)} />}
      {archiving && (
        <Dialog open onOpenChange={(o) => !o && setArchiving(null)} title={t('warehouses.archiveTitle')}
          description={t('warehouses.archiveConfirm', { code: archiving.code })}>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setArchiving(null)}>{t('common.cancel')}</Button>
            <Button variant="destructive" disabled={archive.isPending}
              onClick={() => archive.mutate(archiving.id, { onSuccess: () => setArchiving(null) })}>
              {t('warehouses.archiveTitle')}
            </Button>
          </div>
        </Dialog>
      )}
    </div>
  )
}

function CreateDialog({ onClose }: { onClose: () => void }) {
  const create = useCreateWarehouse()
  const form = useForm<Values, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: { code: '', name: '', widthM: '', lengthM: '', wallHeightM: '8' },
  })
  const { errors } = form.formState
  const server = fieldErrors(create.error)
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('warehouses.new')} description={t('warehouses.newHint')}>
      <form noValidate className="grid gap-4" onSubmit={form.handleSubmit((v) => create.mutate(v, { onSuccess: onClose }))}>
        {create.isError && Object.keys(server).length === 0 && <FormMessage tone="error">{errorMessage(create.error)}</FormMessage>}
        <div className="grid grid-cols-[8rem_1fr] gap-3">
          <Field label={t('warehouses.col.code')} error={errors.code?.message ?? server.code}>
            {(p) => <Input {...p} className="font-mono uppercase" maxLength={20} placeholder="WH1"
              {...form.register('code', { setValueAs: (v: string) => v.toUpperCase() })} />}
          </Field>
          <Field label={t('warehouses.col.name')} error={errors.name?.message ?? server.name}>
            {(p) => <Input {...p} maxLength={120} {...form.register('name')} />}
          </Field>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <Field label={t('warehouses.width')} error={errors.widthM?.message}>
            {(p) => <Input {...p} type="number" inputMode="decimal" step="0.1" {...form.register('widthM')} />}
          </Field>
          <Field label={t('warehouses.length')} error={errors.lengthM?.message}>
            {(p) => <Input {...p} type="number" inputMode="decimal" step="0.1" {...form.register('lengthM')} />}
          </Field>
          <Field label={t('warehouses.height')} error={errors.wallHeightM?.message}>
            {(p) => <Input {...p} type="number" inputMode="decimal" step="0.1" {...form.register('wallHeightM')} />}
          </Field>
        </div>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={create.isPending}>{create.isPending ? t('common.saving') : t('warehouses.create')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
