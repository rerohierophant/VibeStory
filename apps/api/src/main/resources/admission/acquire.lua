-- Persistent marker fences epochs; token state alone expires. Both keys contain only opaque IDs.
local info = redis.call('INFO','server')
local run = string.match(info,'run_id:([^\r\n]+)')
local tm = redis.call('TIME')
local now = tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
local epoch = redis.call('HGET',KEYS[1],'epoch')
if run ~= ARGV[2] or not epoch or redis.call('EXISTS',KEYS[2]) == 0 then
    return {2,0,now,run}
end
if epoch ~= ARGV[1] or redis.call('HGET',KEYS[1],'token') ~= ARGV[3] then return {3,0,now,run} end
local rate = tonumber(ARGV[4])
local burst = tonumber(ARGV[5])
local last = tonumber(redis.call('HGET',KEYS[2],'at'))
local tokens = tonumber(redis.call('HGET',KEYS[2],'tokens'))
if not last or not tokens then return {2,0,now,run} end
-- Clamp backwards server-clock adjustments. Forward clock changes follow server-time semantics.
local at = math.max(last,now)
tokens = math.min(burst,tokens+math.max(0,now-last)*rate/1000)
local allowed = 0
local retry = 0
if tokens >= 1 then tokens=tokens-1;allowed=1 else retry=math.ceil((1-tokens)*1000/rate) end
redis.call('HSET',KEYS[2],'tokens',tostring(tokens),'at',at)
redis.call('PEXPIRE',KEYS[2],ARGV[6])
return {allowed,retry,now,run}
