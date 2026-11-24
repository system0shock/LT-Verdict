import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import http from 'node:http'
import os from 'node:os'
import { delimiter, resolve } from 'node:path'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const rawPort = process.env.LTV_E2E_PORT
const port = rawPort === undefined || rawPort === '' ? 18473 : Number(rawPort)
if ((rawPort !== undefined && rawPort !== '' && !/^[0-9]+$/.test(rawPort)) ||
  !Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error(`Invalid LTV_E2E_PORT: ${rawPort}`)
}

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const usePrebuilt = process.env.LTV_E2E_PREBUILT === '1'
const testClassesDir = resolve(root, 'build/classes/kotlin/test')
const installLibDir = resolve(root, 'build/install/ltv/lib')
if (usePrebuilt) {
  if (!existsSync(testClassesDir)) {
    throw new Error(`Missing prebuilt E2E test classes directory: ${testClassesDir}`)
  }
  if (!existsSync(installLibDir)) {
    throw new Error(`Missing prebuilt E2E runtime lib directory: ${installLibDir}`)
  }
}
const tempPrefix = resolve(os.tmpdir(), 'lt-verdict-e2e-')
const dataDir = await mkdtemp(tempPrefix)
if (!dataDir.startsWith(tempPrefix)) throw new Error(`Unexpected E2E data directory: ${dataDir}`)
let command
let args
if (usePrebuilt) {
  const javaHome = process.env.JAVA_HOME?.trim()
  const javaExe = process.platform === 'win32' ? 'java.exe' : 'java'
  const javaBin = javaHome ? resolve(javaHome, 'bin', javaExe) : 'java'
  command = javaBin
  args = [
    `-De2eDataDir=${dataDir}`,
    '-cp',
    `${testClassesDir}${delimiter}${resolve(installLibDir, '*')}`,
    'io.ltverdict.e2e.E2eServerMainKt',
  ]
} else {
  const gradleArgs = ['runE2eServer', '-x', 'npmCi', `-Pe2eDataDir=${dataDir}`]
  command = process.platform === 'win32' ? process.env.ComSpec ?? 'cmd.exe' : './gradlew'
  args = process.platform === 'win32' ? ['/d', '/s', '/c', 'gradlew.bat', ...gradleArgs] : gradleArgs
}
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
    const request = http.get(`http://127.0.0.1:${port}/api/bootstrap`, (response) => {
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
