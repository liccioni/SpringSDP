import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import App from './App'

describe('App', () => {
  it('renders the platform heading', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: /single dealer platform/i })).toBeInTheDocument()
  })
})
