// ui/tsconfig.json does not load Node types; declare the one global used here.
declare const process: { env: Record<string, string | undefined> }

export function e2ePort(defaultPort: number): number {
  const value = process.env.LTV_E2E_PORT
  if (value === undefined || value === '') return defaultPort
  const port = Number(value)
  if (!/^[0-9]+$/.test(value) || !Number.isInteger(port) || port < 1024 || port > 65535) {
    throw new Error(`Invalid LTV_E2E_PORT: ${value}`)
  }
  return port
}

export function oldShellStorageState(origin: string) {
  return { cookies: [], origins: [{ origin, localStorage: [{ name: 'ltv.shell', value: 'old' }] }] }
}
