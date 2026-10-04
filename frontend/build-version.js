// Strict SemVer 2.0.0 (numeric prerelease identifiers cannot have leading zeroes),
// plus the deliberately non-semver local/edge identities.
const identity = /^(?:dev(?:-[0-9a-fA-F]+)?|(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9][0-9]*|[0-9]*[a-zA-Z-][0-9a-zA-Z-]*))*)?(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?)$/

// Build identity belongs to this artifact, not the runtime UI configuration.
export default function buildVersion() {
  let version
  return {
    name: 'build-version',
    configResolved(config) {
      const isBuild = config.command === 'build'
      version = isBuild ? (process.env.BUILD_VERSION ?? 'dev') : 'dev'
      if (isBuild && process.env.BUILD_VERSION_REQUIRED === 'true' && version === 'dev') {
        throw new Error('BUILD_VERSION is required: inject a release or dev-<hexcommit> identity, not dev')
      }
      // Match the entire input: JS's $ also permits a trailing newline.
      if (version.match(identity)?.[0] !== version) {
        throw new Error('Invalid BUILD_VERSION: expected strict semver, dev, or dev-<hexcommit>')
      }
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'version.json', source: JSON.stringify({ version }) })
    },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/version.json') return next()
        res.setHeader('Content-Type', 'application/json')
        res.setHeader('Cache-Control', 'no-store')
        res.end(JSON.stringify({ version: 'dev' }))
      })
    },
    configurePreviewServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] === '/version.json') res.setHeader('Cache-Control', 'no-store')
        next()
      })
    },
  }
}
