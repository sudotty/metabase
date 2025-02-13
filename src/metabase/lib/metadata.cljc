(ns metabase.lib.metadata
  (:require
   [metabase.lib.metadata.ident :as lib.metadata.ident]
   [metabase.lib.metadata.protocols :as lib.metadata.protocols]
   [metabase.lib.schema :as lib.schema]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.lib.schema.metadata :as lib.schema.metadata]
   [metabase.lib.util :as lib.util]
   [metabase.util.i18n :as i18n]
   [metabase.util.malli :as mu]))

;;; TODO -- deprecate all the schemas below, and just use the versions in [[lib.schema.metadata]] instead.

;;; Column vs Field?
;;;
;;; Lately I've been using `Field` to only mean a something that lives in the application database, i.e. something
;;; that is associated with row in the `Field` table and has an `:id`. I'm using `Column` as a more generic term that
;;; includes not only `Field`s but also the columns returned by a stage of a query, e.g. `SELECT count(*) AS count`
;;; returns a `Column` called `count`, but it's not a `Field` because it's not associated with an actual Field in the
;;; application database.

(mu/defn ->metadata-provider :- ::lib.schema.metadata/metadata-provider
  "Get a MetadataProvider from something that can provide one."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable]
  (if (lib.metadata.protocols/metadata-provider? metadata-providerable)
    metadata-providerable
    (some-> metadata-providerable :lib/metadata ->metadata-provider)))

(mu/defn database :- ::lib.schema.metadata/database
  "Get metadata about the Database we're querying."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable]
  (lib.metadata.protocols/database (->metadata-provider metadata-providerable)))

(mu/defn tables :- [:sequential ::lib.schema.metadata/table]
  "Get metadata about all Tables for the Database we're querying."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable]
  (lib.metadata.protocols/tables (->metadata-provider metadata-providerable)))

(mu/defn table :- ::lib.schema.metadata/table
  "Find metadata for a specific Table, either by string `table-name`, and optionally `schema`, or by ID."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   table-id              :- ::lib.schema.id/table]
  (lib.metadata.protocols/table (->metadata-provider metadata-providerable) table-id))

(mu/defn fields :- [:sequential ::lib.schema.metadata/column]
  "Get metadata about all the Fields belonging to a specific Table."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   table-id              :- ::lib.schema.id/table]
  (let [prefix (lib.metadata.ident/table-prefix (:name (database metadata-providerable))
                                                (table metadata-providerable table-id))]
    (->> (lib.metadata.protocols/fields (->metadata-provider metadata-providerable) table-id)
         (lib.metadata.ident/attach-idents (constantly prefix)))))

(mu/defn metadatas-for-table :- [:sequential [:or
                                              ::lib.schema.metadata/column
                                              ::lib.schema.metadata/metric
                                              ::lib.schema.metadata/segment]]
  "Return active (non-archived) metadatas associated with a particular Table, either Fields, Metrics, or
   Segments -- `metadata-type` must be one of either `:metadata/column`, `:metadata/metric`, `:metadata/segment`."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   metadata-type         :- [:enum :metadata/column :metadata/metric :metadata/segment]
   table-id              :- ::lib.schema.id/table]
  (case metadata-type
    :metadata/column (fields metadata-providerable table-id)
    (lib.metadata.protocols/metadatas-for-table (->metadata-provider metadata-providerable) metadata-type table-id)))

(mu/defn metadatas-for-card :- [:sequential ::lib.schema.metadata/metric]
  "Return active (non-archived) metadatas associated with a particular Card, currently only Metrics.
   `metadata-type` must be `:metadata/metric`."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   metadata-type         :- [:enum :metadata/metric]
   card-id              :- ::lib.schema.id/card]
  (lib.metadata.protocols/metadatas-for-card (->metadata-provider metadata-providerable) metadata-type card-id))

(def ^:dynamic *enforce-idents*
  "Dynamic variable to control whether errors are thrown when we return a `:metadata/column` with no ident.

  Generally that is an error and we should throw, but there are a few tests explicitly checking broken fields that
  don't want to get hung up on this error."
  true)

(mu/defn field :- [:maybe ::lib.schema.metadata/column]
  "Get metadata about a specific Field in the Database we're querying."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   field-id              :- ::lib.schema.id/field]
  (let [fieldd (lib.metadata.protocols/field (->metadata-provider metadata-providerable) field-id)]
    (when (and fieldd
               (not (:ident fieldd))
               *enforce-idents*)
      (throw (ex-info "Returning a field with no :ident" {:metadata-providerable (pr-str metadata-providerable)
                                                          :id                    field-id
                                                          :field                 fieldd})))
    fieldd))

(mu/defn setting :- any?
  "Get the value of a Metabase setting for the instance we're querying."
  ([metadata-providerable :- ::lib.schema.metadata/metadata-providerable
    setting-key           :- [:or string? keyword?]]
   (lib.metadata.protocols/setting (->metadata-provider metadata-providerable) setting-key)))

(mu/defn card :- [:maybe ::lib.schema.metadata/card]
  "Get metadata for a Card, aka Saved Question, with `card-id`, if it can be found."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   card-id               :- ::lib.schema.id/card]
  (lib.metadata.protocols/card (->metadata-provider metadata-providerable) card-id))

(mu/defn segment :- [:maybe ::lib.schema.metadata/segment]
  "Get metadata for the Segment with `segment-id`, if it can be found."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   segment-id            :- ::lib.schema.id/segment]
  (lib.metadata.protocols/segment (->metadata-provider metadata-providerable) segment-id))

