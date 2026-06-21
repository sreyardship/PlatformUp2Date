import { createTheme } from '@mui/material/styles'

// Palette is taken from the authoritative rendered reference
// (style-reference/code.html + screen.png), NOT from style-reference/DESIGN.md.
// DESIGN.md is a stale, internally-inconsistent derived spec: it ships a
// blue-tinted slate palette + lavender/saturated-blue primaries that do not
// match the actual design. The real scheme is a neutral near-black gray with a
// light sky-blue primary (M3 dark roles).
const theme = createTheme({
  palette: {
    mode: 'dark',
    primary: {
      main: '#91ccff',
    },
    secondary: {
      main: '#a2c9ea',
    },
    success: {
      main: '#4ade80',
    },
    warning: {
      main: '#fbbf24',
    },
    error: {
      main: '#f87171',
    },
    background: {
      default: '#0b0c0e',
      paper: '#1e2226',
    },
    text: {
      primary: '#e0e3e8',
      secondary: '#c3c7cf',
    },
    divider: '#43474e',
  },
  typography: {
    fontFamily: 'Inter, system-ui, -apple-system, sans-serif',
    h1: { fontFamily: 'Inter', fontSize: '30px', fontWeight: 700, lineHeight: '38px', letterSpacing: '-0.02em' },
    h2: { fontFamily: 'Inter', fontSize: '24px', fontWeight: 600, lineHeight: '32px', letterSpacing: '-0.01em' },
    h3: { fontFamily: 'Inter', fontSize: '20px', fontWeight: 600, lineHeight: '28px' },
    body1: { fontFamily: 'Inter', fontSize: '16px', fontWeight: 400, lineHeight: '24px' },
    body2: { fontFamily: 'Inter', fontSize: '14px', fontWeight: 400, lineHeight: '20px' },
  },
  shape: {
    borderRadius: 4,
  },
  components: {
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 4,
          // Reference buttons are Title Case ("Refresh All"), not MUI's default
          // ALL-CAPS.
          textTransform: 'none',
        },
      },
    },
    MuiTextField: {
      styleOverrides: {
        root: {
          borderRadius: 4,
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          borderRadius: 8,
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          borderRadius: 8,
        },
      },
    },
  },
})

export default theme
