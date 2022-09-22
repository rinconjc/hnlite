(defproject hnapp "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/clojurescript "1.11.60"]
                 [binaryage/oops "0.7.2"]
                 ;; [org.clojure/core.async "1.3.610"]
                 [reagent "1.1.1"]
                 [cljsjs/react "17.0.2-0"]
                 [cljsjs/react-dom "17.0.2-0"]]

  :source-paths ["src"]

  :aliases {"fig"       ["trampoline" "run" "-m" "figwheel.main"]
            "fig:build" ["trampoline" "run" "-m" "figwheel.main" "-b" "dev" "-r"]
            "fig:min"   ["with-profile" "dev" "run" "-m" "figwheel.main"
                         "-co" "{:closure-defines {hnapp.model/SCRAPNEWS-URL \"api\"}
:process-shim false}"
                         "-O" "advanced" "-bo" "dev"]
            "fig:test"  ["run" "-m" "figwheel.main" "-co" "test.cljs.edn" "-m" "hnapp.test-runner"]}

  :profiles {:dev
             {:dependencies [[com.bhauman/figwheel-main "0.2.18"]
                             [com.bhauman/rebel-readline-cljs "0.1.4"]]

              :resource-paths ["target"]
              ;; need to add the compiled assets to the :clean-targets
              :clean-targets ^{:protect false} ["target"]}
             :uberjar {:dependencies [[ring/ring-jetty-adapter "1.7.1"]
                                      [org.eclipse.jetty/jetty-client "9.4.28.v20200408"]]
                       :aot :all
                       :omit-sources true
                       :env {:production true}
                       :prep-tasks ["compile" "fig:min"]}})
