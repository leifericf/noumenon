local os = require("os")

local M = {}

function M.name()
  return os.getenv("USER") or "world"
end

function M.capitalize(s)
  return s:sub(1, 1):upper() .. s:sub(2)
end

return M
