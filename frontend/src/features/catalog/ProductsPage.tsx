import { zodResolver } from '@hookform/resolvers/zod'
import { Archive, FileUp, ImageOff, Pencil, Plus, Trash2, Upload } from 'lucide-react'
import { useState } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { z } from 'zod'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { Pager, Table, Th, Tr } from '@/components/ui/table'
import { can, useMe } from '@/features/auth/session'
import { formatKg, parseMoney, parseVolume, parseWeight } from '@/features/layout/units'
import { errorMessage, fieldErrors } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useDebounced } from '@/lib/useDebounced'
import { ImportDialog } from './ImportDialog'
import { imageUrl, PRODUCT_PAGE_SIZE, useArchiveProduct, useProductImage, useProducts, useSaveProduct, type Product } from './api'

/** The company's products: SKU, barcode, units of measure, size and weight (v0.4 task 1). */
export function ProductsPage() {
  const me = useMe()
  const manage = can(me.data, 'catalog:manage')
  const [search, setSearch] = useState('')
  const q = useDebounced(search.trim())
  const [page, setPage] = useState(0)
  const list = useProducts(q, page)
  const archive = useArchiveProduct()
  const [editing, setEditing] = useState<Product | 'new' | null>(null)
  const [archiving, setArchiving] = useState<Product | null>(null)
  const [importing, setImporting] = useState(false)
  const data = list.data

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t('products.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('products.hint')}</p>
        </div>
        {manage && (
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setImporting(true)}><FileUp /> {t('import.button')}</Button>
            <Button onClick={() => setEditing('new')}><Plus /> {t('products.new')}</Button>
          </div>
        )}
      </div>
      <Input aria-label={t('products.search')} placeholder={t('products.search')} value={search} maxLength={200} className="max-w-sm"
        onChange={(e) => {
          setSearch(e.target.value)
          setPage(0)
        }} />
      {list.isError && <FormMessage tone="error">{errorMessage(list.error)}</FormMessage>}
      {archive.isError && <FormMessage tone="error">{errorMessage(archive.error)}</FormMessage>}
      <Table>
        <thead>
          <tr>
            <Th>{t('products.col.sku')}</Th>
            <Th>{t('products.col.name')}</Th>
            <Th className="hidden md:table-cell">{t('products.col.barcode')}</Th>
            <Th className="hidden sm:table-cell">{t('products.col.units')}</Th>
            <Th className="hidden lg:table-cell">{t('products.col.weight')}</Th>
            <Th className="w-1"><span className="sr-only">{t('users.col.actions')}</span></Th>
          </tr>
        </thead>
        <tbody>
          {data?.items.map((p) => (
            <Tr key={p.id}>
              <td className="font-mono font-medium">{p.sku}</td>
              <td>
                {p.hasImage && <img src={imageUrl(p)} alt="" loading="lazy" className="mr-2 inline size-8 rounded object-cover align-middle" />}
                {p.name}
                {(p.trackBatch || p.trackExpiry) && (
                  <span className="ml-2 inline-flex gap-1">
                    {p.trackBatch && <Badge tone="neutral">{t('products.batch')}</Badge>}
                    {p.trackExpiry && <Badge tone="warn">{t('products.expiry')}</Badge>}
                  </span>
                )}
              </td>
              <td className="hidden font-mono text-sm md:table-cell">{p.barcode ?? '—'}</td>
              <td className="hidden text-sm sm:table-cell">
                {[p.baseUom, ...p.units.map((u) => `${u.uom} = ${u.factor} ${p.baseUom}`)].join(' · ')}
              </td>
              <td className="hidden tabular-nums lg:table-cell">{p.weightG === null ? '—' : formatKg(p.weightG)}</td>
              <td className="whitespace-nowrap text-right">
                {manage && (
                  <>
                    <Button variant="ghost" size="icon" aria-label={t('products.edit', { sku: p.sku })} onClick={() => setEditing(p)}><Pencil /></Button>
                    <Button variant="ghost" size="icon" aria-label={t('products.archive', { sku: p.sku })} onClick={() => setArchiving(p)}><Archive /></Button>
                  </>
                )}
              </td>
            </Tr>
          ))}
          {data && data.items.length === 0 && (
            <tr><td colSpan={6} className="py-8 text-center text-muted-foreground">{q ? t('products.noMatch') : t('products.empty')}</td></tr>
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
      {importing && <ImportDialog what="products" onClose={() => setImporting(false)} />}
      {editing && <ProductDialog product={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
      {archiving && (
        <Dialog open onOpenChange={(o) => !o && setArchiving(null)} title={t('products.archiveTitle')}
          description={t('products.archiveConfirm', { sku: archiving.sku })}>
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setArchiving(null)}>{t('common.cancel')}</Button>
            <Button variant="destructive" disabled={archive.isPending}
              onClick={() => archive.mutate(archiving.id, { onSuccess: () => setArchiving(null) })}>{t('products.archiveTitle')}</Button>
          </div>
        </Dialog>
      )}
    </div>
  )
}

const BARCODE = /^[0-9A-Za-z-]{1,64}$/
const UOM = /^[A-Z]{1,10}$/
const optional = (test: (v: string) => boolean, message: string) =>
  z.string().trim().refine((v) => v === '' || test(v), message)

const schema = z.object({
  sku: z.string().trim().regex(/^[A-Z0-9][A-Z0-9._-]{0,39}$/, t('products.skuRule')),
  name: z.string().trim().min(1, t('common.required')).max(200, t('common.tooLong')),
  barcode: optional((v) => BARCODE.test(v), t('products.barcodeRule')),
  baseUom: z.string().trim().regex(UOM, t('products.uomRule')),
  weight: optional((v) => { const g = parseWeight(v); return g !== null && g <= 100_000_000 }, t('products.weightRule')),
  volume: optional((v) => { const c = parseVolume(v); return c !== null && c <= 100_000_000 }, t('products.volumeRule')),
  cost: optional((v) => parseMoney(v) !== null, t('products.costRule')),
  trackBatch: z.boolean(),
  trackExpiry: z.boolean(),
  units: z.array(z.object({
    uom: z.string().trim().regex(UOM, t('products.uomRule')),
    factor: z.coerce.number<string>().int(t('rackTypes.whole')).min(2, t('products.factorRule')).max(1_000_000, t('products.factorRule')),
    barcode: optional((v) => BARCODE.test(v), t('products.barcodeRule')),
  })).max(10),
})
type Values = z.input<typeof schema>

function ProductDialog({ product, onClose }: { product: Product | null; onClose: () => void }) {
  const save = useSaveProduct()
  // Uploading a picture bumps the version; keep the latest so Save does not look like someone else's change.
  const [current, setCurrent] = useState(product)
  const form = useForm<Values, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: product
      ? {
          sku: product.sku, name: product.name, barcode: product.barcode ?? '', baseUom: product.baseUom,
          weight: product.weightG === null ? '' : String(product.weightG / 1000),
          volume: product.volumeCm3 === null ? '' : String(product.volumeCm3 / 1000),
          cost: product.costMinor === null ? '' : (product.costMinor / 100).toFixed(2),
          trackBatch: product.trackBatch, trackExpiry: product.trackExpiry,
          units: product.units.map((u) => ({ uom: u.uom, factor: String(u.factor), barcode: u.barcode ?? '' })),
        }
      : { sku: '', name: '', barcode: '', baseUom: 'EA', weight: '', volume: '', cost: '', trackBatch: false, trackExpiry: false, units: [] },
  })
  const units = useFieldArray({ control: form.control, name: 'units' })
  const { errors } = form.formState
  const server = fieldErrors(save.error)
  const submit = form.handleSubmit((v) => save.mutate({
    id: product?.id,
    sku: product ? undefined : v.sku,
    version: current?.version,
    name: v.name,
    barcode: v.barcode || null,
    baseUom: v.baseUom,
    weightG: v.weight ? parseWeight(v.weight) : null,
    volumeCm3: v.volume ? parseVolume(v.volume) : null,
    costMinor: v.cost ? parseMoney(v.cost) : null,
    trackBatch: v.trackBatch,
    trackExpiry: v.trackExpiry,
    units: v.units.map((u) => ({ uom: u.uom, factor: u.factor, barcode: u.barcode || null })),
  }, { onSuccess: onClose }))
  const upper = { setValueAs: (v: string) => v.toUpperCase() }
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={product ? t('products.editTitle') : t('products.new')}
      description={t('products.formHint')}>
      <form noValidate className="grid max-h-[70vh] gap-4 overflow-y-auto pr-1" onSubmit={submit}>
        {save.isError && Object.keys(server).length === 0 && <FormMessage tone="error">{errorMessage(save.error)}</FormMessage>}
        {current && <ImageField product={current} onChange={setCurrent} />}
        <div className="grid grid-cols-[9rem_1fr] gap-3">
          <Field label={t('products.col.sku')} error={errors.sku?.message ?? server.sku}>
            {(p) => <Input {...p} className="font-mono uppercase" maxLength={40} disabled={!!product} {...form.register('sku', upper)} />}
          </Field>
          <Field label={t('products.col.name')} error={errors.name?.message ?? server.name}>
            {(p) => <Input {...p} maxLength={200} {...form.register('name')} />}
          </Field>
        </div>
        <div className="grid grid-cols-[1fr_7rem] gap-3">
          <Field label={t('products.col.barcode')} error={errors.barcode?.message ?? server.barcode}>
            {(p) => <Input {...p} inputMode="numeric" className="font-mono" maxLength={64} {...form.register('barcode')} />}
          </Field>
          <Field label={t('products.baseUom')} error={errors.baseUom?.message}>
            {(p) => <Input {...p} className="font-mono uppercase" maxLength={10} {...form.register('baseUom', upper)} />}
          </Field>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <Field label={t('products.weightField')} error={errors.weight?.message}>
            {(p) => <Input {...p} inputMode="decimal" placeholder="0.95" {...form.register('weight')} />}
          </Field>
          <Field label={t('products.volumeField')} error={errors.volume?.message}>
            {(p) => <Input {...p} placeholder="1.1 or 30x20x15" {...form.register('volume')} />}
          </Field>
          <Field label={t('products.costField')} error={errors.cost?.message}>
            {(p) => <Input {...p} inputMode="decimal" placeholder="450.00" {...form.register('cost')} />}
          </Field>
        </div>
        <div className="flex flex-wrap gap-4 text-sm">
          <label className="flex items-center gap-2"><input type="checkbox" {...form.register('trackBatch')} /> {t('products.trackBatch')}</label>
          <label className="flex items-center gap-2"><input type="checkbox" {...form.register('trackExpiry')} /> {t('products.trackExpiry')}</label>
        </div>
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">{t('products.units')}</legend>
          <p className="text-xs text-muted-foreground">{t('products.unitsHint')}</p>
          {units.fields.map((f, i) => (
            <div key={f.id} className="grid grid-cols-[6rem_6rem_1fr_auto] items-start gap-2">
              <Field label={t('products.unit', { n: i + 1 })} error={errors.units?.[i]?.uom?.message}>
                {(p) => <Input {...p} className="font-mono uppercase" placeholder="CASE" maxLength={10} {...form.register(`units.${i}.uom`, upper)} />}
              </Field>
              <Field label={t('products.factor')} error={errors.units?.[i]?.factor?.message}>
                {(p) => <Input {...p} type="number" min={2} placeholder="12" {...form.register(`units.${i}.factor`)} />}
              </Field>
              <Field label={t('products.unitBarcode')} error={errors.units?.[i]?.barcode?.message}>
                {(p) => <Input {...p} className="font-mono" maxLength={64} {...form.register(`units.${i}.barcode`)} />}
              </Field>
              <Button type="button" variant="ghost" size="icon" className="mt-6" aria-label={t('products.removeUnit', { n: i + 1 })}
                onClick={() => units.remove(i)}><Trash2 /></Button>
            </div>
          ))}
          {units.fields.length < 10 && (
            <Button type="button" variant="outline" size="sm" onClick={() => units.append({ uom: '', factor: '', barcode: '' })}>
              <Plus /> {t('products.addUnit')}
            </Button>
          )}
        </fieldset>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
          <Button type="submit" disabled={save.isPending}>{save.isPending ? t('common.saving') : t('common.save')}</Button>
        </div>
      </form>
    </Dialog>
  )
}

