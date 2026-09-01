import { expect, test } from '@playwright/test'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const policies = {
  fail: fixture('slice1/policies/fail.json'),
  missing: fixture('slice1/policies/missing-transaction.json'),
  pass: fixture('slice1/policies/pass.json'),
}
const verdictInput = fixture('slice1/jmeter/xml-5.6.3/input.xml')

async function uploadAndAnalyze(page: import('@playwright/test').Page, input: string, policy?: string) {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles(input)
  await expect(page.locator('#run-setup')).toContainText(/\.(?:jtl|xml)|simulation\.log/)
  if (policy) await page.getByTestId('policy-file').setInputFiles(policy)

  await page.getByRole('button', { name: 'Analyze run' }).click()
  await expect(page.locator('#job-status')).toBeVisible()
  await expect(page.getByTestId('job-progress')).toHaveAttribute('aria-valuenow', /\d+/)
  await expect(page.locator('#verdict')).toBeVisible()
}

async function submitJob(page: import('@playwright/test').Page, runId: string) {
  return page.evaluate(async (id) => {
    const bootstrap = await fetch('/api/bootstrap').then((response) => response.json())
    const body = new FormData()
    body.append('run_id', id)
    const response = await fetch('/api/jobs', {
      method: 'POST',
      headers: { 'X-LTV-CSRF': bootstrap.csrf_token },
      body,
    })
    return { status: response.status, body: await response.json() }
  }, runId)
}

async function uploadSelectedInput(page: import('@playwright/test').Page) {
  return page.evaluate(async () => {
    const bootstrap = await fetch('/api/bootstrap').then((response) => response.json())
    const file = document.querySelector<HTMLInputElement>('[data-testid="input-file"]')?.files?.item(0)
    if (!file) throw new Error('Input file was not selected')
    const body = new FormData()
    body.append('file', file)
    const response = await fetch('/api/inputs', {
      method: 'POST',
      headers: { 'X-LTV-CSRF': bootstrap.csrf_token },
      body,
    })
    if (!response.ok) throw new Error(`Upload failed: ${response.status}`)
    return response.json() as Promise<{ run_id: string }>
  })
}

async function jobState(page: import('@playwright/test').Page, jobId: string) {
  return page.evaluate(async (id) => fetch(`/api/jobs/${id}`).then((response) => response.json()).then((job) => job.state), jobId)
}

async function cancelJob(page: import('@playwright/test').Page, jobId: string) {
  await page.evaluate(async (id) => {
    const bootstrap = await fetch('/api/bootstrap').then((response) => response.json())
    await fetch(`/api/jobs/${id}`, { method: 'DELETE', headers: { 'X-LTV-CSRF': bootstrap.csrf_token } })
  }, jobId)
}

