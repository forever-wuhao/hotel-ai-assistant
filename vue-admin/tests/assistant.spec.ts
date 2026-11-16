import { test, expect, type Page } from '@playwright/test'

async function send(page: Page, text: string) {
  await page.getByLabel('咨询内容').fill(text)
  await page.getByRole('button', { name: '发送咨询 ↗' }).click()
  await expect(page.getByRole('button', { name: '发送咨询 ↗' })).toBeVisible()
}

test('anonymous consultation works with stale admin storage, changes budget and restores history', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('token', 'expired-admin-token')
    localStorage.setItem('user', '{"role":"ADMIN"}')
  })
  const authHeaders: (string | undefined)[] = []
  page.on('request', req => { if(req.url().endsWith('/api/ai/agent/chat')) authHeaders.push(req.headers()['authorization']) })
  await page.goto('/assistant')
  await expect(page.getByText('当前使用虚构演示酒店资料。', { exact: false })).toBeVisible()
  await send(page, '推荐南京酒店，每晚预算400元')
  await expect(page.locator('.assistant').last()).toContainText('玄武湖')
  await expect(page.locator('.assistant').last()).toContainText('城南')
  await send(page, '预算改为300元')
  await expect(page.locator('.assistant').last()).toContainText('城南')
  await expect(page.locator('.assistant').last()).not.toContainText('玄武湖')
  await page.reload()
  await expect(page.locator('.assistant')).toHaveCount(2)
  await send(page, '酒店1取消退订政策')
  await expect(page.locator('.assistant').last()).toContainText('18:00')
  const source=page.locator('.assistant').last().locator('details').first()
  await source.locator('summary').click()
  await expect(source).toContainText('来源：演示资料')
  expect(authHeaders.every(value => value === undefined)).toBe(true)
  await page.screenshot({ path: 'test-results/assistant-desktop.png', fullPage: true })
})

test('stale conversation displays a system error and explicit reset recovers', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('hotel-chat-session', 'a'.repeat(43)))
  await page.goto('/assistant')
  await send(page, '南京预算400元')
  await expect(page.getByRole('alert')).toContainText('会话已过期')
  await expect(page.locator('.assistant')).toHaveCount(0)
  await expect(page.getByLabel('咨询内容')).toBeDisabled()
  await page.getByRole('alert').getByRole('button', { name: '开始新咨询' }).click()
  await expect(page.locator('.message')).toHaveCount(0)
  await send(page, '南京预算300元')
  await expect(page.locator('.assistant')).toContainText('城南')
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('failed request can be retried without duplicating the user bubble', async ({ page }) => {
  await page.goto('/assistant')
  await send(page, '南京预算400元')
  const payloads: { requestId: string }[] = []
  await page.route('**/api/ai/agent/chat', async route => {
    payloads.push(route.request().postDataJSON())
    if(payloads.length === 1) await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'MODEL_UNAVAILABLE', message: 'private provider secret must never render' }) })
    else await route.continue()
  })
  await send(page, '预算改为300元')
  await expect(page.getByRole('alert')).toContainText('模型服务暂时不可用')
  await expect(page.locator('body')).not.toContainText('private provider secret')
  await expect(page.locator('.assistant')).toHaveCount(1)
  await page.getByRole('button', { name: '重试本条' }).click()
  await expect(page.locator('.assistant')).toHaveCount(2)
  await expect(page.locator('.user')).toHaveCount(2)
  expect(payloads[0]!.requestId).toBe(payloads[1]!.requestId)
})

test('unknown dates and unavailable map remain explicit', async ({ page }) => {
  const date=(offset: number)=>new Date(Date.now()+offset*86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
  await page.goto('/assistant')
  await send(page, `酒店1在${date(30)}至${date(32)}的房态`)
  await expect(page.locator('.assistant').last()).toContainText('库存不完整')
  await send(page, '酒店1到南京站的路线')
  await expect(page.locator('.assistant').last()).toContainText('尚未配置')
  await expect(page.locator('.assistant').last().locator('details')).toHaveCount(0)
})

test('mobile consultation has no horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/assistant')
  await send(page, '南京预算300元')
  await expect(page.locator('.assistant')).toContainText('城南')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/assistant-mobile.png', fullPage: true })
})
