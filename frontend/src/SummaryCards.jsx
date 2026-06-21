import { Box, Paper, Typography } from '@mui/material'
import AppsIcon from '@mui/icons-material/Apps'
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlined'
import SystemUpdateAltIcon from '@mui/icons-material/SystemUpdateAlt'

import { driftCounts, severityColor } from './drift'

const cardSx = {
  bgcolor: 'background.paper',
  border: '1px solid',
  borderColor: '#282c31',
  borderRadius: 2,
  p: 2.5,
  height: '100%',
}

const labelSx = {
  color: 'text.secondary',
  fontSize: '12px',
  fontWeight: 600,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
}

const CardHeader = ({ label, icon }) => (
  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 2 }}>
    <Typography component="span" sx={labelSx}>
      {label}
    </Typography>
    {icon}
  </Box>
)

const SeverityStat = ({ label, count, color }) => (
  <Box sx={{ display: 'flex', flexDirection: 'column' }}>
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 0.75,
        fontSize: '10px',
        fontWeight: 700,
        letterSpacing: '0.08em',
        textTransform: 'uppercase',
        color,
        mb: 0.5,
      }}
    >
      <Box component="span" sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: color }} />
      {label}
    </Box>
    <Typography variant="h2" component="span" sx={{ color: 'text.primary' }}>
      {count}
    </Typography>
  </Box>
)

const SummaryCards = ({ versions }) => {
  const counts = driftCounts(versions)

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' },
        gap: 2,
      }}
    >
      <Paper data-testid="total-apps-card" elevation={0} sx={cardSx}>
        <CardHeader label="Total Apps" icon={<AppsIcon sx={{ color: 'text.secondary' }} />} />
        <Typography variant="h2" component="span" sx={{ color: 'text.primary' }}>
          {counts.total}
        </Typography>
      </Paper>
      <Paper data-testid="up-to-date-card" elevation={0} sx={cardSx}>
        <CardHeader
          label="Up to Date"
          icon={<CheckCircleOutlineIcon sx={{ color: severityColor.NONE }} />}
        />
        <Typography variant="h2" component="span" sx={{ color: 'text.primary' }}>
          {counts.upToDate}
        </Typography>
      </Paper>
      <Paper data-testid="updates-available-card" elevation={0} sx={cardSx}>
        <CardHeader
          label="Updates Available"
          icon={<SystemUpdateAltIcon sx={{ color: severityColor.PATCH }} />}
        />
        <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 2, mt: 0.5 }}>
          <SeverityStat label="Patch" count={counts.patch} color={severityColor.PATCH} />
          <SeverityStat label="Minor" count={counts.minor} color={severityColor.MINOR} />
          <SeverityStat label="Major" count={counts.major} color={severityColor.MAJOR} />
        </Box>
      </Paper>
    </Box>
  )
}

export default SummaryCards
