import { useEffect, useState } from 'react'
import { connect } from '../services/socket'
import type { Trade } from '../types/trade'

const VISIBLE_DURATION_MS = 3000

function ExecutionConfirmation() {
  const [confirmation, setConfirmation] = useState<Trade | null>(null)

  useEffect(() => {
    const socket = connect((envelope) => {
      if (envelope.type === 'TRADE_CREATED') {
        setConfirmation(envelope.payload as Trade)
      }
    })

    return () => socket.close()
  }, [])

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

  return (
    <p role="status" className={`execution-confirmation execution-confirmation--${confirmation.side.toLowerCase()}`}>
      Executed: {confirmation.side} {confirmation.quantity} {confirmation.symbol} @ {confirmation.price}
    </p>
  )
}

export default ExecutionConfirmation