test.describe.serial('local analysis flow', () => {
  test('shows the supported empty state', async ({ page }) => {
    await page.goto('/')

    await expect(page.getByRole('heading', { name: 'LT Verdict' })).toBeVisible()
    await expect(page.locator('#run-setup')).toBeVisible()
    await expect(page.getByText('Choose a supported JMeter JTL or Gatling log.')).toBeVisible()
    await expect(page.locator('#verdict')).toHaveCount(0)
  })

  test('imports, edits, validates and downloads a policy draft', async ({ page }) => {
    await page.goto('/')
    await page.getByTestId('policy-file').setInputFiles(policies.pass)

    await expect(page.locator('#run-setup')).toContainText('Policy is valid')
    await page.getByLabel('Policy ID').fill('slice1-edited')
    const exactThreshold = '0.333333333333333333333333333333333333'
    await page.getByLabel('Threshold').fill(exactThreshold)
    await expect(page.locator('#run-setup')).toContainText('slice1-edited')

    const download = page.waitForEvent('download')
    await page.getByRole('button', { name: 'Download policy' }).click()
    const saved = await download
    expect(saved.suggestedFilename()).toBe('policy.json')
    const downloadPath = await saved.path()
    if (!downloadPath) throw new Error('Policy download path is unavailable')
    expect(await readFile(downloadPath, 'utf8')).toContain(`"threshold": ${exactThreshold}`)
  })

  test('analyzes every supported golden input through the browser', async ({ page }) => {
    for (const input of [
      fixture('slice1/jmeter/csv-5.6.3/input.jtl'),
      fixture('slice1/jmeter/xml-5.6.3/input.xml'),
      fixture('slice1/gatling/text-3.12.0/simulation.log'),
      fixture('slice1/gatling/binary-3.15.1/simulation.log'),
    ]) {
      await uploadAndAnalyze(page, input)
      await expect(page.locator('#verdict')).toContainText('NO_POLICY')
    }
    await expect(page.getByLabel('Start offset (ms)')).toBeVisible()
    await expect(page.getByLabel('End offset (ms)')).toBeVisible()
  })

  test('renders PASS, FAIL, NO_POLICY and NO_VERDICT independently', async ({ page }) => {
    await uploadAndAnalyze(page, verdictInput, policies.pass)
    await expect(page.locator('#verdict')).toContainText('PASS')
    await expect(page.getByText('Run validity', { exact: true }).locator('..').locator('dd')).toHaveText('VALID')
    await expect(page.getByText('Coverage', { exact: true }).locator('..').locator('dd')).toHaveText('COMPLETE')

    await uploadAndAnalyze(page, verdictInput, policies.fail)
    await expect(page.locator('#verdict')).toContainText('FAIL')

    await uploadAndAnalyze(page, verdictInput)
    await expect(page.locator('#verdict')).toContainText('NO_POLICY')

    await uploadAndAnalyze(page, verdictInput, policies.missing)
    await expect(page.locator('#verdict')).toContainText('NO_VERDICT')
    await expect(page.getByText('Coverage', { exact: true }).locator('..').locator('dd')).toHaveText('INCOMPLETE')
    await expect(page.locator('#verdict')).toContainText('TRANSACTION_NOT_FOUND')
  })

  test('shows BUSY and keeps cancellation visible', async ({ page }) => {
    const directory = await mkdtemp(join(tmpdir(), 'ltv-flow-'))
    const input = join(directory, 'sustained.jtl')
    const header = 'timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n'
    const row = '1767225600000,20,steady,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0\n'
    await writeFile(input, header + row)
    let activeJobId: string | undefined
    let queuedJobId: string | undefined

    try {
      await page.goto('/')
      await page.getByTestId('input-file').setInputFiles(input)
      const uploaded = await uploadSelectedInput(page)
      const active = await submitJob(page, uploaded.run_id)
      expect(active.status).toBe(202)
      activeJobId = active.body.job_id
      await expect.poll(() => jobState(page, activeJobId!)).toBe('PROCESSING')

      const queued = await submitJob(page, uploaded.run_id)
      expect(queued.status).toBe(202)
      queuedJobId = queued.body.job_id

      const competing = await page.context().newPage()
      try {
        await competing.goto('/')
        await competing.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/csv-5.6.3/input.jtl'))
        await competing.getByRole('button', { name: 'Analyze run' }).click()
        await expect(competing.getByTestId('busy-notice')).toContainText('BUSY')
      } finally {
        await competing.close()
      }

      await cancelJob(page, queuedJobId)
      await expect.poll(() => jobState(page, queuedJobId!)).toBe('CANCELLED')
      queuedJobId = undefined

      await page.getByTestId('input-file').setInputFiles(fixture('slice1/jmeter/csv-5.6.3/input.jtl'))
      await page.getByRole('button', { name: 'Analyze run' }).click()
      await expect(page.getByRole('button', { name: 'Cancel analysis' })).toBeVisible()
      await page.getByRole('button', { name: 'Cancel analysis' }).click()
      await expect(page.locator('#job-status')).toContainText('CANCELLED')
    } finally {
      if (queuedJobId) await cancelJob(page, queuedJobId)
      if (activeJobId) await cancelJob(page, activeJobId)
      await rm(directory, { force: true, recursive: true })
    }
  })

  test('lists accepted runs and preserves structural parity between themes', async ({ page }) => {
    await uploadAndAnalyze(page, verdictInput, policies.fail)
    await expect(page.getByRole('navigation')).toContainText('Runs')
    await expect(page.getByTestId('run-list')).toContainText('input.xml')

    const lightStructure = await page.locator('main').evaluate((element) =>
      [...element.querySelectorAll('[id]')].map((child) => child.id).join(','),
    )
    await page.getByRole('button', { name: /Dark theme|Light theme/ }).click()
    await expect(page.locator('html')).toHaveAttribute('data-theme', /light|dark/)
    expect(
      await page.locator('main').evaluate((element) =>
        [...element.querySelectorAll('[id]')].map((child) => child.id).join(','),
      ),
    ).toBe(lightStructure)
    for (const id of ['run-setup', 'job-status', 'verdict', 'summary-metrics', 'policy-results', 'transaction-metrics', 'normalized-data']) {
      await expect(page.locator(`#${id}`)).toBeVisible()
    }
  })
})
