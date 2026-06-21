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
  const color = severityColor[drift]
  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, color, fontSize: '12px', fontWeight: 600 }}>
      <Box component="span" sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: color }} />
      {driftStatusLabel[drift]}
    </Box>
  )
}

const ApplicationRow = ({ name, ver, onRefreshed }) => {
  const { current, latest, outdated, drift = 'NONE' } = ver
  const rowTint = hexToRgba(severityColor[drift], 0.1)

  const scrape = (side) => () => versionClient.scrapeApplication(name, side)

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
      <TableCell sx={{ color: 'text.secondary' }}>{current}</TableCell>
      <TableCell sx={outdated ? { color: 'primary.main', fontWeight: 600 } : { color: 'text.secondary' }}>
        {latest}
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
