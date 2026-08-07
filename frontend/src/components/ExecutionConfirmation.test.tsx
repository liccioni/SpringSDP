import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import ExecutionConfirmation from './ExecutionConfirmation'

const WS_URL = 'ws://localhost:8080/ws'

describe('ExecutionConfirmation', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('shows a confirmation for a BUY trade received over the WebSocket', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'TRADE_CREATED',
          payload: {
            id: 'trade-10',
            symbol: 'USD/JPY',
            side: 'BUY',
            price: 149.51,
            quantity: 300000,
            timestamp: '2026-01-01T00:00:00Z',
          },
        }),
      )
    })

    render(<ExecutionConfirmation token="test-token" />)

    const confirmation = await screen.findByRole('status')
    expect(confirmation).toHaveTextContent('Executed: BUY 300000 USD/JPY @ 149.51')
    expect(confirmation).toHaveClass('execution-confirmation--buy')
  })

  it('shows a confirmation for a SELL trade received over the WebSocket', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'TRADE_CREATED',
          payload: {
            id: 'trade-11',
            symbol: 'EUR/GBP',
            side: 'SELL',
            price: 0.855,
            quantity: 400000,
            timestamp: '2026-01-01T00:00:00Z',
          },
        }),
      )
    })

    render(<ExecutionConfirmation token="test-token" />)

    const confirmation = await screen.findByRole('status')
    expect(confirmation).toHaveTextContent('Executed: SELL 400000 EUR/GBP @ 0.855')
    expect(confirmation).toHaveClass('execution-confirmation--sell')
  })

  it('shows a rejection with the reason when a trade is rejected', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'TRADE_REJECTED',
          payload: {
            symbol: 'XAU/USD',
            side: 'BUY',
            price: 2000,
            quantity: 100,
            reason: 'unknown symbol: XAU/USD',
          },
        }),
      )
    })

    render(<ExecutionConfirmation token="test-token" />)

    const rejection = await screen.findByRole('alert')
    expect(rejection).toHaveTextContent('Rejected: BUY 100 XAU/USD @ 2000 — unknown symbol: XAU/USD')
    expect(rejection).toHaveClass('execution-confirmation--rejected')
  })

  it('renders nothing until a trade has been confirmed', () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'PRICE_TICK', payload: { symbol: 'NZD/USD', bid: 0.61, ask: 0.6102 } }))
    })

    render(<ExecutionConfirmation token="test-token" />)

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
