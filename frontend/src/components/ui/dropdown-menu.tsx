import * as Menu from '@radix-ui/react-dropdown-menu'
import type { ComponentProps, ReactNode } from 'react'
import { cn } from '@/lib/utils'

export const DropdownMenu = Menu.Root
export const DropdownMenuTrigger = Menu.Trigger

export function DropdownMenuContent({ children, align = 'end' }: { children: ReactNode; align?: 'start' | 'end' }) {
  return (
    <Menu.Portal>
      <Menu.Content
        align={align}
        sideOffset={6}
        className="z-50 min-w-48 rounded-md border bg-card p-1 text-card-foreground shadow-md"
      >
        {children}
      </Menu.Content>
    </Menu.Portal>
  )
}

export function DropdownMenuItem({ className, ...props }: ComponentProps<typeof Menu.Item>) {
  return (
    <Menu.Item
      className={cn(
        'flex cursor-default items-center gap-2 rounded-sm px-2 py-1.5 text-sm outline-none select-none',
        'data-[highlighted]:bg-secondary data-[disabled]:opacity-50 [&_svg]:size-4',
        className,
      )}
      {...props}
    />
  )
}

export function DropdownMenuSeparator() {
  return <Menu.Separator className="my-1 h-px bg-border" />
}

export function DropdownMenuLabel({ children }: { children: ReactNode }) {
  return <Menu.Label className="px-2 py-1.5 text-xs text-muted-foreground">{children}</Menu.Label>
}
