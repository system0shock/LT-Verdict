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

test('shows when each run was accepted, in the order the server sent', async ({ page }) => {
  const acceptedAt = ['2026-10-07T12:00:05.000Z', '2026-10-07T12:00:00.000Z']
  await fixtureApi(page, [
    { ...runs[0], accepted_at: acceptedAt[0] },
    { ...runs[1], accepted_at: acceptedAt[1] },
    runs[2], // stored before accepted_at existed: no time, still listed
  ])
  await page.goto('/')

  const buttons = page.getByTestId('run-list').getByRole('button')
  await expect(buttons).toHaveCount(3)
  for (const [index, value] of acceptedAt.entries()) {
    const accepted = buttons.nth(index).locator('time')
    await expect(accepted).toHaveAttribute('datetime', value)
    await expect(accepted).not.toHaveText('')
    await expect(buttons.nth(index)).toContainText(`jmeter_jtl_csv · ${prefixes[index]}`)
  }
  await expect(buttons.nth(2).locator('time')).toHaveCount(0)
  await expect(buttons.nth(2)).toContainText(`jmeter_jtl_csv · ${prefixes[2]}`)
})

test('keeps the accepted time inside a narrow window', async ({ page }) => {
  await fixtureApi(page, [{ ...runs[0], accepted_at: '2026-10-07T12:00:05.000Z' }])
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/')

  const accepted = page.getByTestId('run-list').getByRole('button').locator('time')
  await expect(accepted).toBeVisible()
  const box = await accepted.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(375)
})

test('lists the newest accepted run first (live server)', async ({ page }) => {
  const stamp = Date.now()
  const names = [`older-${stamp}.jtl`, `newer-${stamp}.jtl`]
  await page.goto('/')
  for (const [index, name] of names.entries()) {
    await page.getByTestId('input-file').setInputFiles({
      name,
      mimeType: 'text/csv',
      buffer: Buffer.from(`timeStamp,elapsed,label,success\n${1767225600000 + index},${100 + index},accepted-order,true\n`),
    })
    const accepted = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/inputs')
    await page.getByRole('button', { name: 'Analyze run' }).click()
    expect((await accepted).status()).toBe(201)
    await expect(page.locator('#job-status')).toContainText('COMPLETE')
  }

  await page.reload()
  const buttons = page.getByTestId('run-list').getByRole('button')
  await expect(buttons.first()).toContainText(names[1])
  await expect(buttons.nth(1)).toContainText(names[0])
  const newer = await buttons.first().locator('time').getAttribute('datetime')
  const older = await buttons.nth(1).locator('time').getAttribute('datetime')
  expect(newer).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
  expect(older).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
  expect(older! <= newer!).toBe(true)
})
