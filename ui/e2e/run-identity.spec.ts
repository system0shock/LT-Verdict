import { expect, test, type Page } from '@playwright/test'

const prefixes = ['3fa9c1d2', '9b01e77a', 'c4d2a8f0']
const runs = prefixes.map((prefix) => {
  const sha256 = `${prefix}${'0'.repeat(56)}`
  return { run_id: `jmeter_jtl_csv-${sha256}`, source_type: 'jmeter_jtl_csv', sha256, size_bytes: 27_900_000, original_filename: 'results.jtl' }
})

async function fixtureApi(page: Page, listed: typeof runs) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path === '/api/runs') body = { runs: listed, next_after: null }
    else if (path.endsWith('/analyses')) body = { analyses: [], next_after: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
}

test('tells apart runs that share one input file name', async ({ page }) => {
  await fixtureApi(page, runs)
  await page.goto('/')

  const buttons = page.getByTestId('run-list').getByRole('button')
  await expect(buttons).toHaveCount(3)
  for (const [index, prefix] of prefixes.entries()) {
    await expect(buttons.nth(index)).toContainText(`jmeter_jtl_csv · ${prefix}`)
    await expect(page.getByRole('button', { name: new RegExp(`^results\\.jtl.*${prefix}`) })).toHaveCount(1)
    await expect(page.getByTestId('run-list').locator('li').nth(index)).toHaveAttribute('title', runs[index].run_id)
  }
  await expect(buttons.first()).not.toHaveAttribute('aria-label', /.*/)
})

test('opens the run whose visible short id was chosen', async ({ page }) => {
  await fixtureApi(page, runs)
  await page.goto('/')

  const listing = page.waitForRequest((request) => new URL(request.url()).pathname === `/api/runs/${runs[2].run_id}/analyses`)
  await page.getByRole('button', { name: new RegExp(`^results\\.jtl.*${prefixes[2]}`) }).click()
  await listing

  await expect(page.getByTestId('run-list').locator('button[aria-pressed="true"]')).toHaveCount(1)
  await expect(page.getByTestId('run-list').locator('button[aria-pressed="true"]')).toContainText(prefixes[2])
  await expect(page.locator('.run-identity')).toContainText('results.jtl')
  await expect(page.locator('.run-identity')).toContainText(`jmeter_jtl_csv-${prefixes[2]}`)
})

test('keeps the short id inside a narrow window for a very long file name', async ({ page }) => {
  const longName = `${'very-long-load-test-results-'.repeat(5)}.jtl`
  await fixtureApi(page, [{ ...runs[0], original_filename: longName }])
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/')

  const identity = page.getByTestId('run-list').getByRole('button').locator('small')
  await expect(identity).toContainText(prefixes[0])
  const box = await identity.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(375)
})
