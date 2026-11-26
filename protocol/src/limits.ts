/** Normative limits. Both sides enforce the ones that apply to what they receive. */
export const LIMITS = {
  /** Max size of one client -> desktop message, in UTF-8 bytes. */
  clientMessageBytes: 4 * 1024,
  /** Max size of one desktop -> client message, in UTF-8 bytes. */
  serverMessageBytes: 1024 * 1024,
  /** The first message (hello or pair.request) must arrive within this. */
  handshakeTimeoutMs: 10_000,
  /** A pairing window stays open this long unless closed earlier. */
  pairingWindowMs: 120_000,
  /** Failed pairing attempts allowed before the window closes. */
  pairingMaxFailedAttempts: 5,
  /** Command rate limit: token bucket. */
  commandBurst: 20,
  commandsPerSecond: 10,
  /** Invalid messages tolerated on an authenticated connection before closing. */
  maxInvalidMessages: 3,
  /** Max queue items in one `queue` message. */
  queueMaxItems: 500,
  /** Max length of human-readable strings (titles, names). */
  textMax: 500,
  deviceNameMax: 64,
  /** Default TCP port for the listener. */
  defaultPort: 26539,
} as const;

/** WebSocket close codes used by the protocol (4000-4999 is application-defined). */
export const CLOSE_CODES = {
  normal: 1000,
  goingAway: 1001,
  messageTooBig: 1009,
  badMessage: 4400,
  unauthorized: 4401,
  revoked: 4403,
  handshakeTimeout: 4408,
  replaced: 4409,
  versionUnsupported: 4426,
  rateLimited: 4429,
} as const;

/** The WebSocket path on the desktop listener. */
export const WS_PATH = '/pear-remote/ws';

/** mDNS / DNS-SD service type. */
export const MDNS_SERVICE_TYPE = '_pear-remote._tcp';
