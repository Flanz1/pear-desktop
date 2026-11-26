import { createBackend } from '@/utils';

import { service } from './service.ts';

import type { PhoneRemoteConfig } from '../config';
import type { BrowserWindow } from 'electron';

export const backend = createBackend<
  { last: PhoneRemoteConfig | null; win: BrowserWindow | null },
  PhoneRemoteConfig
>({
  last: null,
  win: null,
  async start(ctx) {
    const config = await ctx.getConfig();
    this.last = config;
    this.win = ctx.window;
    await service.start(ctx.window, config);
  },
  async stop() {
    this.last = null;
    this.win = null;
    await service.stop('disabled');
  },
  async onConfigChange(config) {
    const last = this.last;
    this.last = config;
    const unchanged =
      last?.port === config.port && last?.bindAddress === config.bindAddress;
    if (unchanged || !this.win) return;
    await service.start(this.win, config); // restarts on the new address/port
  },
});
