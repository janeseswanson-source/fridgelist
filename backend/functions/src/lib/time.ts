/** CONTRACT §1: times in payloads are Unix seconds. */
export function unixSeconds(date: Date = new Date()): number {
  return Math.floor(date.getTime() / 1000);
}

export const DAY_SECONDS = 86_400;