(mu/defn metric :- [:maybe ::lib.schema.metadata/metric]
  "Get metadata for the Metric with `metric-id`, if it can be found."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   metric-id             :- ::lib.schema.id/metric]
  (when-let [card-meta (lib.metadata.protocols/card (->metadata-provider metadata-providerable) metric-id)]
    (when (= (:type card-meta) :metric)
      (assoc card-meta :lib/type :metadata/metric))))

(mu/defn table-or-card :- [:maybe [:or ::lib.schema.metadata/card ::lib.schema.metadata/table]]
  "Convenience, for frontend JS usage (see #31915): look up metadata based on Table ID, handling legacy-style
  `card__<id>` strings as well. Throws an Exception (Clj-only, due to Malli validation) if passed an integer Table ID
  and the Table does not exist, since this is a real error; however if passed a `card__<id>` that does not exist,
  simply returns `nil` (since we do not have a strict expectation that Cards always be present in the
  MetadataProvider)."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   table-id              :- [:or ::lib.schema.id/table :string]]
  (if-let [card-id (lib.util/legacy-string-table-id->card-id table-id)]
    (card metadata-providerable card-id)
    (table metadata-providerable table-id)))

(defn- editable-stages?
  [query stages]
  (let [{:keys [source-table source-card] :as stage0} (first stages)]
    (boolean (and (when-let [{:keys [id]} (database query)]
                    (= (:database query) id))
                  (or (and source-table (table query source-table))
                      (and source-card  (card  query source-card))
                      (and
                       (= (:lib/type stage0) :mbql.stage/native)
                       ;; Couldn't import and use `lib.native/has-write-permissions` here due to a circular dependency
                       ;; TODO Find a way to unify has-write-permissions and this function?
                       (= :write (:native-permissions (database query)))))
                  (every? #(editable-stages? query %)
                          (for [a-stage      stages
                                a-join       (:joins a-stage)]
                            (:stages a-join)))))))

(mu/defn editable? :- :boolean
  "Given a query, returns whether it is considered editable.

  There's no editable flag! Instead, a query is **not** editable if:
  - Database is missing from the metadata (no permissions at all);
  - Database is present but it doesn't have native write permissions;
  - Database is present but tables (at least the `:source-table`) are missing (missing table permissions); or
  - Similarly, the card specified by `:source-card` is missing from the metadata.
  If metadata for the `:source-table` or `:source-card` can be found, then the query is editable.
  The above conditions must hold for every joined source too."
  [query :- ::lib.schema/query]
  (let [stages (:stages query)]
    (mu/disable-enforcement
      (editable-stages? query stages))))

;;; TODO -- I'm wondering if we need both this AND [[bulk-metadata-or-throw]]... most of the rest of the stuff here
;;; throws if we can't fetch the metadata, not sure what situations we wouldn't want to do that in places that use
;;; this (like QP middleware). Maybe we should only have a throwing version.
(mu/defn bulk-metadata :- [:maybe [:sequential [:map
                                                [:lib/type :keyword]
                                                [:id pos-int?]]]]
  "Fetch multiple objects in bulk. If our metadata provider is a bulk provider (e.g., the application database
  metadata provider), does a single fetch with [[lib.metadata.protocols/bulk-metadata]] if not (i.e., if this is a
  mock provider), fetches them with repeated calls to the appropriate single-object method,
  e.g. [[lib.metadata.protocols/field]].

  The order of the returned objects will match the order of `ids`, but does check that all objects are returned. If
  you want that behavior, use [[bulk-metadata-or-throw]] instead.

  This can also be called for side-effects to warm the cache."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   metadata-type         :- ::lib.schema.metadata/type
   ids                   :- [:maybe [:or [:sequential pos-int?] [:set pos-int?]]]]
  (when-let [ids (not-empty (cond-> ids
                              (not (set? ids)) distinct))] ; remove duplicates but preserve order.
    (let [provider   (->metadata-provider metadata-providerable)
          results    (lib.metadata.protocols/metadatas provider metadata-type ids)
          id->result (into {} (map (juxt :id identity)) results)]
      (when (= metadata-type :metadata/column)
        (when-let [missing (and *enforce-idents*
                                (not-empty (remove :ident results)))]
          (throw (ex-info "Bulk metadata request returned some columns without idents"
                          {:provider      metadata-providerable
                           :type          metadata-type
                           :ids           ids
                           :missing-ident missing}))))
      (into []
            (comp (map id->result)
                  (filter some?))
            ids))))

(defn- missing-bulk-metadata-error [metadata-type id]
  (ex-info (i18n/tru "Failed to fetch {0} {1}: either it does not exist, or it belongs to a different Database"
                     (pr-str metadata-type)
                     (pr-str id))
           {:status-code   400
            :metadata-type metadata-type
            :id            id}))

(mu/defn bulk-metadata-or-throw :- [:maybe [:sequential [:map
                                                         [:lib/type :keyword]
                                                         [:id pos-int?]]]]
  "Like [[bulk-metadata]], but verifies that all the requested objects were returned; throws an Exception otherwise."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   metadata-type         :- ::lib.schema.metadata/type
   ids                   :- [:maybe [:or [:sequential pos-int?] [:set pos-int?]]]]
  (let [results     (bulk-metadata metadata-providerable metadata-type ids)
        fetched-ids (into #{} (keep :id) results)]
    (doseq [id ids]
      (when-not (contains? fetched-ids id)
        (throw (missing-bulk-metadata-error metadata-type id))))
    results))

;; Invocation tracker provider
(mu/defn invoked-ids :- [:maybe [:sequential :any]]
  "Get all invoked ids of a metadata type."
  [metadata-providerable :- ::lib.schema.metadata/metadata-providerable
   metadata-type         :- ::lib.schema.metadata/type]
  (lib.metadata.protocols/invoked-ids metadata-providerable metadata-type))
