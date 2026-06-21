import { AppBar, Box, Button, Snackbar, Alert, Toolbar, Typography } from '@mui/material'
import RefreshIcon from '@mui/icons-material/Refresh'
import { useEffect, useRef, useState } from 'react'

import versionClient from './api/versionClient'

const TopBar = ({ onRefreshed }) => {
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [snackbarOpen, setSnackbarOpen] = useState(false)
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
      const status = await versionClient.triggerScrape()
      setMessage(
        `Scraped ${status.appsSucceeded}/${status.appsAttempted} (${status.triggersRemaining} left)`
      )
      setSnackbarOpen(true)
      await onRefreshed()
      setBusy(false)
    } catch (err) {
      setBusy(false)
      if (err?.status === 429) {
        startCooldown(err.data.retryAfterSeconds)
      }
    }
  }

  const handleSnackbarClose = () => {
    setSnackbarOpen(false)
  }

  const disabled = busy || cooldown > 0
  const label = cooldown > 0 ? `Retry in ${cooldown}s` : 'Refresh All'

  return (
    <AppBar
      position="static"
      elevation={0}
      sx={{
        // Header surface + border from the rendered reference (code.html):
        // surface #181b1f with a surface-container-high #282c31 bottom border.
        bgcolor: '#181b1f',
        borderBottom: '1px solid',
        borderColor: '#282c31',
      }}
    >
      <Toolbar sx={{ justifyContent: 'space-between' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
          <img src="/logo.png" alt="PlatformUp2Date logo" height={32} />
          <Typography variant="h3" color="primary" component="span">
            PlatformUp2Date
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<RefreshIcon />}
          disabled={disabled}
          onClick={handleClick}
        >
          {label}
        </Button>
      </Toolbar>
      <Snackbar
        open={snackbarOpen}
        autoHideDuration={4000}
        onClose={handleSnackbarClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert onClose={handleSnackbarClose} severity="success" variant="filled">
          {message}
        </Alert>
      </Snackbar>
    </AppBar>
  )
}

export default TopBar
