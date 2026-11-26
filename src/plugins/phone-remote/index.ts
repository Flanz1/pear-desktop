import { t } from '@/i18n';
import { createPlugin } from '@/utils';

import { backend } from './backend';
import { defaultConfig, type PhoneRemoteConfig } from './config';
import { onMenu } from './menu';
import { renderer } from './renderer';

export default createPlugin<
  typeof backend,
  unknown,
  typeof renderer,
  PhoneRemoteConfig
>({
  name: () => t('plugins.phone-remote.name'),
  description: () => t('plugins.phone-remote.description'),
  restartNeeded: false,
  addedVersion: '3.13.X',
  config: defaultConfig,
  menu: onMenu,
  backend,
  renderer,
});
