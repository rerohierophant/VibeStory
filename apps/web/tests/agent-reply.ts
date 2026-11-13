import { expect, type Response } from '@playwright/test';
import type { Schema } from '../src/lib/api';

export function isAgentReply(response: Response) {
  return response.url().endsWith('/messages/stream') && response.request().method() === 'POST';
}

export async function readAgentReply(response: Response) {
  const body = await response.text();
  expect(response.ok(), body).toBe(true);
  const events = body.trimEnd().split('\n').map(line => JSON.parse(line) as Schema['ReplyEvent']);
  const last = events.at(-1);
  if (last?.type !== 'complete' || !last.transcript) throw new Error(last?.text ?? '对话流未返回完整会话');
  return { events, transcript: last.transcript };
}
