import { networkInterfaces } from 'node:os';

import { isLanAddress } from '../../../../protocol/src/index.ts';

export interface LanAddress {
  address: string;
  interfaceName: string;
}

/** Private IPv4 addresses of this machine, RFC 1918 first, link-local last. */
export const listLanAddresses = (): LanAddress[] => {
  const result: LanAddress[] = [];
  for (const [name, infos] of Object.entries(networkInterfaces())) {
    for (const info of infos ?? []) {
      if (info.internal || info.family !== 'IPv4') continue;
      if (!isLanAddress(info.address)) continue;
      result.push({ address: info.address, interfaceName: name });
    }
  }
  const linkLocal = (a: string) => a.startsWith('169.254.');
  return result.sort(
    (a, b) => Number(linkLocal(a.address)) - Number(linkLocal(b.address)),
  );
};

/**
 * The address to bind to: the configured one if it is still present, else
 * the first LAN address. Null when there is no LAN (the listener stays off).
 */
export const pickBindAddress = (preferred: string): string | null => {
  const all = listLanAddresses();
  if (preferred && all.some((a) => a.address === preferred)) return preferred;
  return all[0]?.address ?? null;
};
