(ns community.components.search
  (:require [community.controller :as controller]
            [community.models :as models]
            [community.api :as api]
            [community.routes :as routes :refer [routes]]
            [community.components.shared :as shared]
            [community.util :as util :refer-macros [<? p]]
            [community.util.selection-list :as sl]
            [community.partials :as partials :refer [link-to]]
            [om.core :as om]
            [om.dom :as dom]
            [cljs.core.async :as async :refer [chan <! >! close! put! alts!]]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core :refer-macros [html]]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def key->search-filter {:users :author :threads :thread :subforums :subforum})

(defn result-set->suggestion-data [search-filter result-set]
  (map (fn [{:as result :keys [text payload]} counter]
         {:search-filter search-filter
          :text text
          :id (:id payload)
          :slug (:slug payload)
          :count counter})
       result-set (range (count result-set))))

(defn results->suggestions-display [results query-str]
  (let [filter-suggestions
          (mapcat (fn [key result-set]
            (when (not (empty? result-set))
              (let [search-filter (key->search-filter key)
                    suggestion-data (result-set->suggestion-data search-filter result-set)]
                   suggestion-data)))
            (keys results) (vals results))]
        filter-suggestions))

(def ENTER 13)
(def UP_ARROW 38)
(def DOWN_ARROW 40)
(def TAB 9)
(def ESC 27)

(defn display [show]
  (if show {} {:display "none"}))

(defn jump-to-page [{:keys [search-filter text id slug]}]
  (routes/redirect-to
    (condp = search-filter
      :thread (routes :thread {:id id :slug slug})
      :subforum (routes :subforum {:id id :slug slug})
      nil)))

(defn complete-suggestion [query-data suggestion]
  (-> query-data
      (assoc-in [:filters (:search-filter suggestion)] (:text suggestion))
      (assoc :text "")))

(defn search! [query-data]
  (let [query-param-str (->> (for [[filter-name value] (:filters query-data)
                             :when value]
                         (str (name filter-name) "=" value))
                       (str/join "&"))]
    (routes/redirect-to (str (routes :search {:query (:text query-data)
                                              :page (if-let [page (:page query-data)] page 1)})
                             "?" query-param-str))))

