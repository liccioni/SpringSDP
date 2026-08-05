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

  return <p>{message ?? 'Waiting for backend…'}</p>
}

export default Greeting
