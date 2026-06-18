import { Box, Paper, Typography } from '@mui/material'

import UpdateButton from './UpdateButton'
import versionClient from './api/versionClient'

const Version = ({ name, ver, onRefreshed }) => {
  const color = ver.current === ver.latest ? 'success.main' : 'error.main'

  return (
    <Paper
      elevation={2}
      sx={{
        display: 'flex',
        justifyContent: 'space-between',
        width: '95%',
        my: '2px',
        px: '0.6rem',
        py: '1rem',
      }}
    >
      <Typography variant='h4'>{name}:</Typography>
      <Typography
        variant='h4'
        sx={{
          color: color,
        }}
      >
        {ver.current}
      </Typography>
      <Typography variant='h4'>{ver.latest}</Typography>
      <Box sx={{ display: 'flex', gap: '0.4rem' }}>
        <UpdateButton
          label='Update current'
          onScrape={() => versionClient.scrapeApplication(name, 'current')}
          onRefreshed={onRefreshed}
        />
        <UpdateButton
          label='Update latest'
          onScrape={() => versionClient.scrapeApplication(name, 'latest')}
          onRefreshed={onRefreshed}
        />
      </Box>
    </Paper>
  )
}

export default Version
