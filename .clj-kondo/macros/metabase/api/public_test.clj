(ns macros.metabase.api.public-test)

(defmacro with-sharing-enabled-and-temp-card-referencing!
  [table-kw field-kw [card-binding] & body]
  `(let [~card-binding [~table-kw ~field-kw]]
     ~@body))

(defmacro with-sharing-enabled-and-temp-dashcard-referencing!
  [table-kw field-kw [dash-binding card-binding dashcard-binding] & body]
  `(let [~dash-binding             [~table-kw ~field-kw]
         ~(or card-binding '_)     nil
         ~(or dashcard-binding '_) nil]
     ~@body))
