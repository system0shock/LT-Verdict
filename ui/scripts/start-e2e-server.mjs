import { mkdtemp, rm } from 'node:fs/promises'
import http from 'node:http'
import os from 'node:os'
import { resolve } from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const tempPrefix = resolve(os.tmpdir(), 'lt-verdict-e2e-')
const dataDir = await mkdtemp(tempPrefix)
if (!dataDir.startsWith(tempPrefix)) throw new Error(`Unexpected E2E data directory: ${dataDir}`)
const gradleArgs = ['runE2eServer', '-x', 'npmCi', `-Pe2eDataDir=${dataDir}`]
const command = process.platform === 'win32' ? process.env.ComSpec ?? 'cmd.exe' : './gradlew'
const args = process.platform === 'win32' ? ['/d', '/s', '/c', 'gradlew.bat', ...gradleArgs] : gradleArgs
const child = spawn(command, args, {
  cwd: root,
  stdio: 'inherit',
})
const exited = new Promise((resolveExit) => child.once('exit', resolveExit))

let stopping = false
const stop = (signal) => {
  if (!stopping) {
    stopping = true
    child.kill(signal)
  }
}
process.on('SIGINT', () => stop('SIGINT'))
process.on('SIGTERM', () => stop('SIGTERM'))

const ready = () => new Promise((resolveReady, rejectReady) => {
  const deadline = Date.now() + 120_000
  const poll = () => {
    const request = http.get('http://127.0.0.1:18473/api/bootstrap', (response) => {
      response.resume()
      if (response.statusCode === 200) resolveReady()
      else retry()
    })
    request.on('error', retry)
  }
  const retry = () => {
    if (Date.now() >= deadline) rejectReady(new Error('E2E server did not become ready'))
    else setTimeout(poll, 100)
  }
  poll()
})

try {
  await Promise.race([
    ready(),
    exited.then(() => {
      throw new Error('E2E server exited before becoming ready')
    }),
  ])
  await exited
} finally {
  stop('SIGTERM')
  await exited
  await rm(dataDir, { recursive: true, force: true })
}
