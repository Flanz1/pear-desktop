import { BrowserWindow, dialog } from 'electron';
import QRCode from 'qrcode';

import { t } from '@/i18n';

import type { ConfirmRequest } from './pairing.ts';

export interface PairingWindowContent {
  uri: string;
  code: string;
  shortFingerprint: string;
  host: string;
  port: number;
  expiresAt: number;
}

const escapeHtml = (s: string) =>
  s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

const page = (svg: string, c: PairingWindowContent) => {
  const strings = {
    title: t('plugins.phone-remote.pairing.title'),
    scan: t('plugins.phone-remote.pairing.scan'),
    manual: t('plugins.phone-remote.pairing.manual'),
    code: t('plugins.phone-remote.pairing.code'),
    fingerprint: t('plugins.phone-remote.pairing.fingerprint'),
    address: t('plugins.phone-remote.pairing.address'),
    expires: t('plugins.phone-remote.pairing.expires'),
    cancel: t('plugins.phone-remote.pairing.cancel'),
  };
  const e = (k: keyof typeof strings) => escapeHtml(strings[k]);
  // Minimal, presentational only. A design pass comes later.
  return `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:">
<title>${e('title')}</title>
<style>
:root{color-scheme:light dark;font-family:system-ui,sans-serif}
body{margin:0;padding:20px;text-align:center;background:Canvas;color:CanvasText}
h1{font-size:18px;margin:0 0 8px}
p{margin:6px 0;font-size:13px}
.qr{background:#fff;display:inline-block;padding:8px;border-radius:8px;width:240px;height:240px}
.qr svg{width:100%;height:100%}
.code{font:600 30px/1.2 ui-monospace,monospace;letter-spacing:6px;margin:6px 0}
.mono{font-family:ui-monospace,monospace}
#status{min-height:18px;font-weight:600}
button{margin-top:10px;padding:8px 20px;font-size:14px}
button:focus-visible{outline:3px solid Highlight;outline-offset:2px}
</style></head><body>
<h1>${e('title')}</h1>
<p>${e('scan')}</p>
<div class="qr" role="img" aria-label="${e('scan')}">${svg}</div>
<p>${e('manual')}</p>
<p>${e('code')}</p>
<div class="code" aria-label="${e('code')}">${escapeHtml(c.code)}</div>
<p>${e('fingerprint')} <span class="mono">${escapeHtml(c.shortFingerprint)}</span></p>
<p>${e('address')} <span class="mono">${escapeHtml(`${c.host}:${c.port}`)}</span></p>
<p>${e('expires')} <span id="left"></span></p>
<p id="status" role="status"></p>
<button id="cancel" autofocus>${e('cancel')}</button>
<script>
const end=${Number(c.expiresAt)};
const left=document.getElementById('left');
const tick=()=>{const s=Math.max(0,Math.round((end-Date.now())/1000));left.textContent=s+' s';};
tick();setInterval(tick,500);
document.getElementById('cancel').addEventListener('click',()=>window.close());
window.setStatus=(s)=>{document.getElementById('status').textContent=s;};
</script></body></html>`;
};

/** The pairing window (SPEC 6.1). Closing it cancels pairing. */
export class PairingWindow {
  private win: BrowserWindow | null = null;

  async show(
    parent: BrowserWindow,
    content: PairingWindowContent,
    onClosedByUser: () => void,
  ) {
    this.close();
    const svg = await QRCode.toString(content.uri, {
      type: 'svg',
      errorCorrectionLevel: 'M',
      margin: 2,
    });
    const win = new BrowserWindow({
      parent,
      width: 380,
      height: 640,
      resizable: false,
      minimizable: false,
      maximizable: false,
      fullscreenable: false,
      autoHideMenuBar: true,
      title: t('plugins.phone-remote.pairing.title'),
      webPreferences: {
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
        devTools: false,
        spellcheck: false,
      },
    });
    this.win = win;
    win.removeMenu();
    win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    win.webContents.on('will-navigate', (e) => e.preventDefault());
    win.on('closed', () => {
      if (this.win === win) {
        this.win = null;
        onClosedByUser();
      }
    });
    await win.loadURL(
      `data:text/html;charset=utf-8,${encodeURIComponent(page(svg, content))}`,
    );
  }

  setStatus(text: string) {
    this.win?.webContents
      .executeJavaScript(`window.setStatus(${JSON.stringify(text)})`)
      .catch(() => {});
  }

  /** Closes without reporting a user cancel. */
  close() {
    const win = this.win;
    this.win = null;
    if (win && !win.isDestroyed()) win.close();
  }
}

/** SPEC 6.3 step 3: explicit Allow, Deny is the default button. */
export const confirmPairingDialog = async (
  parent: BrowserWindow,
  request: ConfirmRequest,
  signal: AbortSignal,
): Promise<boolean> => {
  const deny = t('plugins.phone-remote.confirm.deny');
  const allow = t('plugins.phone-remote.confirm.allow');
  const { response } = await dialog.showMessageBox(parent, {
    type: 'question',
    title: t('plugins.phone-remote.confirm.title'),
    message: t('plugins.phone-remote.confirm.message', {
      name: request.name,
      model: request.model ?? t('plugins.phone-remote.confirm.unknown-model'),
      address: request.address,
      interpolation: { escapeValue: false },
    }),
    detail: t('plugins.phone-remote.confirm.detail'),
    buttons: [deny, allow],
    defaultId: 0,
    cancelId: 0,
    noLink: true,
    signal,
  });
  return !signal.aborted && response === 1;
};
