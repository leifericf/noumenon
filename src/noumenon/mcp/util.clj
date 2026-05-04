(ns noumenon.mcp.util
  "Re-exports for the MCP top-level — the JSON-RPC dispatch in mcp.clj
   uses tool-error to wrap failures from the proxy. The bridge is a
   pure proxy (mcp/proxy) and does not run handlers in-process; the
   per-tool helpers that used to live here moved out with the handlers
   they served."
  (:require [noumenon.mcp.protocol :as protocol]))

(def tool-result protocol/tool-result)
(def tool-error  protocol/tool-error)
