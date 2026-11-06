import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the API runs on :8080 (docker compose); the built site is served by the API itself.
const api = ['/auth', '/me', '/farm', '/invites', '/members', '/devices', '/sync', '/conflicts', '/files', '/backups',
             '/audit', '/entities', '/health'];
export default defineConfig({
  plugins: [react()],
  server: { proxy: Object.fromEntries(api.map((p) => [p, 'http://127.0.0.1:8080'])) },
  build: { outDir: 'dist', emptyOutDir: true },
});
