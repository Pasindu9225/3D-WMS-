import { zodResolver } from '@hookform/resolvers/zod'
import { Archive, FileUp, Pencil, Plus } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { errorMessage, fieldErrors } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'
import { PRODUCT_PAGE_SIZE, useArchiveSupplier, useSaveSupplier, useSuppliers, type Supplier } from './api'
import { ImportDialog } from './ImportDialog'

/** Suppliers: who the company buys from (purchase orders and receiving use them). */
export function SuppliersPage() {
  const me = useMe()
  const manage = can(me.data, 'catalog:manage')
  const [search, setSearch] = useState('')
  const q = useDebounced(search.trim())
  const [page, setPage] = useState(0)
  const list = useSuppliers(q, page)
  const archive = useArchiveSupplier()
  const [editing, setEditing] = useState<Supplier | 'new' | null>(null)
  const [archiving, setArchiving] = useState<Supplier | null>(null)
  const [importing, setImporting] = useState(false)
  const data = list.data

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('suppliers.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('suppliers.hint')}</p>
        </div>
        {manage && (
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setImporting(true)}><FileUp /> {t('import.button')}</Button>
            <Button onClick={() => setEditing('new')}><Plus /> {t('suppliers.new')}</Button>
          </div>
        )}
      </div>
      <Input aria-label={t('suppliers.search')} placeholder={t('suppliers.search')} value={search} maxLength={200} className="max-w-sm"
        onChange={(e) => {
          setSearch(e.target.value)
          setPage(0)
        }} />
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      {archive.isError && <FormMessage tone="error">{errorMessage(archive.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('suppliers.col.code')}</Th>
            <Th>{t('suppliers.col.name')}</Th>
            <Th className="hidden md:table-cell">{t('suppliers.col.contact')}</Th>
            <Th className="hidden lg:table-cell">{t('suppliers.col.email')}</Th>
            <Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((s) => (
            <Tr key={s.id}>
              <td className="font-mono font-medium">{s.code}</td>
              <td>{s.name}</td>
              <td className="hidden text-sm md:table-cell">{[s.contactName, s.phone].filter(Boolean).join(' · ') || '—'}</td>
              <td className="hidden text-sm lg:table-cell">{s.email ?? '—'}</td>
              <td className="whitespace-nowrap text-right">
                {manage && (
                  <>
                    <Button variant="ghost" size="icon" aria-label={t('suppliers.edit', { code: s.code })} onClick={() => setEditing(s)}><Pencil /></Button>
                    <Button variant="ghost" size="icon" aria-label={t('suppliers.archive', { code: s.code })} onClick={() => setArchiving(s)}><Archive /></Button>
                  </>
                )}
              </td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={5} className="py-8 text-center text-muted-foreground">{q ? t('suppliers.noMatch') : t('suppliers.empty')}</td></tr>
          )}
        </tbody>
      </Table>
      {data && data.total > PRODUCT_PAGE_SIZE && (
        <Pager page={data.page} size={data.size} total={data.total} onPage={setPage}
          labels={{
            previous: t('common.previous'),
            next: t('common.next'),
            range: t('common.pageOf', { from: data.page * data.size + 1, to: data.page * data.size + data.items.length, total: data.total }),
          }} />
      )}
      {editing && <SupplierDialog supplier={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {importing && <ImportDialog what="suppliers" onClose={() => setImporting(false)} />}
      {archiving && (
        <Dialog open onOpenChange={(o) => !o && setArchiving(null)} title={t('suppliers.archiveTitle')}
          description={t('suppliers.archiveConfirm', { code: archiving.code })}>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setArchiving(null)}>{t('common.cancel')}</Button>
            <Button variant="destructive" disabled={archive.isPending}
              onClick={() => archive.mutate(archiving.id, { onSuccess: () => setArchiving(null) })}>{t('suppliers.archiveTitle')}</Button>
          </div>
        </Dialog>
      )}
    </div>
  )
}

const schema = z.object({
  code: z.string().trim().regex(/^[A-Z0-9][A-Z0-9-]{0,19}$/, t('suppliers.codeRule')),
  name: z.string().trim().min(1, t('common.required')).max(200, t('common.tooLong')),
  contactName: z.string().trim().max(120, t('common.tooLong')),
  phone: z.string().trim().refine((v) => v === '' || /^[0-9 +()-]{3,30}$/.test(v), t('suppliers.phoneRule')),
  email: z.string().trim().refine((v) => v === '' || z.email().safeParse(v).success, t('suppliers.emailRule')),
  address: z.string().trim().max(500, t('common.tooLong')),
})
type Values = z.input<typeof schema>

function SupplierDialog({ supplier, onClose }: { supplier: Supplier | null; onClose: () => void }) {
  const save = useSaveSupplier()
  const form = useForm<Values, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: supplier?.code ?? '', name: supplier?.name ?? '', contactName: supplier?.contactName ?? '',
      phone: supplier?.phone ?? '', email: supplier?.email ?? '', address: supplier?.address ?? '',
    },
  })
  const { errors } = form.formState
  const server = fieldErrors(save.error)
  const submit = form.handleSubmit((v) => save.mutate({
    id: supplier?.id, code: supplier ? undefined : v.code, version: supplier?.version, name: v.name,
    contactName: v.contactName || null, phone: v.phone || null, email: v.email || null, address: v.address || null,
  }, { onSuccess: onClose }))
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={supplier ? t('suppliers.editTitle') : t('suppliers.new')}>
      <form noValidate className="grid gap-4" onSubmit={submit}>
        {save.isError && Object.keys(server).length === 0 && <FormMessage tone="error">{errorMessage(save.error)}</FormMessage>}
        <div className="grid grid-cols-[9rem_1fr] gap-3">
          <Field label={t('suppliers.col.code')} error={errors.code?.message ?? server.code}>
            {(p) => <Input {...p} className="font-mono uppercase" maxLength={20} disabled={!!supplier}
              {...form.register('code', { setValueAs: (v: string) => v.toUpperCase() })} />}
          </Field>
          <Field label={t('suppliers.col.name')} error={errors.name?.message ?? server.name}>
            {(p) => <Input {...p} maxLength={200} {...form.register('name')} />}
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label={t('suppliers.col.contact')} error={errors.contactName?.message}>
            {(p) => <Input {...p} maxLength={120} {...form.register('contactName')} />}
          </Field>
          <Field label={t('suppliers.phone')} error={errors.phone?.message ?? server.phone}>
            {(p) => <Input {...p} type="tel" maxLength={30} {...form.register('phone')} />}
          </Field>
        </div>
        <Field label={t('suppliers.col.email')} error={errors.email?.message ?? server.email}>
          {(p) => <Input {...p} type="email" maxLength={254} {...form.register('email')} />}
        </Field>
        <Field label={t('suppliers.address')} error={errors.address?.message}>
          {(p) => <Input {...p} maxLength={500} {...form.register('address')} />}
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={save.isPending}>{save.isPending ? t('common.saving') : t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
