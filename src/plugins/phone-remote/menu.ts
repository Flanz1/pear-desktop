import prompt from 'custom-electron-prompt';
import { dialog } from 'electron';

import { t } from '@/i18n';
import promptOptions from '@/providers/prompt-options';

import { listLanAddresses } from './backend/network.ts';
import { service } from './backend/service.ts';
import { defaultConfig, type PhoneRemoteConfig } from './config';

import type { MenuTemplate } from '@/menu';
import type { MenuContext } from '@/types/contexts';

const formatDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString() : t('plugins.phone-remote.menu.never');

let refreshHooked = false;

export const onMenu = async ({
  getConfig,
  setConfig,
  window,
  refresh,
}: MenuContext<PhoneRemoteConfig>): Promise<MenuTemplate> => {
  if (!refreshHooked) {
    refreshHooked = true;
    service.onChange(() => {
      Promise.resolve(refresh()).catch(() => {});
    });
  }
  const config = await getConfig();
  const status = service.status;

  const statusLabel =
    status.kind === 'running'
      ? t('plugins.phone-remote.menu.status.running', {
          address: `${status.host}:${status.port}`,
        })
      : status.kind === 'no_lan'
        ? t('plugins.phone-remote.menu.status.no-lan')
        : status.kind === 'error'
          ? t('plugins.phone-remote.menu.status.error')
          : t('plugins.phone-remote.menu.status.stopped');

  const devices = service.listDevices();

  return [
    { label: statusLabel, enabled: false },
    {
      label: t('plugins.phone-remote.menu.pair'),
      enabled: status.kind === 'running',
      click: () => service.openPairing(),
    },
    {
      label: t('plugins.phone-remote.menu.devices', { count: devices.length }),
      type: 'submenu',
      submenu:
        devices.length === 0
          ? [
              {
                label: t('plugins.phone-remote.menu.no-devices'),
                enabled: false,
              },
            ]
          : devices.map((d) => ({
              label: `${d.name}${d.model ? ` (${d.model})` : ''}${
                service.isConnected(d.deviceId)
                  ? ` · ${t('plugins.phone-remote.menu.connected')}`
                  : ''
              }`,
              type: 'submenu' as const,
              submenu: [
                {
                  label: t('plugins.phone-remote.menu.paired-at', {
                    date: formatDate(d.pairedAt),
                  }),
                  enabled: false,
                },
                {
                  label: t('plugins.phone-remote.menu.last-seen', {
                    date: formatDate(d.lastSeenAt),
                  }),
                  enabled: false,
                },
                { type: 'separator' as const },
                {
                  label: t('plugins.phone-remote.menu.revoke'),
                  async click() {
                    const { response } = await dialog.showMessageBox(window, {
                      type: 'warning',
                      message: t('plugins.phone-remote.revoke.message', {
                        name: d.name,
                        interpolation: { escapeValue: false },
                      }),
                      buttons: [
                        t('plugins.phone-remote.revoke.cancel'),
                        t('plugins.phone-remote.revoke.confirm'),
                      ],
                      defaultId: 0,
                      cancelId: 0,
                      noLink: true,
                    });
                    if (response === 1) service.revoke(d.deviceId);
                  },
                },
              ],
            })),
    },
    { type: 'separator' },
    {
      label: t('plugins.phone-remote.menu.port'),
      async click() {
        const current = await getConfig();
        const port = await prompt(
          {
            title: t('plugins.phone-remote.menu.port'),
            label: t('plugins.phone-remote.menu.port'),
            value: current.port,
            type: 'counter',
            counterOptions: { minimum: 1024, maximum: 65535 },
            width: 380,
            ...promptOptions(),
          },
          window,
        );
        const n = Number(port);
        if (Number.isInteger(n) && n >= 1024 && n <= 65535) {
          await setConfig({ port: n });
        }
      },
    },
    {
      label: t('plugins.phone-remote.menu.interface'),
      type: 'submenu',
      submenu: [
        {
          label: t('plugins.phone-remote.menu.interface-auto'),
          type: 'radio',
          checked: !config.bindAddress,
          async click() {
            await setConfig({ bindAddress: defaultConfig.bindAddress });
          },
        },
        ...listLanAddresses().map((a) => ({
          label: `${a.address} (${a.interfaceName})`,
          type: 'radio' as const,
          checked: config.bindAddress === a.address,
          async click() {
            await setConfig({ bindAddress: a.address });
          },
        })),
      ],
    },
    {
      label: t('plugins.phone-remote.menu.reset'),
      async click() {
        const { response } = await dialog.showMessageBox(window, {
          type: 'warning',
          message: t('plugins.phone-remote.reset.message'),
          detail: t('plugins.phone-remote.reset.detail'),
          buttons: [
            t('plugins.phone-remote.revoke.cancel'),
            t('plugins.phone-remote.reset.confirm'),
          ],
          defaultId: 0,
          cancelId: 0,
          noLink: true,
        });
        if (response === 1) await service.resetIdentity(await getConfig());
      },
    },
  ];
};
