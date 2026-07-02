// Slice 01: each side is now a { version, readAt } object rather than a bare string.
// readAt is an absolute ISO instant; the component renders relative "read Xm ago" client-side.
const now = new Date().toISOString()

const fakeData = {
  'git-tea': {
    current: { version: '1.21.7', readAt: now },
    latest: { version: '1.22.1', readAt: now },
  },
  'argo-cd': {
    current: { version: '2.10.7+b060053', readAt: now },
    latest: { version: '2.11.7', readAt: now },
  },
  sharry: {
    current: { version: '1.14.0', readAt: now },
    latest: { version: '1.14.0', readAt: now },
  },
}

export default fakeData
