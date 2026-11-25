import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { SHELL_LABELS } from '../src/shell/labels'
import { NORMALIZED_LABELS, TABLES_LABELS } from '../src/shell/labels.tables'
import { e2ePort } from './e2e-env'

const origin = `http://127.0.0.1:${e2ePort(18473)}`
const fixture = (path: string) => fileURLToPath(new URL(`../../fixtures/${path}`, import.meta.url))
const maliciousLabel = '<img src="x" onerror="alert(1)">'
const gappedJtl = `timeStamp,elapsed,label,success
1767225600000,20,first,true
1767225603000,30,second,false
`
const securityHeaders = {
  'cache-control': 'no-store',
  'content-security-policy':
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
  'referrer-policy': 'no-referrer',
  'x-content-type-options': 'nosniff',
}

async function expectVisibleKeyboardFocus(locator: Locator) {
  await expect(locator).toBeFocused()
  expect(
    await locator.evaluate((element) => {
      const style = getComputedStyle(element)
      return {
        offset: style.outlineOffset,
        style: style.outlineStyle,
        width: style.outlineWidth,
      }
    }),
  ).toEqual({ offset: '2px', style: 'solid', width: '2px' })
}

test.describe.serial('local UI security and accessibility', () => {
  test('renders an HTML transaction label as text and stays on the local origin', async ({ page }) => {
    const remoteRequests: string[] = []
    const dialogs: string[] = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if ((url.protocol === 'http:' || url.protocol === 'https:') && url.origin !== origin) {
        remoteRequests.push(request.url())
      }
    })
    page.on('dialog', async (dialog) => {
      dialogs.push(dialog.message())
      await dialog.dismiss()
    })

    await page.goto('/')
    await page.getByTestId('input-file').setInputFiles(fixture('slice1/security/html-label.jtl'))
    await page.getByTestId('start-analysis').click()
    await expect(page.locator('#verdict')).toContainText('NO_POLICY')
    await page.locator('#shell-tab-tables').click()

    await expect(page.locator('#transaction-metrics tbody').getByText(maliciousLabel, { exact: true })).toBeVisible()
    await expect(page.locator('#transaction-metrics tbody').getByText(TABLES_LABELS.statusText.NO_POLICY, { exact: true })).toBeVisible()
    await expect(page.locator('#transaction-metrics tbody').getByText(TABLES_LABELS.statusText.PASS, { exact: true })).toHaveCount(0)
    await expect(page.locator('img[src="x"]')).toHaveCount(0)
    expect(dialogs).toEqual([])
    expect(remoteRequests).toEqual([])

    await page.getByRole('button', { name: new RegExp(`${SHELL_LABELS.themeToDark}|${SHELL_LABELS.themeToLight}`) }).click()
    expect(
      await page.evaluate(() => ({
        local: Object.entries(window.localStorage),
        session: window.sessionStorage.length,
      })),
    ).toEqual({ local: [], session: 0 })
  })

  test('serves the shell and API with the exact security headers and no CORS', async ({ request }) => {
    for (const path of ['/', '/api/bootstrap']) {
      const response = await request.get(path)
      expect(response.ok(), path).toBeTruthy()
      const headers = response.headers()
      expect(
        Object.fromEntries(Object.keys(securityHeaders).map((name) => [name, headers[name]])),
        path,
      ).toEqual(securityHeaders)
      for (const name of [
        'access-control-allow-credentials',
        'access-control-allow-headers',
        'access-control-allow-methods',
        'access-control-allow-origin',
      ]) {
        expect(headers[name], `${path} ${name}`).toBeUndefined()
      }
    }
  })

  test('follows the visual keyboard order with visible focus and 44px targets', async ({ page }) => {
    const listing = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/runs')
    await page.goto('/')
    const listedRuns = await (await listing).json() as { runs: unknown[] }
    await expect(page.getByTestId('run-list').getByRole('button')).toHaveCount(listedRuns.runs.length)
    const input = page.getByTestId('input-file')
    const stagesPropose = page.getByTestId('stages-propose')
    const stagesJson = page.getByTestId('stages-json')
    const policy = page.getByTestId('policy-file')
    const resources = page.getByTestId('resource-snapshot-file')
    const diagnostics = page.getByTestId('correlation-plan-file')
    const capacity = page.getByTestId('capacity-plan-file')
    const trend = page.getByTestId('trend-plan-file')
    const sourceContext = page.locator('#source-context-file')
    const sourceProfile = page.getByTestId('source-profile')
    const reloadSources = page.getByTestId('reload-sources')
    const validateSources = page.getByTestId('validate-sources')
    const probeQueryId = page.getByTestId('source-probe-query-id')
    const probeSource = page.getByTestId('probe-source')
    const sourceWindowOrigin = page.locator('#source-window-origin')
    const sourceStep = page.locator('#source-step')
    const sourceStepMode = page.locator('#source-step-mode')
    const sourceMargin = page.locator('#source-margin')
    const sourceMaxIdleGap = page.locator('#source-max-idle-gap')
    const postgresProfile = page.locator('#postgres-profile')
    const postgresPre = page.locator('#postgres-pre-file')
    const postgresPost = page.locator('#postgres-post-file')
    const pgProfileHtml = page.locator('#pg-profile-html-file')
    const aiRequested = page.getByTestId('ai-requested')
    const pairRequested = page.getByTestId('pair-requested')
    await expect(sourceProfile).toBeEnabled()
    const analyze = page.getByTestId('start-analysis')
    await input.setInputFiles(fixture('slice1/jmeter/csv-5.6.3/input.jtl'))
    await expect(analyze).toBeEnabled()
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())

    const focusOrder = [
      page.locator('#shell-tab-setup'),
      page.getByTestId('run-list'),
      ...await page.getByTestId('run-list').getByRole('button').all(),
      page.getByRole('button', { name: new RegExp(`${SHELL_LABELS.themeToDark}|${SHELL_LABELS.themeToLight}`) }),
      input,
      stagesPropose,
      stagesJson,
      policy,
      resources,
      sourceContext,
      sourceProfile,
      reloadSources,
      validateSources,
      postgresPre,
      postgresPost,
      pgProfileHtml,
      diagnostics,
      capacity,
      trend,
      aiRequested,
      pairRequested,
      analyze,
    ]
    for (const target of focusOrder) {
      await page.keyboard.press('Tab')
      await expectVisibleKeyboardFocus(target)
    }

    // The source window renders only with a profile selected, and selecting one disables the manual snapshot inputs above it.
    await sourceProfile.selectOption('local')
    await expect(sourceWindowOrigin).toHaveValue('auto')
    await expect(page.locator('#source-start')).toHaveCount(0)
    await expect(sourceMargin).toHaveAttribute('aria-describedby', 'source-margin-hint')
    await expect(sourceMaxIdleGap).toHaveAttribute('aria-describedby', 'source-max-idle-gap-hint')
    await expect(page.locator('p#source-margin-hint')).toBeVisible()
    await expect(page.locator('p#source-max-idle-gap-hint')).toBeVisible()
    await sourceProfile.focus()
    for (const target of [reloadSources, validateSources, probeQueryId, probeSource, sourceWindowOrigin, sourceStep, sourceStepMode, sourceMargin, sourceMaxIdleGap, postgresPre]) {
      await page.keyboard.press('Tab')
      await expectVisibleKeyboardFocus(target)
    }

    for (const labelledInput of [input, stagesJson, policy, resources, diagnostics, capacity, trend, sourceContext, sourceProfile, sourceWindowOrigin, sourceStep, sourceStepMode, sourceMargin, sourceMaxIdleGap, postgresProfile, postgresPre, postgresPost, pgProfileHtml]) {
      const labels = await labelledInput.evaluate((element) =>
        [...((element as HTMLInputElement).labels ?? [])].map((label) => {
          const style = getComputedStyle(label)
          const box = label.getBoundingClientRect()
          return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0
        }),
      )
      expect(labels.length).toBeGreaterThan(0)
      expect(labels.every(Boolean)).toBeTruthy()
    }

    const actionTargets = page.locator('a[href], button, input:not([type="hidden"]), select, textarea')
    let visibleTargets = 0
    for (let index = 0; index < (await actionTargets.count()); index += 1) {
      const target = actionTargets.nth(index)
      if (!(await target.isVisible())) continue
      visibleTargets += 1
      const box = await target.boundingBox()
      expect(box, await target.evaluate((element) => element.outerHTML)).not.toBeNull()
      expect(box!.width, await target.evaluate((element) => element.outerHTML)).toBeGreaterThanOrEqual(44)
      expect(box!.height, await target.evaluate((element) => element.outerHTML)).toBeGreaterThanOrEqual(44)
    }
    expect(visibleTargets).toBeGreaterThan(0)
  })

  test('uses semantic status tables, reduced motion and no deferred feature placeholders', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/')
    await page.getByTestId('input-file').setInputFiles({
      name: 'gapped.jtl',
      mimeType: 'text/csv',
      buffer: Buffer.from(gappedJtl),
    })
    await page.getByTestId('policy-file').setInputFiles(fixture('slice1/policies/fail.json'))
    await page.getByTestId('start-analysis').click()
    await expect(page.locator('#verdict')).toContainText('FAIL')
    await page.locator('#shell-tab-tables').click()

    for (const id of ['policy-results', 'transaction-metrics', 'normalized-data']) {
      const table = page.locator(`#${id} table, table#${id}`).first()
      await expect(table).toBeVisible()
      const headers = table.getByRole('columnheader')
      expect(await headers.count(), id).toBeGreaterThan(0)
      expect(
        (await headers.allTextContents()).every((text) => text.trim().length > 0),
        `${id} has an empty column header`,
      ).toBeTruthy()
      expect(await table.getByRole('row').count(), id).toBeGreaterThan(1)
    }
    await expect(page.locator('#policy-results').getByText(TABLES_LABELS.statusText.FAIL, { exact: true }).first()).toBeVisible()
    await expect(page.locator('#normalized-data')).toContainText('20 мс')
    await expect(page.locator('#normalized-data')).toContainText('30 мс')
    await expect(page.locator('#normalized-data')).toContainText(NORMALIZED_LABELS.missing)
    await expect(page.locator('#normalized-data').getByText('Not available', { exact: true })).toHaveCount(0)

    expect(
      await page.locator('*').evaluateAll((elements) => {
        const hasDuration = (value: string) =>
          value.split(',').some((part) => {
            const duration = Number.parseFloat(part)
            return Number.isFinite(duration) && duration > 0
          })
        return elements
          .filter((element) => {
            const style = getComputedStyle(element)
            return hasDuration(style.animationDuration) || hasDuration(style.transitionDuration)
          })
          .map((element) => element.tagName.toLowerCase())
      }),
    ).toEqual([])

    await expect(
      page.locator(
        'canvas, [draggable="true"], [data-chart], [data-dnd], [data-placeholder], [class*="chart-placeholder"]',
      ),
    ).toHaveCount(0)
    await expect(page.getByText(/Export result|Coming soon|Chart placeholder|Drag(?:gable)? placeholder/i)).toHaveCount(0)

    const axe = await new AxeBuilder({ page }).analyze()
    expect(
      axe.violations
        .filter((violation) => violation.impact === 'critical' || violation.impact === 'serious')
        .map((violation) => ({ id: violation.id, impact: violation.impact, nodes: violation.nodes.length })),
    ).toEqual([])
  })
})
