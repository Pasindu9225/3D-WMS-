import { zodResolver } from '@hookform/resolvers/zod'
import { Check, Copy } from 'lucide-react'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { errorMessage, fieldErrors } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { useInvite, useRename, useRoles, useSetEnabled, useSetRoles, type Invite, type User } from './api'

const rolesField = z.array(z.string()).min(1, t('validation.rolesRequired'))

// ---------------------------------------------------------------- invite

const inviteSchema = z.object({
  email: z.email(t('validation.email')).max(190, t('common.tooLong')),
  fullName: z.string().trim().min(1, t('common.required')).max(120, t('common.tooLong')),
  roles: rolesField,
})
type InviteValues = z.infer<typeof inviteSchema>

export function InviteDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const invite = useInvite()
  const form = useForm<InviteValues>({ resolver: zodResolver(inviteSchema), defaultValues: { email: '', fullName: '', roles: [] } })
  const close = (o: boolean) => {
    onOpenChange(o)
    if (!o) {
      form.reset()
      invite.reset()
    }
  }
  if (invite.data) {
    return <InviteLinkDialog invite={invite.data} open={open} onOpenChange={close} />
  }
  const server = fieldErrors(invite.error)
  const { errors } = form.formState
  return (
    <Dialog open={open} onOpenChange={close} title={t('invite.dialog.title')} description={t('invite.dialog.body')}>
      <form onSubmit={form.handleSubmit((v) => invite.mutate(v))} noValidate className="grid gap-4">
        {invite.isError && !Object.keys(server).length && <FormMessage tone="error">{errorMessage(invite.error)}</FormMessage>}
        <Field label={t('invite.dialog.email')} error={errors.email?.message ?? server.email}>
          {(p) => <Input {...p} type="email" autoComplete="off" {...form.register('email')} />}
        </Field>
        <Field label={t('invite.dialog.name')} error={errors.fullName?.message ?? server.fullName}>
          {(p) => <Input {...p} autoComplete="off" {...form.register('fullName')} />}
        </Field>
        <Controller control={form.control} name="roles" render={({ field, fieldState }) => (
          <RolePicker value={field.value} onChange={field.onChange} error={fieldState.error?.message} />
        )} />
        <DialogButtons pending={invite.isPending} submit={t('invite.dialog.submit')} onCancel={() => close(false)} />
      </form>
    </Dialog>
  )
}

/** Shown once after an invite: the only time the link is visible. */
export function InviteLinkDialog({ invite, open, onOpenChange }: { invite: Invite; open: boolean; onOpenChange: (o: boolean) => void }) {
  const [copied, setCopied] = useState(false)
  const date = new Date(invite.expiresAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(invite.inviteUrl)
      setCopied(true)
    } catch {
      document.getElementById('invite-link')?.focus() // clipboard blocked: the field is selected for manual copy
    }
  }
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t('invite.link.title', { name: invite.user.fullName })}
      description={t('invite.link.body', { date })}>
      <div className="flex gap-2">
        <Input id="invite-link" readOnly value={invite.inviteUrl} onFocus={(e) => e.currentTarget.select()} className="font-mono text-xs" />
        <Button type="button" variant="outline" onClick={copy} aria-live="polite">
          {copied ? <Check /> : <Copy />}
          {copied ? t('invite.link.copied') : t('invite.link.copy')}
        </Button>
      </div>
      <Button type="button" className="justify-self-end" onClick={() => onOpenChange(false)}>{t('common.close')}</Button>
    </Dialog>
  )
}

// ---------------------------------------------------------------- rename

const renameSchema = z.object({ fullName: z.string().trim().min(1, t('common.required')).max(120, t('common.tooLong')) })

export function RenameDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const rename = useRename()
  const form = useForm({ resolver: zodResolver(renameSchema), defaultValues: { fullName: user.fullName } })
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('rename.title')}>
      <form onSubmit={form.handleSubmit((v) => rename.mutate({ id: user.id, ...v }, { onSuccess: onClose }))} noValidate className="grid gap-4">
        {rename.isError && <FormMessage tone="error">{errorMessage(rename.error)}</FormMessage>}
        <Field label={t('rename.name')} error={form.formState.errors.fullName?.message}>
          {(p) => <Input {...p} autoFocus {...form.register('fullName')} />}
        </Field>
        <DialogButtons pending={rename.isPending} submit={t('common.save')} onCancel={onClose} />
      </form>
    </Dialog>
  )
}

// ---------------------------------------------------------------- roles

export function RolesDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const setRoles = useSetRoles()
  const form = useForm({ resolver: zodResolver(z.object({ roles: rolesField })), defaultValues: { roles: user.roles } })
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('roles.dialog.title', { name: user.fullName })}
      description={t('roles.dialog.hint')}>
      <form onSubmit={form.handleSubmit((v) => setRoles.mutate({ id: user.id, roles: v.roles }, { onSuccess: onClose }))} noValidate className="grid gap-4">
        {setRoles.isError && <FormMessage tone="error">{errorMessage(setRoles.error)}</FormMessage>}
        <Controller control={form.control} name="roles" render={({ field, fieldState }) => (
          <RolePicker value={field.value} onChange={field.onChange} error={fieldState.error?.message} />
        )} />
        <DialogButtons pending={setRoles.isPending} submit={t('common.save')} onCancel={onClose} />
      </form>
    </Dialog>
  )
}

// ---------------------------------------------------------------- disable

export function DisableDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const setEnabled = useSetEnabled()
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()} title={t('users.disable')} description={t('users.disableConfirm', { name: user.fullName })}>
      {setEnabled.isError && <FormMessage tone="error">{errorMessage(setEnabled.error)}</FormMessage>}
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
        <Button variant="destructive" disabled={setEnabled.isPending}
          onClick={() => setEnabled.mutate({ id: user.id, enabled: false }, { onSuccess: onClose })}>
          {t('users.disable')}
        </Button>
      </div>
    </Dialog>
  )
}

// ---------------------------------------------------------------- shared

function RolePicker({ value, onChange, error }: { value: string[]; onChange: (v: string[]) => void; error?: string }) {
  const roles = useRoles()
  return (
    <fieldset className="grid gap-2" aria-invalid={error ? true : undefined}>
      <legend className="mb-1 text-sm font-medium">{t('invite.dialog.roles')}</legend>
      <div className="grid gap-1.5 sm:grid-cols-2">
        {(roles.data ?? []).map((r) => (
          <label key={r.code} className="flex items-center gap-2 rounded-md border px-3 py-2 text-sm has-checked:border-primary has-checked:bg-primary/5">
            <input type="checkbox" className="size-4 accent-[var(--primary)]" checked={value.includes(r.code)}
              onChange={(e) => onChange(e.target.checked ? [...value, r.code] : value.filter((c) => c !== r.code))} />
            {r.name}
          </label>
        ))}
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
    </fieldset>
  )
}

function DialogButtons({ pending, submit, onCancel }: { pending: boolean; submit: string; onCancel: () => void }) {
  return (
    <div className="flex justify-end gap-2">
      <Button type="button" variant="outline" onClick={onCancel}>{t('common.cancel')}</Button>
      <Button type="submit" disabled={pending}>{pending ? t('common.saving') : submit}</Button>
    </div>
  )
}
