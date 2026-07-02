import { useState } from 'react'
import { Avatar, Box, IconButton, Stack, TableCell, TableRow, Tooltip, Typography } from '@mui/material'
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'

import RescrapeButton from './RescrapeButton'
import versionClient from './api/versionClient'
import { driftStatusLabel, severityColor } from './drift'
import { formatFreshnessLine } from './freshness'

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
 * Renders the version string as a hover target. When readAt is present, hovering
 * reveals a tooltip with "read <relative> ago — <absolute local time>", using
 * formatFreshnessLine from freshness.js. A side without readAt shows "—" (when
 * version is null) and exposes no hover interaction.
 *
 * The tooltip uses controlled React state (not MUI Tooltip's internal timer) so it
 * appears synchronously on hover in both production and test environments.
 */
const VersionString = ({ version, readAt }) => {
  const [tooltipVisible, setTooltipVisible] = useState(false)

  if (!readAt) return <>{version ?? '—'}</>

  return (
    <Box component="span" sx={{ position: 'relative', display: 'inline-block' }}>
      <Typography
        component="span"
        onMouseEnter={() => setTooltipVisible(true)}
        onMouseLeave={() => setTooltipVisible(false)}
        sx={{ cursor: 'default' }}
      >
        {version ?? '—'}
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
          {formatFreshnessLine('read', readAt)}
        </Box>
      )}
    </Box>
  )
}

/**
 * Renders a compact amber warning icon after the version string when a side's most recent
 * refresh attempt failed (failedAt present). Hovering the icon reveals a two-line tooltip:
 *   line 1: "read <relative> ago — <absolute>" or "never read successfully" when readAt is null
 *   line 2: "refresh failed <relative> ago — <absolute>"
 *
 * Uses the same controlled-visibility pattern as VersionString. Returns null when failedAt
 * is absent (healthy side) or when the side is merely pending (no readAt, no failedAt).
 */
const FailedRefreshIcon = ({ readAt, failedAt }) => {
  const [tooltipVisible, setTooltipVisible] = useState(false)

  if (!failedAt) return null

  const line1 = readAt ? formatFreshnessLine('read', readAt) : 'never read successfully'
  const line2 = formatFreshnessLine('refresh failed', failedAt)

  return (
    <Box
      component="span"
      sx={{ position: 'relative', display: 'inline-flex', verticalAlign: 'middle', ml: 0.5 }}
    >
      <WarningAmberIcon
        aria-label="refresh failed"
        sx={{ fontSize: '1rem', color: 'warning.main' }}
        onMouseEnter={() => setTooltipVisible(true)}
        onMouseLeave={() => setTooltipVisible(false)}
      />
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
          <Box component="div">{line1}</Box>
          <Box component="div">{line2}</Box>
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
        <VersionString version={currentVersion} readAt={currentReadAt} />
        <FailedRefreshIcon readAt={currentReadAt} failedAt={currentFailedAt} />
      </TableCell>
      <TableCell sx={outdated ? { color: 'primary.main', fontWeight: 600 } : { color: 'text.secondary' }}>
        <VersionString version={latestVersion} readAt={latestReadAt} />
        <FailedRefreshIcon readAt={latestReadAt} failedAt={latestFailedAt} />
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
