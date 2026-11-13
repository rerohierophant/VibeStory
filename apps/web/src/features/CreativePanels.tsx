import { useRef } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useStore } from '@/lib/store';

export function useCreativeAction(key: readonly unknown[]) {
  const cache = useQueryClient();
  const { performCreative } = useStore();
  const request = useRef<{ fingerprint: string; id: string } | undefined>(undefined);
  const action = useMutation({
    mutationFn: async ({ path, data }: { path: string; data?: object }) => {
      if (!performCreative) return;
      const fingerprint = JSON.stringify([path, data]);
      if (request.current?.fingerprint !== fingerprint) request.current = { fingerprint, id: crypto.randomUUID() };
      const id = request.current.id;
      await performCreative(path + id, () => api(path, 'POST', data === undefined ? undefined : { ...data, requestKey: id }));
      request.current = undefined;
    },
    onSettled: () => cache.invalidateQueries({ queryKey: key }),
  });
  return action;
}
