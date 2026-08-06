import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Server, WebSocket as MockWebSocket } from 'mock-socket'
import PriceGrid from './PriceGrid'

const WS_URL = 'ws://localhost:8080/ws'

describe('PriceGrid', () => {
  let mockServer: Server

  beforeEach(() => {
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockServer = new Server(WS_URL)
  })

  afterEach(() => {
    mockServer.stop()
    vi.unstubAllGlobals()
  })

  it('displays a price tick received over the WebSocket', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'EUR/USD', bid: 1.085, ask: 1.0852, timestamp: '2026-01-01T00:00:00Z' },
        }),
      )
    })

    render(<PriceGrid />)

    expect(await screen.findByText('EUR/USD')).toBeInTheDocument()
    expect(await screen.findByText('1.085')).toBeInTheDocument()
    expect(await screen.findByText('1.0852')).toBeInTheDocument()
  })

  it('updates the row in place when a new tick for the same symbol arrives', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'GBP/USD', bid: 1.265, ask: 1.2652, timestamp: '2026-01-01T00:00:00Z' },
        }),
      )
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'GBP/USD', bid: 1.266, ask: 1.2662, timestamp: '2026-01-01T00:00:01Z' },
        }),
      )
    })

    render(<PriceGrid />)

    expect(await screen.findByText('1.266')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('1.265')).not.toBeInTheDocument())
  })

  it('ignores non-PRICE_TICK envelopes', async () => {
    mockServer.on('connection', (socket) => {
      socket.send(JSON.stringify({ type: 'HELLO', payload: 'hi' }))
    })

    render(<PriceGrid />)

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByRole('row', { name: /hi/i })).not.toBeInTheDocument()
  })

  it('sends a SELL CREATE_TRADE at the bid price when the Sell button is clicked', async () => {
    let received: string | undefined
    mockServer.on('connection', (socket) => {
      socket.on('message', (message) => {
        received = message as string
      })
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'USD/CHF', bid: 0.9123, ask: 0.9125, timestamp: '2026-01-01T00:00:00Z' },
        }),
      )
    })

    render(<PriceGrid />)
    await screen.findByText('USD/CHF')
    await userEvent.click(screen.getByRole('button', { name: 'Sell' }))

    await waitFor(() => expect(received).toBeDefined())
    expect(JSON.parse(received!)).toEqual({
      type: 'CREATE_TRADE',
      payload: { symbol: 'USD/CHF', side: 'SELL', price: 0.9123, quantity: 1_000_000 },
    })
  })

  it('sends a BUY CREATE_TRADE at the ask price when the Buy button is clicked', async () => {
    let received: string | undefined
    mockServer.on('connection', (socket) => {
      socket.on('message', (message) => {
        received = message as string
      })
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'AUD/USD', bid: 0.6601, ask: 0.6603, timestamp: '2026-01-01T00:00:00Z' },
        }),
      )
    })

    render(<PriceGrid />)
    await screen.findByText('AUD/USD')
    await userEvent.click(screen.getByRole('button', { name: 'Buy' }))

    await waitFor(() => expect(received).toBeDefined())
    expect(JSON.parse(received!)).toEqual({
      type: 'CREATE_TRADE',
      payload: { symbol: 'AUD/USD', side: 'BUY', price: 0.6603, quantity: 1_000_000 },
    })
  })

  it('sends a CREATE_TRADE with the entered quantity when the quantity input has been changed', async () => {
    let received: string | undefined
    mockServer.on('connection', (socket) => {
      socket.on('message', (message) => {
        received = message as string
      })
      socket.send(
        JSON.stringify({
          type: 'PRICE_TICK',
          payload: { symbol: 'EUR/GBP', bid: 0.855, ask: 0.8552, timestamp: '2026-01-01T00:00:00Z' },
        }),
      )
    })

    render(<PriceGrid />)
    const quantityInput = screen.getByLabelText('Quantity')
    fireEvent.change(quantityInput, { target: { value: '250000' } })

    await screen.findByText('EUR/GBP')
    await userEvent.click(screen.getByRole('button', { name: 'Buy' }))

    await waitFor(() => expect(received).toBeDefined())
    expect(JSON.parse(received!)).toEqual({
      type: 'CREATE_TRADE',
      payload: { symbol: 'EUR/GBP', side: 'BUY', price: 0.8552, quantity: 250_000 },
    })
  })
})
