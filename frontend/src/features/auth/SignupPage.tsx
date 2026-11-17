import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { MailCheck } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { PageShell } from '@/app/PageShell'
import { Button } from '@/components/ui/button'
import { Field, FormMessage, Input } from '@/components/ui/input'
import { api } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { t } from '@/lib/i18n'

const schema = z
  .object({
    companyName: z.string().trim().min(1, t('common.required')).max(150, t('common.tooLong')),
    fullName: z.string().trim().min(1, t('common.required')).max(120, t('common.tooLong')),
    email: z.string().trim().email(t('signup.emailRule')).max(190, t('common.tooLong')),
    password: z.string().min(10, t('validation.passwordLength')).max(128, t('common.tooLong')),
    confirm: z.string(),
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: t('validation.passwordMatch') })
type Values = z.infer<typeof schema>

/** /signup: a new customer creates their company. Nothing is made until they confirm their email. */
export function SignupPage() {
  const signup = useMutation({
    mutationFn: (v: Values) => api<void>('/signup', { method: 'POST', body: { companyName: v.companyName.trim(), fullName: v.fullName.trim(), email: v.email.trim(), password: v.password } }),
  })
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { companyName: '', fullName: '', email: '', password: '', confirm: '' } })
  const { errors } = form.formState

  if (signup.isSuccess) {
    return (
      <PageShell>
        <div className="grid w-full max-w-sm gap-4 rounded-lg border bg-card p-6 text-center shadow-sm">
          <MailCheck className="mx-auto size-10 text-primary" />
          <h1 className="text-2xl font-semibold">{t('signup.checkTitle')}</h1>
          <p className="text-sm text-muted-foreground">{t('signup.checkBody', { email: form.getValues('email').trim() })}</p>
          <p className="text-xs text-muted-foreground">{t('signup.checkHint')}</p>
        </div>
      </PageShell>
    )
  }
  return (
    <PageShell>
      <form onSubmit={form.handleSubmit((v) => signup.mutate(v))} noValidate className="grid w-full max-w-sm gap-4 rounded-lg border bg-card p-6 shadow-sm">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold">{t('signup.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('signup.subtitle')}</p>
        </div>
        {signup.isError && <FormMessage tone="error">{errorMessage(signup.error)}</FormMessage>}
        <Field label={t('signup.company')} error={errors.companyName?.message}>
          {(p) => <Input {...p} autoComplete="organization" autoFocus {...form.register('companyName')} />}
        </Field>
        <Field label={t('signup.name')} error={errors.fullName?.message}>
          {(p) => <Input {...p} autoComplete="name" {...form.register('fullName')} />}
        </Field>
        <Field label={t('login.email')} error={errors.email?.message}>
          {(p) => <Input {...p} type="email" autoComplete="email" {...form.register('email')} />}
        </Field>
        <Field label={t('invite.password')} error={errors.password?.message}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" {...form.register('password')} />}
        </Field>
        <Field label={t('invite.confirm')} error={errors.confirm?.message}>
          {(p) => <Input {...p} type="password" autoComplete="new-password" {...form.register('confirm')} />}
        </Field>
        <Button type="submit" size="lg" disabled={signup.isPending}>{signup.isPending ? t('signup.submitting') : t('signup.submit')}</Button>
        <p className="text-center text-sm text-muted-foreground">{t('signup.haveAccount')} <Link className="font-medium text-foreground underline" to="/login">{t('login.submit')}</Link></p>
      </form>
    </PageShell>
  )
}