/** Picture of an existing product: uploads straight away (JPEG/PNG up to 5 MB, re-encoded by the server). */
function ImageField({ product, onChange }: { product: Product; onChange: (p: Product) => void }) {
  const image = useProductImage()
  const pick = (file: File | null) => image.mutate({ id: product.id, file }, { onSuccess: onChange })
  return (
    <div className="flex items-center gap-3 rounded-md border p-2">
      {product.hasImage
        ? <img src={imageUrl(product)} alt={t('products.imageAlt', { sku: product.sku })} className="size-16 rounded object-cover" />
        : <div className="grid size-16 place-items-center rounded bg-secondary text-muted-foreground"><ImageOff /></div>}
      <div className="space-y-1 text-sm">
        <div className="flex flex-wrap gap-2">
          <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-md border px-2 py-1 font-medium hover:bg-secondary">
            <Upload className="size-4" /> {product.hasImage ? t('products.imageReplace') : t('products.imageUpload')}
            <input type="file" accept="image/jpeg,image/png" className="sr-only" disabled={image.isPending}
              onChange={(e) => {
                pick(e.target.files?.[0] ?? null)
                e.target.value = ''
              }} />
          </label>
          {product.hasImage && (
            <Button type="button" variant="ghost" size="sm" disabled={image.isPending} onClick={() => pick(null)}>{t('products.imageRemove')}</Button>
          )}
        </div>
        <p className="text-xs text-muted-foreground">{image.isPending ? t('products.imageUploading') : t('products.imageHint')}</p>
        {image.isError && <p role="alert" className="text-xs text-destructive">{errorMessage(image.error)}</p>}
      </div>
    </div>
  )
}
