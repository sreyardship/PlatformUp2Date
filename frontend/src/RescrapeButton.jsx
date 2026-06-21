import { Button } from '@mui/material'
import { useEffect, useRef, useState } from 'react'

// Ported verbatim from UpdateButton.jsx so ApplicationRow can reuse the
// busy/cooldown contract exactly (same props, same 429 retry-countdown UX).
const RescrapeButton = ({ label, onScrape, onRefreshed }) => {
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
    <Button
      variant="contained"
      size="small"
      disableElevation
      disableRipple
      disableFocusRipple
      disabled={disabled}
      onClick={handleClick}
      // "Ghost" style from the reference: dark fill matching the avatar squares,
      // 1px #282c31 border, sharp 2px corners, compact. Focus/active are pinned
      // to the hover look so the colour doesn't shift when pressed or selected.
      sx={{
        bgcolor: 'background.paper',
        color: 'text.primary',
        border: '1px solid #282c31',
        borderRadius: '2px',
        fontSize: '10px',
        fontWeight: 600,
        px: 1.25,
        py: 0.5,
        whiteSpace: 'nowrap',
        boxShadow: 'none',
        '&:hover, &:active, &.Mui-focusVisible': {
          bgcolor: 'rgba(145, 204, 255, 0.16)',
          borderColor: 'primary.main',
          color: 'primary.main',
          boxShadow: 'none',
        },
      }}
    >
      {text}
    </Button>
  )
}

export default RescrapeButton
