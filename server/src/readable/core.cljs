(ns readable.core
  (:require
   ["@mozilla/readability" :as readability]
   [clojure.string :as s]
   [bluebird]
   [cors]
   [express]
   [util]
   [http]
   [https]
   [jsdom]
   [redis]
   [zlib]))

(enable-console-print!)
;; === redis caching ======
;; (def redis-conn {:pool {} :spec {:uri (str "redis://" (or (conf/env :redis) "localhost:6379"))}})
(def cache (delay
            (let [client (redis/createClient
                          #js{:url (str "redis://" (or js/process.env.REDIS "localhost:6379"))})]
              (.catch (.connect client) #(prn "failed to connect to redis:" %))
              client)))

(defn parse-content [html]
  (let [dom (jsdom/JSDOM. html)]
    (-> (readability/Readability. (-> dom (.-window) (.-document)))
        (.parse))))

(defn url-get [url resolve reject]
  (let [get (if (s/starts-with? url "http://") http/get https/get)]
    (get url
         (fn [res]
           (cond
             (#{301 302} (.-statusCode res))
             (url-get res.headers.location resolve reject)
             (= 200 (.-statusCode res))
             (let [data (atom "")
                   headers (js->clj res.headers)
                   source (if (= "gzip" (headers "content-encoding"))
                            (let [gunzip (zlib/createGunzip.)]
                              (.pipe res gunzip)
                              gunzip)
                            res)]
               (.on source "data" #(swap! data str %))
               (.on source "close" #(resolve @data)))
             :else
             (and (.resume res)
                  (reject (str "Response not OK:" (.-statusCode res)))))))))

(defn fetch-content [url]
  (js/Promise.
   (fn [resolve reject]
     (url-get url resolve reject))))

(defn generate-page [url referer article]
  (str "<html>"
       "<base href=\"" url "\">"
       "<link rel=\"stylesheet\" href=\"" referer "/css/article-style.css\"/>"
       "<body>"
       "<a href=\"" url "\" target=\"blank\">" url "</a>"
       "<h1>" (.-title article) "</h1>"
       "<i>" (.-byline article) "</i>"
       "<hr/>"
       "<p>" (.-content article) "</p>"
       "</body"
       "</html>"))

(def app (express))

(.use app (.static express "static"))

(.use app "/parse"
      (fn [req res next]
        (.type res "json")
        (next)))

(.get app "/ping"
      (fn [_ resp]
        (.send resp #js{:status "Hello there!"})))

(.get app "/parse"
      (cors #js{:origin "http://localhost:9501"})
      (fn [req resp]
        (let [url (-> req (.-query) (.-url))]
          (-> (.get @cache url)
              (.then #(if-not % (throw (js/Error. "url not in cache!")) %))
              (.then #(.send resp %))
              (.catch
               (fn [_]
                 (->
                  (fetch-content url)
                  (.then parse-content)
                  (.then #(do
                            (.catch (.set @cache url (js/JSON.stringify %) #js{:EX 86400})
                                    (fn [e] (prn "failed to put cache:" e)))
                            %))
                  (.then #(.. resp (send %) end))
                  (.catch #(.send (.. resp (status 500) (send (str "Error:" %)) end))))))))))

(.get app "/embed"
      (fn [req resp]
        (let [url (-> req (.-query) (.-url))
              cache-key (str "page:" url)
              referer (-> req (.-headers) (.-referer))]
          (.type resp "html")
          (-> (.get @cache cache-key)
              (.then #(if-not % (throw (js/Error. "url not in cache!")) %))
              (.then #(.send resp %))
              (.catch
               (fn [_]
                 (->
                  (fetch-content url)
                  (.then parse-content)
                  (.then (partial generate-page url referer))
                  (.then #(do
                            (.catch (.set @cache cache-key % #js{:EX 86400})
                                    (fn [e] (prn "failed to put cache:" e)))
                            %))
                  (.then #(.. resp (send %) end))
                  (.catch #(do
                             (prn "failed to retrieve :" url %)
                             (.send (.. resp (status 500) (send (generate-page url referer #js{:content "Failed to retrieve url content, please visit link directly."})) end)))))))))))

(defn -main []
  (prn "starting server ...")
  (try
    (doto (.createServer http #(app %1 %2))
      (.listen (or js/process.env.PORT 7100)))
    (catch js/Any e
      (prn "server crashed..." e))))

(set! *main-cli-fn* -main)
