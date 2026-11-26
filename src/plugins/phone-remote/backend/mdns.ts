import { hostname } from 'node:os';

import { Bonjour, type Service } from 'bonjour-service';

import { DESKTOP_SUPPORTED } from '../../../../protocol/src/index.ts';

/** SPEC 3: advertise `_pear-remote._tcp` on the bound interface only. */
export class MdnsAdvertiser {
  private bonjour: Bonjour | null = null;
  private service: Service | null = null;

  start(opts: {
    bindAddress: string;
    port: number;
    instanceId: string;
    fingerprint: string;
    name: string;
    onError?: (err: unknown) => void;
  }) {
    this.stop();
    try {
      this.bonjour = new Bonjour(
        // Passed through to multicast-dns: send and listen on our LAN interface.
        { interface: opts.bindAddress } as never,
        (err: unknown) => opts.onError?.(err),
      );
      this.service = this.bonjour.publish({
        name: `${opts.name.slice(0, 40)} (${opts.instanceId.slice(0, 8)})`,
        type: 'pear-remote',
        protocol: 'tcp',
        port: opts.port,
        host: `${hostname().replace(/[^A-Za-z0-9-]/g, '-')}.local`,
        disableIPv6: true,
        txt: {
          v: Object.keys(DESKTOP_SUPPORTED).join(','),
          id: opts.instanceId,
          fp: opts.fingerprint,
        },
      });
      this.service.on('error', (err: unknown) => opts.onError?.(err));
    } catch (err) {
      opts.onError?.(err);
      this.stop();
    }
  }

  stop() {
    const bonjour = this.bonjour;
    this.bonjour = null;
    this.service = null;
    if (!bonjour) return;
    try {
      bonjour.unpublishAll(() => bonjour.destroy());
    } catch {
      /* already closed */
    }
  }
}
