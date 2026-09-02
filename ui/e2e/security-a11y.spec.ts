import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator } from '@playwright/test'
import { fileURLToPath } from 'node:url'

const origin = 'http://127.0.0.1:18473'
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
    await page.getByRole('button', { name: 'Analyze run' }).click()
    await expect(page.locator('#verdict')).toContainText('NO_POLICY')

    await expect(page.locator('#transaction-metrics').getByText(maliciousLabel, { exact: true })).toBeVisible()
    await expect(page.locator('#transaction-metrics').getByText('NO_POLICY', { exact: true })).toBeVisible()
    await expect(page.locator('#transaction-metrics').getByText('PASS', { exact: true })).toHaveCount(0)
    await expect(page.locator('img[src="x"]')).toHaveCount(0)
    expect(dialogs).toEqual([])
    expect(remoteRequests).toEqual([])

    await page.getByRole('button', { name: /Dark theme|Light theme/ }).click()
    expect(
      await page.evaluate(() => ({
        local: window.localStorage.length,
        session: window.sessionStorage.length,
      })),
    ).toEqual({ local: 0, session: 0 })
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
    await page.goto('/')
    const input = page.getByTestId('input-file')
    const policy = page.getByTestId('policy-file')
    const analyze = page.getByRole('button', { name: 'Analyze run' })
    await input.setInputFiles(fixture('slice1/jmeter/csv-5.6.3/input.jtl'))
    await expect(analyze).toBeEnabled()
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())

    const navigation = page.getByRole('navigation').locator('a[href], button')
    await expect(navigation).toHaveCount(2)
    await expect(navigation.nth(0)).toHaveText('Runs')
    await expect(navigation.nth(1)).toHaveText('Policies')
    const focusOrder = [
      navigation.nth(0),
      navigation.nth(1),
      page.getByTestId('run-list'),
      page.getByRole('button', { name: /Dark theme|Light theme/ }),
      input,
      policy,
      analyze,
    ]
    for (const target of focusOrder) {
      await page.keyboard.press('Tab')
      await expectVisibleKeyboardFocus(target)
    }

    for (const labelledInput of [input, policy]) {
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
    await page.getByRole('button', { name: 'Analyze run' }).click()
    await expect(page.locator('#verdict')).toContainText('FAIL')

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
    await expect(page.locator('#policy-results').getByText('FAIL', { exact: true })).toBeVisible()
    await expect(page.locator('#normalized-data')).toContainText('20 ms')
    await expect(page.locator('#normalized-data')).toContainText('30 ms')
    await expect(page.locator('#normalized-data')).toContainText('Missing / no samples')
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
