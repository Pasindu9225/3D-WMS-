import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { useLogout } from '@/features/auth/session'
import { api } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'

const schema = z
  .object({
    current: z.string().min(1, t('common.required')).max(128, t('common.tooLong')),
    password: z.string().min(10, t('validation.passwordLength')).max(128, t('common.tooLong')),
    confirm: z.string(),
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: t('validation.passwordMatch') })
type Values = z.infer<typeof schema>

export function AccountPage() {
  const navigate = useNavigate()
  const signOutAll = useLogout(true)
  const change = useMutation({
    mutationFn: (v: Values) =>
      api<void>('/auth/password/change', { method: 'POST', body: { currentPassword: v.current, newPassword: v.password } }),
  })
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { current: '', password: '', confirm: '' } })
  const { errors } = form.formState

  return (
    <div className="grid max-w-xl gap-8">
      <h1 className="text-2xl font-semibold">{t('account.title')}</h1>

      <form
        onSubmit={form.handleSubmit((v) => change.mutate(v, { onSuccess: () => form.reset() }))}
        noValidate
        className="grid gap-4 rounded-lg border bg-card p-5"
      >
        <h2 className="font-medium">{t('account.changePassword')}</h2>
        {change.isError && <FormMessage tone="error">{errorMessage(change.error)}</FormMessage>}
        {change.isSuccess && <FormMessage tone="success">{t('account.changed')}</FormMessage>}
        <Field label={t('account.current')} error={errors.current?.message}>
          {(p) => <Input {...p} type="password" autoComplete="current-password" {...form.register('current')} />}
        </Field>
        <Field label={t('account.new')} error={errors.password?.message}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" {...form.register('password')} />}
        </Field>
        <Field label={t('account.confirm')} error={errors.confirm?.message}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" {...form.register('confirm')} />}
        </Field>
        <Button type="submit" className="justify-self-start" disabled={change.isPending}>
          {change.isPending ? t('account.saving') : t('account.save')}
        </Button>
      </form>

      <section className="grid gap-3 rounded-lg border bg-card p-5">
        <h2 className="font-medium">{t('account.sessions')}</h2>
        <p className="text-sm text-muted-foreground">{t('account.sessionsBody')}</p>
        <Button
          variant="outline"
          className="justify-self-start"
          disabled={signOutAll.isPending}
          onClick={() => signOutAll.mutate(undefined, { onSettled: () => navigate('/login', { replace: true }) })}
        >
          {t('menu.signOutAll')}
        </Button>
      </section>
    </div>
  )
}
