import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(cleanup);
// Component tests have no business API or network dependency.
vi.stubGlobal('fetch', vi.fn(() => { throw new Error('Component tests must not use the network'); }));
