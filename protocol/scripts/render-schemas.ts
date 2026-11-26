import { z } from 'zod';

import { ClientCommandSchema, ClientHandshakeSchema } from '../src/client.ts';
import { PairingPayloadSchema } from '../src/pairing.ts';
import { ServerMessageSchema } from '../src/server.ts';
import { PROTOCOL_VERSION } from '../src/version.ts';

const render = (id: string, title: string, schema: z.ZodType) =>
  JSON.stringify(
    {
      $id: `https://github.com/Flanz1/pear-desktop/protocol/schema/${id}`,
      title: `${title} (protocol ${PROTOCOL_VERSION.major}.${PROTOCOL_VERSION.minor})`,
      ...z.toJSONSchema(schema, { unrepresentable: 'any' }),
    },
    null,
    2,
  ) + '\n';

/** File name -> JSON text. Pure, so tests can compare against the committed files. */
export const renderSchemas = (): Record<string, string> => ({
  'client-handshake.schema.json': render(
    'client-handshake.schema.json',
    'First message from the phone',
    ClientHandshakeSchema,
  ),
  'client-command.schema.json': render(
    'client-command.schema.json',
    'Phone command after welcome',
    ClientCommandSchema,
  ),
  'server-message.schema.json': render(
    'server-message.schema.json',
    'Message from the desktop',
    ServerMessageSchema,
  ),
  'pairing-payload.schema.json': render(
    'pairing-payload.schema.json',
    'Decoded pairing QR payload',
    PairingPayloadSchema,
  ),
});
