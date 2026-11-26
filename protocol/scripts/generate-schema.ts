/**
 * Writes protocol/schema/*.schema.json from the zod sources.
 * Run: node protocol/scripts/generate-schema.ts
 * The contract tests fail if the committed files are out of date.
 */
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { renderSchemas } from './render-schemas.ts';

const dir = fileURLToPath(new URL('../schema/', import.meta.url));
for (const [file, content] of Object.entries(renderSchemas())) {
  writeFileSync(dir + file, content);
  console.log('wrote schema/' + file);
}
