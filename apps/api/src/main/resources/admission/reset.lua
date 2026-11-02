local run = string.match(redis.call('INFO','server'),'run_id:([^\r\n]+)')
if run ~= ARGV[2] then return 0 end
local epoch = tonumber(redis.call('HGET',KEYS[1],'epoch') or '-1')
if epoch > tonumber(ARGV[1]) then return 0 end
if epoch == tonumber(ARGV[1]) and redis.call('HGET',KEYS[1],'token') ~= ARGV[3] then return 0 end
-- Idempotent completion after a DB write failure cannot refill an already rebuilt bucket.
if epoch == tonumber(ARGV[1]) and redis.call('EXISTS',KEYS[2]) == 1 then return 1 end
local tm=redis.call('TIME')
local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
redis.call('HSET',KEYS[1],'epoch',ARGV[1],'token',ARGV[3])
redis.call('PERSIST',KEYS[1])
redis.call('HSET',KEYS[2],'tokens','0','at',now)
redis.call('PEXPIRE',KEYS[2],ARGV[4])
return 1