(defn complete-and-respond! [query-data selected]
  (cond
    (and (nil? selected) (not (empty? (:text query-data))))
      (search! query-data)
    (= :author (:search-filter selected))
      (search! (complete-suggestion query-data selected))
    (contains? #{:thread :subforum} (:search-filter selected))
      (jump-to-page selected)))

(def initial-query-state {:text ""
                          :filters {:author nil :subforum nil :thread nil}})

(defn suggestions-dropdown [{:keys [query-data suggestions show-suggestions?]}]
  (html
   [:ol
    {:id "suggestions" :ref "suggestions"
     :style (display (and show-suggestions? (not (empty? (:text query-data)))
                          (not (empty? suggestions))))}
    (for [{:keys [selected? value] :as suggestion} suggestions]
      [:li {:class (when selected? "selected")
            :onMouseDown #(complete-and-respond! query-data value)
            :onTouchStart #(complete-and-respond! query-data value)
            :data-search-filter (when (= 0 (:count value)) (name (:search-filter value)))}
       (:text value)])]))

(defn search-input-box [{:keys [query-data suggestions]} owner]
  (letfn [(select! [direction]
            (om/set-state! owner :suggestions (sl/select direction suggestions)))
          (query-text-change! [e]
            (let [text (.. e -target -value)]
              (om/update-state! owner :query-data #(assoc % :text text))
              (controller/dispatch :update-search-suggestions text)))
          (blur! []
            (.blur (om/get-node owner "search-query")))
          (handle-key-down! [e]
            (let [keycode (.-keyCode e)]
              (when (contains? #{UP_ARROW DOWN_ARROW ENTER ESC} keycode)
                (.preventDefault e)
                (condp = keycode
                  DOWN_ARROW (select! :next)
                  UP_ARROW (select! :prev)
                  ENTER (do
                          (complete-and-respond! query-data (sl/selected suggestions))
                          (blur!))
                  ESC (if (sl/selected suggestions)
                        (om/set-state! owner :suggestions (sl/unselect suggestions))
                        (blur!))))))]
    (html
     [:form
      {:id "search-form"
       :name "search-form"}
      [:i {:id "search-icon" :class "fa fa-search"}]
      [:input.form-control {:ref "search-query"
                            :type "search"
                            :id "search-box"
                            :value (:text query-data)
                            :onFocus #(om/set-state! owner :show-suggestions? true)
                            :onBlur #(om/set-state! owner :show-suggestions? false)
                            :onChange query-text-change!
                            :onKeyDown handle-key-down!}]])))

(defn suggestion-sl [suggestions query-str]
  (-> suggestions
      (results->suggestions-display query-str)
      (sl/selection-list nil)))

(defcomponent autocomplete [app owner]
  (display-name [_] "Autocomplete")

  (init-state [_]
    {:query-data initial-query-state
     :show-suggestions? false
     :suggestions (suggestion-sl (:suggestions app) (:query-str app))})

  (will-receive-props [_ next-props]
    (when (not= (:query-str next-props) (:query-str (om/get-props owner)))
      (om/set-state! owner
                     :suggestions (suggestion-sl (:suggestions next-props) (:query-str next-props)))))

  (render-state [_ {:as state :keys [query-data suggestions]}]
    (html
     [:div {:id "search"}
      (search-input-box state owner)
      (suggestions-dropdown state)])))

(defn result [{:keys [post author thread subforum highlight]}]
  (html
   [:div.row.search-result
    [:div.metadata {:data-ui-color (:ui-color subforum)}
     [:div.author
      [:a {:href (routes/hs-route :person {:hacker-school-id (:hacker-school-id author)})}
          (:name author)]]
     [:div.subforum
      (link-to (routes :subforum {:id (:id subforum)
                                  :slug (:slug subforum)})
                       {:style {:color (:ui-color subforum)}}
                       (:subforum-group-name subforum) " / " (:name subforum))]]
    [:div.result
     [:div.title
      (link-to (routes :thread {:id (:id thread)
                                :slug (:slug thread)
                                :post-number (:post-number post)})
                       {:style {:color (:ui-color subforum)}}
                       [:h4 (:title thread)])]
     [:div.body
      (partials/html-from-markdown highlight)]]]))

(defn load-page [query filters page]
  #(search! (assoc {} :page page :text query :filters (if filters @filters nil))))

(defn pages [{:keys [current-page total-pages _ query filters]}]
  (let [max-inbetween 5 max-displayed 7 radius 2]
    (letfn [(page-click [page] (load-page query filters page))
            (lower-bound [page] (if (> (- page radius) 2) (- page radius) 2))
            (upper-bound [page] (let [ub (+ (lower-bound page) max-inbetween)]
                                  (if (> ub total-pages) total-pages ub)))
            (first-ellipsis? [page] (> (lower-bound page) 2))
            (last-ellipsis? [page] (< (upper-bound page) total-pages))
            (mid-range [page] (range (lower-bound page) (upper-bound page)))]
      (html
        (when (> total-pages 1) 
          [:ul.page-links
           [:li {:class (when (= current-page 1) "disabled")}
            [:a {:href "#"
                 :onClick (page-click (dec current-page))} "Previous"]]
           [:li {:class (when (= current-page 1) "active")}
            [:a {:href "#"
                 :onClick (page-click 1)} "1"]]
           [:li {:style {:display (when-not (first-ellipsis? current-page) "none")}} "..."]
           (for [page (mid-range current-page)]
              [:li {:class (when (= page current-page) "active")} 
               [:a {:href "#"
                    :onClick (page-click page)} page]])
           [:li {:style {:display (when-not (last-ellipsis? current-page) "none")}} "..."]
           [:li {:class (when (= current-page total-pages) "active")}
            [:a {:href "#"
                 :onClick (page-click total-pages)} total-pages]]
           [:li {:class (when (= current-page total-pages) "disabled")}
            [:a {:href "#"
                 :onClick (page-click (inc current-page))} "Next"]]])))))

(defcomponent search-results [{:keys [search] :as app} owner]
  (display-name [_] "Search Results")

  (render [_]
    (let [results (:results search)
          {:as metadata :keys [hits took query filters]} (:metadata search)]
      (html
       [:div {:id "search-results-view"}
        [:div.query (if (:author filters)
                      (str (util/pluralize hits "post") " by " (:author filters) ".")
                      (str (util/pluralize hits "post") " matching \"" query "\"."))]
        (when-not (empty? results)
          [:div
            [:div.results (map (partial result) results)]
            [:div.paginate (pages metadata)]])]))))