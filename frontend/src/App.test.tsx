import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import App from './App'

const WS_URL = 'ws://localhost:8080/ws'

describe('App', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('renders the app shell directly, with no login gate', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: /single dealer platform/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /rates/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /blotter/i })).toBeInTheDocument()
  })
})
