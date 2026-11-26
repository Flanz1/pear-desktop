/** IPC channels between the backend and the page bridge (renderer.ts). */
export const CHANNELS = {
  snapshot: 'phone-remote:snapshot',
  requestSnapshot: 'phone-remote:request-snapshot',
  getQueue: 'phone-remote:get-queue',
  queue: 'phone-remote:queue',
  clearLike: 'phone-remote:clear-like',
  setVolume: 'phone-remote:set-volume',
  catalogRequest: 'phone-remote:catalog-request',
  catalogReply: 'phone-remote:catalog-reply',
} as const;
