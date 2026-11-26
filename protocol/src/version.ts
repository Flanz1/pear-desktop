/**
 * Protocol version constants and negotiation.
 *
 * The desktop supports the current major and (for at least one release after
 * a breaking change) the previous one. Within a major, the effective minor is
 * the lower of the two sides' minors, so neither side sends anything the other
 * does not understand.
 */

export const PROTOCOL_VERSION = { major: 1, minor: 0 } as const;

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
  1: 0,
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
