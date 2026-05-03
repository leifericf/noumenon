(ns noumenon.http
  "HTTP API for the Noumenon daemon. Public surface; delegates to
   noumenon.http.server for lifecycle and noumenon.http.routes for the
   ring handler. Per-cluster handlers live under noumenon.http.handlers."
  (:require [noumenon.http.routes :as routes]
            [noumenon.http.server :as server]))

(def make-handler routes/make-handler)
(def start! server/start!)
(def stop!  server/stop!)
