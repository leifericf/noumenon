local utils = require("mylib.utils")

local M = {}

function M.greet(name)
  return "Hello, " .. utils.capitalize(name)
end

return M
