const FORBIDDEN_KEY_PATTERN =
  /authorization|query|coordinate|latitude|longitude|waypoint|token|apikey|api_key|purchaseToken|password|secret/i;

const FORBIDDEN_VALUE_PATTERN = /apiKey=|api_key=|purchaseToken|Bearer\s+/i;

export type SafeLogFields = Record<string, string | number | boolean | null>;

export function sanitizeLogFields(fields: SafeLogFields): SafeLogFields {
  const sanitized: SafeLogFields = {};
  for (const [key, value] of Object.entries(fields)) {
    if (FORBIDDEN_KEY_PATTERN.test(key)) {
      continue;
    }
    if (typeof value === "string" && FORBIDDEN_VALUE_PATTERN.test(value)) {
      continue;
    }
    sanitized[key] = value;
  }
  return sanitized;
}

export function logSafe(event: string, fields: SafeLogFields = {}): void {
  console.log(JSON.stringify({ event, ...sanitizeLogFields(fields) }));
}
