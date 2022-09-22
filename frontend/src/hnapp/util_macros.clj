(ns hnapp.util-macros
  (:require [oops.core :refer [ocall oget]]))

(defmacro handler [f & args]
  `(fn [e#]
     (doto e# (ocall "preventDefault") (ocall "stopPropagation"))
     (~f ~@args)))

(defmacro handle-key [k & body]
  `(fn [e#]
     (when (= ~k (oget e# "key"))
       ~@body)))
