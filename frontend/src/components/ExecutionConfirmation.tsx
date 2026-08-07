import { useEffect, useState } from 'react'
import { connect } from '../services/socket'
import type { Trade } from '../types/trade'
import type { TradeRejected } from '../types/tradeRejected'

const VISIBLE_DURATION_MS = 3000

type Confirmation = { outcome: 'accepted'; trade: Trade } | { outcome: 'rejected'; rejection: TradeRejected }

interface ExecutionConfirmationProps {
  token: string
}

function ExecutionConfirmation({ token }: ExecutionConfirmationProps) {
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)

  useEffect(() => {
    const socket = connect((envelope) => {
      if (envelope.type === 'TRADE_CREATED') {
        setConfirmation({ outcome: 'accepted', trade: envelope.payload as Trade })
      } else if (envelope.type === 'TRADE_REJECTED') {
        setConfirmation({ outcome: 'rejected', rejection: envelope.payload as TradeRejected })
      }
    }, token)

    return () => socket.close()
  }, [token])

  useEffect(() => {
    if (!confirmation) {
      return
    }

    const timer = setTimeout(() => setConfirmation(null), VISIBLE_DURATION_MS)
    return () => clearTimeout(timer)
  }, [confirmation])

  if (!confirmation) {
    return null
  }

  if (confirmation.outcome === 'rejected') {
    const { side, quantity, symbol, price, reason } = confirmation.rejection
    return (
      <p role="alert" className="execution-confirmation execution-confirmation--rejected">
        Rejected: {side} {quantity} {symbol} @ {price} — {reason}
      </p>
    )
  }

  const { side, quantity, symbol, price } = confirmation.trade
  return (
    <p role="status" className={`execution-confirmation execution-confirmation--${side.toLowerCase()}`}>
      Executed: {side} {quantity} {symbol} @ {price}
    </p>
  )
}

export default ExecutionConfirmation
