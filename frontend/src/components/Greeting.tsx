import { useEffect, useState } from 'react'
import { connect } from '../services/socket'

function Greeting() {
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    const socket = connect((envelope) => {
      if (envelope.type === 'HELLO') {
        setMessage(String(envelope.payload))
      }
    })

    return () => socket.close()
  }, [])

  return (
    <div className="status-pill" data-live={message !== null}>
      <span className="status-dot" aria-hidden="true" />
      <span>{message ?? 'Waiting for backend…'}</span>
    </div>
  )
}

export default Greeting
