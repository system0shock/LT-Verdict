import { expect, test } from '@playwright/test'

const input = Buffer.from('timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,bytes,sentBytes,grpThreads,allThreads,Latency,IdleTime,Connect\n1767225600000,1000,request,200,OK,thread,true,1,1,1,1,1,0,0\n')

test('acquires a configured online source, downloads its snapshot, and reloads the saved status', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'online-source.jtl', mimeType: 'text/csv', buffer: input })
  await page.getByLabel('Online source profile').selectOption('local')
  await page.getByLabel('Source start (UTC epoch ms)').fill('1767225600000')
  await page.getByLabel('Source end (UTC epoch ms)').fill('1767225601000')
  await page.getByLabel('Source step (ms)').fill('1000')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()

  const acquisition = page.getByTestId('source-acquisition')
  await expect(acquisition).toContainText('COMPLETE')
  const snapshot = page.getByRole('link', { name: 'Download resource snapshot' })
  const response = await page.request.get(await snapshot.getAttribute('href') ?? '')
  expect(response.ok()).toBe(true)
  expect(await response.json()).toMatchObject({ schema_version: 'resource-snapshot.v1' })

  await page.reload()
  await page.getByRole('button', { name: 'online-source.jtl' }).click()
  await page.getByRole('button', { name: /^Analysis / }).click()
  await expect(page.getByTestId('source-acquisition')).toContainText('COMPLETE')
})
