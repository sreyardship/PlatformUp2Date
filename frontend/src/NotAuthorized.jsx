import { Box, Button, Paper, Typography } from '@mui/material'
import LockPersonIcon from '@mui/icons-material/LockPerson'

import { userManager } from './auth/userManager'

const handleLogout = () => {
  userManager.removeUser()
  userManager.signoutRedirect()
}

const NotAuthorized = () => {
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
        <LockPersonIcon sx={{ color: 'error.main', fontSize: 48, mb: 2 }} />
        <Typography variant="h5" component="h2" sx={{ mb: 1 }}>
          Not authorized
        </Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 3 }}>
          Your account isn't authorized for this app
        </Typography>
        <Button variant="outlined" onClick={handleLogout}>
          Log out
        </Button>
      </Paper>
    </Box>
  )
}

export default NotAuthorized
