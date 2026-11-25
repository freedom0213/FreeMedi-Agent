import { preview } from 'vite'

// Ignore the development configuration, which proxies API calls to the Java server.
await preview({
  configFile: false,
  preview: {
    host: '127.0.0.1', port: 4178, strictPort: true,
    // A stalled external font stylesheet must not block offline browser regressions.
    headers: { 'Content-Security-Policy': "style-src 'self' 'unsafe-inline'; font-src 'self' data:" },
  },
})
