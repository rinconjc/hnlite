(ns ^:figwheel-hooks hnapp.core
  (:require
   [goog.dom :as gdom]
   [hnapp.model :as model]
   [hnapp.utils :as utils]
   [oops.core :refer [ocall oget]]
   [reagent.core :as r  :refer [atom]]
   [reagent.dom :as rdom]
   [clojure.string :as s])
  (:require-macros
   [hnapp.util-macros :refer [handler handle-key]]))

(defn log [x]
  (js/console.log x))

(defonce route-listener
  (ocall js/window "addEventListener" "hashchange" model/handle-routes))

(defn get-app-element []
  (gdom/getElement "app"))

(defn story-item [id i]
  (let [story (r/track model/item id)
        active-item (r/track model/active-item)]
    [:a.story-link.collection-item.row
     {:class (cond
               (= (:id @story) (:id @active-item)) "active"
               (:visited @story) "visited")
      :href (str "#/story/" id)}
     [:div.col.s1.center [:b (inc i)]  [:div.center (:score @story)]]
     [:div.col.s11
      [:div.col.s12 [:b (:title @story)]]
      [:div.col.s8.center
       [:span (:time @story) " - " (:by @story)]]
      [:div.col.s4.valign-wrapper
       [:i.material-icons "comment"] (:descendants @story)]]]))

(defn top-stories []
  (r/with-let [stories (r/track model/top-stories)
               fn-dock (handler model/set-docking! true)]
    [:div.full-h
     [:ul#items.dropdown-content
      (for [[path label] model/categories] ^{:key path}
           [:li>a {:href (str "#/" path)} label])]
     [:nav>div.nav-wrapper
      ;; [:a.sidenav-trigger {:href "#!"} [:i.material-icons "menu"]]
      [:ul
       [:li>a {:href "#!"} [:i.material-icons "menu"]]
       [utils/with-init
        [:li.dropdown-trigger {:data-target "items"} (:label @stories)
         [:i.right.material-icons "arrow_drop_down"]]
        #(ocall js/M.Dropdown "init" %)]
       [:li.right [:a.right {:href "#!" :on-click fn-dock}
                   [:i.material-icons "chevron_left"]]]]]
     [:div#stories.collection {:on-scroll (utils/on-scroll-bottom model/page-down!)}
      (for [[i id] (map-indexed vector (:items @stories))]
        ^{:key i} [story-item id i])]]))

(defn comment-buttons [id collapsed? parent]
  [:div.center
   [:a {:href "#!" :on-click (handler model/reply-to id)}
    [:i.material-icons "reply"]] "  "
   (if collapsed?
     [:a {:href "#!" :on-click (handler model/expand! id)}
      [:i.material-icons "expand_more"]]
     [:a {:href "#!" :on-click (handler model/collapse! id)}
      [:i.material-icons "expand_less"]])
   "  "
   (when parent
     [:a {:href "#!" :on-click (handler model/collapse! parent)}
      [:i.material-icons "keyboard_capslock"]])])

(defn comment-item [id level parent]
  (let [item (r/track model/item id)]
    [:div.comment {:class (str "lvl-" level)}
     [:div.col.s12
      [:span.comment-voting.left
       [:a {:href "#!"} "▲"]
       ;; [:a {:href "#!"} "▼"]
       ]
      [:span.comment-info
       [:b.author (:by @item)] [:br] [:span (:time @item)]]]
     [:div.row.z-depth-3
      [:div.col.s12
       [:span {:dangerouslySetInnerHTML {:__html (:text @item)}}]]
      [comment-buttons (:id @item) (:collapsed? @item) parent]]
     (when-not (:collapsed? @item)
       [:div.replies
        (doall (for [k (:kids @item)]
                 ^{:key k} [comment-item k (inc level) (:id @item)]))])]))

(defn article-comments [item]
  [:div.comments-wrapper
   [:br]
   (doall (for [c (:kids item)]
            ^{:key c} [comment-item c 0 nil]))])

(defn about []
  [:div
   [:h1 "Hacker News Reader App"]
   [:p>i "A more eye friendly version of "
    [:a {:href "https://news.ycombinator.com"} "https://news.ycombinator.com"]]
   [:ul
    [:li "‣ Built with "
     [:a {:href "https://clojurescript.org/"} "ClojureScript"] " and "
     [:a {:href "https://reagent-project.github.io/"} "Reagent"]]
    [:li "‣ Single Page Layout with " [:a {:href "https://materializecss.com"} "Materializecss"]]
    [:li "‣ Article Extraction with " [:a {:href "https://github.com/mozilla/readability"} "Readability"]]
    [:li "‣ Content Caching with " [:a {:href "https://redis.io"} "Redis"]]
    [:li "‣ Powered by " [:a {:href "https://github.com/HackerNews/API"} "Hacker News API"]]]])

(defn spinner []
  (r/with-let [loading (r/track model/loading)]
    (when @loading
      [:div.spinner
       [:div.center.spinner>div.preloader-wrapper.big.active>div.spinner-layer.spinner-red-only
        [:div.circle-clipper.left>div.circle]
        [:div.gap-patch>div.circle]
        [:div.circle-clipper.right>div.circle]]])))

(defn article-view [article]
  (cond
    (:text article) [:div
                     [:h1 (:title article)]
                     [:a {:target "blank" :href (:url article)} (:url article)]
                     [:p {:dangerouslySetInnerHTML {:__html (:text article)}}]]
    (s/ends-with? (:url article) ".pdf") [:object
                                          {:data (:url article) :height "100%"}]
    :else [:div
           [spinner]
           [:iframe {:on-load model/loaded! :src (model/embed-url (:url article))}]]))

(defn story-pane []
  (r/with-let [article (r/track model/active-item)
               on-show-fn #(model/story-tab! (oget % "id"))
               story-tab (r/track model/story-tab)
               docked (r/track model/docking)
               fn-undock (handler model/set-docking! false)]
    [:div
     [:div.undock {:class (when-not @docked "hide-on-med-and-up")}
      [:a {:href "#/items"} [:i.material-icons "chevron_right"]]]
     [utils/tabs
      [:ul.tabs.tabs-fixed-width
       [:li.tab>a {:href "#article-tab"} "Article"]
       [:li.tab>a {:href "#comments-tab"} (str (:descendants @article) " Comments")]]
      :on-show on-show-fn :active-id @story-tab]
     [:div#article-tab.tab-content
      [:div.article-body
       (if @article
         [article-view @article]
         [about])
       [:br] [:br]]]
     [:div#comments-tab.tab-content
      (when (= :comments-tab @story-tab)
        [article-comments @(r/track model/item (:id @article))])]]))

(defn stories-page []
  (r/with-let [docked (r/track model/docking)
               show-stories? (r/track model/show-stories?)
               fn-undock (handler model/set-docking! false)]
    [:div.row.full-h
     (when-not @docked
       [:div.col.m5.full-h
        {:class (when-not @show-stories? "hide-on-small-only")}
        [top-stories]])
     [:div.col.s12.full-h {:class [(if @docked "m12" "m7")
                                   (when @show-stories? "hide-on-small-only")]}
      [story-pane]]]))

(defn mount [el]
  (rdom/render [stories-page] el)
  (model/handle-routes nil))

(defn mount-app-element []
  (when-let [el (get-app-element)]
    (mount el)))

;; conditionally start your application based on the presence of an "app" element
;; this is particularly helpful for testing this ns without launching the app
(mount-app-element)

;; specify reload hook with ^;after-load metadata
(defn ^:after-load on-reload []
  (mount-app-element)
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )
