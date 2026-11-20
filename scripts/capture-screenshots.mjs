import { spawn } from 'child_process';
import fs from 'fs';
import path from 'path';

const SCREENSHOT_DIR = path.resolve('docs/screenshots');
const tmpDir = fs.mkdtempSync('/tmp/chrome-cap-');

async function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function sendCdp(ws, method, params = {}) {
  const id = Math.floor(Math.random() * 1000000);
  return new Promise((resolve, reject) => {
    const handler = (evt) => {
      try {
        const msg = JSON.parse(evt.data);
        if (msg.id === id) {
          ws.removeEventListener('message', handler);
          if (msg.error) reject(new Error(msg.error.message));
          else resolve(msg.result);
        }
      } catch (err) {
        // ignore other messages
      }
    };
    ws.addEventListener('message', handler);
    ws.send(JSON.stringify({ id, method, params }));
  });
}

async function run() {
  const chrome = spawn('google-chrome', [
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--remote-debugging-port=9240',
    '--window-size=1080,1920',
    '--force-device-scale-factor=1',
    '--user-data-dir=' + tmpDir,
  ]);

  try {
    await wait(2000);

    const targetsRes = await fetch('http://127.0.0.1:9240/json');
    const targets = await targetsRes.json();
    const page = targets.find((t) => t.type === 'page');

    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res) => ws.addEventListener('open', res));

    await sendCdp(ws, 'Page.enable');
    await sendCdp(ws, 'Runtime.enable');
    await sendCdp(ws, 'Emulation.setDeviceMetricsOverride', {
      width: 1080,
      height: 1920,
      deviceScaleFactor: 1,
      mobile: false,
    });

    console.log('Connected to CDP. Navigating to Swonal Kiosk...');
    await sendCdp(ws, 'Page.navigate', { url: 'http://127.0.0.1:3000' });

    // Wait until root is mounted
    let attempts = 0;
    while (attempts < 20) {
      await wait(500);
      const evalRes = await sendCdp(ws, 'Runtime.evaluate', {
        expression: 'Boolean(document.querySelector(".kiosk-screen") || (document.getElementById("root") && !document.getElementById("initial-loader")))',
      });
      if (evalRes.result?.value) break;
      attempts++;
    }
    await wait(1000);

    // Helper to safely apply theme override
    async function applyTheme(color, glow, ink, muted) {
      await sendCdp(ws, 'Runtime.evaluate', {
        expression: `
          (() => {
            let s = document.getElementById('cdp-theme-override');
            if (!s) {
              s = document.createElement('style');
              s.id = 'cdp-theme-override';
              document.head.appendChild(s);
            }
            s.innerHTML = \`
              :root {
                --accent-primary: \${${JSON.stringify(color)}} !important;
                --accent-glow: \${${JSON.stringify(glow)}} !important;
                --accent-ink: \${${JSON.stringify(ink)}} !important;
                --accent-muted: \${${JSON.stringify(muted)}} !important;
              }
            \`;
            document.documentElement.style.setProperty('--accent-primary', ${JSON.stringify(color)}, 'important');
            document.documentElement.style.setProperty('--accent-glow', ${JSON.stringify(glow)}, 'important');
            document.documentElement.style.setProperty('--accent-ink', ${JSON.stringify(ink)}, 'important');
            document.documentElement.style.setProperty('--accent-muted', ${JSON.stringify(muted)}, 'important');
          })()
        `,
      });
    }

    // 1. Kiosk Live (Electric Lime accent #CCFF00) - Clean stage
    console.log('Capturing kiosk_live.png (Electric Lime)...');
    await sendCdp(ws, 'Page.navigate', { url: 'http://127.0.0.1:3000/' });
    await wait(1500);
    await applyTheme('#CCFF00', 'rgba(204, 255, 0, 0.35)', '#CCFF00', 'rgba(204, 255, 0, 0.12)');
    await sendCdp(ws, 'Runtime.evaluate', {
      expression: `
        // Hide PiP visually only for screenshot 1 clean stage without altering DB
        const pipEl = document.querySelector('button[aria-label="Close Form Demo"]')?.closest('.surface-card');
        if (pipEl) pipEl.style.display = 'none';
      `,
    });
    await wait(800);
    let screenshotData = await sendCdp(ws, 'Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(SCREENSHOT_DIR, 'kiosk_live.png'), Buffer.from(screenshotData.data, 'base64'));

    // 2. Kiosk Exercise Library (Free Lift Catalog active, Vibrant Violet #A855F7)
    console.log('Capturing kiosk_exercise_library.png (Vibrant Violet, Catalog tab with exercises)...');
    await sendCdp(ws, 'Page.navigate', { url: 'http://127.0.0.1:3000/' });
    await wait(1500);
    await applyTheme('#A855F7', 'rgba(168, 85, 247, 0.35)', '#A855F7', 'rgba(168, 85, 247, 0.15)');
    await sendCdp(ws, 'Runtime.evaluate', {
      expression: `
        // Open library dialog
        const libBtn = Array.from(document.querySelectorAll('button')).find(b => b.textContent && b.textContent.includes('Routines & Exercises'));
        if (libBtn) libBtn.click();
      `,
    });
    await wait(600);
    // Click "Free Lift Catalog" tab and expand categories so exercises are visible
    await sendCdp(ws, 'Runtime.evaluate', {
      expression: `
        const catalogTabBtn = Array.from(document.querySelectorAll('button')).find(b => b.textContent && b.textContent.includes('Free Lift Catalog'));
        if (catalogTabBtn) catalogTabBtn.click();
      `,
    });
    await wait(600);
    await sendCdp(ws, 'Runtime.evaluate', {
      expression: `
        const summaries = document.querySelectorAll('details summary');
        summaries.forEach((s, idx) => {
          if (idx < 2) s.click();
        });
      `,
    });
    await wait(800);
    screenshotData = await sendCdp(ws, 'Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(SCREENSHOT_DIR, 'kiosk_exercise_library.png'), Buffer.from(screenshotData.data, 'base64'));

    // 3. Kiosk Arm Guide (Neon Cyan #00F0FF)
    console.log('Capturing kiosk_arm_guide.png (Neon Cyan, Arm Guide active)...');
    await sendCdp(ws, 'Page.navigate', { url: 'http://127.0.0.1:3000/' });
    await wait(1500);
    await applyTheme('#00F0FF', 'rgba(0, 240, 255, 0.35)', '#00F0FF', 'rgba(0, 240, 255, 0.15)');
    await sendCdp(ws, 'Runtime.evaluate', {
      expression: `
        // Toggle Arm Info bubble
        const armBtn = document.querySelector('button[aria-label="Explain Arm Position Codes"]');
        if (armBtn) armBtn.click();
      `,
    });
    await wait(1000);
    screenshotData = await sendCdp(ws, 'Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(SCREENSHOT_DIR, 'kiosk_arm_guide.png'), Buffer.from(screenshotData.data, 'base64'));

    // 4. Kiosk Form Demo (Hot Coral #FF007A)
    console.log('Capturing kiosk_form_demo.png (Hot Coral, Form Demo PiP on stage)...');
    await sendCdp(ws, 'Page.navigate', { url: 'http://127.0.0.1:3000/' });
    await wait(1500);
    await applyTheme('#FF007A', 'rgba(255, 0, 122, 0.35)', '#FF007A', 'rgba(255, 0, 122, 0.15)');
    await wait(800);
    screenshotData = await sendCdp(ws, 'Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(SCREENSHOT_DIR, 'kiosk_form_demo.png'), Buffer.from(screenshotData.data, 'base64'));

    // 5. AI Voice Coach & MCP Server in Settings (Solar Amber #F59E0B)
    console.log('Capturing kiosk_ai_coach.png (Solar Amber, /settings?tab=ai)...');
    await sendCdp(ws, 'Page.navigate', { url: 'http://127.0.0.1:3000/settings?tab=ai' });
    await wait(2500);
    await applyTheme('#F59E0B', 'rgba(245, 158, 11, 0.35)', '#F59E0B', 'rgba(245, 158, 11, 0.15)');
    await wait(1000);
    screenshotData = await sendCdp(ws, 'Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(SCREENSHOT_DIR, 'kiosk_ai_coach.png'), Buffer.from(screenshotData.data, 'base64'));

    // 6. Mobile Companion & Decentralized P2P Swocial Feed (Emerald #10B981)
    console.log('Capturing kiosk_friends_feed.png (Emerald, /companion swocial tab)...');
    await sendCdp(ws, 'Page.navigate', { url: 'http://127.0.0.1:3000/companion' });
    await wait(2500);
    await applyTheme('#10B981', 'rgba(16, 185, 129, 0.35)', '#10B981', 'rgba(16, 185, 129, 0.15)');
    await sendCdp(ws, 'Runtime.evaluate', {
      expression: `
        // Switch to Swocial tab
        const swocialTabBtn = Array.from(document.querySelectorAll('button')).find(b => b.textContent && (b.textContent.includes('Swocial') || b.textContent.includes('Friends')));
        if (swocialTabBtn) swocialTabBtn.click();
      `,
    });
    await wait(1500);
    screenshotData = await sendCdp(ws, 'Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(SCREENSHOT_DIR, 'kiosk_friends_feed.png'), Buffer.from(screenshotData.data, 'base64'));

    ws.close();
    console.log('All 6 screenshots captured successfully!');
  } finally {
    chrome.kill();
  }
}

run().catch((err) => {
  console.error('Capture failed:', err);
  process.exit(1);
});
