import { Box, Button, Typography } from '@mui/material'
import { useEffect, useRef, useState } from 'react'

import versionClient from './api/versionClient'

const RefreshButton = ({ onRefreshed }) => {
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
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
    setMessage('')
    try {
      const status = await versionClient.triggerScrape()
      setMessage(
        `Scraped ${status.appsSucceeded}/${status.appsAttempted} (${status.triggersRemaining} left)`
      )
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
  const label = cooldown > 0 ? `Retry in ${cooldown}s` : 'Refresh now'

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '0.4rem' }}>
      <Button variant="contained" disabled={disabled} onClick={handleClick}>
        {label}
      </Button>
      {message && <Typography variant="body2">{message}</Typography>}
    </Box>
  )
}

export default RefreshButton
