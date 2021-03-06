(ns sci.impl.utils
  {:no-doc true}
  (:require [clojure.string :as str]
            [sci.impl.vars :as vars]
            [sci.impl.types :as t]))

(derive :sci.error/realized-beyond-max :sci/error)

(defn constant? [x]
  (or (number? x) (string? x) (keyword? x)))

(defn mark-resolve-sym
  [sym]
  (vary-meta
   sym
   (fn [m]
     (assoc m :sci.impl/op :resolve-sym))))

(defn eval? [x]
  (some-> x meta :sci.impl/op))

(def kw-identical? #?(:clj identical? :cljs keyword-identical?))

(defn gensym*
  ([] (mark-resolve-sym (gensym)))
  ([prefix] (mark-resolve-sym (gensym prefix))))

(defn mark-eval-call
  [expr]
  (vary-meta
   expr
   (fn [m]
     (assoc m :sci.impl/op :call))))

(defn mark-eval
  [expr]
  (vary-meta
   expr
   (fn [m]
     (assoc m :sci.impl/op :eval))))

(defn throw-error-with-location
  ([msg iobj] (throw-error-with-location msg iobj {}))
  ([msg iobj data]
   (let [{:keys [:line :column]} (meta iobj)
         msg (str msg
                  " [at "
                  (when-let [v @vars/current-file]
                    (str v ", "))
                  "line "
                  line ", column " column"]") ]
     (throw (ex-info msg (merge {:type :sci/error
                                 :line line
                                 :column column} data))))))

(defn rethrow-with-location-of-node [ctx ^Throwable e node]
  (if-not (:sci.impl/in-try ctx)
    (if-let [m #?(:clj (.getMessage e)
                  :cljs (.-message e))]
      (if (str/includes? m "[at")
        (throw e)
        (let [{:keys [:line :column] :or {line (:line ctx)
                                          column (:column ctx)}} (meta node)]
          (if (and line column)
            (let [m (str m
                         " [at "
                         (when-let [v @vars/current-file]
                           (str v ", "))
                         "line "
                         line ", column " column"]")
                  new-exception (let [d (ex-data e)]
                                  (ex-info m (merge {:type :sci/error
                                                     :line line
                                                     :column column
                                                     :message m} d) e))]
              (throw new-exception))
            (throw e))))
      (throw e))
    (throw e)))

(defn vary-meta*
  "Only adds metadata to obj if d is not nil and if obj already has meta"
  [obj f & args]
  (if (not (vars/var? obj)) ;; vars can have metadata but don't support with-meta
    (if (meta obj)
      (apply vary-meta obj f args)
      obj)
    obj))

(defn merge-meta
  "Only adds metadata to obj if d is not nil and if meta on obj isn't already nil."
  [obj d]
  (if (and d (not (vars/var? obj))
             (not (vars/namespace? obj))) ;; vars can have metadata but don't support with-meta
    (if-let [m (meta obj)]
      (with-meta obj (merge m d))
      obj)
    obj))

(defn strip-core-ns [sym]
  (case (namespace sym)
    ("clojure.core" "cljs.core") (symbol (name sym))
    sym))

(def allowed-loop (with-meta (symbol "loop") {:line :allow}))
(def allowed-recur (with-meta (symbol "recur") {:line :allow}))

(defn walk*
  [inner form]
  (cond
    (:sci.impl/op (meta form)) form
    (list? form) (with-meta (apply list (map inner form))
                   (meta form))
    #?(:clj (instance? clojure.lang.IMapEntry form) :cljs (map-entry? form))
    #?(:clj (clojure.lang.MapEntry/create (inner (key form)) (inner (val form)))
       :cljs (MapEntry. (inner (key form)) (inner (val form)) nil))
    (seq? form) (with-meta (doall (map inner form))
                  (meta form))
    #?(:clj (instance? clojure.lang.IRecord form)
       :cljs (record? form))
    (reduce (fn [r x] (conj r (inner x))) form form)
    (coll? form) (into (empty form) (map inner form))
    :else form))

(defn prewalk
  "Prewalk with metadata preservation. Does not prewalk :sci.impl/op nodes."
  [f form]
  (walk* (partial prewalk f) (f form)))

(defn get-namespace
  "Fetches namespaces from env if it exists. Else produces one and adds it to env before returning it."
  [env ns-sym attr-map]
  ;; (prn "env" (some? env))
  (or (let [v (get-in @env [:namespaces ns-sym :obj])]
        ;; (prn "v" v)
        v)
      (let [ns-obj (vars/->SciNamespace ns-sym attr-map)]
        (swap! env assoc-in [:namespaces ns-sym :obj] ns-obj)
        ns-obj)))

(defn set-namespace! [ctx ns-sym attr-map]
  (let [env (:env ctx)
        attr-map (merge (meta ns-sym) attr-map)
        ns-obj (get-namespace env ns-sym attr-map)]
    (t/setVal vars/current-ns ns-obj)))
