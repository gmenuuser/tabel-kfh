// Which page the site shows before anyone is signed in: the public page with the app links, or the admin's
// sign-in form. A signed-in owner always gets the app itself.

export type Gate = 'public' | 'login' | 'app';

export function gate(hash: string, signedIn: boolean): Gate {
  if (signedIn) return 'app';
  const page = hash.replace(/^#\/?/, '').split('/')[0];
  return page === 'admin' ? 'login' : 'public';
}
