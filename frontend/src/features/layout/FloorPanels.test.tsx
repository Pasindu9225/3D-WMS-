import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { useEditor } from './editorStore'
import { CornerTable } from './FloorPanels'

it('renders the corner table before a layout has loaded (no update loop)', () => {
  useEditor.setState({ doc: null })
  render(<CornerTable />)
  expect(screen.getByRole('heading', { name: 'Floor corners (metres)' })).toBeInTheDocument()
})
