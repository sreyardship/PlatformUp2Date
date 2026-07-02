import { useState } from 'react'
import { Avatar, Box, IconButton, Stack, TableCell, TableRow, Tooltip, Typography } from '@mui/material'
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined'

import RescrapeButton from './RescrapeButton'
import versionClient from './api/versionClient'
import { driftStatusLabel, severityColor } from './drift'

const hexToRgba = (hex, alpha) => {
  const value = hex.replace('#', '')
  const r = parseInt(value.substring(0, 2), 16)
  const g = parseInt(value.substring(2, 4), 16)
  const b = parseInt(value.substring(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

const StatusBadge = ({ drift }) => {
  // Map null drift (Unresolved app) to the UNKNOWN sentinel for colour/label lookups.
  const effectiveDrift = drift ?? 'UNKNOWN'
  const color = severityColor[effectiveDrift]
  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, color, fontSize: '12px', fontWeight: 600 }}>
      <Box component="span" sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: color }} />
      {driftStatusLabel[effectiveDrift]}
    </Box>
  )
}

/**
 * Renders a muted relative "read Xm ago" label under the version string, with the
 * absolute local time in a hover tooltip. Only rendered when a readAt instant is present.
 * Minutes are used for durations under one hour (slice 01 contract).
 *
 * The tooltip uses controlled React state (not MUI Tooltip's internal timer) so it
 * appears synchronously on hover in both production and test environments.
 */
const ReadAtLabel = ({ readAt }) => {
  const [tooltipVisible, setTooltipVisible] = useState(false)

  if (!readAt) return null

  const readAtDate = new Date(readAt)
  const diffMs = Date.now() - readAtDate.getTime()
  const diffMinutes = Math.floor(diffMs / 60_000)
  const relativeLabel = `read ${diffMinutes}m ago`
  const absoluteLabel = readAtDate.toLocaleTimeString()

  return (
    <Box
      component="span"
      sx={{ display: 'block', position: 'relative' }}
      onMouseEnter={() => setTooltipVisible(true)}
      onMouseLeave={() => setTooltipVisible(false)}
    >
      <Typography
        component="span"
        display="block"
        variant="caption"
        sx={{ color: 'text.disabled', cursor: 'default', userSelect: 'none' }}
      >
        {relativeLabel}
      </Typography>
      {tooltipVisible && (
        <Box
          role="tooltip"
          sx={{
            position: 'absolute',
            bottom: '100%',
            left: 0,
            bgcolor: 'grey.800',
            color: 'common.white',
            px: 1,
            py: 0.5,
            borderRadius: 1,
            fontSize: '11px',
            whiteSpace: 'nowrap',
            zIndex: 1500,
            pointerEvents: 'none',
          }}
        >
          {absoluteLabel}
        </Box>
      )}
    </Box>
  )
}

/**
 * Renders a muted relative "refresh failed Xm ago" marker under the readAt label when a
 * side's most recent refresh attempt failed. Mirrors ReadAtLabel with the same tooltip pattern.
 * Only rendered when a failedAt instant is present.
 */
const FailedRefreshLabel = ({ failedAt }) => {
  const [tooltipVisible, setTooltipVisible] = useState(false)

  if (!failedAt) return null

  const failedAtDate = new Date(failedAt)
  const diffMs = Date.now() - failedAtDate.getTime()
  const diffMinutes = Math.floor(diffMs / 60_000)
  const relativeLabel = `refresh failed ${diffMinutes}m ago`
  const absoluteLabel = failedAtDate.toLocaleTimeString()

  return (
    <Box
      component="span"
      sx={{ display: 'block', position: 'relative' }}
      onMouseEnter={() => setTooltipVisible(true)}
      onMouseLeave={() => setTooltipVisible(false)}
    >
      <Typography
        component="span"
        display="block"
        variant="caption"
        sx={{ color: 'text.disabled', cursor: 'default', userSelect: 'none' }}
      >
        {relativeLabel}
      </Typography>
      {tooltipVisible && (
        <Box
          role="tooltip"
          sx={{
            position: 'absolute',
            bottom: '100%',
            left: 0,
            bgcolor: 'grey.800',
            color: 'common.white',
            px: 1,
            py: 0.5,
            borderRadius: 1,
            fontSize: '11px',
            whiteSpace: 'nowrap',
            zIndex: 1500,
            pointerEvents: 'none',
          }}
        >
          {absoluteLabel}
        </Box>
      )}
    </Box>
  )
}

const ApplicationRow = ({ name, ver, onRefreshed }) => {
  const { current, latest, outdated, drift } = ver
  // null drift means Unresolved; map to UNKNOWN for colour computation.
  const effectiveDrift = drift ?? 'UNKNOWN'
  const rowTint = hexToRgba(severityColor[effectiveDrift], 0.1)

  const scrape = (side) => () => versionClient.scrapeApplication(name, side)

  // version and readAt may be null for Unresolved (value-less) sides.
  const currentVersion = current.version
  const latestVersion = latest.version
  const currentReadAt = current.readAt
  const latestReadAt = latest.readAt
  const currentFailedAt = current.failedAt ?? null
  const latestFailedAt = latest.failedAt ?? null

  return (
    <TableRow sx={{ bgcolor: rowTint }}>
      <TableCell>
        <Stack direction="row" alignItems="center" spacing={1.5}>
          <Avatar
            variant="rounded"
            sx={{
              width: 32,
              height: 32,
              // Reference avatar (code.html): surface fill + 1px #282c31 border,
              // so it reads as an outlined square that stays neutral over the
              // tinted row.
              bgcolor: 'background.paper',
              border: '1px solid #282c31',
              borderRadius: 1,
              color: 'text.primary',
              fontSize: '14px',
              fontWeight: 700,
            }}
          >
            {name.charAt(0).toUpperCase()}
          </Avatar>
          <Typography component="span" sx={{ fontWeight: 500 }}>
            {name}
          </Typography>
        </Stack>
      </TableCell>
      <TableCell>
        <StatusBadge drift={drift} />
      </TableCell>
      <TableCell sx={{ color: 'text.secondary' }}>
        {currentVersion ?? '—'}
        <ReadAtLabel readAt={currentReadAt} />
        <FailedRefreshLabel failedAt={currentFailedAt} />
      </TableCell>
      <TableCell sx={outdated ? { color: 'primary.main', fontWeight: 600 } : { color: 'text.secondary' }}>
        {latestVersion ?? '—'}
        <ReadAtLabel readAt={latestReadAt} />
        <FailedRefreshLabel failedAt={latestFailedAt} />
      </TableCell>
      <TableCell align="center">
        <Tooltip title="Changelog — coming soon">
          <IconButton aria-label="Changelog" size="small" sx={{ color: 'text.secondary' }}>
            <DescriptionOutlinedIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </TableCell>
      <TableCell align="right" sx={{ pr: 2 }}>
        <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ width: '100%' }}>
          <RescrapeButton label="Rescrape current" onScrape={scrape('current')} onRefreshed={onRefreshed} />
          <RescrapeButton label="Rescrape latest" onScrape={scrape('latest')} onRefreshed={onRefreshed} />
        </Stack>
      </TableCell>
    </TableRow>
  )
}

export default ApplicationRow
