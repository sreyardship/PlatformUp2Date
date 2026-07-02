import { Box, Button, Paper, Typography } from '@mui/material'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'

const BackendUnavailable = ({ failure, onRetry }) => {
  return (
    <Box
      sx={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        flex: 1,
        py: 6,
      }}
    >
      <Paper
        elevation={0}
        sx={{
          bgcolor: 'background.paper',
          border: '1px solid #282c31',
          borderRadius: 2,
          p: 4,
          maxWidth: 480,
          width: '100%',
          textAlign: 'center',
        }}
      >
        <WarningAmberIcon sx={{ color: 'error.main', fontSize: 48, mb: 2 }} />
        <Typography variant="h5" component="h2" sx={{ mb: 1 }}>
          Backend unavailable
        </Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 3 }}>
          {failure.message}
        </Typography>
        <Button variant="outlined" onClick={onRetry}>
          Retry
        </Button>
      </Paper>
    </Box>
  )
}

export default BackendUnavailable
