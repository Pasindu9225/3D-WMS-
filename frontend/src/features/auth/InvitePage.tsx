import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { PageShell } from '@/app/PageShell'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { api } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'
import { newPasswordSchema, type NewPassword } from './passwordSchema'

/** Opened from the invite link: /invite#<token>. The token is in the #fragment so it never reaches a server log. */
export function InvitePage() {
  // Read once, then remove it from the address bar so it is not left in history or shared by mistake.
  const [token] = useState(() => {
    const value = window.location.hash.slice(1)
    if (value) window.history.replaceState(null, '', window.location.pathname)
    return value
  })
  const navigate = useNavigate()
  const accept = useMutation({
    mutationFn: (password: string) => api<void>('/auth/invite/accept', { method: 'POST', body: { token, password } }),
    onSuccess: () => navigate('/login?welcome=1', { replace: true }),
  })
  const form = useForm<NewPassword>({ resolver: zodResolver(newPasswordSchema), defaultValues: { password: '', confirm: '' } })
  const { errors } = form.formState

  return (
    <PageShell>
      <form
        onSubmit={form.handleSubmit((v) => accept.mutate(v.password))}
        noValidate
        className="grid w-full max-w-sm gap-5 rounded-lg border bg-card p-6 shadow-sm"
      >
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold">{t('invite.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('invite.subtitle')}</p>
        </div>
        {!token && <FormMessage tone="error">{t('invite.noToken')}</FormMessage>}
        {accept.isError && <FormMessage tone="error">{errorMessage(accept.error)}</FormMessage>}
        <Field label={t('invite.password')} error={errors.password?.message}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" autoFocus {...form.register('password')} />}
        </Field>
        <Field label={t('invite.confirm')} error={errors.confirm?.message}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" {...form.register('confirm')} />}
        </Field>
        <Button type="submit" size="lg" disabled={!token || accept.isPending}>
          {accept.isPending ? t('invite.submitting') : t('invite.submit')}
        </Button>
      </form>
    </PageShell>
  )
}
