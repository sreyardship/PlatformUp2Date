import { useMemo, useState } from 'react'
import {
  Box,
  InputAdornment,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TableSortLabel,
  TextField,
  Typography,
} from '@mui/material'
import SearchIcon from '@mui/icons-material/Search'

import ApplicationRow from './ApplicationRow'
import { DRIFT_LEVELS, compareVersions } from './drift'

const headerCellSx = {
  color: 'text.secondary',
  fontSize: '12px',
  fontWeight: 600,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  borderColor: '#282c31',
}

// Each sortable column knows how to compare two [name, ver] entries and which
// direction it activates in on first click (all ascending). The initial table
// order (most-outdated-first) is set separately by `defaultSort` below.
const columns = {
  name: {
    defaultDirection: 'asc',
    compare: ([nameA], [nameB]) => nameA.localeCompare(nameB),
  },
  status: {
    defaultDirection: 'asc',
    compare: ([, a], [, b]) => DRIFT_LEVELS.indexOf(a.drift) - DRIFT_LEVELS.indexOf(b.drift),
  },
  current: {
    defaultDirection: 'asc',
    compare: ([, a], [, b]) => compareVersions(a.current, b.current),
  },
  latest: {
    defaultDirection: 'asc',
    compare: ([, a], [, b]) => compareVersions(a.latest, b.latest),
  },
}

const defaultSort = { column: 'status', direction: 'desc' }

const applyDirection = (compare, direction) => (direction === 'desc' ? (a, b) => -compare(a, b) : compare)

const ApplicationTable = ({ versions, onRefreshed }) => {
  const [filter, setFilter] = useState('')
  const [sort, setSort] = useState(defaultSort)

  const entries = useMemo(() => Object.entries(versions || {}), [versions])

  const filteredEntries = useMemo(() => {
    const needle = filter.trim().toLowerCase()
    if (!needle) return entries
    return entries.filter(([name]) => name.toLowerCase().includes(needle))
  }, [entries, filter])

  const sortedEntries = useMemo(() => {
    const { compare } = columns[sort.column]
    return [...filteredEntries].sort(applyDirection(compare, sort.direction))
  }, [filteredEntries, sort])

  const toggleSort = (column) => () => {
    setSort((current) => {
      if (current.column === column) {
        return { column, direction: current.direction === 'asc' ? 'desc' : 'asc' }
      }
      return { column, direction: columns[column].defaultDirection }
    })
  }

  const sortLabelProps = (column) => ({
    active: sort.column === column,
    direction: sort.column === column ? sort.direction : columns[column].defaultDirection,
    onClick: toggleSort(column),
  })

  const count = sortedEntries.length
  const countLabel = `${count} application${count === 1 ? '' : 's'}`

  return (
    <Paper
      data-testid="application-status-card"
      elevation={0}
      sx={{ bgcolor: 'background.paper', border: '1px solid', borderColor: '#282c31', borderRadius: 2, mt: 2 }}
    >
      <Box
        sx={{
          px: 2.5,
          py: 2,
          borderBottom: '1px solid',
          borderColor: '#282c31',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 2,
        }}
      >
        <Typography variant="h3">Application Status</Typography>
        <TextField
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          placeholder="Filter applications…"
          size="small"
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" sx={{ color: 'text.secondary' }} />
                </InputAdornment>
              ),
            },
          }}
          sx={{
            width: 256,
            '& .MuiOutlinedInput-root': {
              bgcolor: '#0b0c0e',
              borderRadius: 2,
              '& fieldset': { borderColor: '#282c31' },
            },
          }}
        />
      </Box>
      <TableContainer>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell sx={headerCellSx}>
                <TableSortLabel {...sortLabelProps('name')}>App Name</TableSortLabel>
              </TableCell>
              <TableCell sx={headerCellSx}>
                <TableSortLabel {...sortLabelProps('status')}>Status</TableSortLabel>
              </TableCell>
              <TableCell sx={headerCellSx}>
                <TableSortLabel {...sortLabelProps('current')}>Current Version</TableSortLabel>
              </TableCell>
              <TableCell sx={headerCellSx}>
                <TableSortLabel {...sortLabelProps('latest')}>Latest Version</TableSortLabel>
              </TableCell>
              <TableCell sx={headerCellSx} align="center">Changelog</TableCell>
              <TableCell sx={{ ...headerCellSx, pr: 2 }} align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sortedEntries.map(([name, ver]) => (
              <ApplicationRow key={name} name={name} ver={ver} onRefreshed={onRefreshed} />
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <Box
        sx={{
          px: 2.5,
          py: 1.5,
          borderTop: '1px solid',
          borderColor: '#282c31',
          color: 'text.secondary',
          fontSize: '13px',
        }}
      >
        <Typography component="span" variant="body2" color="text.secondary">
          {countLabel}
        </Typography>
      </Box>
    </Paper>
  )
}

export default ApplicationTable
