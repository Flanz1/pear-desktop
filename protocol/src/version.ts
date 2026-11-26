/**
 * Protocol version constants and negotiation.
 *
 * The desktop supports the current major and (for at least one release after
 * a breaking change) the previous one. Within a major, the effective minor is
 * the lower of the two sides' minors, so neither side sends anything the other
 * does not understand.
 */

export const PROTOCOL_VERSION = { major: 1, minor: 1 } as const;

export interface ProtocolVersion {
  major: number;
  minor: number;
}

/**
 * Majors this build of the desktop accepts, mapped to the highest minor it
 * implements for each. Keep the previous major here for at least one release
 * after bumping PROTOCOL_VERSION.major.
 */
export const DESKTOP_SUPPORTED: Readonly<Record<number, number>> = {
  1: 1,
};

export type NegotiationResult =
  | { ok: true; version: ProtocolVersion }
  | {
      ok: false;
      code: 'update_required' | 'desktop_update_required';
      supported: { min: number; max: number };
    };

export const negotiate = (
  client: ProtocolVersion,
  supported: Readonly<Record<number, number>> = DESKTOP_SUPPORTED,
): NegotiationResult => {
  const majors = Object.keys(supported)
    .map(Number)
    .sort((a, b) => a - b);
  const min = majors[0];
  const max = majors[majors.length - 1];

  const serverMinor = supported[client.major];
  if (serverMinor !== undefined) {
    return {
      ok: true,
      version: {
        major: client.major,
        minor: Math.min(client.minor, serverMinor),
      },
    };
  }

  return {
    ok: false,
    code: client.major < min ? 'update_required' : 'desktop_update_required',
    supported: { min, max },
  };
};

/**
 * Optional features a desktop can advertise in `welcome.capabilities`.
 * Only meaningful when the negotiated minor is at least the one noted.
 */
export const CAPABILITIES = {
  /** 1.1: catalog.browse, catalog.open, catalog.play. */
  catalogBrowse: 'catalog.browse',
  /** 1.1: catalog.search, catalog.suggest. Never advertised without catalog.browse. */
  catalogSearch: 'catalog.search',
  /** 1.1: catalog.thumb, and non-null `thumb` refs. Never advertised without catalog.browse. */
  catalogThumbnails: 'catalog.thumbnails',
} as const;

export type Capability = (typeof CAPABILITIES)[keyof typeof CAPABILITIES];

/** Command type -> the minor version and capability it needs. Commands not listed are in 1.0. */
export const COMMAND_REQUIREMENTS: Readonly<
  Record<string, { minor: number; capability: Capability }>
> = {
  'catalog.browse': { minor: 1, capability: CAPABILITIES.catalogBrowse },
  'catalog.open': { minor: 1, capability: CAPABILITIES.catalogBrowse },
  'catalog.play': { minor: 1, capability: CAPABILITIES.catalogBrowse },
  'catalog.search': { minor: 1, capability: CAPABILITIES.catalogSearch },
  'catalog.suggest': { minor: 1, capability: CAPABILITIES.catalogSearch },
  'catalog.thumb': { minor: 1, capability: CAPABILITIES.catalogThumbnails },
};

/**
 * Whether a command may be sent in a session (phone side) or must be answered
 * with `unknown_type` (desktop side). `version` is the negotiated version from
 * `welcome.protocol`; `capabilities` is `welcome.capabilities`.
 */
export const isCommandAvailable = (
  type: string,
  version: ProtocolVersion,
  capabilities: readonly string[],
): boolean => {
  const req = COMMAND_REQUIREMENTS[type];
  if (!req) return true;
  return version.minor >= req.minor && capabilities.includes(req.capability);
};
