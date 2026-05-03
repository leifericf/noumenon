local cfg = dofile("config.lua")
local utils = require("mylib.utils")

print(utils.name(), cfg.version)
