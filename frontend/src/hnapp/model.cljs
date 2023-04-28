(ns hnapp.model
  (:require
   [oops.core :refer [ocall oget oset!]]
   [reagent.core :as r :refer [atom]]))

(goog-define SCRAPNEWS-URL "http://localhost:7100")

(def hn-base-url "https://news.ycombinator.com")

(def categories [["topstories" "Top Stories"]
                 ["askstories" "Ask HN"]
                 ["showstories" "Show HN"]
                 ["jobstories" "Jobs"]
                 ["beststories" "Best Stories"]
                 ["newstories" "News"]])

(defonce app (atom {:story-tab :article-tab :limit 30
                    :article {:default? true}
                    :stories (->> (first categories) (zipmap [:cat :label]))}))

(def db (ocall js/firebase "database"))

(defn merge-values [old-val new-val]
  (cond
    (= "comment" (:type new-val)) (merge old-val new-val)
    :else new-val))

(defn fetch! [path & [target]]
  (-> (ocall db "ref" (str "/v0/" path))
      (ocall "on" "value"
             #(swap! app update-in (or target [path]) merge-values
                     (js->clj (ocall % "val") :keywordize-keys true))))
  nil)

(defn detach! [path]
  (some-> (ocall db "ref" (str "/v0/" path))
          (ocall "off" "value"))
  (swap! app dissoc path))

(defn top-stories []
  (let [stories (@app :stories)]
    (when-not (:items stories)
      (fetch! (:cat stories) [:stories :items]))
    (update stories :items #(take (:limit @app) %))))

(defn detach-stories! []
  (let [stories (:stories @app)]
    (detach! (:cat stories))
    (doseq [it (:items stories)]
      (detach! (str "item/" it)))))

(defn fetch-article! [url]
  (swap! app assoc-in [:article :url] url)
  (-> (js/fetch (str SCRAPNEWS-URL "/?url=" (js/encodeURIComponent url))
                #js{"method" "GET"})
      (ocall "then"
             (fn [resp]
               (if (oget resp "ok")
                 (-> (ocall resp "text")
                     (ocall "then"
                            #(swap! app assoc-in [:article :body]
                                    (js->clj (js/JSON.parse %) :keywordize-keys true))))
                 (js/console.log "resp:" resp))))
      (ocall "catch"
             (fn [err]
               (js/console.log "failed to fetch article:" err)
               (swap! app assoc-in [:article :body :content] "<h2>Failed to load article. :(</h2>")))))

(defn hf-time [t]
  (let [now (/ (js/Date.now) 1000)]
    (condp > (- now t)
      60 "now"
      3600 (str (js/Math.round (/ (- now t) 60)) "m")
      86400 (str (js/Math.round (/ (- now t) 3600)) "h")
      (str (js/Math.round (/ (- now t) 86400)) "d"))))

(defn item-by-id [id]
  (when id
    (if-let [it (@app (str "item/" id))]
      it
      (fetch! (str "item/" id)))))

(defn item [id]
  (let [it (r/track item-by-id id)]
    (when @it
      (update @it :time hf-time))))

(defn all-kids [root start-id]
  (when-let [kids (:kids (root (str "item/" start-id)))]
    (concat kids (mapcat (partial all-kids root) kids))))

(defn active-item []
  @(r/track item-by-id (:active @app)))

(defn article-content []
  (let [article (:article @app)
        it (r/track active-item)]
    (if (and article (= (:url @it) (:url article)))
      (:body article)
      (fetch-article! (:url @it)))))

(defn activate! [id]
  (let [current (:active @app)]
    (swap! app assoc :active id
           :story-tab :article-tab
           :loading true
           :article nil
           :activated true)
    (when current
      (swap! app update (str "item/" current) assoc :visited true))
    (doseq [k (some->> current (all-kids @app))]
      (detach! (str "item/" k)))))

(defn story-tab! [tab-id]
  (swap! app assoc :story-tab (keyword tab-id)))

(defn story-tab []
  (:story-tab @app))

(defn docking []
  (:docking @app))

(defn set-docking! [v]
  (swap! app assoc :docking v :activated v))

(defn show-stories? []
  (or (nil? (active-item)) (false? (:activated @app))))

(defn page-down! []
  (swap! app update :limit + 10))

(defn handle-routes [_]
  (let [path (-> js/window (oget "location") (oget "hash"))
        [cat & more] (map second (re-seq #"/([^/]+)" path))]
    (js/console.log "path:" path)
    (cond
      (= cat "story") (activate! (first more))
      (or (= cat "items") (empty? cat)) (set-docking! false)
      :else (when-let [[categ label] (some #(and (= cat (first %)) %) categories)]
              (detach-stories!)
              (swap! app assoc
                     :stories {:cat categ :label label :items nil}
                     :limit 15)))))

(defn reply-to [id]
  (let [url (str "https://news.ycombinator.com/reply?id=" id)]
    (ocall js/window "open" url "HN_WINDOW")))

(defn collapse! [id]
  (swap! app assoc-in [(str "item/" id) :collapsed?] true))

(defn expand! [id]
  (swap! app assoc-in [(str "item/" id) :collapsed?] false))

(defn embed-url [url]
  (str SCRAPNEWS-URL "/embed?url=" (js/encodeURIComponent url)))

(defn loading []
  (:loading @app))

(defn loaded! []
  (swap! app assoc :loading false))
