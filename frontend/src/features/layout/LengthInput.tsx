import { useState } from 'react'
import { cn } from '@/lib/utils'
import { useEditor } from './editorStore'
import { format, parse } from './units'

/**
 * Length field in the editor's unit. Accepts any unit typed with it ("400ft", "12.5 m") and commits on
 * Enter or blur, so half-typed values ("12.") never reach the drawing. Shows red until the text is a length.
 */
export function LengthInput({ mm, onCommit, min = 0, label, className, autoFocus, clearOnCommit, live }: {
  mm: number | null
  onCommit: (mm: number) => void
  min?: number
  label: string
  className?: string
  autoFocus?: boolean
  /** For "next wall" style inputs: empty again after each value. */
  clearOnCommit?: boolean
  /** Commit every valid value while typing (forms with their own submit button). */
  live?: boolean
}) {
  const unit = useEditor((s) => s.unit)
  const shown = mm === null ? '' : format(mm, unit)
  const [text, setText] = useState<string | null>(null) // null = not editing, show the stored value
  const value = text ?? shown
  const parsed = parse(value, unit)
  const bad = text !== null && text.trim() !== '' && (parsed === null || parsed < min)
  const commit = () => {
    if (text !== null && parsed !== null && parsed >= min) onCommit(parsed)
    setText(clearOnCommit ? '' : null)
  }
  return (
    <input aria-label={label} inputMode="decimal" value={value} autoFocus={autoFocus} aria-invalid={bad || undefined}
      onChange={(e) => {
        setText(e.target.value)
        const mmTyped = parse(e.target.value, unit)
        if (live && mmTyped !== null && mmTyped >= min) onCommit(mmTyped)
      }}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          e.stopPropagation()
          commit()
        }
        if (e.key === 'Escape') setText(null)
      }}
      className={cn('h-8 w-20 rounded-md border border-input bg-background px-2 text-sm tabular-nums',
        bad && 'border-destructive', className)} />
  )
}
