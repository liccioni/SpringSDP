import { useEffect, useState } from 'react'
import { subscribe } from '../services/socket'

function Greeting() {
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    return subscribe('HELLO', (envelope) => setMessage(String(envelope.payload)))
  }, [])

  return (
    <div className="status-pill" data-live={message !== null}>
      <span className="status-dot" aria-hidden="true" />
      <span>{message ?? 'Waiting for backend…'}</span>
    </div>
  )
}

export default Greeting
