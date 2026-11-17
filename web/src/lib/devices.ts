export interface Device {
  id: string; name: string; platform: string; kind: 'app' | 'owner';
  /** The person the phone belongs to (paired by a key); absent for the owner's own web sessions. */
  personName?: string; role?: string;
  createdAt: string; lastSeenAt: string | null; revoked: boolean; current: boolean;
}

/** Revoked devices go last, the rest keep the server's order. */
export function sortDevices(list: Device[]): Device[] {
  return [...list].sort((a, b) => Number(a.revoked) - Number(b.revoked));
}
