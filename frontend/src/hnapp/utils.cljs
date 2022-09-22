(ns hnapp.utils
  (:require [oops.core :refer [ocall oget oget+ oset!]]
            [reagent.core :as r]
            [reagent.dom :as rd]))

(defn log [& xs]
  (apply println xs))

(defn spy [& x]
  (log x)
  (last x))

(defn map-as-vector [state]
  (fn
    ([k] (into [] (get-in @state k)))
    ([k v] (swap! state assoc-in k (into {} v)))))

(defn ->form [evt]
  (oget (oget evt "target") "form"))

(defn form-elem
  ([form elem]
   (-> form (oget "elements") (ocall "namedItem" elem)))
  ([form elem n]
   (ocall (form-elem form elem) "item" n)))

(defn with-init [elem mount-fn & {:keys [did-update] :as opts}]
  (r/create-class
   {:display-name "with-init"
    :reagent-render
    (fn [elem _ & {:as opts}] elem)
    :component-did-update
    (fn [this old-argv old-state snapshot]
      (when (fn? did-update) (did-update this old-argv old-state snapshot)))
    :component-did-mount
    (fn [this]
      (mount-fn (rd/dom-node this)))}))

(defn tabs [content & {:keys [on-show active-id]}]
  (r/with-let [inst (r/atom nil)
               init-fn #(reset! inst (ocall js/M.Tabs "init" %
                                            (some->> on-show (hash-map "onShow") clj->js)))
               update-fn #(some->> (r/argv %) last (spy "activating:") name (ocall @inst "select"))]
    [with-init content init-fn :did-update update-fn :active-id active-id]))

(defn- init-modal [this show-ref modal-ref]
  (if @modal-ref
    (ocall @modal-ref "open")
    (when-let [dom (rd/dom-node this)]
      (-> modal-ref
          (reset! (-> js/M.Modal
                      (ocall "init" dom
                             #js{"onCloseEnd" #(do (reset! show-ref nil) (reset! modal-ref nil))})))
          (ocall "open")))))

(defn modal [show-ref content]
  (r/with-let [modal-obj (atom nil)]
    (r/create-class
     {:display-name "modal"
      :reagent-render
      (fn [show-ref content]
        (when @show-ref content))
      :component-did-update
      (fn [this old-argv old-state snapshot]
        (let [[_ show-ref content] (r/argv this)]
          (if @show-ref
            (init-modal this show-ref modal-obj)
            (and @modal-obj (ocall @modal-obj "close")))))
      :component-did-mount
      (fn [this]
        (init-modal this show-ref modal-obj))})))

(defn with-value [f]
  (fn [e]
    (-> (oget e "target") (oget "value") f)))

(defn with-file [f]
  (fn [e]
    (f (-> (oget e "target") (oget "files") (aget 0)))))

(defn on-change-for
  [doc path type listener]
  (fn [e]
    (let [v (cond
              (= "file" type) (-> (oget e "target") (oget "files") (aget 0))
              (= "checkbox" type) (-> (oget e "target") (oget "checked"))
              :else (-> (oget e "target") (oget "value")))]
      (swap! doc assoc-in path v)
      (when (fn? listener) (listener v)))))

(defn input-field
  [{:keys [doc path on-change] :as attrs}]
  (r/with-let [on-change (on-change-for doc path (:type attrs) on-change)]
    [:input.validate
     (conj (dissoc attrs :doc :path) [:on-change on-change]
           (case (:type attrs)
             "checkbox" [:checked (or (get-in @doc path) false)]
             "radio" [:checked (= (:value attrs) (get-in @doc path))]
             [:value (or (get-in @doc path) "")]))]))

(defn text-area
  [{:keys [doc path on-change] :as attrs}]
  (r/with-let [on-change (on-change-for doc path (:type attrs) on-change)]
    [:textarea.materialize-textarea
     (conj (dissoc attrs :doc :path) [:on-change on-change])
     (or (get-in @doc path) "")]))

(defn select-field [{:keys [doc path on-change] :as attrs} & options]
  (r/with-let [did-mount #(ocall js/M.FormSelect "init" %)
               did-update
               (fn [this _ _ _]
                 (let [el (rd/dom-node this)
                       form-select (oget el "M_FormSelect")]
                   (when-not (= (oget el "value") (first (ocall form-select "getSelectedValues")))
                     (ocall form-select "_handleSelectChange"))))
               on-change (on-change-for doc path (:type attrs) on-change)]
    [with-init
     [:select.validate (conj (dissoc attrs :doc :path)
                             [:on-change on-change]
                             [:value (or (get-in @doc path) "")])
      options]
     did-mount
     :did-update did-update]))

(defn with-binding
  ([form path] (with-binding {} form path identity))
  ([attrs form path] (with-binding attrs form path identity))
  ([attrs form path xf]
   (let [path (if (vector? path) path [path])]
     (assoc attrs
            :on-change #(swap! form assoc-in path
                               (if (= "file" (:type attrs))
                                 (-> (oget % "target") (oget "files") (aget 0) xf)
                                 (-> (oget % "target") (oget "value") xf)))
            :value (or (get-in @form path) "")))))

(defn autocomplete [attrs updater on-complete]
  (r/with-let
    [model (atom {:obj nil :data {}})
     did-mount-fn
     (fn [dom]
       (swap! model assoc :obj
              (ocall js/M.Autocomplete "init" dom
                     #js{"onAutocomplete"
                         #(do
                            (ocall dom "setCustomValidity" "")
                            (on-complete [(get-in @model [:data %]) %]))
                         "data" #js{} "minLength" 0})))
     on-change
     (fn [v]
       (updater v
                (fn [items]
                  (swap! model assoc :data items)
                  (ocall (:obj @model) "updateData"
                         (->> items (map #(vector (first %) nil)) vec (into {}) (clj->js))))))
     on-input (fn [e]
                (when (:required attrs)
                  (ocall (oget e "target") "setCustomValidity" "Please select a valid value")))]
    [with-init [input-field (assoc attrs :on-change on-change :auto-complete "off" :on-input on-input)]
     did-mount-fn]))


(defn on-scroll-bottom [f & args]
  (fn [e]
    (let [target (oget e "target")]
      (when (= (oget target "scrollTop") (oget target "scrollTopMax"))
        (apply f args)))))
