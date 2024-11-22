(ns metabase.search.postgres.index-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [java-time.api :as t]
   [metabase.db :as mdb]
   [metabase.search.postgres.core :as search.postgres]
   [metabase.search.postgres.index :as search.index]
   [metabase.search.postgres.ingestion :as search.ingestion]
   [metabase.search.test-util :as search.tu]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn legacy-results
  "Use the source tables directly to search for records."
  [search-term & {:as opts}]
  (-> (assoc opts :search-engine :search.engine/in-place :search-term search-term)
      (#'search.postgres/in-place-query)
      t2/query))

(def legacy-models
  "Just the identity of the matches"
  (comp (partial mapv (juxt :id :model)) legacy-results))

(defn- legacy-hits [term]
  (count (legacy-results term)))

(defn- index-hits [term]
  (count (search.index/search term)))

;; These helpers only mutate the temp local AppDb.
#_{:clj-kondo/ignore [:metabase/test-helpers-use-non-thread-safe-functions]}
(defmacro with-index
  "Ensure a clean, small index."
  [& body]
  `(when (= :postgres (mdb/db-type))
     (binding [search.ingestion/*force-sync* true]
       (mt/dataset ~(symbol "test-data")
         (mt/with-temp [:model/Card     {}           {:name "Customer Satisfaction" :collection_id 1}
                        :model/Card     {}           {:name "The Latest Revenue Projections" :collection_id 1}
                        :model/Card     {}           {:name "Projected Revenue" :collection_id 1}
                        :model/Card     {}           {:name "Employee Satisfaction" :collection_id 1}
                        :model/Card     {}           {:name "Projected Satisfaction" :collection_id 1}
                        :model/Database {db-id# :id} {:name "Indexed Database"}
                        :model/Table    {}           {:name "Indexed Table", :db_id db-id#}]
           (search.index/reset-index!)
           (search.ingestion/populate-index!)
           ~@body)))))

(deftest idempotent-test
  (with-index
    (let [count-rows  (fn [] (t2/count @#'search.index/*active-table*))
          rows-before (count-rows)]
      (search.ingestion/populate-index!)
      (is (= rows-before (count-rows))))))

;; Disabled due to CI issue
#_(deftest incremental-update-test
    (with-index
      (testing "The index is updated when models change"
     ;; Has a second entry is "Revenue Project(ions)", when using English dictionary
        (is (= 1 #_2 (count (search.index/search "Projected Revenue"))))
        (is (= 0 (count (search.index/search "Protected Avenue"))))
        (t2/update! :model/Card {:name "Projected Revenue"} {:name "Protected Avenue"})
        (is (= 0 #_1 (count (search.index/search "Projected Revenue"))))
        (is (= 1 (count (search.index/search "Protected Avenue"))))
     ;; Delete hooks are disabled, for now, over performance concerns.
     ;(t2/delete! :model/Card :name "Protected Avenue")
        (search.ingestion/delete-model! (t2/select-one :model/Card :name "Protected Avenue"))
        (is (= 0 #_1 (count (search.index/search "Projected Revenue"))))
        (is (= 0 (count (search.index/search "Protected Avenue")))))))

;; Disabled due to CI issue
#_(deftest related-update-test
    (with-index
      (testing "The index is updated when model dependencies change"
        (let [index-table    @#'search.index/*active-table*
              table-id       (t2/select-one-pk :model/Table :name "Indexed Table")
              legacy-input   #(-> (t2/select-one [index-table :legacy_input] :model "table" :model_id table-id)
                                  :legacy_input
                                  (json/parse-string true))
              db-id          (t2/select-one-fn :db_id :model/Table table-id)
              db-name-fn     (comp :database_name legacy-input)
              alternate-name (str (random-uuid))]
          (is (= "Indexed Database" (db-name-fn)))
          (t2/update! :model/Database db-id {:name alternate-name})
          (is (= alternate-name (db-name-fn)))))))

(deftest consistent-subset-test
  (with-index
    (testing "It's consistent with in-place search on various full words"
      (doseq [term ["e-commerce" "example" "rasta" "new" "collection" "revenue"]]
        (testing term
          (is (= (set (legacy-models term))
                 (set (search.index/search term)))))))))

(deftest partial-word-test
  (with-index
    (testing "It does not match partial words"
      ;; does not include revenue
      (is (< (index-hits "venue")
             ;; but this one does
             (legacy-hits "venue"))))

    ;; no longer works without english dictionary
    #_(testing "Unless their lexemes are matching"
        (doseq [[a b] [["revenue" "revenues"]
                       ["collect" "collection"]]]
          (is (= (search.index/search a)
                 (search.index/search b)))))

    (testing "Or we match a completion of the final word"
      (is (seq (search.index/search "sat")))
      (is (seq (search.index/search "satisf")))
      (is (seq (search.index/search "employee sat")))
      (is (seq (search.index/search "satisfaction empl")))
      (is (empty? (search.index/search "sat employee")))
      (is (empty? (search.index/search "emp satisfaction"))))))

(deftest either-test
  (with-index
    (testing "legacy search does not understand stop words or logical operators"
      (is (= 3 (legacy-hits "satisfaction")))
      ;; Add some slack because things are leaking into this "test" database :-(
      (is (<= 2 (legacy-hits "or")))
      (is (<= 4 (legacy-hits "its the satisfaction of it")))
      (is (<= 1 (legacy-hits "user")))
      (is (<= 6 (legacy-hits "satisfaction or user"))))

    (testing "We get results for both terms"
      (is (= 3 (index-hits "satisfaction")))
      (is (<= 1 (index-hits "user"))))
    (testing "But stop words are skipped"
      (is (= 0 (index-hits "or")))
      ;; stop words depend on a dictionary
      (is (= #_0 3 (index-hits "its the satisfaction of it"))))
    (testing "We can combine the individual results"
      (is (= (+ (index-hits "satisfaction")
                (index-hits "user"))
             (index-hits "satisfaction or user"))))))

(deftest negation-test
  (with-index
    (testing "We can filter out results"
      (is (= 3 (index-hits "satisfaction")))
      (is (= 1 (index-hits "customer")))
      (is (= 1 (index-hits "satisfaction and customer")))
      (is (= 2 (index-hits "satisfaction -customer"))))))

(deftest phrase-test
  (with-index
    ;; Less matches without an english dictionary
    (is (= #_2 3 (index-hits "projected")))
    (is (= 2 (index-hits "revenue")))
    (is (= #_1 2 (index-hits "projected revenue")))
    (testing "only sometimes do these occur sequentially in a phrase"
      (is (= 1 (index-hits "\"projected revenue\""))))
    (testing "legacy search has a bunch of results"
      (is (= 3 (legacy-hits "projected revenue")))
      (is (= 0 (legacy-hits "\"projected revenue\""))))))

;; lower level search expression tests

(def search-expr #'search.index/to-tsquery-expr)

(deftest to-tsquery-expr-test
  (is (= "'a' & 'b' & 'c':*"
         (search-expr "a b c")))

  (is (= "'a' & 'b' & 'c':*"
         (search-expr "a AND b AND c")))

  (is (= "'a' & 'b' & 'c'"
         (search-expr "a b \"c\"")))

  (is (= "'a' & 'b' | 'c':*"
         (search-expr "a b or c")))

  (is (= "'this' & !'that':*"
         (search-expr "this -that")))

  (is (= "'a' & 'b' & 'c' <-> 'd' & 'e' | 'b' & 'e':*"
         (search-expr "a b \" c d\" e or b e")))

  (is  (= "'ab' <-> 'and' <-> 'cde' <-> 'f' | !'abc' & 'def' & 'ghi' | 'jkl' <-> 'mno' <-> 'or' <-> 'pqr'"
          (search-expr "\"ab and cde f\" or -abc def AND ghi OR \"jkl mno OR pqr\"")))

  (is (= "'big' & 'data' | 'business' <-> 'intelligence' | 'data' & 'wrangling':*"
         (search-expr "Big Data oR \"Business Intelligence\" OR data and wrangling")))

  (testing "unbalanced quotes"
    (is (= "'big' <-> 'data' & 'big' <-> 'mistake':*"
           (search-expr "\"Big Data\" \"Big Mistake")))
    (is (= "'something'"
           (search-expr "something \""))))

  (is (= "'partial' <-> 'quoted' <-> 'and' <-> 'or' <-> '-split':*"
         (search-expr "\"partial quoted AND OR -split")))

  (testing "dangerous characters"
    (is (= "'you' & '<-' & 'pointing':*"
           (search-expr "you <- pointing"))))

  (testing "single quotes"
    (is (= "'you''re':*"
           (search-expr "you're")))))

(defn ingest!
  [model where-clause]
  (#'search.ingestion/batch-update!
   (#'search.ingestion/spec-index-reducible model where-clause)))

(defn ingest-then-fetch!
  [model entity-name]
  (ingest! model [:= :this.name entity-name])
  (t2/query-one {:select [:*]
                 :from   [search.index/*active-table*]
                 :where  [:and
                          [:= :name entity-name]
                          [:= :model model]]}))

(deftest card-complex-ingestion-test
  (search.tu/with-temp-index-table
    (doseq [[model-type card-type] [["card" "question"] ["dataset" "model"] ["metric" "metric"]]]
      (testing (format "simple %s" model-type)
        (let [card-name (mt/random-name)]
          (mt/with-temp [:model/Card {card-id :id} {:name card-name
                                                    :type card-type}]
            (is (=? {:model                    model-type
                     :model_id                 card-id
                     :official_collection      nil
                     :database_id              (mt/id)
                     :pinned                   false
                     :view_count               0
                     :collection_id            nil
                     :dashboardcard_count      0
                     :last_edited_at           nil
                     :last_editor_id           nil
                     :verified                 nil}
                    (ingest-then-fetch! model-type card-name))))))

      (testing (format "everything %s" model-type)
        (let [search-term  (mt/random-name)
              yesterday    (t/- (t/offset-date-time) (t/days 1))
              two-days-ago (t/- yesterday (t/days 1))]
          (mt/with-temp
            [:model/Collection    {coll-id :id}      {:name            "My collection"
                                                      ;; :official_collection = true
                                                      :authority_level "official"}
             :model/Card          {card-id :id}      {:name                search-term
                                                      :type                card-type
                                                      :query_type          "query"
                                                      ;; :database_id = (mt/id)
                                                      :database_id         (mt/id)
                                                      ;; :pinned = true
                                                      :collection_position 1
                                                      ;; :view_count = 42
                                                      :view_count          42
                                                      ;; :collection_id = coll-id
                                                      :collection_id       coll-id
                                                      ;; :last_viewed_at = yesterday
                                                      :last_used_at        yesterday
                                                      ;; :model_created_at = two-days-ago
                                                      :created_at          two-days-ago
                                                      ;; :model_updated_at = two-days-ago
                                                      :updated_at          two-days-ago}

             ;; :dashboardcard_count = 2
             :model/Dashboard     {dashboard-id :id} {}
             :model/DashboardCard _                  {:dashboard_id dashboard-id :card_id card-id}
             :model/DashboardCard _                  {:dashboard_id dashboard-id :card_id card-id}

             ;; :last_edited_at = yesterday
             ;; :last_editor_id = rasta-id
             :model/Revision      _                 {:model_id    card-id
                                                     :model       "Card"
                                                     :user_id     (mt/user->id :rasta)
                                                     :most_recent true
                                                     :timestamp   yesterday
                                                     :object      {}}
             ;; :verified = true
             :model/ModerationReview _              {:moderated_item_type "card"
                                                     :moderated_item_id   card-id
                                                     :most_recent         true
                                                     :status              "verified"
                                                     :moderator_id        (mt/user->id :crowberto)}]
            (is (=? {:model                    model-type
                     :model_id                 card-id
                     :official_collection      true
                     :database_id              (mt/id)
                     :pinned                   true
                     :view_count               42
                     :collection_id            coll-id
                     :last_viewed_at           yesterday
                     :model_created_at         two-days-ago
                     :model_updated_at         two-days-ago
                     :dashboardcard_count      2
                     :last_edited_at           yesterday
                     :last_editor_id           (mt/user->id :rasta)
                     :verified                 true}
                    (ingest-then-fetch! model-type search-term)))))))))
