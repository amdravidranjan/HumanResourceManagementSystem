-- Token bucket, executed atomically by Redis.
-- KEYS[1] bucket key; ARGV[1] capacity; ARGV[2] refill tokens per ms; ARGV[3] now (ms)
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local perMs = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil then
  tokens = capacity
  ts = now
end
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * perMs)
local allowed = 0
local retry = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
else
  retry = math.ceil((1 - tokens) / perMs)
end
redis.call('HMSET', key, 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', key, math.ceil(capacity / perMs) + 1000)
return {allowed, retry}
