import { Button } from '@mui/material'
import { useEffect, useRef, useState } from 'react'

const UpdateButton = ({ label, onScrape, onRefreshed }) => {
  const [busy, setBusy] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const intervalRef = useRef(null)

  useEffect(() => {
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current)
    }
  }, [])

  const startCooldown = (seconds) => {
    setCooldown(seconds)
    intervalRef.current = setInterval(() => {
      setCooldown((remaining) => {
        if (remaining <= 1) {
          clearInterval(intervalRef.current)
          intervalRef.current = null
          return 0
        }
        return remaining - 1
      })
    }, 1000)
  }

  const handleClick = async () => {
    setBusy(true)
    try {
      await onScrape()
      await onRefreshed()
      setBusy(false)
    } catch (err) {
      setBusy(false)
      if (err?.status === 429) {
        startCooldown(err.data.retryAfterSeconds)
      }
    }
  }

  const disabled = busy || cooldown > 0
  const text = cooldown > 0 ? `Retry in ${cooldown}s` : label

  return (
    <Button variant='outlined' disabled={disabled} onClick={handleClick}>
      {text}
    </Button>
  )
}

export default UpdateButton
